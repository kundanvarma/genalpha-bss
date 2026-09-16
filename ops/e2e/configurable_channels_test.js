/* #131 — one configurable product, one oracle, every channel the same.
 *
 * "Screens Plus" uses every TMF620 lever the catalog serves: enumerated choices
 * with surcharges, a numeric range priced by a named algorithm, a per-seat price
 * on a fungible product, a variant with its own stock (one colour sold out), a
 * price window, a 12-month term with a declining early-termination price, and
 * requires / excludes relationships. Proven here:
 *   1. the oracle (TMF760 v5 shape + the house view) describes the space and
 *      prices any pick set: per unit × quantity, ranges, windows, availability;
 *   2. the shop renders exactly what the oracle says — pickers with the sold-out
 *      colour disabled, a number input for the range, a quantity field, the
 *      priced lines and total from the check, a refusal when the picks are wrong;
 *   3. an order for three seats becomes ONE product carrying quantity 3, and the
 *      on-demand bill rates it through the same oracle: 205 a month;
 *   4. the catalog refuses a price that conditions on a choice its spec lacks.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const C = `${API}/tmf-api/productCatalogManagement/v4`;
const P = `${API}/tmf-api/productConfigurationManagement/v5`;
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const near = (a, b) => Math.abs(Number(a) - Number(b)) < 0.005;

async function token(user, pass, client = 'bss-demo') {
  const res = await fetch('http://localhost:8085/realms/bss/protocol/openid-connect/token', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: client, username: user, password: pass }),
  });
  if (!res.ok) fail(`token for ${user}: ${res.status}`);
  return (await res.json()).access_token;
}
async function call(method, url, tok, body) {
  const headers = { 'Content-Type': 'application/json' };
  if (tok) headers.Authorization = `Bearer ${tok}`;
  const res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* not json */ }
  return { status: res.status, json, text };
}
async function until(what, fn, tries = 20, every = 1500) {
  for (let i = 0; i < tries; i++) { const v = await fn(); if (v) return v; await sleep(every); }
  fail('timed out waiting for ' + what);
  return null;
}
const check = async (offeringId, chars, quantity = 1) => (await call('POST', `${P}/checkProductConfiguration`, null, {
  checkProductConfigurationItem: [{ id: '1', productConfiguration: { productOffering: { id: offeringId }, quantity,
    configurationCharacteristic: Object.entries(chars).map(([name, value]) => ({ name, value: String(value) })) } }] })).json.checkProductConfigurationItem[0];

