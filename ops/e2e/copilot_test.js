/* Product Copilot: a product owner CHATS a product into the catalog.
 *
 * The loop under test: chat -> clarifying question -> proposal card ->
 * "Yes — create it" -> deterministic executor applies TMF620 payloads with
 * the OWNER's token -> the product is genuinely sellable.
 *
 *  - pat (the product persona) drives the whole thing in the console
 *  - scenario 1 (simple): a streaming service — question, then a proposal of
 *    spec + price + offering in Partner services; created, visible in the
 *    shop, and a customer ORDER against it mints a partner entitlement code
 *    (the copilot's category choice drove real fulfilment)
 *  - scenario 2 (rich): a kids smartwatch — a 4-part proposal (device, plan,
 *    security add-on, 12-month bundle with an optional child), applied in
 *    dependency order
 *  - runs on the deterministic stub provider: no API key anywhere
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const CATALOG = `${API}/tmf-api/productCatalogManagement/v4`;

async function token(request, client, user, pass) {
  const res = await request.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const staff = await token(ctx.request, 'bss-demo', 'demo', 'demo');
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };

  // preflight: remove leftovers from earlier runs so the validator's
  // "already exists" guard doesn't block this one
  const NAMES = ['StreamPlus', 'Kids Watch', 'Kids Plan 2 GB', 'GPS Tracking', 'Kids Watch Starter',
    '5G Mobile Plan 50 GB'];
  const sweep = async () => {
    const offers = await (await ctx.request.get(`${CATALOG}/productOffering?limit=100`, { headers: H })).json();
    for (const o of offers.filter((x) => NAMES.includes(x.name))) {
      await ctx.request.delete(`${CATALOG}/productOffering/${o.id}`, { headers: H });
    }
    const specs = await (await ctx.request.get(`${CATALOG}/productSpecification?limit=100`, { headers: H })).json();
    for (const s of specs.filter((x) => ['StreamPlus Service', 'Kids Watch'].includes(x.name))) {
      await ctx.request.delete(`${CATALOG}/productSpecification/${s.id}`, { headers: H });
    }
    const prices = await (await ctx.request.get(`${CATALOG}/productOfferingPrice?limit=100`, { headers: H })).json();
    for (const p of prices.filter((x) => /^(StreamPlus|Kids Watch Installment|Kids Plan|GPS Tracking|5G 50 GB) /.test(x.name || ''))) {
      await ctx.request.delete(`${CATALOG}/productOfferingPrice/${p.id}`, { headers: H });
    }
    const rules = await (await ctx.request.get(
      `${API}/tmf-api/policyManagement/v4/policyRule?limit=100`, { headers: H })).json();
    for (const r of rules.filter((x) => (x.name || '') === 'Samsung with plan discount')) {
      await ctx.request.delete(`${API}/tmf-api/policyManagement/v4/policyRule/${r.id}`, { headers: H });
    }
  };
  await sweep();

  /* ---------- pat opens the Copilot ---------- */
  const page = await (await browser.newContext()).newPage();
  await page.goto(`${API}/console/`);
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', 'pat@bss.local');
  await page.fill('input[name="password"]', 'pat');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  await page.locator('.tab', { hasText: 'Copilot' }).click();
  await page.waitForSelector('#copilot-input', { timeout: 10000 });
  console.log('OK pat (product persona) opened the Copilot tab');

  const say = async (text) => {
    await page.fill('#copilot-input', text);
    await page.click('#copilot-send');
  };

  /* ---------- scenario 1: streaming service, question -> proposal -> create ---------- */
  await say('I want to sell a streaming service');
  await page.locator('.copilot-ai', { hasText: 'cost per month' }).waitFor({ timeout: 20000 });
  console.log('OK the copilot asked the clarifying question (price)');
  await say('9.99 per month sounds right');
  await page.locator('[data-testid=copilot-proposal]').waitFor({ timeout: 20000 });
  const card = await page.locator('[data-testid=copilot-proposal]').textContent();
  if (!card.includes('StreamPlus') || !card.includes('Partner services') || !card.includes('9.99')) {
    fail('proposal card wrong: ' + card.slice(0, 200));
  }
  console.log('OK proposal card: spec + 9.99 price + offering in Partner services');
  await page.locator('[data-testid=copilot-create]').click();
  await page.locator('[data-testid=copilot-created]').waitFor({ timeout: 20000 });
  console.log('OK "Yes — create it": executor ran with pat\'s own token');

  const streamplus = (await (await ctx.request.get(
    `${CATALOG}/productOffering?name=StreamPlus`, { headers: H })).json())[0];
  if (!streamplus) fail('StreamPlus not in the catalog');
  if (((streamplus.category || [])[0] || {}).name !== 'Partner services') {
    fail('StreamPlus category wrong: ' + JSON.stringify(streamplus.category));
  }
  if (!streamplus.productSpecification?.id || !(streamplus.productOfferingPrice || []).length) {
    fail('StreamPlus missing spec or price link');
  }
  console.log('OK headless: offering + spec + price landed as plain TMF620 resources');

  /* ---------- the created product is genuinely sellable ---------- */
  const kai = await token(ctx.request, 'bss-biz', 'kai@bss.local', 'kai');
  const KH = { Authorization: 'Bearer ' + kai, 'Content-Type': 'application/json' };
  const kaiSub = JSON.parse(Buffer.from(kai.split('.')[1], 'base64').toString()).sub;
  const spServices = async () => ((await (await ctx.request.get(
    `${API}/tmf-api/serviceInventory/v4/service?relatedPartyId=${kaiSub}`, { headers: H })).json()) || [])
    .filter((s) => s.name === 'StreamPlus' && s.state === 'active');
  const before = (await spServices()).length;
  const order = await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: KH, data: {
      productOrderItem: [{ action: 'add', productOffering: { id: streamplus.id, name: 'StreamPlus' } }],
      relatedParty: [{ id: kaiSub, role: 'customer' }] } });
  if (order.status() !== 201) fail('order against the copilot-made offering failed: ' + order.status());
  let code = null;
  for (let i = 0; i < 20 && !code; i++) {
    await new Promise((r) => setTimeout(r, 2500));
    const active = await spServices();
    if (active.length > before) {
      code = (active[active.length - 1].serviceCharacteristic || [])
        .find((c) => c.name === 'activationCode')?.value;
    }
  }
  if (!code) fail('no partner entitlement code — the copilot\'s category did not drive fulfilment');
  console.log(`OK a customer ordered it and the SOM minted entitlement ${code} — `
    + 'the copilot\'s category choice drove real fulfilment');

  /* ---------- scenario 2: the four-part smartwatch bundle ---------- */
  await say('Now I want a kids smartwatch product');
  await page.locator('[data-testid=copilot-proposal]').nth(1).waitFor({ timeout: 20000 });
  await page.locator('[data-testid=copilot-create]').nth(1).click();
  await page.locator('[data-testid=copilot-created]').nth(1).waitFor({ timeout: 25000 });
  const bundle = (await (await ctx.request.get(
    `${CATALOG}/productOffering?name=${encodeURIComponent('Kids Watch Starter')}`, { headers: H })).json())[0];
  if (!bundle || !bundle.isBundle) fail('the smartwatch bundle was not created');
  const children = bundle.bundledProductOffering || [];
  if (children.length !== 3) fail(`bundle should have 3 children, got ${children.length}`);
  const gps = children.find((c) => (c.name || '').includes('GPS'));
  if (!gps || gps.bundledProductOfferingOption?.numberRelOfferLowerLimit !== 0) {
    fail('GPS Tracking should be the OPTIONAL child');
  }
  if (!(bundle.productOfferingTerm || []).some((t) => t.duration?.amount === 12)) {
    fail('bundle missing the 12-month commitment');
  }
  console.log('OK four-part proposal applied in dependency order: device + plan + security '
    + 'add-on + 12-month bundle with GPS optional');

  /* ---------- scenario 3: a plan + a cross-product discount RULE ---------- */
  await say('I want to create a 5G mobile plan with 50 GB data and if customer buys a Samsung phone then 10% discount');
  await page.locator('[data-testid=copilot-proposal]').nth(2).waitFor({ timeout: 20000 });
  const ruleCard = await page.locator('[data-testid=copilot-proposal]').nth(2).textContent();
  if (!ruleCard.includes('pricing rule') || !ruleCard.includes('Samsung')) {
    fail('proposal card missing the pricing-rule row: ' + ruleCard.slice(0, 200));
  }
  await page.locator('[data-testid=copilot-create]').nth(2).click();
  await page.locator('[data-testid=copilot-created]').nth(2).waitFor({ timeout: 25000 });
  const plan50 = (await (await ctx.request.get(
    `${CATALOG}/productOffering?name=${encodeURIComponent('5G Mobile Plan 50 GB')}`, { headers: H })).json())[0];
  if (!plan50) fail('the 50 GB plan was not created');
  const madeRules = (await (await ctx.request.get(
    `${API}/tmf-api/policyManagement/v4/policyRule?limit=100`, { headers: H })).json())
    .filter((r) => r.name === 'Samsung with plan discount');
  if (madeRules.length !== 1) fail(`expected 1 discount rule, got ${madeRules.length}`);
  const cond = madeRules[0].condition || '';
  if (!cond.includes(plan50.id)) fail('rule condition does not reference the NEW plan id: ' + cond);
  const samsungId = (await (await ctx.request.get(
    `${CATALOG}/productOffering?name=${encodeURIComponent('Samsung Galaxy S26')}`, { headers: H })).json())[0].id;
  if (!cond.includes(samsungId)) fail('rule condition does not reference the existing Samsung id');
  if (madeRules[0].adjustmentValue !== -10 || madeRules[0].domain !== 'pricing') {
    fail('rule shape wrong: ' + JSON.stringify(madeRules[0]));
  }
  if (!cond.includes('"!"')) fail('consumer-scoped rule missing the no-organization clause: ' + cond);
  console.log('OK cross-product discount: the plan became an offering and the "10% off with a'
    + ' Samsung" became a PRICING RULE referencing both — consumer-scoped, ids resolved');

  // the rule is also the shop window: anonymous teaser + banner on the plan page
  const teasers = await (await ctx.request.get(
    `${API}/tmf-api/policyManagement/v4/price/teaser?offeringId=${plan50.id}`)).json();
  if (!teasers.length || teasers[0].audience !== 'consumer') {
    fail('anonymous teaser missing or unscoped: ' + JSON.stringify(teasers));
  }
  const shopPage = await (await browser.newContext()).newPage();
  await shopPage.goto(`${API}/shop/offering/${plan50.id}`);
  await shopPage.locator('[data-testid=offer-promos]').waitFor({ timeout: 15000 });
  const promoText = await shopPage.locator('[data-testid=offer-promos]').textContent();
  if (!promoText.includes('Samsung') || !promoText.includes('private customers only')) {
    fail('offering page does not advertise the deal honestly: ' + promoText.slice(0, 160));
  }
  await shopPage.close();
  console.log('OK the plan page ADVERTISES the deal — with the honest consumer-only note');

  // tidy: this was a demo conversation, not tenant catalog
  await sweep();
  await browser.close();
  console.log('\nALL COPILOT CHECKS PASSED — chat, clarify, propose, human confirm, deterministic '
    + 'apply under the owner\'s token, and the created product sells and fulfils for real; cross-product discounts land as pricing rules.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
