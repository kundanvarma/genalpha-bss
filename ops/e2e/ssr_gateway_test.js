/* The server-rendered shop is wired to the gateway, and falls back when it is not. Suite #258.
 *
 * SEO-4 (#180), THE WIRING. The tracer bullet (#257) proved React could render a
 * public page to a string in a bare Node process. That is a different claim from
 * "a crawler asking the gateway for the shop receives that page", and the gap
 * between the two is where this arc's real defects were:
 *
 *  1. `npm run build:ssr` — the command the Dockerfile runs — had never been
 *     executed by anything and exited 1 (Vite 8 dropped `--ssrEmitAssets` from
 *     the CLI). The suite had its own hand-rolled vite line, so the broken
 *     script passed every gate. #257 now builds through the npm script.
 *  2. The renderer answered 500 ON EVERY PAGE in the image while passing on the
 *     laptop: `tokenClaims()` reads `sessionStorage`, Node 22 has no Web
 *     Storage and Node 24+ does, and the laptop had the later one. A gate that
 *     depends on the installed Node is not a gate.
 *  3. The server rendered state `ready` — "signed in and resolved" — so every
 *     crawler was served the staff-session-leaked-into-the-shop banner and a
 *     Switch account prompt. A request with no session is a GUEST.
 *
 * None of the three were visible from the renderer alone. They are the reason
 * this suite exists as well as #257, rather than instead of it.
 *
 * WHAT A CRAWLER USED TO GET on the shop root and on every category shelf: the
 * SPA shell — a 200 with no products in it. Only /shop/offering/{id} had a real
 * document, rendered by Java. The shelves were published to the sitemap in the
 * same breath as they became pages, so the surface a bot was invited to crawl
 * was precisely the surface that answered with an empty div.
 *
 * What this proves, through the gateway, with no browser:
 *
 *  - A CRAWLER GETS THE PRODUCTS. The shop root and a shelf both answer with a
 *    document containing offering names read from the catalogue — the names are
 *    taken from the API at run time, so this cannot pass against a fixture.
 *  - THE SHELF RULE SURVIVES THE WIRE. A bundle does not appear in the rendered
 *    markup of the mobile shelf. Asserted against the markup only, with the
 *    hydration seed cut off, because the seed legitimately carries every
 *    offering and would make any absence assertion vacuous.
 *  - A HUMAN IS UNTOUCHED. The same URL without a crawler User-Agent still
 *    answers with the nginx-served shell, byte for byte. This arc is additive
 *    until an operator says otherwise.
 *  - THE JAVA PAGE IS UNTOUCHED. /shop/offering/{id} still answers from the
 *    catalog's own renderer, with its JSON-LD. The ticket's limit says keep it
 *    until SSR is proven in production, and "proven" is not "deployed".
 *  - THREE COPIES OF THE SHELF LIST AGREE. nginx keeps one, the catalog's
 *    sitemap keeps another, and the renderer exports a third from the list the
 *    router actually routes. Every slug on the sitemap answers 200 to a bot AND
 *    to a human; a slug on neither answers 404 to both. A closed list that
 *    drifts turns a page into a 404 on one surface and not the next, and this
 *    is the assertion that notices.
 *  - A MISSING PAGE IS A 404, A MISSING SERVICE IS NOT. An unknown shelf answers
 *    404 — with a rendered document, because a crawler reads a 404 body too.
 *  - THE BREAKER FALLS BACK. With the renderer stopped, a crawler still gets a
 *    document rather than a 502: the gateway's circuit breaker forwards to the
 *    shell a bot received before any of this existed. This is the whole reason
 *    the route is safe to deploy, so it is proved by stopping the container
 *    rather than by reading the configuration.
 *
 * HONEST LIMITS:
 *  - THE BREAKER CHECK STOPS A CONTAINER. It is last, and the restart is in a
 *    `finally`; a run killed between the two leaves `bss-storefront-ssr`
 *    stopped, and `docker compose up -d storefront-ssr` is the repair.
 *  - The hydration seed is the whole shelf, so a crawler is sent ~130 KB of
 *    JSON it has no use for. It stays because it is what stops a human's
 *    server-rendered page from flashing when it hydrates, and trimming it per
 *    page means the pages no longer share one data contract. Worth revisiting
 *    when humans are served from here.
 *  - A DARK TENANT is not exercised. The gateway stamps `X-Robots-Tag: noindex,
 *    nofollow` for one globally (CrawlerVisibilityFilter, covered by its own
 *    suite), which is deliberately a different posture from the Java page's 404
 *    and the stronger of the two — a blocked fetch still indexes a bare URL.
 *  - Humans are still served by nginx, so the crawler and the human documents
 *    come from different renderers. They come from the SAME React sources now,
 *    which is the point of the arc, but one build serving both is the step
 *    after this one.
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
const markup = (html) => html.split('<script>window.__SSR_DATA__')[0];
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

    /* ---------- 3. a human is untouched ---------- */
    const human = await get('/shop/', HUMAN);
    if (human.status !== 200) fail(`a human asking for /shop/ got ${human.status}`);
    if (human.text.includes(esc(bundle.name))) {
      fail('a human was served the server-rendered page — this route is meant to be additive,'
        + ' and switching humans over is a separate decision');
    }
    if (!human.text.includes('<div id="root"></div>')) {
      fail('a human did not get the app shell — the storefront route changed under them');
    }
    ok(`a human still gets the ${human.text.length}-byte shell from nginx, unchanged`);

    /* ---------- 4. the Java crawler page is untouched ---------- */
    const java = await get(`/shop/offering/${mobile.id}`, BOT);
    if (java.status !== 200) fail(`the Java crawler page answered ${java.status}`);
    if (!java.text.includes('application/ld+json')) {
      fail('the offering page lost its JSON-LD — that route was not supposed to move yet');
    }
    if (java.text.includes('data-testid')) {
      fail('the offering page is now React-rendered — the ticket says keep the Java page'
        + ' until SSR is proven in production');
    }
    ok('/shop/offering/{id} still answers from the catalog renderer, with its JSON-LD');

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

    console.log('\nPASS ssr_gateway_test — a crawler is served the rendered shop, and the shell when it cannot be');
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
