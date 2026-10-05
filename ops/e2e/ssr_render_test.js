/* The shop renders on a server, with no browser and no JavaScript. Suite #257.
 *
 * SEO-4 (#180), FIRST TRACER BULLET. The arc replaces dual-rendering-by-
 * User-Agent with one document for humans and machines. Before any of that can
 * be designed, one question has to be answered with something running: can this
 * React application render a real public page to complete HTML on a server at
 * all?
 *
 * It could not. Three things were in the way, and none of them were obvious
 * from reading the ticket:
 *
 *  1. TWO MODULES READ `window` AT IMPORT TIME. `auth.js` and `address.js`
 *     captured the tenant manifest as a module constant, so the module graph
 *     threw before anything could render. Thirteen more read it during render.
 *  2. THE APP GATES EVERY ROUTE BEHIND A CLIENT SESSION BOOTSTRAP. `App.jsx`
 *     starts in `boot` and resolves the session in an effect — and effects do
 *     not run during `renderToString`. Every public page therefore rendered the
 *     string "Loading…" and nothing else. This is the one that mattered: it
 *     would have produced a technically-successful SSR deployment serving
 *     crawlers a loading spinner.
 *  3. THE ROUTER'S BASENAME. The gateway strips `/shop` before the storefront
 *     sees a request, but the router keeps the basename so its links carry the
 *     prefix a browser will follow. The server has to put it back.
 *
 * What this proves, with no fleet and no browser:
 *
 *  - THE SSR BUILD WORKS. The server bundle is built here, from the same
 *    sources the client bundle uses, so a change that breaks server rendering
 *    fails this suite rather than a deployment.
 *  - A REAL PAGE, NOT A SPINNER. The shop root renders the tenant's brand, the
 *    products the server resolved, and links that carry `/shop/`.
 *  - THE DATA THE SERVER ALREADY HAD. Products appear because the renderer
 *    seeded them, not because an effect fetched them — that is the difference
 *    between a complete page and valid HTML that says nothing.
 *  - NO BROWSER IS TOUCHED. The render runs in plain Node with Web Storage,
 *    `window` and `document` deleted from the global scope first — see the
 *    block below, and the reason it exists: this suite passed for a fortnight
 *    while the runtime image answered 500 on every page, because the laptop's
 *    Node supplied a `sessionStorage` the image's Node does not.
 *  - THE TENANT MANIFEST THE GATEWAY ACTUALLY SENDS. Rendering with the three
 *    fields a test author thinks of exercises fewer code paths than a real
 *    config does; the full manifest shape is rendered here too, because the
 *    fields nobody thinks about are the ones that reach for a browser.
 *  - REQUEST SCOPE, NOT MODULE SCOPE. Two requests interleaved across an await
 *    keep their own tenant, and the Norwegian one is served Norwegian while the
 *    English one is not. Module state cannot survive that test, which is why it
 *    is here rather than a comment promising it would be fine.
 *
 * HONEST LIMITS, and they are the reason this is a tracer rather than the arc:
 *  - It renders the two PUBLIC routes — the shop root and an offering. The
 *    account pages still fetch in effects and would render their loading
 *    state; they are behind sign-in and `noindex`, so that is correct rather
 *    than pending.
 *  - HELP PAGES ARE NOT COVERED, and not for a rendering reason: knowledge
 *    articles require `knowledge:read`, so there is no anonymous door to render
 *    from. Making them crawlable means publishing content that is behind
 *    sign-in today, which is the operator's decision and not a renderer's.
 *  - Streaming is not used. `renderToString` is synchronous, so nothing here
 *    exercises Suspense or partial flushing; request scope is in place so that
 *    move does not become a correctness problem, but it has not been made.
 *  - THIS SUITE DEPLOYS NOTHING AND SERVES NOTHING. It renders in-process, so
 *    it cannot tell you what a crawler asking the gateway receives — and that
 *    gap is not academic: everything here passed while the deployed renderer
 *    answered 500 on every page. Suite #258 covers the wiring, and found three
 *    defects this one structurally could not.
 */
