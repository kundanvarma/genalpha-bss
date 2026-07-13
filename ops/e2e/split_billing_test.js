/* Split billing: the payer follows the orderer, and the device co-pay follows
 * the company policy.
 *
 * Setup (staff): fresh org + Bianca as its business admin. Then:
 *  - Bianca (browser, /biz): sets the Company policy — a device allowance —
 *    adds Mia with a real login, orders her a plan AND a device (company-paid).
 *  - Mia (API, her own token): buys Netflix herself — personal.
 *  - Operator billing run, then the proof:
 *      company bill  = plan + device capped at the allowance "(company share)",
 *                      and NO Netflix;
 *      Mia's bill    = Netflix + the device excess "above company allowance".
 *  - Mia (browser, /biz member view): sees her personal bill below the note.
 * Boundary: Bianca may patch the POLICY but not rename the company. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const run = Date.now();

async function token(request, client, user, pass) {
  const res = await request.post(KC, {
    form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

  // --- operator staff provisions the org + Bianca as its admin
  const staff = await token(ctx.request, 'bss-demo', 'demo', 'demo');
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };
  const bianca = await token(ctx.request, 'bss-biz', 'bianca@acme.example', 'bianca');
  const biancaSub = JSON.parse(Buffer.from(bianca.split('.')[1], 'base64').toString()).sub;
  const org = await (await ctx.request.post(`${API}/tmf-api/party/v4/organization`, {
    headers: H, data: { name: `Splitco AS ${run}`, tradingName: 'Splitco' } })).json();
  await ctx.request.post(`${API}/tmf-api/party/v4/individual`, {
    headers: H, data: { id: biancaSub, givenName: 'Bianca', familyName: 'Boss',
      organization: { id: org.id } } });
  await ctx.request.patch(`${API}/tmf-api/party/v4/individual/${biancaSub}`, {
    headers: H, data: { organization: { id: org.id } } });
  console.log('OK operator provisioned', `Splitco AS ${run}`, '+ Bianca as its business admin');

  // --- what we'll sell: a plan, a device, and a self-bought partner service
  const offers = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`, { headers: H })).json();
  const catOf = (o) => ((o.category || [])[0] || {}).name || '';
  const prices = Object.fromEntries((await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOfferingPrice?limit=100`,
    { headers: H })).json()).map((p) => [p.id, p]));
  const monthlyOf = (o) => (o.productOfferingPrice || []).map((r) => prices[r.id])
    // base price only: characteristic-conditioned components (a colour
    // premium) apply per configuration, and this order configures none
    .filter((p) => p && p.priceType === 'recurring' && p.price?.value != null
      && !(p.prodSpecCharValueUse || []).length)
    .reduce((s, p) => s + p.price.value, 0);
  const plan = offers.find((o) => catOf(o) === 'Mobile plans' && !o.isBundle && monthlyOf(o) > 0);
  const device = offers.find((o) => catOf(o) === 'Devices' && !o.isBundle && monthlyOf(o) > 1);
  const vas = offers.find((o) => catOf(o) === 'Partner services' && !o.isBundle && monthlyOf(o) > 0);
  if (!plan || !device || !vas) fail(`catalog missing pieces: plan=${!!plan} device=${!!device} vas=${!!vas}`);
  const deviceMonthly = monthlyOf(device);
  const vasMonthly = monthlyOf(vas);
  const allowance = Number((deviceMonthly / 2).toFixed(2));
  const excess = Number((deviceMonthly - allowance).toFixed(2));

  // --- boundary first: the admin may set POLICY, not identity
  const BH = { Authorization: 'Bearer ' + bianca, 'Content-Type': 'application/json' };
  const rename = await ctx.request.patch(`${API}/tmf-api/party/v4/organization/${org.id}`, {
    headers: BH, data: { name: 'Evil Rename AS' } });
  if (rename.status() !== 400) fail('admin renamed the company — should be policy-only: ' + rename.status());
  console.log('OK boundary: a company admin may set the policy but not rename the company');

  // --- Bianca in /biz: policy, member, company-paid orders
  const page = await (await browser.newContext()).newPage();
  await page.goto(`${API}/biz/`);
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', 'bianca@acme.example');
  await page.fill('input[name="password"]', 'bianca');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });

  await page.fill('#policy-allowance', String(allowance));
  await page.selectOption('#policy-unit', 'EUR');
  await page.click('#save-policy');
  await page.locator('#policy-status.ok').waitFor({ timeout: 15000 });
  const orgNow = await (await ctx.request.get(`${API}/tmf-api/party/v4/organization/${org.id}`,
    { headers: H })).json();
  if (Number(orgNow.deviceAllowance?.value) !== allowance) {
    fail('policy did not persist: ' + JSON.stringify(orgNow.deviceAllowance));
  }
  console.log(`OK Bianca set the Company policy in /biz: device allowance ${allowance.toFixed(2)} EUR/month`);

  const miaEmail = `mia-${run}@splitco.example`;
  await page.fill('#new-given', 'Mia');
  await page.fill('#new-family', `Member${run}`);
  await page.fill('#new-email', miaEmail);
  await page.click('#add-member');
  await page.locator('#member-status.ok').waitFor({ timeout: 15000 });
  const creds = await page.locator('[data-testid=invite-credentials]').textContent();
  const miaPassword = creds.split('/').pop().trim();
  console.log('OK Bianca added Mia — with a real sign-in');

  await page.locator('#order-member option', { hasText: 'Mia' }).waitFor({ state: 'attached', timeout: 10000 });
  for (const offering of [plan, device]) {
    await page.selectOption('#order-member', { label: `Mia Member${run}` });
    await page.selectOption('#order-offering', { label: offering.name });
    await page.click('#place-order');
    await page.locator('#order-status.ok').waitFor({ timeout: 25000 });
  }
  console.log(`OK Bianca ordered ${plan.name} + ${device.name} for Mia — company-paid`);

  // --- Mia buys Netflix HERSELF: no payer stamp, so it's hers
  const mia = await token(ctx.request, 'bss-biz', miaEmail, miaPassword);
  const miaSub = JSON.parse(Buffer.from(mia.split('.')[1], 'base64').toString()).sub;
  const MH = { Authorization: 'Bearer ' + mia, 'Content-Type': 'application/json' };
  const selfBuy = await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: MH, data: { productOrderItem: [{ action: 'add',
      productOffering: { id: vas.id, name: vas.name } }],
      relatedParty: [{ id: miaSub, role: 'customer' }] } });
  if (selfBuy.status() !== 201) fail('Mia self-buy failed: ' + selfBuy.status());
  console.log(`OK Mia bought ${vas.name} herself — personal, not company-paid`);

  // --- everything active in inventory (payer stamps included)
  let products = [];
  for (let i = 0; i < 30; i++) {
    await new Promise((r) => setTimeout(r, 3000));
    products = await (await ctx.request.get(
      `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${miaSub}&status=active&limit=50`,
      { headers: H })).json();
    if (products.length >= 3) break;
  }
  if (products.length < 3) fail(`Mia has ${products.length}/3 active products`);
  const payerOf = (p) => ((p.relatedParty || []).find((r) => r.role === 'payer') || {}).id;
  const planProd = products.find((p) => p.name === plan.name);
  const deviceProd = products.find((p) => p.name === device.name);
  const vasProd = products.find((p) => p.name === vas.name);
  if (payerOf(planProd) !== org.id || payerOf(deviceProd) !== org.id) {
    fail('admin-placed orders are not payer-stamped to the org');
  }
  if (payerOf(vasProd)) fail('Mia\'s self-buy got a payer stamp: ' + payerOf(vasProd));
  console.log('OK payer stamps: plan+device -> Splitco, Netflix -> none (Mia\'s own)');

  // --- the billing run splits it
  await ctx.request.post(`${API}/tmf-api/customerBillManagement/v4/billingRun`, { headers: H });
  const orgBills = await (await ctx.request.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill?relatedPartyId=${org.id}`,
    { headers: H })).json();
  if (orgBills.length !== 1) fail(`expected 1 company bill, got ${orgBills.length}`);
  const orgLines = await (await ctx.request.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill/${orgBills[0].id}/appliedCustomerBillingRate`,
    { headers: H })).json();
  const share = orgLines.find((r) => String(r.name).includes(device.name)
    && String(r.name).includes('company share'));
  if (!share) fail('company bill missing the capped device line: '
    + orgLines.map((r) => r.name).join(' | '));
  if (Math.abs(share.taxExcludedAmount.value - allowance) > 0.01) {
    fail(`company share should be the allowance ${allowance}, got ${share.taxExcludedAmount.value}`);
  }
  if (!orgLines.some((r) => String(r.name).includes(plan.name))) fail('company bill missing the plan');
  if (orgLines.some((r) => String(r.name).includes(vas.name))) {
    fail("Mia's personal Netflix leaked onto the company bill");
  }
  console.log(`OK company bill: ${plan.name} + ${device.name} (company share ${allowance.toFixed(2)}), no ${vas.name}`);

  const miaBills = await (await ctx.request.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill?relatedPartyId=${miaSub}`,
    { headers: H })).json();
  if (miaBills.length !== 1) fail(`expected 1 personal bill for Mia, got ${miaBills.length}`);
  const miaLines = await (await ctx.request.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill/${miaBills[0].id}/appliedCustomerBillingRate`,
    { headers: H })).json();
  const excessLine = miaLines.find((r) => String(r.name).includes('above company allowance'));
  if (!excessLine) fail('personal bill missing the device excess line: '
    + miaLines.map((r) => r.name).join(' | '));
  if (Math.abs(excessLine.taxExcludedAmount.value - excess) > 0.01) {
    fail(`device excess should be ${excess}, got ${excessLine.taxExcludedAmount.value}`);
  }
  const vasLine = miaLines.find((r) => String(r.name).includes(vas.name));
  if (!vasLine || Math.abs(vasLine.taxExcludedAmount.value - vasMonthly) > 0.01) {
    fail(`personal bill should carry ${vas.name} at ${vasMonthly}`);
  }
  if (miaLines.some((r) => String(r.name).includes(plan.name))) {
    fail('the company-paid plan leaked onto the personal bill');
  }
  console.log(`OK Mia's personal bill: ${vas.name} ${vasMonthly.toFixed(2)} + device excess ${excess.toFixed(2)} — nothing else`);

  // --- Mia sees it herself in /biz
  const miaPage = await (await browser.newContext()).newPage();
  await miaPage.goto(`${API}/biz/`);
  await miaPage.waitForSelector('input[name="username"]', { timeout: 20000 });
  await miaPage.fill('input[name="username"]', miaEmail);
  await miaPage.fill('input[name="password"]', miaPassword);
  await miaPage.click('input[type="submit"], button[type="submit"]');
  await miaPage.waitForSelector('#memberview:not([hidden])', { timeout: 20000 });
  await miaPage.locator('#member-bills .billrow').first().waitFor({ timeout: 15000 });
  await miaPage.locator('#member-bills .billlines div').first().waitFor({ timeout: 15000 });
  const shown = await miaPage.locator('#member-bills').textContent();
  if (!shown.includes('above company allowance')) {
    fail('member view does not show the labelled excess line: ' + shown.slice(0, 200));
  }
  console.log("OK /biz member view: Mia's personal bill renders, excess line labelled");

  await browser.close();
  console.log('\nALL SPLIT-BILLING CHECKS PASSED — policy in /biz, payer-stamped company orders, '
    + 'personal self-buy, device co-pay split across two bills, member sees their own bill.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
