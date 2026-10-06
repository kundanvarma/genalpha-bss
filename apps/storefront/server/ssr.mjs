/*
 * THE SERVER-RENDERING RUNTIME (#180).
 *
 * The public routes, rendered by React on a server: the SSR build loads, the app
 * renders to a string with the tenant's own config, the data it needs is fetched
 * BEFORE the render rather than in an effect that never runs, and the document a
 * crawler receives is complete with no JavaScript executed.
 *
 * WIRED, AND DELIBERATELY NARROW. The gateway sends this service the two page
 * kinds that had NO crawler document at all — the shop root and the category
 * shelves, where a bot got the empty SPA shell — and only when the caller is a
 * named crawler. Everything else is untouched:
 *
 *  - humans still get the nginx-served bundle, byte for byte as before;
 *  - /shop/offering/** still gets the Java-rendered page, because that one
 *    works in production today and the ticket's own limit says keep it until
 *    this is proven there. This renders offering pages too — the suite asserts
 *    it — so the swap is a route change, not a build.
 *
 * AN UPSTREAM THAT IS DOWN IS NOT A PAGE THAT IS MISSING, and conflating the
 * two is how a slow catalogue becomes a deindexed shop. So:
 *
 *  - a page that does not exist answers 404 (an unknown shelf slug, an offering
 *    id the catalogue does not have) — with a real rendered document, because a
 *    crawler reads a 404 body too;
 *  - a catalogue that times out answers 200 with the page frame and no seed, so
 *    the client fetches exactly as it always did. A thin page for one request is
 *    recoverable; a 404 teaches a crawler the URL is gone.
 *
 * WHAT IS STILL NOT HERE: streaming. `renderToString` is synchronous, so nothing
 * exercises Suspense or partial flushing; the tenant is request-scoped so that
 * move stays a performance decision rather than a correctness one.
 *
 * A DARK TENANT. The gateway's CrawlerVisibilityFilter stamps `X-Robots-Tag:
 * noindex, nofollow` on every response for a dark tenant's host, so a rendered
 * page carries the directive. That is deliberately not the same as the Java
 * page's 404 for a dark tenant, and it is the stronger of the two: Google
 * documents that keeping a URL out of an index requires letting the crawler in
 * and telling it noindex — a blocked fetch still indexes the bare URL.
 */
import { createServer } from 'node:http';
import { render, SHELVES } from '../dist-ssr/entry-server.js';

const PORT = Number(process.env.PORT || 8080);
const GATEWAY = process.env.GATEWAY_URL || 'http://gateway:8080';
/*
 * THE CATALOGUE IS READ THROUGH THE GATEWAY, not straight from the service,
 * and that is a correctness fix rather than a preference.
 *
 * A tenant is chosen for anonymous traffic by `X-Tenant-Id`, which the GATEWAY
 * stamps from the request's hostname — no other component sets it. Reading the
 * catalogue directly therefore sent no tenant at all and the catalogue fell
 * back to `registry.defaultTenantId()`: invisible on a one-tenant demo, and on
 * any second hostname it would have served every visitor the DEFAULT tenant's
 * products. That was survivable while only crawlers came here. It stops being
 * survivable the moment humans do.
 *
 * So the renderer asks the same door a browser asks, and the tenant is decided
 * in the one place that owns the decision.
 */
const CATALOG = process.env.CATALOG_URL || GATEWAY;
const TIMEOUT = Number(process.env.SSR_FETCH_TIMEOUT_MS || 4000);
const CAT = '/tmf-api/productCatalogManagement/v4';
const SHELF = new Set(SHELVES);

const STOREFRONT = process.env.STOREFRONT_URL || 'http://storefront:8080';

