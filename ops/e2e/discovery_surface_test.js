/* A factual discovery surface: traceable, whole-price, and never a second answer. Suite #255.
 *
 * SEO-5 (#181), on the foundation #179 laid. Generative engines and shopping
 * agents need factual product data they can be held to — not marketing copy,
 * and not a scrape of rendered HTML. This adds a richer read surface beside the
 * agentic-commerce feed WITHOUT touching that contract.
 *
 * The rule that makes it a discovery feed rather than a marketing feed is
 * traceability: every commercial fact carries the catalog records it was read
 * from, so an agent — or a person checking after the fact — can verify any
 * price against TMF620. A feed that cannot be checked is a feed that can be
 * wrong without anyone noticing, which matters on the day an AI quotes a price
 * to a customer.
 *
 * What this proves, end to end through the gateway:
 *
 *  - ONE PROJECTION, STILL. The discovery surface and the agentic feed return
 *    the SAME commercial facts for the same offering. Not similar — equal, on
 *    the headline amount, the currency, the period and the type. This is the
 *    acceptance criterion the ticket asks to be proven rather than asserted.
 *  - THE AGENTIC CONTRACT IS UNTOUCHED. The feed still carries exactly the
 *    fields it carried before, in the same shape. The richer surface sits
 *    beside it; it does not leak into it.
 *  - THE WHOLE PRICE, NOT ONE NUMBER. For a bundle, discovery carries the
 *    headline, the one-off beside it, AND every component behind both — and
 *    those components sum to the headline. The agentic feed can only carry one
 *    number and must; this surface does not have to.
 *  - EVERY FACT TRACES BACK. Each product names its offering, its specification
 *    and its price ids, and those ids resolve against the catalog API. The
 *    suite follows one of them and checks the price actually matches.
 *  - NOTHING IS SYNTHESISED. An offering with no price carries no price object
 *    rather than a zero, and an offering whose specification declares nothing
 *    carries no facts rather than invented ones.
 *  - THE TENANT DECIDES WHO READS IT. ai-visibility governs this surface as it
 *    governs robots.txt: a tenant on `search-only` — "classic search yes, AI
 *    no" — does not serve a machine-readable catalogue at all, because that
 *    posture would otherwise be meaningless.
 *
 * HONEST LIMITS: money carries the CATALOG'S OWN SCALE — a computed sum keeps
 * two decimal places, a stored amount keeps whatever it was stored with, so the
 * same 99 euros can read "99" here and "99.00" elsewhere. Normalising to two
 * places would be wrong for a zero-decimal currency, so nothing is invented and
 * this suite compares numbers rather than strings. A consumer that string-matches
 * money will be surprised, and that is worth knowing before it is sold.
 *
 * This proves the surface, not its consumption. No crawler is
 * obliged to read it, and nothing here claims a ranking effect. The freshness
 * fields are asserted to be present and real dates, not to be accurate to the
 * second — an agent caching this for a week will still quote a stale price, and
 * no feed design prevents that.
 *
 * Everything this suite creates, it deletes.
 */
const API = 'http://localhost:8080';
const NOVA = 'http://shop.nova.localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = '/tmf-api/productCatalogManagement/v4';
const BOT = 'Mozilla/5.0 (compatible; GPTBot/1.2; +https://openai.com/gptbot)';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const tag = `D181-${Date.now()}`;

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
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* a refusal need not be JSON */ }
  return { status: r.status, body: json, text };
}
async function read(base, path) {
  const r = await fetch(base + path, { headers: { 'User-Agent': BOT, 'Cache-Control': 'no-cache' } });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* not json */ }
  return { status: r.status, body: json, text };
}

