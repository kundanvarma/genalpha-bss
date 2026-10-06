/* Structured data is SERIALISED, and three facts stopped being constants. Suite #247.
 *
 * SEO-2 (#178). The crawler-facing page used to assemble its schema.org
 * document from 22 string fragments with its own escaping, and three of its
 * values were literals. Five things are proven here, end to end through the
 * gateway with a real token:
 *
 *  - THE DEFECT, RED FIRST: this suite replicates the pre-change generator
 *    (the exact concatenation that stood in GeoController) and feeds it a
 *    product honestly named `Fiber 500 "Pro" \ Home`. The old path emits a
 *    document that JSON.parse REFUSES — printed here, so the failure is seen
 *    rather than described. The live page then parses with the same fixture.
 *  - ROUND-TRIP: the quote, the backslash and the newline come back byte for
 *    byte in `name` and `description`, and a `</script>` in the description
 *    cannot end the element it rides in.
 *  - THE HEADLINE PRICE: a subscription's Offer carries its RECURRING charge
 *    with the billing period beside it, never its activation fee. Proven on a
 *    fixture AND on the seeded fibre plan, whose page used to advertise its
 *    49.00 installation fee as the price of a 39.99/month line.
 *  - AVAILABILITY FROM EVIDENCE: the warehouse answers for a stock-managed
 *    product — InStock where there are rows left, OutOfStock where there are
 *    none — and ordering semantics answer for a plan the warehouse knows
 *    nothing about. Three different answers from one generator: it cannot be a
 *    constant. (The generator can also say Discontinued or InStoreOnly, and
 *    this suite does NOT reach those: the catalog's own door already 404s a
 *    retired, expired or dealer-only offering to an anonymous crawler, so the
 *    two branches are a guard for the day SEO-1/SEO-3 change that door, not a
 *    path a suite can walk today. Said here rather than claimed as proven.)
 *  - LANGUAGE AND CURRENCY FROM THE TENANT: the page declares the operator's
 *    own locale (genalpha en, nova no) and prices in the operator's own
 *    currency where a price names none; and since #180 server-renders it, the
 *    shop shell itself declares that tenant's language rather than a literal.
 *
 * Also asserted: specification characteristics reach the page as structured
 * properties (data allowance, speed, network, roaming) while internal facts
 * named as codes (chargingSpecId, volte) do not; images are absolute and
 * fetchable; and there are NO reviews and NO ratings anywhere — this page
 * displays none, so publishing them would be fabricated structured data.
 *
 * HONEST LIMIT ON "VALIDATES": there is no offline schema.org validator in
 * this repo and the suite has no network. What is asserted is therefore
 * WELL-FORMED JSON (JSON.parse of the exact bytes in the script element) plus
 * the presence and correctness of the required fields — @context, @type, name,
 * and offers.price / priceCurrency / availability. That is a weaker claim than
 * "Google accepts it" and is deliberately not dressed up as one.
 *
 * Fixtures are created, asserted and deleted; the page 404s afterwards.
 */
const API = 'http://localhost:8080';
const NOVA = 'http://shop.nova.localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = '/tmf-api/productCatalogManagement/v4';
const BOT = 'Mozilla/5.0 (compatible; GPTBot/1.2; +https://openai.com/gptbot)';

/* The product the ticket names. One real quote pair, one real backslash. */
const NAME = 'Fiber 500 "Pro" \\ Home';
/* A real newline, a quote, a backslash, an ampersand, and a closing script
 * tag — every character that used to break the document, in one value. */
const DESC = 'Line one, with a quote (") and a backslash (\\).\n'
  + 'Line two, with an ampersand (&) and a </script> tag.';

const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const tag = `SUITE${Date.now()}`;
const fixtures = { offerings: [], prices: [], specs: [] };

