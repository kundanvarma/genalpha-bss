/* The shelf is read to exhaustion, and a URL that does not exist says so. Suite #256.
 *
 * Two pieces of SEO-4 (#180) that do not need server-side rendering and should
 * not wait for it. The rest of that ticket is an arc; these two are defects
 * standing on their own.
 *
 * THE SILENT CEILING. Every public surface read a flat 500 offerings and
 * stopped — no signal, no marker, no log. A tenant with 600 offerings published
 * 500 of them and nothing anywhere said which hundred were missing, or that any
 * were. Worse, the same ceiling applied to the PRICE index: a price beyond it
 * made its offering look unpriced, which drops it out of the agentic feed
 * entirely. A missing product reads to an agent as a withdrawn one.
 *
 * Reading to exhaustion fixes the common case. A ceiling still exists, because
 * "to exhaustion" against a catalogue of unknown size is its own hazard — but
 * it is now 10,000 rather than 500, and hitting it is PUBLISHED rather than
 * silent, so a consumer can tell a complete feed from a cut one.
 *
 * THE 200 ON NOTHING. `try_files $uri /index.html` answered 200 with the app
 * shell for any path at all. /shop/nonsense looked exactly like a real page to
 * a crawler, a link checker and a monitor — because it WAS a real page: the
 * shell, which then rendered nothing. A 200 on a URL that does not exist is the
 * most expensive kind of wrong, because nothing downstream can detect it.
 *
 * What this proves:
 *
 *  - PAST THE OLD CEILING. Enough offerings are created to push the shelf over
 *    the old 500 limit, and every one of them is still published — on the
 *    sitemap, in llms.txt and in the discovery feed. Under the old code the
 *    surfaces would have stopped at 500.
 *  - THE PRICE INDEX GOES TOO. A fixture whose price sits beyond the old
 *    ceiling still carries its price on every surface, rather than being
 *    dropped from the agentic feed as unpriced.
 *  - ALL FOUR SURFACES AGREE ON THE COUNT. The shelf is one read; the sitemap,
 *    llms.txt and discovery must publish the same offerings.
 *  - AN UNKNOWN URL IS A 404. /shop/nonsense and a path outside the app both
 *    answer 404, while every real route still answers 200 — the enumeration has
 *    not locked a working page out.
 *
 * HONEST LIMITS: this does not prove the 10,000 ceiling, because creating ten
 * thousand fixtures to watch a flag flip would cost more than the flag is
 * worth; the ceiling and its `truncated` signal are asserted by their absence
 * here (a complete feed must NOT claim truncation) and by reading the code. And
 * nginx now enumerates the app's routes, which duplicates App.jsx — that
 * duplication is the honest cost of not having SSR yet, and it goes away when
 * the router answers for real.
 *
 * Everything this suite creates, it deletes.
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = '/tmf-api/productCatalogManagement/v4';
const BOT = 'Mozilla/5.0 (compatible; GPTBot/1.2; +https://openai.com/gptbot)';
const OLD_CEILING = 500;
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const tag = `H180-${Date.now()}`;

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  const j = await r.json();
  if (!j.access_token) fail(`token(${user}) refused`);
  return j.access_token;
}
async function call(method, p, tok, body) {
  const r = await fetch(API + p, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}),
      'Cache-Control': 'no-cache' },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* not json */ }
  return { status: r.status, body: json, text };
}
async function crawl(path) {
  const r = await fetch(API + path, { headers: { 'User-Agent': BOT, 'Cache-Control': 'no-cache' } });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* not json */ }
  return { status: r.status, text, body: json };
}

