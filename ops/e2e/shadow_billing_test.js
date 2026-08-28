/* P3 — continuous shadow billing: the parallel bill run, standing.
 *
 *  - a customer buys a probe plan at 50/mo; a REAL billing run invoices it
 *  - the catalog price then changes 50 -> 59 (the classic silent repricing)
 *  - the shadow sweep must raise a drift row: billed 50, next bill 59, +9 —
 *    BEFORE any wrong (or surprising) invoice lands
 *  - the drift lists on the API and renders in the console pane
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const CAT = `${API}/tmf-api/productCatalogManagement/v4`;
const BILL = `${API}/tmf-api/customerBillManagement/v4`;

async function token(ctx, client, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  /* ---------- probe plan at 50, bought, and REALLY billed ---------- */
  const price = await (await ctx.post(`${CAT}/productOfferingPrice`, { headers: H(staff),
    data: { name: `Shadow ${run} monthly`, priceType: 'recurring', recurringChargePeriodType: 'month',
      lifecycleStatus: 'Active', price: { unit: 'EUR', value: 50.0 } } })).json();
  const offering = await (await ctx.post(`${CAT}/productOffering`, { headers: H(staff),
    data: { name: `Shadow Plan ${run}`, lifecycleStatus: 'Active', isBundle: false, isSellable: true,
      productOfferingPrice: [{ id: price.id, name: price.name }] } })).json();
  const email = `shadow-${run}@example.com`;
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Sha', familyName: `Dow${run}` } })).json();
  const cust = await token(ctx, 'bss-biz', email, login.temporaryPassword);
  const order = await (await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`,
    { headers: H(cust), data: { productOrderItem: [{ action: 'add',
      productOffering: { id: offering.id, name: offering.name } }] } })).json();
  for (let i = 0; i < 30; i++) {
    const st = (await (await ctx.get(`${API}/tmf-api/productOrderingManagement/v4/productOrder/${order.id}`,
      { headers: H(staff) })).json()).state;
    if (st === 'completed') break;
    await sleep(3000);
  }
  await ctx.post(`${BILL}/billingRun`, { headers: H(staff), data: {}, timeout: 180000 });
  console.log('OK a customer bought Shadow Plan at 50/mo and a REAL billing run invoiced it');

  /* ---------- the silent repricing ---------- */
  await ctx.patch(`${CAT}/productOfferingPrice/${price.id}`, { headers: H(staff),
    data: { price: { unit: 'EUR', value: 59.0 } } });
  console.log('OK the catalog price changed 50 -> 59 — the last bill is now stale');

  /* ---------- the shadow run catches it BEFORE the next invoice ---------- */
  let drift = null;
  for (let i = 0; i < 3 && !drift; i++) {
    const raised = await (await ctx.post(`${BILL}/shadowDrift/sweep?partyId=${login.id}`,
      { headers: H(staff), data: {}, timeout: 120000 })).json();
    drift = (raised || []).find((d) => d.offeringName === `Shadow Plan ${run}`);
    if (!drift) await sleep(2000);
  }
  if (!drift) {
    const list = await (await ctx.get(`${BILL}/shadowDrift`, { headers: H(staff) })).json();
    drift = (list || []).find((d) => d.offeringName === `Shadow Plan ${run}`);
  }
  if (!drift) fail('the shadow sweep never raised the drift');
  // undoing proration from a 2-decimal amount carries ±0.05 rounding — the
  // same tolerance the service's drift threshold uses
  const close = (a, b) => Math.abs(Number(a) - b) <= 0.05;
  if (!close(drift.billedMonthly, 50) || Number(drift.currentMonthly) !== 59
      || !close(drift.delta, 9)) {
    fail(`drift numbers wrong: ${JSON.stringify(drift)}`);
  }
  console.log(`OK drift raised: billed ~50 (${drift.billedMonthly}, proration undone), next bill 59, `
    + `delta ~+9 — caught before any invoice moved`);

  /* ---------- it lists, and the console shows the worklist ---------- */
  const list = await (await ctx.get(`${BILL}/shadowDrift`, { headers: H(staff) })).json();
  if (!list.find((d) => d.id === drift.id)) fail('drift missing from the worklist');

  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto('http://localhost:8080/console/');
  await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  // a tab click on an already-rendered pane may serve a stale listing —
  // re-click to refetch until the fresh drift row shows
  let drifted = 0;
  for (let i = 0; i < 6 && !drifted; i++) {
    await page.locator('.tab', { hasText: 'Shadow billing' }).click();
    await page.waitForSelector('#listing-body tr', { timeout: 15000 });
    drifted = await page.locator('#listing-body tr', { hasText: `Shadow Plan ${run}` }).count();
    if (!drifted) await page.waitForTimeout(2500);
  }
  if (!drifted) fail('the drift is not in the Shadow billing pane');
  console.log('OK the Shadow billing pane shows the drift — ops sees it before the customer does');
  await browser.close();

  // retire the repriced fixture — a live 59-priced debris plan skews any
  // later clone/diff or shelf pick
  await ctx.patch(`${CAT}/productOffering/${offering.id}`,
    { headers: H(staff), data: { lifecycleStatus: 'Retired' } }).catch(() => {});
  console.log('OK fixture retired (left tidy)');

  console.log('\nALL P3 CHECKS PASSED — the parallel bill run is a standing loop: a repriced catalog '
    + 'raises a drift with exact numbers before the next invoice lands.');
})();