async function form(url, params) {
  const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(params) });
  return r.json();
}
async function token(user, pass) {
  const j = await form(KC, { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass });
  if (!j.access_token) fail(`token(${user}) refused`);
  return j.access_token;
}
async function call(method, p, tok, body) {
  const r = await fetch(API + p, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}), 'Cache-Control': 'no-cache' },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* not json */ }
  return { status: r.status, body: json, text };
}
async function created(method, p, tok, body) {
  const r = await call(method, p, tok, body);
  if (r.status !== 201 && r.status !== 200) fail(`${method} ${p}: ${r.status} ${r.text.slice(0, 300)}`);
  return r.body;
}
async function page(url) {
  const r = await fetch(url, { headers: { 'User-Agent': BOT, 'Cache-Control': 'no-cache' } });
  return { status: r.status, html: await r.text() };
}
/** The bytes between the script tags, and nothing else. */
function scriptBody(html) {
  const m = html.match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/);
  return m ? m[1] : null;
}
/** Parse the structured data, insisting the document is well formed. */
function structuredData(html, where) {
  const raw = scriptBody(html) || fail(`${where}: no JSON-LD on the page`);
  try {
    return JSON.parse(raw);
  } catch (e) {
    fail(`${where}: the JSON-LD does not parse (${e.message}) — the bytes were:\n${raw.slice(0, 400)}`);
  }
}
/** Every required field a Product/Offer must carry, checked by name. */
function requireFields(schema, where) {
  for (const key of ['@context', '@type', 'name']) {
    if (!schema[key]) fail(`${where}: structured data has no ${key}`);
  }
  if (schema['@type'] !== 'Product') fail(`${where}: @type is ${schema['@type']}, not Product`);
  if (schema['@context'] !== 'https://schema.org') fail(`${where}: wrong @context`);
  if (!schema.offers) fail(`${where}: no Offer`);
  for (const key of ['@type', 'price', 'priceCurrency', 'availability']) {
    if (!schema.offers[key]) fail(`${where}: the Offer has no ${key}`);
  }
  if (schema.aggregateRating || schema.review || schema.offers.review) {
    fail(`${where}: a review or rating was published — this page displays none`);
  }
}

/* ======================================================================
 * THE PRE-CHANGE GENERATOR, kept here on purpose.
 * Copied from GeoController as it stood before #178: the same fragments and
 * the same four-replacement escaping. It is the thing that must be seen to
 * fail, because a gate nobody has watched go red is not a gate.
 * ==================================================================== */