/*
 * THE SHELL COMES FROM THE SERVER THAT SERVES THE ASSETS, at run time.
 *
 * This used to be a copy of index.html baked into this image at build time,
 * and that copy was WRONG the moment the two images were built apart: Vite
 * hashes the bundle, so this image said `/shop/assets/index-D9yjfnwE.js` while
 * the nginx image served `index-Y6T0RIJj.js`. The document rendered perfectly
 * and then loaded NO JAVASCRIPT — no hydration, no client routing, no cart. It
 * looked right and was dead, and only a human would ever have found out,
 * because a crawler does not run the script it cannot fetch.
 *
 * So the renderer asks nginx for the shell it is currently serving and wraps
 * its markup in that. One source of truth for the asset hashes, and no build
 * coupling between the two images at all. Cached briefly, because this is on
 * the request path and the shell changes only on a deploy.
 *
 * If the shell cannot be fetched the render FAILS rather than guessing: the
 * gateway's circuit breaker then serves nginx's own shell, which is always
 * self-consistent. A stale copy here would be worse than an error — it is what
 * produced this bug.
 */
const SHELL_TTL = Number(process.env.SSR_SHELL_TTL_MS || 60000);
let shell = { html: null, at: 0 };

async function template() {
  if (shell.html && Date.now() - shell.at < SHELL_TTL) {
    return shell.html;
  }
  const res = await fetch(`${STOREFRONT}/index.html`, { signal: AbortSignal.timeout(TIMEOUT) });
  if (!res.ok) throw new Error(`the storefront shell answered ${res.status}`);
  const html = await res.text();
  if (!html.includes('<div id="root">')) {
    throw new Error('the storefront shell has no root element to render into');
  }
  shell = { html, at: Date.now() };
  return html;
}

/**
 * A slow catalogue must not become a slow page, and must not become a missing
 * one either: `null` here means "no answer", which is a different fact from an
 * answer of nothing, and the caller is required to tell them apart.
 */
async function fetchJson(url, headers) {
  try {
    const res = await fetch(url, { headers, signal: AbortSignal.timeout(TIMEOUT) });
    if (res.status === 404) {
      return { status: 404, json: null };
    }
    return { status: res.status, json: res.ok ? await res.json() : null };
  } catch {
    return { status: 0, json: null };
  }
}

/** The tenant manifest the gateway stamps into every page, fetched the same way. */
async function tenantConfig(host) {
  const js = await fetch(`${GATEWAY}/shop/tenant-config.js`, {
    headers: host ? { Host: host, 'X-Forwarded-Host': host } : {},
    signal: AbortSignal.timeout(TIMEOUT),
  }).then((r) => (r.ok ? r.text() : '')).catch(() => '');
  const m = js.match(/window\.BSS_STOREFRONT_CONFIG\s*=\s*(\{[\s\S]*?\});/);
  if (!m) return {};
  try {
    // the manifest is a literal the gateway writes; evaluate it as JSON5-ish
    return Function(`"use strict";return (${m[1]})`)();
  } catch {
    return {};
  }
}

/**
 * Page through a list endpoint the way the client's own api.js does — the API
 * caps a page at 100 and the shelf outgrew that. `null` when the catalogue did
 * not answer at all, which is not the same as an empty shelf.
 */
async function listAll(pathAndQuery, host) {
  const headers = host ? { 'X-Forwarded-Host': host, Host: host } : {};
  const all = [];
  for (let offset = 0; ; offset += 100) {
    const sep = pathAndQuery.includes('?') ? '&' : '?';
    const got = await fetchJson(`${CATALOG}${pathAndQuery}${sep}limit=100&offset=${offset}`, headers);
    if (!Array.isArray(got.json)) {
      return offset === 0 ? null : all;
    }
    all.push(...got.json);
    if (got.json.length < 100) break;
  }
  return all;
}

const index = (rows) => {
  const by = {};
  for (const p of rows || []) by[p.id] = p;
  return by;
};

/**
 * What kind of page is this path, if any?
 *
 * One classification, used for both the status code and the data to resolve, so
 * the server cannot answer 200 for a shape it then has nothing to render.
 */
function classify(route) {
  if (route === '/' || route === '/shop' || route === '/shop/') return { kind: 'home' };
  const shelf = route.match(/^\/(?:shop\/)?category\/([^/]+)\/?$/);
  if (shelf) return { kind: 'shelf', slug: decodeURIComponent(shelf[1]) };
  const offering = route.match(/^\/(?:shop\/)?offering\/([^/]+)\/?$/);
  if (offering) return { kind: 'offering', id: decodeURIComponent(offering[1]) };
  return { kind: 'unknown' };
}

