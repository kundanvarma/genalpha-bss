/* Colour-conditioned pricing (TMF620 prodSpecCharValueUse): one offering, one
 * spec, no SKU per colour — and the price follows the pick like the photo does.
 *
 *  - catalog: the "Titanium Edition premium" price carries its condition and
 *    round-trips (headless).
 *  - shop: the Samsung page shows the base price; picking Titanium Edition
 *    adds the premium row AND swaps the hero photo; picking another colour
 *    removes it.
 *  - order → bill: two customers buy the same offering in different colours;
 *    the billing run rates Titanium at base+premium, Phantom Black at base.
 *  - colour campaign: one pricing rule conditioned on "color:Titanium
 *    Edition" (the console has a preset for it) lands a discount line on the
 *    Titanium bill only — cart preview and invoice share the context.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const CATALOG = `${API}/tmf-api/productCatalogManagement/v4`;
const run = Date.now();

async function token(request, user, pass) {
  const res = await request.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const staff = await token(ctx.request, 'demo', 'demo');
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };

  /* ---------- 1. the conditioned price, headless ---------- */
  const samsung = (await (await ctx.request.get(
    `${CATALOG}/productOffering?name=${encodeURIComponent('Samsung Galaxy S26')}`, { headers: H })).json())[0];
  const prices = await (await ctx.request.get(`${CATALOG}/productOfferingPrice?limit=100`, { headers: H })).json();
  const priceById = Object.fromEntries(prices.map((p) => [p.id, p]));
  const linked = (samsung.productOfferingPrice || []).map((r) => priceById[r.id]).filter(Boolean);
  const premium = linked.find((p) => (p.prodSpecCharValueUse || []).length);
  if (!premium) fail('Samsung has no characteristic-conditioned price');
  const condition = premium.prodSpecCharValueUse[0];
  if (condition.name !== 'color'
      || !(condition.productSpecCharacteristicValue || []).some((v) => v.value === 'Titanium Edition')) {
    fail('premium condition wrong: ' + JSON.stringify(premium.prodSpecCharValueUse));
  }
  const premiumValue = premium.price.value;
  const base = linked.filter((p) => p.priceType === 'recurring' && !(p.prodSpecCharValueUse || []).length)
    .reduce((s, p) => s + p.price.value, 0);
  console.log(`OK catalog: base ${base.toFixed(2)} + conditioned "${premium.name}" `
    + `${premiumValue.toFixed(2)}/mo when color=Titanium Edition — one offering, no colour SKUs`);

  /* ---------- 2. the shop: price and photo follow the pick ---------- */
  const page = await (await browser.newContext()).newPage();
  await page.goto(`${API}/shop/offering/${samsung.id}`);
  await page.waitForSelector('.pricetable', { timeout: 20000 });
  const colorSelect = page.locator('.charfield', { hasText: 'color' }).locator('select');
  await colorSelect.waitFor({ timeout: 10000 });
  const totalOf = async () => Number((await page.locator('.pricetable .total .num').last().textContent())
    .replace(/[^\d.,]/g, '').replace(',', '.'));
  await colorSelect.selectOption('Phantom Black');
  await page.waitForTimeout(400);
  const blackTotal = await totalOf();
  if (await page.locator('.pricetable', { hasText: premium.name }).count()
      && (await page.locator('.pricetable td', { hasText: premium.name }).count())) {
    fail('premium row visible without the Titanium pick');
  }
  const heroBlack = await page.locator('[data-testid=offer-hero]').getAttribute('src');
  await colorSelect.selectOption('Titanium Edition');
  await page.waitForTimeout(400);
  const titaniumTotal = await totalOf();
  if (Math.abs(titaniumTotal - blackTotal - premiumValue) > 0.01) {
    fail(`total did not gain the premium: ${blackTotal} -> ${titaniumTotal}`);
  }
  if (!(await page.locator('.pricetable td', { hasText: premium.name }).count())) {
    fail('premium row missing from the pricing table');
  }
  const heroTitanium = await page.locator('[data-testid=offer-hero]').getAttribute('src');
  if (heroTitanium === heroBlack) fail('hero photo did not follow the Titanium pick');
  console.log(`OK shop: ${blackTotal.toFixed(2)} in Phantom Black -> ${titaniumTotal.toFixed(2)} `
    + 'in Titanium Edition — the price AND the photo follow the pick');

  /* ---------- 3. the colour campaign rule (console preset shape) ---------- */
  const POLICY = `${API}/tmf-api/policyManagement/v4`;
  const ruleRes = await ctx.request.post(`${POLICY}/policyRule`, { headers: H, data: {
    name: `Titanium launch ${run}`, domain: 'pricing', effect: 'adjust',
    priority: 40, enabled: true,
    condition: JSON.stringify({ in: ['color:Titanium Edition', { var: 'characteristicValues' }] }),
    adjustmentType: 'percent', adjustmentValue: -10,
    message: 'Titanium launch: 10% off',
  } });
  if (ruleRes.status() !== 201) fail('colour campaign rule create failed: ' + ruleRes.status());
  const ruleId = (await ruleRes.json()).id;
  const quote = await (await ctx.request.post(`${POLICY}/price`, { headers: H, data: { context: {
    subtotal: titaniumTotal, offeringIds: [samsung.id],
    characteristicValues: ['color:Titanium Edition', 'storage:512GB'],
  } } })).json();
  if (!(quote.adjustments || []).some((a) => (a.label || '').includes('Titanium launch'))) {
    fail('colour campaign did not fire in the engine');
  }
  const quietQuote = await (await ctx.request.post(`${POLICY}/price`, { headers: H, data: { context: {
    subtotal: titaniumTotal, offeringIds: [samsung.id],
    characteristicValues: ['color:Phantom Black'],
  } } })).json();
  if ((quietQuote.adjustments || []).some((a) => (a.label || '').includes('Titanium launch'))) {
    fail('colour campaign fired for the wrong colour');
  }
  console.log('OK campaign on a colour: the rule fires for Titanium picks only — data, no redeploy');

  /* ---------- 4. order -> bill: the premium and the campaign on the invoice ---------- */
  const mkCustomer = async (name) => (await (await ctx.request.post(`${API}/tmf-api/party/v4/individual`, {
    headers: H, data: { givenName: name, familyName: `Colour${run}` } })).json()).id;
  const buy = async (partyId, color) => {
    const res = await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
      headers: H, data: {
        productOrderItem: [{ action: 'add',
          productOffering: { id: samsung.id, name: samsung.name },
          product: { productCharacteristic: [
            { name: 'color', value: color }, { name: 'storage', value: '256GB' }] } }],
        relatedParty: [{ id: partyId, role: 'customer' }] } });
    if (res.status() !== 201) fail(`order for ${color} failed: ` + res.status());
  };
  const tina = await mkCustomer('Tina');   // Titanium Edition
  const paal = await mkCustomer('Paal');   // Phantom Black
  await buy(tina, 'Titanium Edition');
  await buy(paal, 'Phantom Black');
  // wait until both products are active in inventory
  for (const [party, color] of [[tina, 'Titanium Edition'], [paal, 'Phantom Black']]) {
    let ok = false;
    for (let i = 0; i < 25 && !ok; i++) {
      await new Promise((r) => setTimeout(r, 2500));
      const products = await (await ctx.request.get(
        `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${party}&status=active&limit=20`,
        { headers: H })).json();
      ok = products.some((p) => (p.productCharacteristic || []).some((c) => c.value === color));
    }
    if (!ok) fail(`product for ${color} never went active`);
  }
  await ctx.request.post(`${API}/tmf-api/customerBillManagement/v4/billingRun`, { headers: H });
  const billOf = async (party) => {
    const bills = await (await ctx.request.get(
      `${API}/tmf-api/customerBillManagement/v4/customerBill?relatedPartyId=${party}`, { headers: H })).json();
    if (bills.length !== 1) fail(`expected 1 bill, got ${bills.length}`);
    return await (await ctx.request.get(
      `${API}/tmf-api/customerBillManagement/v4/customerBill/${bills[0].id}/appliedCustomerBillingRate`,
      { headers: H })).json();
  };
  const tinaLines = await billOf(tina);
  const paalLines = await billOf(paal);
  const recurringOf = (lines) => lines.filter((r) => r.type === 'recurringCharge')
    .reduce((s, r) => s + r.taxExcludedAmount.value, 0);
  const tinaRecurring = recurringOf(tinaLines);
  const paalRecurring = recurringOf(paalLines);
  // both ordered today, so both bills are prorated — the premium is too
  const nowC = new Date();
  const cStart = Date.UTC(nowC.getUTCFullYear(), nowC.getUTCMonth(), 1);
  const cEnd = Date.UTC(nowC.getUTCFullYear(), nowC.getUTCMonth() + 1, 0);
  const cTotal = Math.round((cEnd - cStart) / 86400000) + 1;
  const cLeft = Math.round((cEnd - Date.UTC(nowC.getUTCFullYear(), nowC.getUTCMonth(), nowC.getUTCDate())) / 86400000) + 1;
  const proratedPremium = premiumValue * cLeft / cTotal;
  if (Math.abs(tinaRecurring - paalRecurring - proratedPremium) > 0.02) {
    fail(`bills should differ by the prorated premium ~${proratedPremium.toFixed(2)}: `
      + `titanium ${tinaRecurring}, black ${paalRecurring}`);
  }
  if (!tinaLines.some((r) => (r.name || '').includes('Titanium launch'))) {
    fail('Titanium bill missing the colour-campaign discount line');
  }
  if (paalLines.some((r) => (r.name || '').includes('Titanium launch'))) {
    fail('the colour campaign leaked onto the Phantom Black bill');
  }
  console.log(`OK invoices: Titanium rated ${tinaRecurring.toFixed(2)} (base+premium) WITH the campaign `
    + `discount line; Phantom Black rated ${paalRecurring.toFixed(2)}, untouched`);

  // tidy: the campaign was for the take, not the tenant
  await ctx.request.delete(`${POLICY}/policyRule/${ruleId}`, { headers: H });

  await browser.close();
  console.log('\nALL COLOUR-PRICING CHECKS PASSED — conditioned price components, the shop follows '
    + 'the pick, the bill rates the configuration, and marketing can campaign on a colour as data.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