(async () => {
  const staff = await token('demo', 'demo');
  const made = [];
  try {
    /* ---------- a bundle: a monthly charge, a discount, and a joining fee ---------- */
    const price = async (name, type, value, period) => {
      const p = (await call('POST', `${CAT}/productOfferingPrice`, staff, {
        name: `${tag} ${name}`, priceType: type, recurringChargePeriodType: period,
        price: { unit: 'EUR', value }, lifecycleStatus: 'Active' })).body;
      if (!p || !p.id) fail(`could not create price ${name}`);
      made.push([`${CAT}/productOfferingPrice`, p.id]);
      return p;
    };
    const line = await price('line', 'recurring', 30.00, 'month');
    const tv = await price('tv', 'recurring', 14.99, 'month');
    const discount = await price('bundle discount', 'recurring', -5.00, 'month');
    const joining = await price('joining fee', 'oneTime', 99.00, null);

    const offering = (await call('POST', `${CAT}/productOffering`, staff, {
      name: `${tag} Home Bundle`, description: 'A line, TV, and a joining fee.',
      lifecycleStatus: 'Active', version: '1.0', isBundle: true, isSellable: true,
      productOfferingPrice: [line, tv, discount, joining].map((p) => ({ id: p.id })),
    })).body;
    if (!offering || !offering.id) fail('could not create the fixture offering');
    made.push([`${CAT}/productOffering`, offering.id]);
    ok(`fixture bundle: 30.00 + 14.99 - 5.00 monthly, plus 99.00 once`);

    /* ---------- 1. the surface answers, and is versioned ---------- */

    const one = await read(API, `/discovery/v1/products/${offering.id}`);
    if (one.status !== 200) fail(`the discovery surface answered ${one.status}: ${one.text.slice(0, 160)}`);
    if (one.body.version !== '1.0') fail(`the feed is not versioned (got ${one.body.version})`);
    if (!one.body.generatedAt || Number.isNaN(Date.parse(one.body.generatedAt))) {
      fail('the feed carries no real generatedAt — an agent cannot tell how old this is');
    }
    const product = (one.body.products || [])[0] || fail('the discovery surface returned no product');
    ok(`the surface answers, versioned ${one.body.version}, generated ${one.body.generatedAt.slice(0, 19)}`);

    /* ---------- 2. the whole price, and the components sum to it ---------- */

    const p = product.price || fail('the product carries no price');
    if (!p.headline) fail('no headline charge');
    // amounts carry the catalog's own scale — a computed sum keeps two places,
    // a stored value keeps whatever it was stored with. Compare the number.
    const money = (v) => Number(v);
    if (money(p.headline.amount) !== 39.99) fail(`the headline is ${p.headline.amount}, not 30.00 + 14.99 - 5.00`);
    if (p.headline.period !== 'month') fail(`the headline period is ${p.headline.period}`);
    if (!p.upfront || money(p.upfront.amount) !== 99) {
      fail(`the one-off is ${p.upfront && p.upfront.amount}, not 99.00 — it must sit beside the headline, not replace it`);
    }
    if (!Array.isArray(p.components) || p.components.length !== 4) {
      fail(`the price carries ${p.components && p.components.length} components, not the four it is made of`);
    }
    const recurring = p.components.filter((c) => c.type === 'recurring')
      .reduce((sum, c) => sum + Number(c.amount), 0);
    if (Math.abs(recurring - Number(p.headline.amount)) > 0.005) {
      fail(`the components sum to ${recurring} but the headline says ${p.headline.amount}`);
    }
    ok(`the whole price: ${p.headline.amount}/${p.headline.period} plus ${p.upfront.amount} once, from ${p.components.length} components that sum to the headline`);

    /* ---------- 3. one projection: discovery and the agentic feed agree ---------- */

    const feed = await read(API, `/acp/product_feed?id=${offering.id}`);
    if (feed.status !== 200) fail(`the agentic feed answered ${feed.status}`);
    const row = (feed.body.products || []).find((x) => x.id === offering.id)
      || fail('the agentic feed does not carry the fixture');

    if (money(row.price.amount) !== money(p.headline.amount)) {
      fail(`the agentic feed says ${row.price.amount} and discovery says ${p.headline.amount}`
        + ' — two surfaces, two answers, which is the whole defect SEO-3 closed');
    }
    if (row.price.currency !== p.headline.currency) fail('the two surfaces disagree on currency');
    if (row.price_type !== p.headline.type) fail(`type differs: ${row.price_type} vs ${p.headline.type}`);
    if (row.recurring_period !== p.headline.period) fail('the two surfaces disagree on the period');
    ok(`both surfaces say ${row.price.amount} ${row.price.currency} ${row.price_type}/${row.recurring_period} — one projection, still`);

    /* ---------- 4. the agentic contract is untouched ---------- */

    const EXPECTED = ['id', 'title', 'description', 'item_category', 'link', 'availability',
      'price', 'price_type', 'recurring_period', 'is_bundle'];
    const unexpected = Object.keys(row).filter((k) => !EXPECTED.includes(k));
    if (unexpected.length) {
      fail(`the agentic feed grew fields it did not have: ${unexpected.join(', ')}`
        + ' — the richer surface must sit beside that contract, never leak into it');
    }
    if (!('amount' in row.price) || !('currency' in row.price)) fail('the agentic price shape changed');
    ok('the agentic feed carries exactly the fields it carried before — its contract is untouched');

    /* ---------- 5. every fact traces back, and the trace resolves ---------- */

    const prov = product.provenance || fail('the product carries no provenance — then it is marketing');
    if (prov.offeringId !== offering.id) fail('provenance does not name the offering');
    if (!Array.isArray(prov.priceIds) || prov.priceIds.length !== 4) {
      fail(`provenance names ${prov.priceIds && prov.priceIds.length} price ids, not the four behind the figure`);
    }
    // follow one of them and check the number actually matches
    const traced = await call('GET', `${CAT}/productOfferingPrice/${prov.priceIds[0]}`, staff);
    if (traced.status !== 200) fail(`a provenance id does not resolve: ${prov.priceIds[0]}`);
    const quoted = product.price.components.find((c) => Number(c.amount) === Number(traced.body.price.value));
    if (!quoted) {
      fail(`the catalog says ${traced.body.price.value} for ${prov.priceIds[0]} but no component quotes it`);
    }
    ok(`provenance resolves: ${prov.priceIds[0].slice(0, 8)}… is ${traced.body.price.value} in the catalog and ${quoted.amount} in the feed`);

    /* ---------- 6. nothing is synthesised ---------- */

    const bare = (await call('POST', `${CAT}/productOffering`, staff, {
      name: `${tag} Priced on application`, lifecycleStatus: 'Active', version: '1.0', isSellable: true,
    })).body;
    made.push([`${CAT}/productOffering`, bare.id]);
    const bareRead = await read(API, `/discovery/v1/products/${bare.id}`);
    const bareProduct = (bareRead.body.products || [])[0] || fail('the unpriced offering is absent');
    if (bareProduct.price !== undefined && bareProduct.price !== null) {
      fail(`an offering with no price carries ${JSON.stringify(bareProduct.price)} — a feed must not invent one`);
    }
    if (bareProduct.facts && bareProduct.facts.length) {
      fail('an offering whose specification declares nothing carries facts from somewhere');
    }
    ok('an offering with no price carries no price, and no declared facts means no facts — nothing is synthesised');

    /* ---------- 7. the tenant decides who reads it ---------- */

    const novaFeed = await read(NOVA, '/discovery/v1/products');
    if (novaFeed.status === 200) {
      fail('a tenant on search-only serves a machine-readable catalogue to AI agents anyway'
        + ' — that posture is then meaningless');
    }
    if (novaFeed.status !== 404) fail(`a search-only tenant answered ${novaFeed.status}, expected 404`);
    const ours = await read(API, '/discovery/v1/products');
    if (ours.status !== 200) fail(`the open tenant stopped serving the feed (${ours.status})`);
    ok(`ai-visibility governs it: this tenant serves ${ours.body.productCount} products, the search-only tenant answers 404`);

    console.log('\nPASS discovery_surface_test — factual, traceable, and never a second answer');
  } finally {
    for (const [path, id] of made.reverse()) {
      await call('DELETE', `${path}/${id}`, staff).catch(() => {});
    }
    console.log('fixtures removed');
  }
})().catch((e) => { console.error('\nFAIL ' + e.message); process.exit(1); });
