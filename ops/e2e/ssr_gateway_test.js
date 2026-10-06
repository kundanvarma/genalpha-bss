/* One document for humans and machines, and it runs. Suite #258.
 *
 * SEO-4 (#180), THE SWITCH-OVER. Dual-serving by User-Agent is gone: there is
 * no crawler predicate on the route any more and no second renderer behind one.
 * The Java bot page that answered /shop/offering/** for crawlers is deleted,
 * and everyone — browser or bot — gets the same server-rendered document from
 * the storefront's own React app.
 *
 * Two renderers behind one URL was always a liability: two chances to disagree,
 * and Google reads a disagreement as cloaking. It existed because React could
 * not render on a server here. It can now.
 *
 * WHAT HAD TO SURVIVE THE DELETION. The bot page was the only thing emitting a
 * per-page <title>, a meta description, a canonical link and schema.org
 * Product JSON-LD. Retiring it without those would have been a straight SEO
 * regression, so they now come from the SAME projection via
 * /seo/offering/{id}/meta — which also settles the question the renderer could
 * only flag: the catalogue is the authority for which price leads, not the
 * React page.
 *
 * THREE BUGS THIS SUITE EXISTS TO CATCH, all of them invisible to a crawler and
 * fatal to a human, which is why serving only bots proved so little:
 *
 *  1. THE DOCUMENT MUST BE RUNNABLE. The renderer baked its own copy of
 *     index.html, so when the two images were built apart it referenced a Vite
 *     hash nginx did not serve: the page rendered perfectly and loaded NO
 *     JAVASCRIPT. No hydration, no routing, no cart. A crawler never notices,
 *     because it does not run the script it cannot fetch. The shell is fetched
 *     from nginx at run time now, and every asset the document names is
 *     fetched here.
 *  2. THE SEED MUST SURVIVE THE CONTENT POLICY. The gateway sets
 *     `script-src 'self'` with no unsafe-inline, so the inline
 *     `window.__SSR_DATA__ = …` was BLOCKED in a browser and the client threw
 *     the server render away and rebuilt it. It rides in a
 *     `type="application/json"` block now, which is data and not executed.
 *  3. REACT MUST ACTUALLY HYDRATE. A mismatch between the server's first render
 *     and the client's makes React discard the markup — the one thing
 *     server-rendering exists to avoid. The server renders the GUEST state and
 *     the client now starts there too when a seed is present.
 *
 * What this proves, through the gateway:
 *
 *  - ONE DOCUMENT. A human and a crawler get byte-identical HTML for the same
 *    offering URL, with the product named in it.
 *  - THE HEAD IS INTACT. Title, description, canonical and a schema.org
 *    Product JSON-LD whose name is the product's.
 *  - THE JAVA ROUTE IS GONE. /seo/offering/{id} no longer answers through the
 *    gateway.
 *  - IT RUNS. Every asset resolves, the seed is policy-safe, a real browser
 *    hydrates it with no console errors, and a nav click routes in the client
 *    rather than reloading the document.
 *  - THE CRAWLER SURFACES STILL AGREE. All seven shelves on the sitemap answer
 *    200 to both, and an unknown shelf is a 404 to both with a real page in it.
 *  - THE BREAKER STILL FALLS BACK. With the renderer stopped, a visitor gets
 *    the nginx shell — a working client-rendered shop, not an error page. It
 *    matters more now than when only bots came here.
 *
 * HONEST LIMITS:
 *  - THE BREAKER CHECK STOPS A CONTAINER. It is last, and the restart is in a
 *    `finally`; a run killed between the two leaves `bss-storefront-ssr`
 *    stopped, and `docker compose up -d storefront-ssr` is the repair.
 *  - A signed-in visitor opening a public page now sees the guest header for
 *    the moment before the session resolves, where they used to see a spinner.
 *    That is a deliberate trade, not an oversight.
 *  - Only the three public page kinds are rendered. The cart, support and every
 *    signed-in page are still nginx-served shells, because they are behind a
 *    session the renderer has no business holding.
 *  - Streaming is still not used, and the shelf and root pages carry no
 *    JSON-LD: claiming a single Product for a page that lists many would be a
 *    lie a crawler acts on.
 */
const { execFileSync } = require('child_process');

const API = 'http://localhost:8080';
const CAT = '/tmf-api/productCatalogManagement/v4';
const BOT = 'Mozilla/5.0 (compatible; GPTBot/1.2; +https://openai.com/gptbot)';
const HUMAN = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/131.0 Safari/537.36';
const SSR = 'bss-storefront-ssr';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);