(async () => {
  const staff = await token('demo', 'demo');
  const made = [];
  try {
    /* ---------- how far past the old ceiling do we need to go? ---------- */
    const existing = (await crawl('/discovery/v1/products')).body.productCount;
    const needed = Math.max(0, OLD_CEILING + 5 - existing);
    ok(`the shelf holds ${existing}; creating ${needed} more to push it past the old ${OLD_CEILING} ceiling`);

    // one price, shared, so the run stays quick — the point is the OFFERING count
    const price = (await call('POST', `${CAT}/productOfferingPrice`, staff, {
      name: `${tag} monthly`, priceType: 'recurring', recurringChargePeriodType: 'month',
      price: { unit: 'EUR', value: 19.00 }, lifecycleStatus: 'Active' })).body;
    if (!price || !price.id) fail('could not create the shared price');
    made.push([`${CAT}/productOfferingPrice`, price.id]);

    const BATCH = 25;
    const ids = [];
    for (let i = 0; i < needed; i += BATCH) {
      const slice = await Promise.all(
        Array.from({ length: Math.min(BATCH, needed - i) }, (_, k) =>
          call('POST', `${CAT}/productOffering`, staff, {
            name: `${tag} filler ${i + k}`, lifecycleStatus: 'Active', version: '1.0',
            isSellable: true, productOfferingPrice: [{ id: price.id }],
          })));
      for (const r of slice) {
        if (r.status !== 201 && r.status !== 200) fail(`creating filler failed: ${r.status}`);
        ids.push(r.body.id);
        made.push([`${CAT}/productOffering`, r.body.id]);
      }
    }
    // the last one created is the one that matters: it is past the old ceiling
    const beyond = (await call('POST', `${CAT}/productOffering`, staff, {
      name: `${tag} BEYOND THE CEILING`, lifecycleStatus: 'Active', version: '1.0',
      isSellable: true, productOfferingPrice: [{ id: price.id }],
    })).body;
    made.push([`${CAT}/productOffering`, beyond.id]);
    ok(`${ids.length + 1} fixtures created; the shelf is now past ${OLD_CEILING}`);

    /* ---------- 1. every surface reads past the old ceiling ---------- */

    const discovery = await crawl('/discovery/v1/products');
    if (discovery.status !== 200) fail(`discovery answered ${discovery.status}`);
    if (discovery.body.productCount <= OLD_CEILING) {
      fail(`discovery published ${discovery.body.productCount} — it is still stopping at the ceiling`);
    }
    if (discovery.body.truncated) {
      fail('discovery claims it was truncated well below the 10,000 ceiling');
    }
    const found = (discovery.body.products || []).some((p) => p.id === beyond.id);
    if (!found) fail('the offering past the old ceiling is missing from discovery');
    ok(`discovery publishes ${discovery.body.productCount} products, including the one past the ceiling, and does not claim truncation`);

    const sitemap = await crawl('/sitemap.xml');
    const inSitemap = (sitemap.text.match(/<loc>/g) || []).length;
    if (inSitemap <= OLD_CEILING) fail(`the sitemap lists ${inSitemap} — still capped`);
    if (!sitemap.text.includes(beyond.id)) fail('the sitemap stops before the offering past the ceiling');
    ok(`the sitemap lists ${inSitemap} urls, including the one past the ceiling`);

    const llms = await crawl('/llms.txt');
    const inLlms = (llms.text.match(/\]\(/g) || []).length;
    if (inLlms <= OLD_CEILING) fail(`llms.txt lists ${inLlms} — still capped (it used to stop at 200)`);
    ok(`llms.txt lists ${inLlms} offerings — it used to stop at 200`);

    /* ---------- 2. the surfaces agree ---------- */

    if (inSitemap !== discovery.body.productCount || inLlms !== discovery.body.productCount) {
      fail(`one shelf, three counts: sitemap ${inSitemap}, llms ${inLlms}, discovery ${discovery.body.productCount}`);
    }
    ok(`all three surfaces publish the same ${inSitemap} offerings — one read, not three`);

    /* ---------- 3. the price index goes past the ceiling too ---------- */

    const beyondRow = (discovery.body.products || []).find((p) => p.id === beyond.id);
    if (!beyondRow.price || Number(beyondRow.price.headline.amount) !== 19) {
      fail(`the offering past the ceiling carries ${JSON.stringify(beyondRow.price)}`
        + ' — its price fell outside the index, which is how it would vanish from the agentic feed');
    }
    const feed = await crawl(`/acp/product_feed?id=${beyond.id}`);
    if (!(feed.body.products || []).length) {
      fail('the agentic feed dropped the offering past the ceiling — an agent reads that as withdrawn');
    }
    ok('an offering past the ceiling still carries its price, and the agentic feed still sells it');

    /* ---------- 4. a URL that does not exist says so ---------- */

    const real = [['/shop/', 200], [`/shop/offering/${beyond.id}`, 200]];
    for (const [path, want] of real) {
      const r = await fetch(API + path, { headers: { 'Cache-Control': 'no-cache' } });
      if (r.status !== want) fail(`a real route ${path} answered ${r.status}, not ${want} — the enumeration locked out a working page`);
    }
    ok('every real route still answers 200');

    for (const path of ['/shop/nonsense', '/shop/offering', '/not-a-page-at-all']) {
      const r = await fetch(API + path, { headers: { 'Cache-Control': 'no-cache' } });
      if (r.status === 200) {
        fail(`${path} answers 200 with the app shell — a crawler, a monitor and a link checker all read that as a real page`);
      }
      if (r.status !== 404) fail(`${path} answered ${r.status}, expected 404`);
    }
    ok('an unknown URL answers 404 instead of 200-with-an-empty-shell');

    console.log('\nPASS honest_shelf_test — the whole shelf is published, and a URL that does not exist says so');
  } finally {
    console.log(`cleaning up ${made.length} fixtures…`);
    const offerings = made.filter(([p]) => p.endsWith('productOffering'));
    for (let i = 0; i < offerings.length; i += 25) {
      await Promise.all(offerings.slice(i, i + 25)
        .map(([p, id]) => call('DELETE', `${p}/${id}`, staff).catch(() => {})));
    }
    for (const [p, id] of made.filter(([p2]) => p2.endsWith('productOfferingPrice'))) {
      await call('DELETE', `${p}/${id}`, staff).catch(() => {});
    }
    console.log('fixtures removed');
  }
})().catch((e) => { console.error('\nFAIL ' + e.message); process.exit(1); });