function oldGenerator(name, description) {
  const esc = (s) => (s == null ? '' : s.replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;'));
  return '{"@context":"https://schema.org","@type":"Product",'
    + '"name":"' + name + '","description":"' + description + '",'
    + '"brand":{"@type":"Organization","name":"' + esc('MyGenAlpha') + '"}'
    + ',"offers":{"@type":"Offer","price":"49.0","priceCurrency":"EUR",'
    + '"availability":"https://schema.org/InStock"}}';
}

(async () => {
  const staff = await token('pat@bss.local', 'pat');
  const tenant = await (await fetch(`${API}/app/tenant-config.json`)).json();

  /* ---------- 0. the defect, red on purpose ---------- */
  const brokenText = oldGenerator(NAME, DESC);
  let brokenError = null;
  try {
    JSON.parse(brokenText);
  } catch (e) {
    brokenError = e.message;
  }
  if (!brokenError) {
    fail('the pre-change generator accepted a quoted name — this suite proves nothing');
  }
  console.log(`OK RED FIRST: the pre-change string generator, given the name ${JSON.stringify(NAME)},`
    + ` emitted structured data that JSON.parse REFUSES — "${brokenError.split('\n')[0]}".`
    + `\n   The bytes it published: ${brokenText.slice(0, 120)}…`);

  /* ---------- 1. the fixture ---------- */
  const feed = await (await fetch(`${API}/acp/product_feed`)).json();
  // A crawlable image the fixture can honestly carry: the seeded catalog's own.
  // Minted documents are the document service's business, so the fixture
  // borrows a picture that already answers rather than inventing a URL.
  let heroUrl = null;
  for (const p of (feed.products || []).slice(0, 20)) {
    const full = (await call('GET', `${CAT}/productOffering/${p.id}`, staff)).body;
    const image = (full && full.attachment || []).find((a) => a.url
      && String(a.mimeType || '').startsWith('image/'));
    if (!image) continue;
    const probe = await fetch(API + image.url, { headers: { 'User-Agent': BOT } });
    if (probe.ok && (probe.headers.get('content-type') || '').startsWith('image/')) {
      heroUrl = image.url;
      break;
    }
  }
  if (!heroUrl) fail('no crawlable image anywhere in the seeded catalog to attach to the fixture');

  // a stray fixture from a crashed run would accumulate under the same name
  const strays = await call('GET', `${CAT}/productOffering?name=${encodeURIComponent(NAME)}&limit=50`, staff);
  for (const stray of (Array.isArray(strays.body) ? strays.body : [])) {
    if (stray.name === NAME) await call('DELETE', `${CAT}/productOffering/${stray.id}`, staff);
  }

  const monthly = await created('POST', `${CAT}/productOfferingPrice`, staff, {
    name: `${tag} monthly`, priceType: 'recurring', price: { unit: 'EUR', value: 299 },
    recurringChargePeriodType: 'month', recurringChargePeriodLength: 1,
    lifecycleStatus: 'Active', version: '1.0' });
  fixtures.prices.push(monthly.id);
  const activation = await created('POST', `${CAT}/productOfferingPrice`, staff, {
    name: `${tag} activation`, priceType: 'oneTime', price: { unit: 'EUR', value: 499 },
    lifecycleStatus: 'Active', version: '1.0' });
  fixtures.prices.push(activation.id);

  const spec = await created('POST', `${CAT}/productSpecification`, staff, {
    name: `${tag} fibre spec`, brand: 'GenAlpha', lifecycleStatus: 'Active',
    productSpecCharacteristic: [
      // a fact written for a person
      { name: 'Data', valueType: 'string', configurable: false,
        productSpecCharacteristicValue: [{ value: 'Unlimited', valueType: 'string', isDefault: true }] },
      { name: 'EU roaming', valueType: 'string', configurable: false,
        productSpecCharacteristicValue: [{ value: 'Included', valueType: 'string', isDefault: true }] },
      // a choice, with a unit and a declared default
      { name: 'downloadSpeed', valueType: 'number', configurable: true,
        description: 'Download speed',
        productSpecCharacteristicValue: [
          { value: 100, valueType: 'number', unitOfMeasure: 'Mbit/s' },
          { value: 500, valueType: 'number', unitOfMeasure: 'Mbit/s', isDefault: true }] },
      // an internal fact named as a code: must NOT reach a page
      { name: 'chargingSpecId', valueType: 'string', configurable: false,
        productSpecCharacteristicValue: [{ value: `RG-${tag}`, valueType: 'string' }] },
    ] });
  fixtures.specs.push(spec.id);

  const ref = (o, type) => ({ id: o.id, name: o.name, '@referredType': type });
  const offering = await created('POST', `${CAT}/productOffering`, staff, {
    name: NAME, description: DESC, lifecycleStatus: 'Active', version: '1.0', isBundle: false,
    isSellable: true, category: [{ name: 'Broadband' }],
    productSpecification: ref(spec, 'ProductSpecification'),
    productOfferingPrice: [ref(monthly, 'ProductOfferingPrice'), ref(activation, 'ProductOfferingPrice')],
    attachment: [
      { name: 'hero', mimeType: 'image/svg+xml', '@type': 'Attachment', url: heroUrl },
      // not an image: a datasheet is not a product picture and must be left out
      { name: 'datasheet', mimeType: 'application/pdf', '@type': 'Attachment',
        url: `/docs/${tag}.pdf` },
    ] });
  fixtures.offerings.push(offering.id);
  if (offering.name !== NAME) fail(`the catalog stored the name as ${JSON.stringify(offering.name)}`);

  /* ---------- 2. round-trip: the document parses, the characters survive ---------- */
  const fixturePage = await page(`${API}/shop/offering/${offering.id}`);
  if (fixturePage.status !== 200) fail(`bot page: ${fixturePage.status}`);
  const schema = structuredData(fixturePage.html, 'the fixture page');
  requireFields(schema, 'the fixture page');
  if (schema.name !== NAME) {
    fail(`name did not round-trip: ${JSON.stringify(schema.name)} != ${JSON.stringify(NAME)}`);
  }
  if (schema.description !== DESC) {
    fail(`description did not round-trip: ${JSON.stringify(schema.description)}`);
  }
  if (!schema.description.includes('\n')) fail('the newline was lost');
  if (!schema.description.includes('\\')) fail('the backslash was lost');
  if (!schema.description.includes('"')) fail('the quote was lost');
  // the closing tag in the description must not appear as markup in the element
  if (scriptBody(fixturePage.html).includes('</script')) {
    fail('a value closed the script element — the document is cut in half');
  }
  ok(`ROUND-TRIP: ${JSON.stringify(NAME)} with a quote, a backslash, an ampersand, a newline`
    + ' and a </script> tag in its description came back byte for byte, in a document that'
    + ' parses — the same fixture the old generator could not publish at all.');

  /* ---------- 3. the headline price ---------- */
  const price = Number(schema.offers.price);
  if (price !== 299) fail(`Offer price ${schema.offers.price} is not the 299/month recurring charge`);
  if (price === 499) fail('the Offer advertised the activation fee');
  if (schema.offers.priceCurrency !== 'EUR') fail(`currency ${schema.offers.priceCurrency}`);
  const unitPrice = schema.offers.priceSpecification || fail('a recurring charge with no period declared');
  if (unitPrice['@type'] !== 'UnitPriceSpecification') fail(`priceSpecification @type ${unitPrice['@type']}`);
  if (unitPrice.billingDuration !== 1 || unitPrice.unitCode !== 'MON') {
    fail(`the billing period is not one month: ${JSON.stringify(unitPrice)}`);
  }
  if (!fixturePage.html.includes('499')) fail('the human sentence hides the activation fee');
  ok(`HEADLINE PRICE: the Offer says ${schema.offers.price} ${schema.offers.priceCurrency} per`
    + ` ${unitPrice.unitCode}, not the ${activation.price.value} activation fee beside it — and the`
    + ' page still tells a human about the one-time charge in words.');

  /* ---------- 3b. the same defect on seeded data ---------- */
  const fibre = (feed.products || []).find((p) => p.title === 'GenAlpha Fiber 1000');
  if (fibre) {
    const full = (await call('GET', `${CAT}/productOffering/${fibre.id}`, staff)).body;
    const resolved = [];
    for (const r of full.productOfferingPrice || []) {
      const p = (await call('GET', `${CAT}/productOfferingPrice/${r.id}`, staff)).body;
      if (p && p.price && p.price.value != null
          && !(p.prodSpecCharValueUse || []).length) resolved.push(p);
    }
    const sum = (type) => resolved.filter((p) => p.priceType === type)
      .reduce((t, p) => t + Number(p.price.value), 0);
    const recurring = sum('recurring');
    const once = sum('oneTime');
    const fibreSchema = structuredData((await page(`${API}/shop/offering/${fibre.id}`)).html, 'the fibre page');
    requireFields(fibreSchema, 'the fibre page');
    if (Number(fibreSchema.offers.price) !== recurring) {
      fail(`the fibre page says ${fibreSchema.offers.price}, its recurring charge is ${recurring}`);
    }
    if (once && Number(fibreSchema.offers.price) === once) {
      fail(`the fibre page still advertises its ${once} one-time fee`);
    }
    ok(`SEEDED DATA: "${fibre.title}" publishes ${fibreSchema.offers.price} —`
      + ` its recurring charge — where it used to publish its ${once} installation fee.`);
  } else {
    console.log('WARN SEEDED DATA: no "GenAlpha Fiber 1000" in the feed; the fixture carries this rung alone.');
  }

  /* ---------- 4. availability from evidence ---------- */
  if (schema.offers.availability !== 'https://schema.org/OnlineOnly') {
    fail(`a plan the warehouse knows nothing about says ${schema.offers.availability}`);
  }
  const seen = new Set([schema.offers.availability]);
  // a stock-managed product: the warehouse decides, not the generator
  const stocks = (await call('GET', '/tmf-api/productStockManagement/v4/productStock?limit=100', staff)).body || [];
  const stocked = {};
  for (const s of stocks) {
    const id = ((s.stockedProduct || {}).productOffering || {}).id || (s.productOffering || {}).id;
    if (!id) continue;
    stocked[id] = (stocked[id] || 0) + Number((s.availableQuantity || {}).amount || 0);
  }
  const stockedId = Object.keys(stocked).find((id) => (feed.products || []).some((p) => p.id === id));
  if (stockedId) {
    const stockedSchema = structuredData((await page(`${API}/shop/offering/${stockedId}`)).html, 'a stocked page');
    const expected = stocked[stockedId] > 0 ? 'https://schema.org/InStock' : 'https://schema.org/OutOfStock';
    if (stockedSchema.offers.availability !== expected) {
      fail(`${stocked[stockedId]} in the warehouse but the page says ${stockedSchema.offers.availability}`);
    }
    seen.add(stockedSchema.offers.availability);
    ok(`STOCK ANSWERS: "${stockedSchema.name}" has ${stocked[stockedId]} available and the page`
      + ` says ${stockedSchema.offers.availability.split('/').pop()} — read from the warehouse.`);
  } else {
    console.log('WARN STOCK: no stock-managed offering in this tenant; the plan and retired rungs carry availability.');
  }
  // and the other direction: a product the warehouse has none of
  const soldOutPrice = await created('POST', `${CAT}/productOfferingPrice`, staff, {
    name: `${tag} sold-out monthly`, priceType: 'oneTime', price: { unit: 'EUR', value: 149 },
    lifecycleStatus: 'Active', version: '1.0' });
  fixtures.prices.push(soldOutPrice.id);
  const soldOut = await created('POST', `${CAT}/productOffering`, staff, {
    name: `${tag} sold out`, description: 'a boxed thing the warehouse has none of',
    lifecycleStatus: 'Active', version: '1.0', isBundle: false, isSellable: true,
    productOfferingPrice: [ref(soldOutPrice, 'ProductOfferingPrice')] });
  fixtures.offerings.push(soldOut.id);
  const row = await created('POST', '/tmf-api/productStockManagement/v4/productStock', staff, {
    name: `${tag} stock`, stockedQuantity: { amount: 0, units: 'unit' },
    reservedQuantity: { amount: 0, units: 'unit' }, availableQuantity: { amount: 0, units: 'unit' },
    productStockStatusType: 'outOfStock', productOffering: { id: soldOut.id, name: soldOut.name },
    stockedProduct: { productOffering: { id: soldOut.id } } });
  const soldOutSchema = structuredData((await page(`${API}/shop/offering/${soldOut.id}`)).html, 'the sold-out page');
  if (soldOutSchema.offers.availability !== 'https://schema.org/OutOfStock') {
    fail(`zero in the warehouse but the page says ${soldOutSchema.offers.availability}`);
  }
  seen.add(soldOutSchema.offers.availability);
  await call('DELETE', `/tmf-api/productStockManagement/v4/productStock/${row.id}`, staff);
  if (seen.size < 3) fail(`availability varied only ${seen.size} ways — it may still be a constant`);
  ok(`AVAILABILITY IS NOT A CONSTANT: ${seen.size} different answers from one generator`
    + ` (${[...seen].map((a) => a.split('/').pop()).join(', ')}) — each from evidence: the`
    + ' warehouse where it keeps rows, ordering semantics where it keeps none.');

  /* ---------- 5. language and currency from the tenant ---------- */
  const langOf = (html) => (html.match(/<html lang="([^"]*)"/) || [])[1];
  if (langOf(fixturePage.html) !== tenant.locale) {
    fail(`the page declares lang="${langOf(fixturePage.html)}", the tenant sells in ${tenant.locale}`);
  }
  const novaMap = await (await fetch(`${NOVA}/sitemap.xml`)).text();
  const novaId = (novaMap.match(/\/shop\/offering\/([^<]+)</) || [])[1] || fail('nova has no sitemap entry');
  const novaCfg = await (await fetch(`${NOVA}/app/tenant-config.json`)).json();
  const novaPage = await page(`${NOVA}/shop/offering/${novaId}`);
  if (langOf(novaPage.html) !== novaCfg.locale) {
    fail(`nova's page declares lang="${langOf(novaPage.html)}", nova sells in ${novaCfg.locale}`);
  }
  if (novaCfg.locale === tenant.locale) fail('the two tenants share a locale — this rung proves nothing');
  // THE SHELL'S LANGUAGE. This used to assert that /shop/ carried NO lang at all,
  // because it was the static nginx shell and a baked-in language would have been
  // one tenant's answer served to every tenant. #180 server-renders /shop/, so the
  // document now DOES declare a language — the renderer takes it from the tenant.
  // The intent is unchanged and the bar is higher: not "no literal" but "the right
  // one, per tenant", which is what a baked-in literal could never be.
  for (const [host, cfg] of [[API, tenant], [NOVA, novaCfg]]) {
    const shell = await (await fetch(`${host}/shop/`)).text();
    const declared = (shell.match(/<html[^>]*\slang="([^"]+)"/) || [])[1];
    if (declared !== cfg.locale) {
      fail(`${host}/shop/ declares lang="${declared}", that tenant sells in ${cfg.locale}`);
    }
  }
  for (const [host, cfg] of [[API, tenant], [NOVA, novaCfg]]) {
    const js = await (await fetch(`${host}/shop/tenant-config.js`)).text();
    if (!js.includes(`document.documentElement.lang = '${cfg.locale}'`)) {
      fail(`${host} does not stamp lang=${cfg.locale} on the shop shell`);
    }
  }
  // currency: a price that names no unit falls back to the TENANT's money
  const unitless = await created('POST', `${CAT}/productOfferingPrice`, staff, {
    name: `${tag} unitless`, priceType: 'recurring', price: { value: 99 },
    recurringChargePeriodType: 'month', lifecycleStatus: 'Active', version: '1.0' });
  fixtures.prices.push(unitless.id);
  const probe = await created('POST', `${CAT}/productOffering`, staff, {
    name: `${tag} currency probe`, description: 'a price with no unit of its own',
    lifecycleStatus: 'Active', version: '1.0', isBundle: false, isSellable: true,
    productOfferingPrice: [ref(unitless, 'ProductOfferingPrice')] });
  fixtures.offerings.push(probe.id);
  const probeSchema = structuredData((await page(`${API}/shop/offering/${probe.id}`)).html, 'the currency probe');
  if (probeSchema.offers.priceCurrency !== tenant.currency) {
    fail(`a unit-less price published ${probeSchema.offers.priceCurrency}, the tenant prices in ${tenant.currency}`);
  }
  ok(`LANGUAGE AND CURRENCY: genalpha's page declares lang="${tenant.locale}" and nova's`
    + ` lang="${novaCfg.locale}" from each operator's own configuration; the server-rendered shell`
    + ` declares each tenant's OWN language and the gateway stamps it per hostname; and a price naming no unit`
    + ` publishes ${probeSchema.offers.priceCurrency} because that is what this tenant prices in.`);

  /* ---------- 6. characteristics projected, internal facts withheld ---------- */
  const props = schema.additionalProperty || fail('the specification characteristics were dropped');
  const byName = Object.fromEntries(props.map((p) => [p.name, p]));
  for (const name of ['Data', 'EU roaming', 'downloadSpeed']) {
    if (!byName[name]) fail(`"${name}" is not on the page`);
    if (byName[name]['@type'] !== 'PropertyValue') fail(`"${name}" is not a PropertyValue`);
  }
  if (byName.Data.value !== 'Unlimited') fail(`Data says ${byName.Data.value}`);
  if (byName.downloadSpeed.value !== '500') fail(`downloadSpeed says ${byName.downloadSpeed.value}, not its default`);
  if (byName.downloadSpeed.unitText !== 'Mbit/s') fail('the unit of measure was dropped');
  if (byName.chargingSpecId) fail('an internal charging key was published to crawlers');
  if (fixturePage.html.includes(`RG-${tag}`)) fail('an internal charging key reached the page body');
  // the same rule on seeded data: a mobile plan's marketing facts, not its switches
  const plan = (feed.products || []).find((p) => p.title === 'GenAlpha Mobile 60 GB 5G');
  if (plan) {
    const planSchema = structuredData((await page(`${API}/shop/offering/${plan.id}`)).html, 'the plan page');
    const planProps = (planSchema.additionalProperty || []).map((p) => p.name);
    for (const want of ['Data', 'Network', 'EU roaming']) {
      if (!planProps.includes(want)) fail(`the plan page dropped "${want}"`);
    }
    for (const hide of ['chargingSpecId', 'volte', 'vonr', 'smsoip']) {
      if (planProps.includes(hide)) fail(`the plan page published the internal fact "${hide}"`);
    }
    ok(`SEEDED CHARACTERISTICS: "${plan.title}" publishes ${planProps.join(', ')} — and none of`
      + ' its internal switches (chargingSpecId, volte, vonr, smsoip).');
  }
  ok('CHARACTERISTICS: data allowance, roaming and speed are structured properties with their'
    + ' units, the speed shows its declared default, and a fact named as a code and documented'
    + ' nowhere stays off the page.');

  /* ---------- 7. images: absolute, crawlable, pictures only ---------- */
  const images = schema.image || fail('the image attachment was dropped');
  if (images.length !== 1) fail(`${images.length} images — the datasheet should not be one of them`);
  if (!/^https?:\/\//.test(images[0])) fail(`the image URL is not absolute: ${images[0]}`);
  if (images[0].includes('.pdf')) fail('a datasheet was published as a product image');
  const fetched = await fetch(images[0], { headers: { 'User-Agent': BOT } });
  if (!fetched.ok || !(fetched.headers.get('content-type') || '').startsWith('image/')) {
    fail(`the published image is not fetchable as an image: ${fetched.status} ${fetched.headers.get('content-type')}`);
  }
  if (!schema.url || !/^https?:\/\//.test(schema.url)) fail(`the canonical URL is not absolute: ${schema.url}`);
  ok(`IMAGES AND CANONICAL: ${images[0]} is absolute and answers ${fetched.status}`
    + ` ${fetched.headers.get('content-type')}; the datasheet beside it was left out; the`
    + ` canonical URL is ${schema.url}.`);

  /* ---------- 8. cleanup ---------- */
  for (const id of fixtures.offerings) await call('DELETE', `${CAT}/productOffering/${id}`, staff);
  for (const id of fixtures.prices) await call('DELETE', `${CAT}/productOfferingPrice/${id}`, staff);
  for (const id of fixtures.specs) await call('DELETE', `${CAT}/productSpecification/${id}`, staff);
  // THE BROWSE CACHE IS IN THIS PATH NOW. Before #180 the public offering page was
  // rendered by product-catalog on its own route; it is server-rendered now, and
  // the renderer reads the catalogue through the GATEWAY — which carries
  // LocalResponseCache=60s on the product-catalog route. So a hard DELETE is not
  // visible on the page until that entry expires. Measured: 61s.
  //
  // That is the cache working, not a leak, and it is bounded: an operator RETIRES
  // an offering rather than deleting it, and the withdrawn path answers from
  // /seo/offering/{id}/meta — a different route, uncached — so a withdrawn product
  // redirects at once. This waits the documented TTL out and still demands the
  // 404, rather than dropping the assertion.
  let gone = await page(`${API}/shop/offering/${offering.id}`);
  for (let waited = 0; gone.status !== 404 && waited < 90; waited += 5) {
    await new Promise((r) => setTimeout(r, 5000));
    gone = await page(`${API}/shop/offering/${offering.id}`);
  }
  if (gone.status !== 404) {
    fail(`the fixture page still answers ${gone.status} 90s after cleanup — longer than the 60s browse cache explains`);
  }
  const left = await call('GET', `${CAT}/productOffering?name=${encodeURIComponent(NAME)}&limit=50`, staff);
  if ((Array.isArray(left.body) ? left.body : []).some((o) => o.name === NAME)) {
    fail('the fixture offering was left behind');
  }
  ok('CLEANUP: every fixture offering, price and specification is gone and the page 404s.');

  console.log('\nALL GEO STRUCTURED-DATA CHECKS PASSED — the crawler-facing document is serialised'
    + ' from typed objects, so a product named Fiber 500 "Pro" \\ Home publishes valid structured'
    + ' data instead of none; availability comes from the warehouse or from the lifecycle, the'
    + ' headline price is the recurring charge rather than the activation fee, and the language'
    + ' and the currency are the operator\'s own. Asserted as well-formed JSON plus required'
    + ' fields, not as third-party validation.');
})().catch(async (e) => {
  // a failure must not leave the demo tenant carrying this run's fixtures
  try {
    const staff = await token('pat@bss.local', 'pat');
    for (const id of fixtures.offerings) await call('DELETE', `${CAT}/productOffering/${id}`, staff);
    for (const id of fixtures.prices) await call('DELETE', `${CAT}/productOfferingPrice/${id}`, staff);
    for (const id of fixtures.specs) await call('DELETE', `${CAT}/productSpecification/${id}`, staff);
  } catch { /* the failure below is the one that matters */ }
  console.error('FAIL:', e.message.split('\n').slice(0, 4).join(' | '));
  process.exit(1);
});