/** The markup, without the hydration seed — the seed carries every offering. */
const markup = (html) => html.split('<script type="application/json" id="ssr-data">')[0];
/* A product called "Home & Mobile" is rendered as "Home &amp; Mobile", so a
 * name compared raw against the markup is a false failure — and, worse, a false
 * pass for any assertion that a name is ABSENT. */
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

async function get(path, agent) {
  const r = await fetch(API + path, { headers: { 'User-Agent': agent, 'Cache-Control': 'no-cache' } });
  return { status: r.status, text: await r.text(), server: r.headers.get('server') || '' };
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** The renderer is behind a breaker, so a cold or just-restarted route may serve
 *  the fallback for a moment. Poll for the real page rather than racing it. */
async function untilRendered(path, tries = 24) {
  for (let i = 0; i < tries; i += 1) {
    const got = await get(path, BOT);
    if (got.text.length > 5000) return got;
    await sleep(5000);
  }
  fail(`${path} never came back server-rendered — the breaker stayed open`);
  return null;
}

(async () => {
  let stopped = false;
  try {
    /* ---------- what the catalogue actually holds ---------- */
    const offerings = await fetch(`${API}${CAT}/productOffering?lifecycleStatus=Active&limit=100`)
      .then((r) => r.json());
    const bundle = offerings.find((o) => o.isBundle);
    const mobile = offerings.find((o) => !o.isBundle
      && ((o.category || [])[0] || {}).name === 'Mobile plans');
    if (!bundle || !mobile) fail('the fleet has no bundle and no mobile plan to tell the shelves apart with');
    ok(`the catalogue holds ${offerings.length} active offerings, including "${mobile.name}" and the bundle "${bundle.name}"`);

    /* ---------- 1. a crawler gets the products ---------- */
    const root = await untilRendered('/shop/');
    if (root.status !== 200) fail(`a crawler asking for /shop/ got ${root.status}`);
    if (/gatepost|Loading…/.test(markup(root.text))) {
      fail('a crawler received the boot gate — a spinner, server-rendered');
    }
    if (!markup(root.text).includes(esc(bundle.name))) {
      fail(`the server-rendered shop root does not name "${bundle.name}"`
        + ' — the document reached the crawler without the catalogue in it');
    }
    for (const marker of ['staff-in-shop', 'switch-account']) {
      if (markup(root.text).includes(marker)) {
        fail(`the crawler's document shows "${marker}" — it is being served somebody's session state`);
      }
    }
    ok(`a crawler gets ${root.text.length} bytes on /shop/ with real products in it, as a guest`);

    /* ---------- 2. the shelf rule survives the wire ---------- */
    const shelf = await untilRendered('/shop/category/mobile');
    if (shelf.status !== 200) fail(`a crawler asking for the mobile shelf got ${shelf.status}`);
    if (!markup(shelf.text).includes(esc(mobile.name))) {
      fail(`the mobile shelf does not name "${mobile.name}", which the catalogue files under Mobile plans`);
    }
    if (markup(shelf.text).includes(esc(bundle.name))) {
      fail(`the mobile shelf's markup names the bundle "${bundle.name}"`
        + ' — the shelf rule means one thing in the app and another through the gateway');
    }
    ok(`the mobile shelf carries "${mobile.name}" and not the bundle — one shelf rule, app and server`);

    /* ---------- 3. ONE DOCUMENT for humans and machines ---------- */
    // The acceptance for the whole arc. Not "the human page also works" —
    // byte-identical, because two renderers behind one URL is what Google reads
    // as cloaking and what made the old dual-serve a liability.
    const humanOffering = await get(`/shop/offering/${mobile.id}`, HUMAN);
    const botOffering = await get(`/shop/offering/${mobile.id}`, BOT);
    if (humanOffering.status !== 200) fail(`a human asking for an offering got ${humanOffering.status}`);
    if (humanOffering.text !== botOffering.text) {
      fail('a human and a crawler received DIFFERENT documents for the same URL'
        + ` (${humanOffering.text.length} vs ${botOffering.text.length} bytes)`);
    }
    if (!markup(humanOffering.text).includes(esc(mobile.name))) {
      fail('the offering document does not name the product it is about');
    }
    ok(`a human and a crawler get the same ${humanOffering.text.length}-byte document, products included`);

    /* ---------- 4. the machine-readable head survived the Java page ---------- */
    // The bot page used to be the ONLY source of these. Retiring it without
    // them would have been a straight SEO regression, so they now come from the
    // same schema.org projection through /seo/offering/{id}/meta.
    const headFacts = {
      title: /<title>([^<]+)<\/title>/.exec(humanOffering.text),
      description: /<meta name="description" content="([^"]+)"/.exec(humanOffering.text),
      canonical: /<link rel="canonical" href="([^"]+)"/.exec(humanOffering.text),
    };
    for (const [what, m] of Object.entries(headFacts)) {
      if (!m) fail(`the offering page has no ${what} — the Java bot page emitted one and this must too`);
    }
    if (!headFacts.title[1].includes(mobile.name)) {
      fail(`the title is "${headFacts.title[1]}" and does not name the product`);
    }
    if (!/<script type="application\/ld\+json">/.test(humanOffering.text)) {
      fail('the offering page carries no JSON-LD — the structured data the bot page published is gone');
    }
    const ld = JSON.parse(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/
      .exec(humanOffering.text)[1]);
    if (ld['@type'] !== 'Product' || ld.name !== mobile.name) {
      fail(`the JSON-LD describes ${JSON.stringify(ld['@type'])}/${JSON.stringify(ld.name)},`
        + ` not the Product "${mobile.name}"`);
    }
    ok(`the head carries title, description, canonical and schema.org Product JSON-LD for "${ld.name}"`);

    /* ---------- 4b. the Java bot route is GONE ---------- */
    const seo = await fetch(`${API}/seo/offering/${mobile.id}`, { headers: { 'User-Agent': BOT } });
    if (seo.status === 200) {
      fail('/seo/offering/{id} still answers through the gateway — the dual-serve route'
        + ' was supposed to be deleted, and a second renderer for one URL is the thing this arc removes');
    }
    ok(`the Java bot page is no longer routed (/seo/offering/{id} → ${seo.status})`);

    /* ---------- 5. three copies of the shelf list agree ---------- */
    const sitemap = await get('/sitemap.xml', BOT);
    const slugs = [...new Set([...sitemap.text.matchAll(/\/shop\/category\/([a-z0-9-]+)/g)]
      .map((m) => m[1]))];
    if (slugs.length < 2) fail(`the sitemap advertises ${slugs.length} shelves — nothing to cross-check`);
    for (const slug of slugs) {
      const asBot = await get(`/shop/category/${slug}`, BOT);
      const asHuman = await get(`/shop/category/${slug}`, HUMAN);
      if (asBot.status !== 200) {
        fail(`the sitemap advertises /shop/category/${slug} and the renderer answers ${asBot.status}`
          + ' — the catalog\'s shelf list and the router\'s have drifted');
      }
      if (asHuman.status !== 200) {
        fail(`the sitemap advertises /shop/category/${slug} and nginx answers ${asHuman.status}`
          + ' — the catalog\'s shelf list and nginx\'s have drifted');
      }
    }
    ok(`all ${slugs.length} shelves on the sitemap answer 200 to a bot and to a human: ${slugs.join(', ')}`);

    /* ---------- 6. a page that does not exist says so, to both ---------- */
    for (const [path, agent, who] of [
      ['/shop/category/not-a-shelf', BOT, 'a crawler'],
      ['/shop/category/not-a-shelf', HUMAN, 'a human'],
      ['/shop/category', BOT, 'a crawler'],
    ]) {
      const got = await get(path, agent);
      if (got.status !== 404) {
        fail(`${path} answered ${got.status} to ${who} — a 200 on a URL that does not exist`
          + ' is the most expensive kind of wrong, because nothing downstream can detect it');
      }
    }
    const notFound = await get('/shop/category/not-a-shelf', BOT);
    if (!/Not found|No such part of the shop/.test(notFound.text)) {
      fail('the 404 served to a crawler has no document in it — a crawler reads a 404 body too');
    }
    ok('an unknown shelf is a 404 for both, and the crawler\'s 404 carries a real page');

    /* ---------- 6b. THE DOCUMENT MUST BE RUNNABLE ---------- */
    // The bug this exists for: the renderer baked its own copy of index.html,
    // so when the two images were built apart it referenced a Vite hash nginx
    // did not serve. The page rendered perfectly and then loaded NO
    // JAVASCRIPT — no hydration, no routing, no cart. A crawler never notices,
    // because it does not run the script it cannot fetch. The shell is fetched
    // from nginx at run time now; this is what proves it.
    const assets = [...humanOffering.text.matchAll(/(?:src|href)="(\/shop\/assets\/[^"]+)"/g)]
      .map((m) => m[1]);
    if (!assets.some((a) => a.endsWith('.js'))) {
      fail('the server-rendered document references no javascript bundle at all —'
        + ' it could never hydrate');
    }
    for (const a of assets) {
      const r = await fetch(API + a, { headers: { 'User-Agent': HUMAN } });
      if (r.status !== 200) {
        fail(`the document references ${a} and it answers ${r.status} —`
          + ' the renderer and nginx disagree about the bundle, so the page is dead on arrival');
      }
    }
    ok(`every asset the document references resolves (${assets.length}: ${assets.join(', ')})`);

    /* ---------- 6c. THE SEED SURVIVES THE CONTENT POLICY ---------- */
    // The gateway sets `script-src 'self'` with no unsafe-inline, so an inline
    // `window.__SSR_DATA__ = …` is blocked in a browser and the client silently
    // re-renders from scratch. A JSON data block is not executed, so the policy
    // does not apply. Again: invisible to a crawler, fatal for a human.
    if (/<script>\s*window\.__SSR_DATA__/.test(humanOffering.text)) {
      fail('the seed is an inline script — the gateway\'s content policy blocks it,'
        + ' and the browser throws the server render away');
    }
    if (!humanOffering.text.includes('<script type="application/json" id="ssr-data">')) {
      fail('the document carries no seed block, so a browser cannot hydrate from it');
    }
    ok('the hydration seed rides in a JSON block, which the content policy permits');

    /* ---------- 6d. a browser hydrates it, with no complaints ---------- */
    const { chromium } = require('playwright');
    const browser = await chromium.launch();
    const page = await browser.newPage();
    const errors = [];
    const broken = [];
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text().slice(0, 140)); });
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message.slice(0, 140)}`));
    page.on('response', (r) => { if (r.status() >= 400) broken.push(`${r.status()} ${r.url()}`); });
    try {
      await page.goto(`${API}/shop/offering/${mobile.id}`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(1200);
      const seen = await page.title();
      if (!seen.includes(mobile.name)) {
        fail(`the browser's title is "${seen}" — the client overwrote the server's per-page title`);
      }
      const hydrationErrors = errors.filter((e) => /hydrat|did not match|Minified React error #(418|423|425)/i.test(e));
      if (hydrationErrors.length) {
        fail(`React would not hydrate the server markup: ${hydrationErrors[0]}`);
      }
      if (broken.length) fail(`the page requested something that failed: ${broken.slice(0, 2).join(', ')}`);
      if (errors.length) fail(`the hydrated page logged errors: ${errors.slice(0, 2).join(' | ')}`);
      // and it is actually alive: a click must route in the client, not reload
      // the document. A marker on `window` is the honest probe — Playwright's
      // `framenavigated` fires for pushState too, so counting it proves
      // nothing, which this suite learned the hard way.
      await page.evaluate(() => { window.__hydrationWitness = true; });
      const from = page.url();
      // the Shop link, deliberately: Support asks a signed-in question and so
      // starts a real sign-in redirect, which looks exactly like a failed
      // hydration and is not one
      await page.click('nav.nav a[href="/shop"]').catch(() => {});
      await page.waitForTimeout(900);
      const moved = page.url() !== from;
      const survived = await page.evaluate(() => window.__hydrationWitness === true);
      if (!moved) fail('clicking the nav went nowhere — the hydrated app is not handling links');
      if (!survived) {
        fail('the click reloaded the document (the window marker is gone) — React is not'
          + ' routing, so the page never hydrated');
      }
      ok(`a browser hydrates it: title "${seen}", no console errors, and a nav click`
        + ` routes in the client to ${page.url().replace(API, '')}`);
    } finally {
      await browser.close();
    }

    /* ---------- 7. the breaker falls back ---------- */
    // LAST, because it stops a container. Without this the route turns one
    // unhealthy container into 502s for Googlebot on the shop's most linked URL.
    execFileSync('docker', ['stop', SSR], { stdio: ['ignore', 'ignore', 'inherit'] });
    stopped = true;
    let served = null;
    for (let i = 0; i < 12; i += 1) {
      served = await get('/shop/', BOT);
      if (served.status === 200 && served.text.includes('<div id="root"></div>')) break;
      await sleep(2000);
    }
    if (served.status !== 200) {
      fail(`with the renderer stopped a crawler got ${served.status} — the breaker did not fall back,`
        + ' so one unhealthy container means errors on the shop\'s most linked URL');
    }
    if (!served.text.includes('<div id="root"></div>')) {
      fail('the fallback served something that is not the app shell');
    }
    ok(`with the renderer stopped, a crawler still gets a ${served.text.length}-byte document —`
      + ' the shell it received before any of this existed');

    console.log('\nPASS ssr_gateway_test — one document for humans and machines, and it runs');
  } finally {
    if (stopped) {
      console.log('restarting the renderer…');
      execFileSync('docker', ['start', SSR], { stdio: ['ignore', 'ignore', 'inherit'] });
      for (let i = 0; i < 30; i += 1) {
        const up = execFileSync('docker', ['ps', '--filter', `name=${SSR}`,
          '--filter', 'health=healthy', '-q']).toString().trim();
        if (up) break;
        await sleep(2000);
      }
      console.log('renderer back');
    }
  }
})().catch((e) => { console.error('\nFAIL ' + e.message); process.exit(1); });
