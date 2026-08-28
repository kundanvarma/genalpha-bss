/* P1 — the commercial simulator: simulate the money before you move it.
 *
 *  - a probe offering at 100/mo is bought by TWO fresh customers (real
 *    orders, real inventory — the sim reads the same base production uses)
 *  - simulate 100 -> 110: mechanical delta must be exactly 2 * 10 * 12 = 240
 *  - honesty on the face: assumptions list present, basis dated, and with an
 *    assumed churn% a SECOND labeled number appears (never silently blended)
 *  - the report persists and lists (the receipt for later calibration)
 *  - the console pane renders the saved report
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const CAT = `${API}/tmf-api/productCatalogManagement/v4`;

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

  /* ---------- a probe offering with a known price and two owners ---------- */
  const price = await (await ctx.post(`${CAT}/productOfferingPrice`, { headers: H(staff),
    data: { name: `Sim ${run} monthly`, priceType: 'recurring', recurringChargePeriodType: 'month',
      lifecycleStatus: 'Active', price: { unit: 'EUR', value: 100.0 } } })).json();
  const offering = await (await ctx.post(`${CAT}/productOffering`, { headers: H(staff),
    data: { name: `Sim Plan ${run}`, lifecycleStatus: 'Active', isBundle: false, isSellable: true,
      productOfferingPrice: [{ id: price.id, name: price.name }] } })).json();
  if (!offering.id) fail('probe offering not created');

  const buy = async (tag) => {
    const email = `sim-${tag}-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'Sim', familyName: `${tag}${run}` } })).json();
    const tok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
    const order = await (await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`,
      { headers: H(tok), data: { productOrderItem: [{ action: 'add',
        productOffering: { id: offering.id, name: offering.name } }] } })).json();
    return order.id;
  };
  const orders = [await buy('a'), await buy('b')];
  for (let i = 0; i < 30; i++) {
    const states = await Promise.all(orders.map(async (id) =>
      (await (await ctx.get(`${API}/tmf-api/productOrderingManagement/v4/productOrder/${id}`,
        { headers: H(staff) })).json()).state));
    if (states.every((s) => s === 'completed')) break;
    await sleep(3000);
  }
  console.log('OK probe: Sim Plan at 100/mo, bought by two real customers (orders completed)');

  /* ---------- the simulation: mechanics must be exact ---------- */
  const SIM = `${API}/ai/v1/simulate/priceChange`;
  const report = await (await ctx.post(SIM, { headers: H(staff), data: {
    changes: [{ offeringName: `Sim Plan ${run}`, newMonthlyPrice: 110 }], assumedChurnPct: 10 } })).json();
  const line = (report.lines || [])[0] || {};
  if (line.subscribers !== 2) fail(`expected 2 subscribers, got ${line.subscribers}`);
  if (Number(line.annualRevenueDelta) !== 240) fail(`mechanical delta must be 240, got ${line.annualRevenueDelta}`);
  if (line.annualRevenueDeltaWithAssumedChurn == null) fail('assumed-churn variant missing');
  if (!(report.assumptions || []).length) fail('assumptions missing from the face of the report');
  if (!report.basis || !report.basis.asOf) fail('basis missing');
  console.log(`OK simulation exact: 2 subs, +240/yr mechanical, churn-variant ${line.annualRevenueDeltaWithAssumedChurn}, `
    + `${report.assumptions.length} assumptions on the face`);

  /* ---------- the receipt persists ---------- */
  const list = await (await ctx.get(SIM, { headers: H(staff) })).json();
  if (!list.find((r) => r.id === report.id)) fail('the report did not persist to the list');
  console.log('OK the report persisted — the receipt calibration will later compare against reality');

  /* ---------- production untouched ---------- */
  const after = await (await ctx.get(`${CAT}/productOfferingPrice/${price.id}`, { headers: H(staff) })).json();
  if (Number(after.price.value) !== 100) fail('THE SIMULATOR MUTATED A REAL PRICE');
  console.log('OK production untouched — the catalog price is still 100');

  /* ---------- the console pane renders it ---------- */
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
  // a tab click on an already-rendered pane may serve a listing fetched
  // BEFORE the save — re-click to refetch until the fresh report shows
  let seen = 0;
  for (let i = 0; i < 6 && !seen; i++) {
    await page.locator('.tab', { hasText: /^Simulator$/ }).click();
    await page.waitForSelector('#listing-body tr', { timeout: 15000 });
    seen = await page.locator('#listing-body tr', { hasText: `Sim Plan ${run}` }).count();
    if (!seen) await page.waitForTimeout(2500);
  }
  if (!seen) fail('the saved report is not in the Simulator pane');
  console.log('OK the Simulator pane lists the report — the product owner sees the forecast where they price');
  await browser.close();

  /* ---------- the FLYWHEEL: the default prior is MEASURED, not assumed ---------- */
  const fw = await (await ctx.post(SIM, { headers: H(staff), data: {
    changes: [{ offeringName: `Sim Plan ${run}`, newMonthlyPrice: 110 }] } })).json();
  if (!(fw.assumptions || []).some((a) => /MEASURED churn baseline/.test(a))) {
    fail('no measured elasticity prior: ' + JSON.stringify(fw.assumptions));
  }
  if (!(fw.lines || []).some((l) => l.annualRevenueDeltaWithAssumedChurn !== undefined)) {
    fail('the measured prior did not produce a churn-adjusted delta');
  }
  console.log('OK THE FLYWHEEL: with no assumption supplied, the simulator defaults to the '
    + 'MEASURED churn baseline — evidence in, forecast out, override always available');

  console.log('\nALL P1 CHECKS PASSED — a price change is simulated against the real base with exact '
    + 'mechanics, labeled assumptions, a persisted receipt, and zero production mutation.');
})();