const { execFileSync } = require('child_process');
const path = require('path');

const APP = path.resolve(__dirname, '../../apps/storefront');
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);

/*
 * THE LAPTOP MUST REPRODUCE THE IMAGE.
 *
 * This suite claimed "no browser is touched" and was wrong about it for a
 * fortnight. Node 22 — what the runtime image runs — has no Web Storage; Node
 * 24 and later expose `sessionStorage`, and this laptop runs Node 26. So
 * `tokenClaims()` reading `sessionStorage` on every render passed here and
 * threw in the container, and the first thing the deployed renderer did was
 * answer 500 on every page.
 *
 * A gate that depends on which Node happens to be installed is not a gate, so
 * the globals go before anything is imported. Deleted rather than asserted
 * absent: asserting would make the suite fail on a newer Node instead of
 * proving the thing it is here to prove.
 */
for (const api of ['sessionStorage', 'localStorage', 'window', 'document']) {
  if (api in globalThis) {
    delete globalThis[api];
    console.log(`-- removed globalThis.${api}: this Node provides it, the runtime image does not`);
  }
}

(async () => {
  /* ---------- the server bundle builds from the same sources ---------- */
  // THROUGH THE NPM SCRIPT, not a hand-rolled vite line. This suite used to
  // spell the build out itself, which meant `npm run build:ssr` — the command
  // the Dockerfile runs — was never executed by anything and had been broken
  // since the day it was written (Vite 8 dropped `--ssrEmitAssets` from the
  // CLI, so it exited 1). A build command that only CI and a Dockerfile run is
  // a build command nobody tests; this is now one command with one prover.
  execFileSync('npm', ['run', 'build:ssr'],
    { cwd: APP, stdio: ['ignore', 'ignore', 'inherit'] });
  ok('the server bundle builds from the same sources as the client, through the npm script the image uses');

  const { render, withRequest } = await import(path.join(APP, 'dist-ssr/entry-server.js'));

  /* ---------- a real page, not a spinner ---------- */
  const config = { brandName: 'MyGenAlpha', locale: 'en', currency: 'EUR' };
  const data = {
    offerings: [
      { id: 'ssr-1', name: 'SSR Home Bundle', description: 'fibre, mobile and TV',
        category: [{ name: 'Bundles' }], isBundle: true, lifecycleStatus: 'Active' },
      { id: 'ssr-2', name: 'SSR Second Bundle', description: 'a second one',
        category: [{ name: 'Bundles' }], isBundle: true, lifecycleStatus: 'Active' },
    ],
  };
  const html = render('/', config, data);

  if (!html || html.length < 500) fail(`the render produced ${html.length} bytes — that is not a page`);
  if (/gatepost|Loading…/.test(html)) {
    fail('the server rendered the boot gate: "Loading…" and nothing else'
      + ' — a crawler would receive a spinner, which is the defect this exists to catch');
  }
  ok(`the shop root renders ${html.length} bytes of real page, not the boot gate`);

  if (!html.includes(config.brandName)) fail('the tenant brand is absent — the server config never reached the render');
  ok(`the tenant's own brand is in the document ("${config.brandName}")`);

  /* ---------- a request with no session is a GUEST, not a resolved one ---------- */
  // The first SSR unblock started the server render in `ready`, which means
  // "signed in and resolved" — so `!isCustomer()` was true and every crawler
  // was served the staff-session-leaked-into-the-shop banner with a Switch
  // account prompt. A page whose first words to an answer engine are about
  // somebody else's session is worse than no page.
  for (const marker of ['staff-in-shop', 'switch-account']) {
    if (html.includes(marker)) {
      fail(`the server render shows "${marker}" — it is rendering a signed-in-but-not-a-customer`
        + ' session for a request that has no session at all');
    }
  }
  if (!html.includes('data-testid="shop-tabs"')) fail('the guest shop is missing its own shelves');
  ok('a request with no session renders the guest shop — no staff banner, no account prompt');

  /* ---------- the data the server already had ---------- */
  for (const o of data.offerings) {
    if (!html.includes(o.name)) {
      fail(`"${o.name}" was resolved by the server and is not in the HTML`
        + ' — the page is rendering its loading state, which is valid HTML that says nothing');
    }
  }
  ok(`both server-resolved products are in the HTML, without an effect ever running`);

  if (!html.includes('/shop/offering/ssr-1')) {
    fail('the product link does not carry the /shop basename the gateway strips — a crawler would follow it to nothing');
  }
  ok('links carry the /shop prefix a browser will actually follow');

  /* ---------- nothing reached for a browser ---------- */
  if (typeof globalThis.window !== 'undefined') fail('this test is not running in a bare Node process');
  const second = render('/', { brandName: 'Nova', locale: 'no' }, data);
  if (!second.includes('Nova')) fail('a second render did not pick up the second tenant — config is stuck from the first');
  if (second.includes('MyGenAlpha')) fail('the first tenant leaked into the second render');
  ok('two renders, two tenants, no leak between them — and no browser was touched');

  /* ---------- request scope: correct by construction, not by luck ---------- */
  const A = { brandName: 'MyGenAlpha', locale: 'en', currency: 'EUR' };
  const B = { brandName: 'Nova', locale: 'no', currency: 'NOK' };
  const [a, b] = await Promise.all([
    withRequest(A, data, async () => { await new Promise((r) => setTimeout(r, 30)); return render('/', A, data); }),
    withRequest(B, data, async () => { await new Promise((r) => setTimeout(r, 10)); return render('/', B, data); }),
  ]);
  if (!a.includes('MyGenAlpha') || a.includes('Nova')) fail('the slower request came back wearing the other tenant\'s brand');
  if (!b.includes('Nova') || b.includes('MyGenAlpha')) fail('the faster request came back wearing the other tenant\'s brand');
  ok('two requests interleaved across an await keep their own tenant — module state could not survive this');

  /* ---------- the offering page: what a crawler is actually sent today ---------- */
  // This is the route the User-Agent branch currently serves from Java. It is
  // the one that has to render here before that branch can ever be deleted.
  const offeringData = {
    offering: {
      id: 'off-1', name: 'GenAlpha Fiber 1000', description: '1 Gbps fibre to the home',
      category: [{ name: 'Broadband' }], isBundle: false, lifecycleStatus: 'Active',
      productOfferingPrice: [{ id: 'p1' }],
    },
    prices: {
      p1: { id: 'p1', name: 'Monthly', priceType: 'recurring', recurringChargePeriodType: 'month',
        price: { unit: 'EUR', value: 39.99 } },
    },
  };
  const offeringHtml = render('/offering/off-1', A, offeringData);
  if (/gatepost|Loading…/.test(offeringHtml)) fail('the offering page rendered the boot gate');
  if (!offeringHtml.includes('GenAlpha Fiber 1000')) fail('the offering name is not in the server-rendered page');
  if (!offeringHtml.includes('1 Gbps fibre to the home')) fail('the offering description is missing');
  if (!/39[.,]99/.test(offeringHtml)) {
    fail('the price is not in the server-rendered page — the price index was not seeded, so a crawler sees a product with no price');
  }
  ok(`the offering page renders ${offeringHtml.length} bytes with its name, description and price`);

  /* ---------- an unseeded page still renders, it just says less ---------- */
  const unseeded = render('/offering/off-1', A, null);
  if (/gatepost/.test(unseeded)) fail('an unseeded route fell back to the boot gate rather than the page');
  if (unseeded.includes('GenAlpha Fiber 1000')) fail('an unseeded render produced data from somewhere');
  ok('an unseeded route still renders the page frame — the client path is unchanged');

  /* ---------- a shelf is a page now, not a tab ---------- */
  const shelfData = {
    offerings: [
      { id: 'm1', name: 'SSR Mobile 60 GB', description: '60 GB', category: [{ name: 'Mobile plans' }],
        isBundle: false, lifecycleStatus: 'Active' },
      { id: 'b1', name: 'SSR Home Bundle', description: 'triple play', category: [{ name: 'Bundles' }],
        isBundle: true, lifecycleStatus: 'Active' },
    ],
    prices: {},
  };
  const mobile = render('/category/mobile', A, shelfData);
  if (/gatepost/.test(mobile)) fail('the category page rendered the boot gate');
  if (!mobile.includes('SSR Mobile 60 GB')) fail('the mobile shelf does not carry the mobile plan');
  if (mobile.includes('SSR Home Bundle')) {
    fail('the mobile shelf carries a bundle — the shelf rule differs from the shop tab it was taken from');
  }
  if (!mobile.includes('/shop/category/internet')) {
    fail('a shelf does not link to its siblings — a crawler that lands here cannot reach the rest');
  }
  ok(`the mobile shelf renders ${mobile.length} bytes: its own products, not the others, and links onward`);

  const rootWithShelves = render('/', A, shelfData);
  if (!rootWithShelves.includes('/shop/category/mobile')) {
    fail('the shop root does not link to its shelves — the crawl graph has no edges, only pages');
  }
  ok('the shop root links to every shelf, so the shelves are reachable rather than merely addressable');

  const unknownShelf = render('/category/nonsense', A, shelfData);
  if (!/Not found|No such part of the shop/.test(unknownShelf)) {
    fail('an unknown shelf rendered an empty page — a crawler reads that as a real shelf with no products');
  }
  ok('an unknown shelf says so rather than rendering an empty one');

  /* ---------- the language follows the request, not the process ---------- */
  // Asserted through the rendered document rather than by importing i18n: the
  // bundle has its own module instance, and testing the source would prove
  // something about a copy nobody serves.
  const [enHtml, noHtml] = await Promise.all([
    withRequest(A, data, async () => { await new Promise((r) => setTimeout(r, 25)); return render('/', A, data); }),
    withRequest(B, data, async () => render('/', B, data)),
  ]);
  if (!/Butikk|Handlekurv|Kundeservice/.test(noHtml)) {
    fail('the Norwegian tenant was served English — i18n captured the language once, for whoever rendered first');
  }
  if (/Butikk|Handlekurv|Kundeservice/.test(enHtml)) {
    fail('the English tenant was served Norwegian — the other request\'s language leaked into it');
  }
  ok('the language follows the request: the Norwegian render says "Butikk", the English one does not');

  /* ---------- the manifest the gateway actually sends ---------- */
  // Every field /shop/tenant-config.js emits, not the three a test author
  // reaches for. The 500 in the container came from a path the short config
  // never rendered, so the full shape is now part of the proof.
  const REAL = {
    issuer: 'http://localhost:8085/realms/bss',
    logoUrl: '/tmf-api/documentManagement/v4/document/brand-logo',
    brandName: 'MyGenAlpha', brandColor: '#0E7C7B', locale: 'en', currency: 'EUR',
    timezone: 'Europe/Oslo', country: '', priceDecimals: null, currencyDisplay: 'symbol',
    priceNote: '', simRegistration: 'off', shopWindow: 'static', supportPhone: '',
    supportWhatsapp: '', supportEmail: '', appStoreUrl: '', playStoreUrl: '',
    privacyUrl: '', termsUrl: '', tagline: '', priceParity: 'uniform',
    businessSales: true, seamVendors: { ocs: 'mock' },
  };
  for (const [label, path, seed] of [['the shop root', '/', data],
    ['a shelf', '/category/mobile', shelfData],
    ['an offering', '/offering/off-1', offeringData]]) {
    const full = render(path, REAL, seed);
    if (/gatepost|Loading…/.test(full)) fail(`${label} rendered the boot gate under the real tenant manifest`);
    if (full.length < 500) fail(`${label} produced ${full.length} bytes under the real tenant manifest`);
  }
  ok('all three public pages render under the full tenant manifest the gateway emits, not a three-field stand-in');

  console.log('\nPASS ssr_render_test — the shop renders on a server, with no browser and no JavaScript');
})().catch((e) => { console.error('\nFAIL ' + e.message); process.exit(1); });