/**
 * What this path needs resolved before it can render, and what status it earns.
 *
 * THE SAME ENDPOINTS THE CLIENT CALLS, deliberately. The acceptance for this arc
 * is that a bot and a browser receive the same document, and the cheapest way to
 * guarantee that is for both to read the same shapes from the same doors — no
 * mapping layer in between to drift.
 *
 * That leaves one question open, and it is a real one: the crawler page renders
 * from the shared projection (#179), which decides which price leads, while the
 * React app has its own price logic in money.js. They agree today. When the
 * crawler route is finally deleted, somebody has to decide which of the two is
 * the authority for a headline price — this file is not the place to decide it
 * quietly.
 */
async function resolve(page, host) {
  if (page.kind === 'unknown') return { status: 404, data: null };
  if (page.kind === 'shelf' && !SHELF.has(page.slug)) return { status: 404, data: null };

  if (page.kind === 'home' || page.kind === 'shelf') {
    const [offerings, prices] = await Promise.all([
      listAll(`${CAT}/productOffering?lifecycleStatus=Active`, host),
      listAll(`${CAT}/productOfferingPrice`, host),
    ]);
    // no answer from the catalogue: render the frame and let the client fetch,
    // rather than teach a crawler that the shop is gone
    if (offerings === null) return { status: 200, data: null };
    return { status: 200, data: { offerings, prices: index(prices) } };
  }

  const headers = host ? { 'X-Forwarded-Host': host, Host: host } : {};
  const got = await fetchJson(`${CATALOG}${CAT}/productOffering/${encodeURIComponent(page.id)}`, headers);
  if (got.status === 404) return { status: 404, data: null };
  if (!got.json || !got.json.id) return { status: 200, data: null };
  const prices = await listAll(`${CAT}/productOfferingPrice`, host);
  return { status: 200, data: { offering: got.json, prices: index(prices) } };
}

/**
 * The machine-readable head of an offering page, from the catalogue's own
 * schema.org projection (/seo/offering/{id}/meta) — title, description,
 * canonical and the JSON-LD. The crawler page used to be the only thing that
 * emitted these; retiring it without them would have been a straight SEO
 * regression, and rebuilding them here from the React page's data would have
 * made a second authority for which price leads.
 */
async function offeringMeta(id, host) {
  const got = await fetchJson(`${CATALOG}/seo/offering/${encodeURIComponent(id)}/meta`,
    host ? { 'X-Forwarded-Host': host, Host: host } : {});
  // a title OR a redirect: a withdrawn offering answers with the latter and no
  // title at all, and requiring one here swallowed the redirect entirely
  return got.json && (got.json.title || got.json.redirect) ? got.json : null;
}

const esc = (s) => String(s)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/** The head a crawler reads, assembled from facts rather than guessed. */
function head(meta, canonical, title) {
  const out = [];
  if (title) out.push(`<title>${esc(title)}</title>`);
  if (meta && meta.description) {
    out.push(`<meta name="description" content="${esc(meta.description)}">`);
  }
  if (canonical) out.push(`<link rel="canonical" href="${esc(canonical)}">`);
  // the JSON-LD arrives already serialised by Jackson; only the closing-tag
  // sequence needs neutralising, and escaping anything else would corrupt it
  if (meta && meta.jsonLd) {
    out.push('<script type="application/ld+json">'
      + String(meta.jsonLd).replace(/<\/script/gi, '<\\/script') + '</script>');
  }
  return out.join('\n  ');
}

