/*
 * THE SERVER-RENDERING RUNTIME (#180, tracer bullet).
 *
 * The public routes, rendered by React on a server, to prove the path end to end:
 * the SSR build loads, the app renders to a string with the tenant's own
 * config, the data it needs is fetched BEFORE the render rather than in an
 * effect that never runs, and the document a crawler receives is complete with
 * no JavaScript executed.
 *
 * WHAT THIS IS NOT, and the reasons are in docs/geo-discoverability-plan.md:
 *  - not the storefront's runtime. nginx still serves the app; this sits beside
 *    it on its own path, so nothing existing changes and a bad render is
 *    reversible by deleting a gateway route.
 *  - not streaming. `renderToString` is synchronous, so Suspense and partial
 *    flushing are unexercised. The tenant is request-scoped, so that move is a
 *    performance decision rather than a correctness one.
 *  - not the crawler route's replacement. That is deleted only once SSR is
 *    proven in production, because removing it first makes a bad deploy
 *    invisible to crawlers and visible to Google.
 */
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { render } from '../dist-ssr/entry-server.js';

const PORT = Number(process.env.PORT || 8080);
const CATALOG = process.env.CATALOG_URL || 'http://product-catalog:8080';
const GATEWAY = process.env.GATEWAY_URL || 'http://gateway:8080';
const TIMEOUT = Number(process.env.SSR_FETCH_TIMEOUT_MS || 4000);

/** The built client bundle's tags, lifted from the client build's index.html. */
const TEMPLATE = readFileSync(new URL('../dist/index.html', import.meta.url), 'utf8');

/**
 * A slow catalogue must not become a slow page. The ticket's honest limit says
 * SSR needs the same timeout treatment as any other service on the request
 * path; this is that treatment, and it fails to an empty seed rather than a
 * 500, so the page still renders and the client fetches as it always did.
 */
async function fetchJson(url, headers) {
  const stop = AbortSignal.timeout(TIMEOUT);
  try {
    const res = await fetch(url, { headers, signal: stop });
    return res.ok ? await res.json() : null;
  } catch {
    return null;
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
 * caps a page at 100 and the shelf outgrew that.
 */
async function page(pathAndQuery, host) {
  const headers = host ? { 'X-Forwarded-Host': host, Host: host } : {};
  const all = [];
  for (let offset = 0; ; offset += 100) {
    const sep = pathAndQuery.includes('?') ? '&' : '?';
    const got = await fetchJson(`${CATALOG}${pathAndQuery}${sep}limit=100&offset=${offset}`, headers);
    if (!Array.isArray(got)) break;
    all.push(...got);
    if (got.length < 100) break;
  }
  return all;
}

/**
 * What this path needs resolved before it can render.
 *
 * THE SAME ENDPOINTS THE CLIENT CALLS, deliberately. The acceptance for this
 * arc is that a bot and a browser receive the same document, and the cheapest
 * way to guarantee that is for both to read the same shapes from the same
 * doors — no mapping layer in between to drift.
 *
 * That leaves one question open, and it is a real one: the crawler page renders
 * from the shared projection (#179), which decides which price leads, while the
 * React app has its own price logic in money.js. They agree today. When the
 * crawler route is finally deleted, somebody has to decide which of the two is
 * the authority for a headline price — this file is not the place to decide it
 * quietly.
 */
async function resolve(path, host) {
  const headers = host ? { 'X-Forwarded-Host': host, Host: host } : {};
  const route = path.split('?')[0];

  if (route === '/' || route === '/shop') {
    const offerings = await page('/tmf-api/productCatalogManagement/v4/productOffering?lifecycleStatus=Active', host);
    return offerings.length ? { offerings } : null;
  }

  const offeringMatch = route.match(/^\/offering\/([^/]+)\/?$/);
  if (offeringMatch) {
    const [offering, priceRows] = await Promise.all([
      fetchJson(`${CATALOG}/tmf-api/productCatalogManagement/v4/productOffering/${encodeURIComponent(offeringMatch[1])}`, headers),
      page('/tmf-api/productCatalogManagement/v4/productOfferingPrice', host),
    ]);
    if (!offering || !offering.id) return null;
    const prices = {};
    for (const p of priceRows) prices[p.id] = p;
    return { offering, prices };
  }

  return null;
}

function document(appHtml, config, data) {
  const seed = data ? `<script>window.__SSR_DATA__=${JSON.stringify(data).replace(/</g, '\\u003C')}</script>` : '';
  const cfg = `<script>window.BSS_STOREFRONT_CONFIG=${JSON.stringify(config).replace(/</g, '\\u003C')}</script>`;
  return TEMPLATE
    .replace('<div id="root"></div>', `<div id="root">${appHtml}</div>${cfg}${seed}`)
    .replace('<html>', `<html lang="${(config.locale || 'und').replace(/"/g, '')}">`);
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
    const config = await tenantConfig(host);
    const data = await resolve(url.pathname + url.search, host);
    const html = render(url.pathname + url.search, config, data);
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' });
    res.end(document(html, config, data));
  } catch (e) {
    // A render that throws must not serve a half-written page: say so plainly
    // and let the gateway decide. Silence here would look like a thin page to
    // a crawler, which is worse than an error.
    res.writeHead(500, { 'Content-Type': 'text/plain' });
    res.end(`server render failed: ${e.message}`);
  }
}).listen(PORT, () => console.log(`storefront ssr on :${PORT}`));