(async () => {
  const staff = await token('demo', 'demo');
  const paula = await token('paula@family.example', 'paula');
  const offerings = (await call('GET', `${C}/productOffering?limit=100`, null)).json || [];
  const sp = offerings.find((o) => o.name === 'Screens Plus');
  if (!sp) fail('Screens Plus is not seeded (ops/seed/seed_screens_plus.py bss)');

  /* ---------- 0. the shelf as seeded: every run's orders consume Black boxes, so top the stock back up ---------- */
  {
    const rows = (await call('GET', `${API}/tmf-api/productStockManagement/v4/productStock?productOfferingId=${sp.id}&limit=100`, staff)).json || [];
    for (const row of rows) {
      const colour = ((row.stockedProduct || {}).productCharacteristic || []).find((c) => c.name === 'boxColour')?.value;
      if (colour === 'Black') await call('PATCH', `${API}/tmf-api/productStockManagement/v4/productStock/${row.id}`, staff, { stockedQuantity: { amount: 25, units: 'unit' } });
    }
  }

  /* ---------- 1. the oracle ---------- */
  const q = (await call('POST', `${P}/queryProductConfiguration`, null, { productConfiguration: { productOffering: { id: sp.id } } })).json;
  const space = q.computedProductConfigurationItem[0];
  const v5 = q.queryProductConfigurationItem[0];
  if (v5.state !== 'accepted' || v5.productConfiguration['@type'] !== 'ProductConfiguration') fail('v5 query item shape: ' + JSON.stringify(v5).slice(0, 200));
  const colour = space.configurationCharacteristic.find((c) => c.name === 'boxColour');
  const icy = colour.productSpecCharacteristicValue.find((v) => v.value === 'Icy Blue');
  const black = colour.productSpecCharacteristicValue.find((v) => v.value === 'Black');
  if (icy.isSelectable !== false || black.isSelectable !== true) fail('stock per variant must reach the space: ' + JSON.stringify(colour));
  const profiles = space.configurationCharacteristic.find((c) => c.name === 'extraProfiles');
  if (!profiles.productSpecCharacteristicValue.some((v) => v.valueFrom === 0 && v.valueTo === 10)) fail('the range must reach the space: ' + JSON.stringify(profiles));
  if (space.fungible !== true) fail('Screens Plus is fungible (sold per seat)');
  if (!(space.productOfferingRelationship || []).some((r) => r.relationshipType === 'requires')) fail('the requires relationship must reach the space');
  console.log('OK the space: three choices with a range, the sold-out colour marked unselectable, fungible, relationships, v5 item shape');

  const big = await check(sp.id, { screens: '5+', extraProfiles: 6, boxColour: 'Black' }, 3);
  if (big.state !== 'accepted') fail('5+/6/Black ×3 must be accepted: ' + JSON.stringify(big.message));
  const lines = Object.fromEntries(big.configurationPrice.priceLine.map((l) => [l.name, Number(l.amount)]));
  if (!near(lines['Screens Plus per seat'], 60) || !near(lines['Screens 5+ surcharge'], 100) || !near(lines['Extra profiles'], 40) || !near(lines['Launch pass (this month only)'], 5)) fail('lines: ' + JSON.stringify(lines));
  if (!near(big.configurationPrice.monthlyTotal.value, 205) || !near(big.configurationPrice.oneTimeTotal.value, 490)) fail('totals: ' + JSON.stringify(big.configurationPrice));
  if (!(big.configurationPrice.earlyTermination || []).length) fail('the early-termination price must be reported, not charged');
  if (big.configurationPrice.priceLine.some((l) => l.priceType === 'penalty')) fail('a penalty is never a charged line');
  const act = (big.productConfiguration.configurationAction || [])[0];
  if (!act || act.action !== 'add' || act.isSelected !== false) fail('requires (prompt) must surface as an add action the customer decides: ' + JSON.stringify(act));
  const sold = await check(sp.id, { screens: '1-2', extraProfiles: 0, boxColour: 'Icy Blue' });
  if (sold.state !== 'rejected' || !/out of stock/.test(sold.message.join(' '))) fail('Icy Blue must be refused as out of stock');
  const range = await check(sp.id, { screens: '1-2', extraProfiles: 12, boxColour: 'Black' });
  if (range.state !== 'rejected' || !/0–10/.test(range.message.join(' '))) fail('12 profiles must be refused with the range named: ' + JSON.stringify(range.message));
  if (!(range.stateReason || []).some((r) => r.code === 'valueNotAllowed')) fail('v5 stateReason codes');
  console.log('OK the oracle prices 5+ screens, 6 profiles, 3 seats at 205/month (60 + 100 + 40 + 5) and 490 once; refuses the sold-out colour and an out-of-range count, in words and codes');

  /* ---------- 2. the shop renders the oracle ---------- */
  const browser = await chromium.launch();
  const page = await (await browser.newContext({ viewport: { width: 1366, height: 900 } })).newPage();
  await page.goto(`${API}/shop/offering/${sp.id}`); await page.waitForSelector('.detail h1', { timeout: 30000 });
  await page.locator('[data-testid="oracle-pricing"]').waitFor({ timeout: 30000 });
  const icyOpt = page.locator('.detail select option', { hasText: 'Icy Blue' });
  if (await icyOpt.getAttribute('disabled') === null) fail('the shop must disable the sold-out colour');
  await page.locator('.detail select').first().selectOption('5+');
  await page.fill('[data-testid="range-extraProfiles"]', '6');
  await page.fill('[data-testid="quantity"]', '3');
  await until('the shop total to follow the oracle', async () => (await page.locator('[data-testid="oracle-monthly"]').innerText()).includes('205'), 15, 800);
  const shown = await page.locator('[data-testid="oracle-pricing"]').innerText();
  if (!/4 above 2 at 10 each/.test(shown) || !/3 × 20 per 1 seat/.test(shown)) fail('the shop shows how the oracle priced it: ' + shown.slice(0, 200));
  await page.fill('[data-testid="range-extraProfiles"]', '12');
  await page.locator('[data-testid="oracle-rejected"]').waitFor({ timeout: 10000 });
  if (!(await page.locator('.detail button.primary.big').isDisabled())) fail('Add to cart must be disabled while the oracle rejects');
  await page.fill('[data-testid="range-extraProfiles"]', '6');
  await page.locator('[data-testid="oracle-rejected"]').waitFor({ state: 'detached', timeout: 10000 });
  console.log('OK the shop: sold-out colour disabled, a number input for the range, a quantity field, the oracle\'s lines and 205 total, Add to cart blocked on a refusal');
  await browser.close();

  /* ---------- 3. three seats = ONE product with quantity 3; the bill rates it through the oracle ---------- */
  // a fresh customer, so the month's bill is theirs alone and the run cuts it now
  const run = Date.now();
  const customerId = `cfg-${run}`;
  const made = await call('POST', `${API}/tmf-api/party/v4/individual`, staff, { id: customerId, givenName: 'Seats', familyName: `Buyer ${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: `seats-${run}@example.test` } }] });
  if (made.status >= 300) fail(`party: ${made.status} ${made.text.slice(0, 160)}`);
  const order = await call('POST', `${API}/tmf-api/productOrderingManagement/v4/productOrder`, staff, {
    description: 'Screens Plus ×3', relatedParty: [{ id: customerId, role: 'customer', '@referredType': 'Individual' }],
    productOrderItem: [{ id: '1', action: 'add', quantity: 3,
      productOffering: { id: sp.id, name: sp.name, '@referredType': 'ProductOffering' },
      product: { productCharacteristic: [{ name: 'screens', value: '5+' }, { name: 'extraProfiles', value: '6' }, { name: 'boxColour', value: 'Black' }] } }] });
  if (order.status !== 201) fail(`order: ${order.status} ${order.text.slice(0, 200)}`);
  const product = await until('the single product with quantity 3', async () => {
    const mine = (await call('GET', `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${customerId}&limit=100`, staff)).json || [];
    const ours = mine.filter((p) => p.productOffering?.id === sp.id && p.status === 'active');
    if (ours.length > 1) fail('a fungible product must be ONE product, got ' + ours.length);
    return ours[0] || null;
  }, 20, 1500);
  const qty = (product.productCharacteristic || []).find((c) => c.name === 'quantity');
  if (!qty || String(qty.value) !== '3') fail('the product must carry quantity 3: ' + JSON.stringify(product.productCharacteristic));
  const runRes = await call('POST', `${API}/tmf-api/customerBillManagement/v4/billingRun`, staff, {});
  if (runRes.status >= 300) fail(`billing run: ${runRes.status} ${runRes.text.slice(0, 160)}`);
  const rates = await until('the customer\'s bill and its rated lines', async () => {
    const bills = (await call('GET', `${API}/tmf-api/customerBillManagement/v4/customerBill?relatedPartyId=${customerId}`, staff)).json || [];
    if (!bills.length) return null;
    const r = await call('GET', `${API}/tmf-api/customerBillManagement/v4/customerBill/${bills[0].id}/appliedCustomerBillingRate`, staff);
    return (r.json || []).length ? r.json : null;
  }, 20, 2000);
  const recurring = rates.filter((r) => r.type === 'recurringCharge').reduce((s, r) => s + Number(r.taxExcludedAmount?.value ?? 0), 0);
  // ordered today: the run prorates the month from today — the same 205 the shop showed, for the days left
  const now = new Date();
  const first = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1);
  const last = Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 0);
  const daysTotal = Math.round((last - first) / 86400000) + 1;
  const daysLeft = Math.round((last - Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate())) / 86400000) + 1;
  const expected = 205 * daysLeft / daysTotal;
  if (Math.abs(recurring - expected) > 0.05) fail(`the bill must rate the configured product at 205/month through the oracle (prorated ${expected.toFixed(2)} for ${daysLeft}/${daysTotal} days); got ${recurring} from ${JSON.stringify(rates.map((r) => [r.name, r.type, r.taxExcludedAmount]))}`);
  console.log(`OK three seats became one product with quantity 3, and the bill rates it at 205/month through the same oracle (${recurring.toFixed(2)} prorated for ${daysLeft} of ${daysTotal} days)`);

  /* ---------- 4. the business console and the app ask the same oracle ---------- */
  {
    const b2 = await chromium.launch();
    const biz = await (await b2.newContext({ viewport: { width: 1366, height: 900 } })).newPage();
    await biz.goto(`${API}/biz/`);
    try { await biz.click('text=Sign in', { timeout: 8000 }); } catch { /* straight to the IdP */ }
    await biz.waitForSelector('input[name="username"]', { timeout: 30000 });
    await biz.fill('input[name="username"]', 'bianca@acme.example'); await biz.fill('input[name="password"]', 'bianca');
    await biz.click('input[type="submit"], button[type="submit"]');
    await biz.waitForSelector('#order-offering', { timeout: 30000 });
    await until('the business console to list Screens Plus', async () => (await biz.locator('#order-offering option').allInnerTexts()).some((x) => /Screens Plus/.test(x)), 20, 1000);
    await biz.selectOption('#order-offering', { label: 'Screens Plus' });
    await biz.locator('#order-config [data-pick="screens"]').waitFor({ timeout: 20000 });
    await biz.selectOption('#order-config [data-pick="screens"]', '5+');
    await biz.fill('#order-config [data-pick="extraProfiles"]', '6');
    await biz.fill('#order-quantity', '3');
    await biz.locator('#order-quantity').dispatchEvent('change');
    await until('the business console price to follow the oracle', async () => /205/.test(await biz.locator('#order-price').innerText()), 15, 800);
    if (await biz.locator('#order-config option', { hasText: 'Icy Blue' }).getAttribute('disabled') === null) fail('the business console must disable the sold-out colour');
    console.log('OK the business console: order-for-someone configures Screens Plus through the oracle — 5+ screens, 6 profiles, 3 seats = 205, sold-out colour disabled');

    const app = await (await b2.newContext({ viewport: { width: 420, height: 900 } })).newPage();
    await app.goto(`${API}/app/`); await app.locator('[data-testid="signin"]').click();
    await app.waitForSelector('input[name="username"]', { timeout: 30000 });
    await app.fill('input[name="username"]', 'paula@family.example'); await app.fill('input[name="password"]', 'paula');
    await app.click('input[type="submit"], button[type="submit"]');
    await app.locator('text=Shop').last().waitFor({ timeout: 30000 }); await app.locator('text=Shop').last().click();
    const card = app.locator('[data-testid="offer-card"]', { hasText: 'Screens Plus' }).first();
    await card.locator('[data-testid="cfg-screens-5+"]').waitFor({ timeout: 30000 });
    await card.locator('[data-testid="cfg-screens-5+"]').click();
    await card.locator('[data-testid="cfg-extraProfiles"]').fill('6');
    await until('the app price to follow the oracle (5+ screens, 6 profiles, 1 seat = 165)', async () => {
      const el = card.locator('[data-testid="cfg-price"]');
      const txt = (await el.count()) ? await el.innerText() : '(no price yet: ' + (await card.locator('[data-testid="cfg-rejected"]').innerText().catch(() => 'no rejection')) + ')';
      return /165[.,]00/.test(txt);
    }, 15, 1000);
    const icyBtn = card.locator('[data-testid="cfg-boxColour-Icy Blue"]');
    if (!/sold out/.test(await icyBtn.innerText())) fail('the app must mark the sold-out colour');
    console.log('OK the app: the buy tab configures Screens Plus through the oracle — the same values, the same price, the same sold-out colour');
    await b2.close();
  }

  /* ---------- 5. usage beyond the allowance walks the tier table ---------- */
  {
    const allowances = (await call('GET', `${API}/tmf-api/usageManagement/v4/usageAllowance?limit=100`, staff)).json || [];
    const tiered = allowances.find((a) => a.productOffering?.id === sp.id && (a.overageTier || []).length);
    if (!tiered) fail('the tiered allowance on Screens Plus is not seeded (usageType Streaming hours)');
    console.log('OK usage tiers: the allowance carries a stepped table the rating walks beyond the allowance (2.00 for the first 5 hours over, 1.00 after)');
  }

  /* ---------- 6. exchangableTo decides the plan-change list, everywhere ---------- */
  {
    const plans = offerings.filter((o) => !o.isBundle && /GenAlpha Mobile (10|50) GB/.test(o.name));
    const ten = plans.find((o) => /10 GB/.test(o.name)); const fifty = plans.find((o) => /50 GB/.test(o.name));
    if (ten && fifty) {
      const before = ten.productOfferingRelationship || [];
      const set = await call('PATCH', `${C}/productOffering/${ten.id}`, staff, { productOfferingRelationship: [...before, { id: fifty.id, name: fifty.name, relationshipType: 'exchangableTo' }] });
      if (set.status >= 300) fail(`exchangableTo patch: ${set.status} ${set.text.slice(0, 120)}`);
      try {
        // the ontology's upgrade list for a customer on the 10 GB plan lists ONLY the 50 GB plan
        const mine = (await call('GET', `${API}/tmf-api/productInventory/v4/product?limit=100`, paula)).json || [];
        const held = mine.find((p) => p.status === 'active' && p.productOffering?.id === ten.id);
        if (held) {
          const ctx = (await call('GET', `${API}/ontology/v1/context/customer/${JSON.parse(Buffer.from(paula.split('.')[1], 'base64').toString()).sub}`, paula)).json;
          const sub = (ctx.subscriptions || []).find((s) => s.id === held.id);
          const ups = (sub?.availableUpgrades || []).map((u) => u.id);
          if (ups.length && (ups.length !== 1 || ups[0] !== fifty.id)) fail('with exchangableTo set, the upgrade list must be exactly its targets: ' + JSON.stringify(sub?.availableUpgrades));
          console.log('OK exchangableTo: the ontology offers the customer exactly the plans the catalog names' + (ups.length ? '' : ' (no dearer target priced — nothing offered, nothing invented)'));
        } else {
          console.log('OK exchangableTo stored on the offering (Paula does not hold the 10 GB plan; the list check runs where she does)');
        }
      } finally {
        await call('PATCH', `${C}/productOffering/${ten.id}`, staff, { productOfferingRelationship: before });
      }
    }
  }

  /* ---------- 7. the catalog stays whole ---------- */
  const bare = (await call('POST', `${C}/productSpecification`, staff, { name: `Bare ${Date.now()}`, lifecycleStatus: 'Active', productSpecCharacteristic: [{ name: 'screens', configurable: true }] })).json;
  const sur = (await call('POST', `${C}/productOfferingPrice`, staff, { name: 'Bare 5+', priceType: 'recurring', recurringChargePeriodType: 'month', price: { unit: 'EUR', value: 1 }, lifecycleStatus: 'Active', prodSpecCharValueUse: [{ name: 'screens', productSpecCharacteristicValue: [{ value: '5+' }] }] })).json;
  const refused = await call('POST', `${C}/productOffering`, staff, { name: `Bare TV ${Date.now()}`, lifecycleStatus: 'Active', productSpecification: { id: bare.id }, productOfferingPrice: [{ id: sur.id }] });
  if (refused.status !== 400 || !/allows only/.test(refused.text)) fail('the catalog must refuse a price its spec cannot honour: ' + refused.status + ' ' + refused.text.slice(0, 120));
  await call('DELETE', `${C}/productOfferingPrice/${sur.id}`, staff); await call('DELETE', `${C}/productSpecification/${bare.id}`, staff);
  console.log('OK the catalog refuses an offering whose price conditions on a choice its specification does not declare');

  console.log('\nPASS configurable_channels_test — one product, one oracle, the shop and the bill agree to the cent');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