function document(shellHtml, appHtml, config, data, headTags) {
  /*
   * THE SEED IS A JSON DATA BLOCK, NOT AN INLINE SCRIPT, and that is not a
   * style choice — the gateway's content policy is `script-src 'self'` with no
   * `unsafe-inline` (SecurityHeadersFilter), so an inline `window.__SSR_DATA__
   * = …` is BLOCKED in a browser. Crawlers neither execute nor enforce CSP, so
   * while only bots were served this looked fine; the first human would have
   * got the server's markup, no seed, and React rebuilding the page from
   * scratch — every byte of the render wasted.
   *
   * A `type="application/json"` block is data, never executed, so the policy
   * does not apply and nothing has to be loosened. The client reads it in
   * ssr-data.js.
   *
   * The config is simply gone: index.html already loads /shop/tenant-config.js
   * from the gateway, which is same-origin and allowed, and that is where the
   * browser has always got it. The inline copy was belt-and-braces that the
   * policy forbade anyway.
   */
  const seed = data
    ? `<script type="application/json" id="ssr-data">${JSON.stringify(data).replace(/</g, '\\u003C')}</script>`
    : '';
  const cfg = '';
  let out = shellHtml
    .replace('<div id="root"></div>', `<div id="root">${appHtml}</div>${cfg}${seed}`)
    .replace('<html>', `<html lang="${(config.locale || 'und').replace(/"/g, '')}">`);
  if (headTags) {
    // the template carries a generic <title>; the page's own wins when it has
    // one, and the template's stays when it does not — stripping it either way
    // left pages with no title at all, which is worse than a generic one
    if (/<title>/.test(headTags)) {
      out = out.replace(/<title>[^<]*<\/title>/, '');
    }
    out = out.replace('</head>', `  ${headTags}\n</head>`);
  }
  return out;
}

createServer(async (req, res) => {
  const url = new URL(req.url, 'http://ssr.local');
  if (url.pathname === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('ok');
    return;
  }
  const host = req.headers['x-forwarded-host'] || req.headers.host;
  try {
    const page = classify(url.pathname);
    if (page.kind === 'unknown') {
      // the shape is not a page at all — say so the way nginx does, with no
      // document, because there is no page here to render
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('not found');
      return;
    }
    /*
     * AN OFFERING'S HEAD IS ASKED FOR FIRST, because it is the thing that knows
     * a withdrawn product from a missing one. The catalogue's public read 404s
     * for both — a no-oracle rule it keeps on purpose — so resolving the page
     * before asking would turn every retired URL into a 404 and throw away its
     * inbound links. It also saves a wasted read when the answer is a redirect.
     */
    const config = await tenantConfig(host);
    let meta = null;
    if (page.kind === 'offering') {
      meta = await offeringMeta(page.id, host);
      if (meta && meta.redirect) {
        // a product that is no longer sold MOVED; 301 keeps what the URL earned
        res.writeHead(301, { Location: meta.redirect, 'Cache-Control': 'no-cache' });
        res.end();
        return;
      }
    }
    const { status, data } = await resolve(page, host);
    const html = render(url.pathname + url.search, config, data);

    // The head is assembled from facts, never from the rendered body. An
    // offering's come from the catalogue's projection; a shelf and the root get
    // a title and a canonical, and deliberately no product JSON-LD — claiming
    // a single Product for a page that lists many would be a lie a crawler
    // acts on.
    const base = `http${req.headers['x-forwarded-proto'] === 'https' || !host ? 's' : ''}://${host || 'localhost'}`;
    const brand = config.brandName || 'the operator';
    let headTags = '';
    if (status === 200 && page.kind === 'offering') {
      headTags = meta
        ? head(meta, meta.canonical, meta.title)
        : head(null, `${base}/shop/offering/${page.id}`, null);
    } else if (status === 200 && page.kind === 'shelf') {
      const label = page.slug.replace(/-/g, ' ');
      headTags = head(null, `${base}/shop/category/${page.slug}`, `${label} — ${brand}`);
    } else if (status === 200 && page.kind === 'home') {
      headTags = head(null, `${base}/shop/`, `${brand} · shop`);
    }

    res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' });
    res.end(document(await template(), html, config, data, headTags));
  } catch (e) {
    // A render that throws must not serve a half-written page: say so plainly
    // and let the gateway's circuit breaker fall back to the shell. Silence
    // here would look like a thin page to a crawler, which is worse than an
    // error the gateway can act on.
    res.writeHead(500, { 'Content-Type': 'text/plain' });
    res.end(`server render failed: ${e.message}`);
  }
}).listen(PORT, () => console.log(`storefront ssr on :${PORT}`));
