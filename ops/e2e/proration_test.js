/* Mid-cycle plan change — each plan pays for its own days.
 *
 *  - a customer upgrades mid-month (a modify order: same product, same
 *    number, new offering); the product remembers WHAT it was and WHEN
 *  - the billing run charges the old plan for its days and the new plan
 *    for the rest — two line items that name their dates, so the bill
 *    explains itself instead of pretending the month had one price
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const PLAN_A = { id: '14291c1a-df26-4232-8084-500466888e46', name: 'GenAlpha Mobile 10 GB' };
const PLAN_B = { id: 'c0a81054-212a-486e-8b22-9ac3621e7831', name: 'GenAlpha Mobile Unlimited 5G' };

async function token(ctx, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  /* catalog truth: what each plan costs per month */
  const monthlyOf = async (offeringId) => {
    const offering = await (await ctx.get(
      `${API}/tmf-api/productCatalogManagement/v4/productOffering/${offeringId}`)).json();
    let monthly = 0;
    for (const ref of offering.productOfferingPrice || []) {
      const price = await (await ctx.get(
        `${API}/tmf-api/productCatalogManagement/v4/productOfferingPrice/${ref.id}`)).json();
      if (price.priceType === 'recurring' && price.recurringChargePeriodType === 'month') {
        monthly += price.price.value;
      }
    }
    return monthly;
  };
  const oldMonthly = await monthlyOf(PLAN_A.id);
  const newMonthly = await monthlyOf(PLAN_B.id);
  if (!(oldMonthly > 0) || !(newMonthly > 0) || oldMonthly === newMonthly) {
    fail(`the two plans need distinct monthly prices: ${oldMonthly} vs ${newMonthly}`);
  }

  /* the shared day-math */
  const now = new Date();
  const periodStart = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1));
  const periodEnd = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 0));
  const today = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const totalDays = Math.round((periodEnd - periodStart) / 86400000) + 1;
  const round2 = (x) => Math.round(x * 100) / 100;

  /* ========== ACT 1: a NEW product mid-month pays only its own days ========== */
  const email1 = `newcomer-${run}@example.com`;
  const login1 = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email: email1, givenName: 'New', familyName: `Comer${run}` } })).json();
  const newcomer = await token(ctx, email1, login1.temporaryPassword);
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(newcomer), data: {
      productOrderItem: [{ action: 'add', productOffering: PLAN_A }] } });
  let fresh = null;
  for (let i = 0; i < 30 && !fresh; i++) {
    await sleep(2000);
    const products = await (await ctx.get(
      `${API}/tmf-api/productInventory/v4/product?limit=50`, { headers: H(newcomer) })).json();
    fresh = products.find((p) => p.status === 'active' && p.productOffering?.id === PLAN_A.id) || null;
  }
  if (!fresh) fail('the newcomer plan never activated');
  if (!fresh.startDate) fail('the product does not know when it started');
  await ctx.post(`${API}/tmf-api/customerBillManagement/v4/billingRun`, { headers: H(staff) });
  const nBills = await (await ctx.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill?limit=50`, { headers: H(newcomer) })).json();
  const nBill = nBills.find((b) => b.state === 'new');
  if (!nBill) fail('no bill for the newcomer');
  const startedOn = new Date(fresh.startDate.slice(0, 10) + 'T00:00:00Z');
  const newcomerDays = Math.round((periodEnd - startedOn) / 86400000) + 1;
  const expectedFresh = newcomerDays < totalDays
    ? round2(oldMonthly * newcomerDays / totalDays) : oldMonthly;
  if (Math.abs(nBill.amountDue.value - expectedFresh) > 0.011) {
    fail(`a mid-month start must be prorated: wanted ~${expectedFresh}`
      + ` (${newcomerDays}/${totalDays} days of ${oldMonthly}), got ${nBill.amountDue.value}`);
  }
  console.log(`OK NEW MID-MONTH: the newcomer pays ${nBill.amountDue.value} for ${newcomerDays}`
    + ` of ${totalDays} days — not a whole month for half a month's service`);

  /* ========== ACT 2: plan change mid-cycle on a line as old as the month ========== */
  const email = `prorate-${run}@example.com`;
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Pro', familyName: `Rata${run}` } })).json();
  const cust = await token(ctx, email, login.temporaryPassword);
  // the line predates the month: seed the installed product with a
  // backdated TMF637 startDate (a migration would do exactly this)
  const product = await (await ctx.post(`${API}/tmf-api/productInventory/v4/product`,
    { headers: H(staff), data: {
      name: PLAN_A.name, status: 'active', productOffering: PLAN_A,
      startDate: new Date(periodStart.getTime() - 86400000 * 40).toISOString(),
      relatedParty: [{ id: login.id, role: 'customer', '@referredType': 'Individual' }] } })).json();
  if (!product.id) fail('could not seed the backdated product: ' + JSON.stringify(product).slice(0, 200));
  console.log(`OK on ${PLAN_A.name} (${oldMonthly}/month) since last month — now the upgrade, mid-cycle`);

  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(cust), data: {
      productOrderItem: [{ action: 'modify', product: { id: product.id },
        productOffering: PLAN_B }] } });
  let switched = null;
  for (let i = 0; i < 30 && !switched; i++) {
    await sleep(2000);
    const products = await (await ctx.get(
      `${API}/tmf-api/productInventory/v4/product?limit=50`, { headers: H(cust) })).json();
    const p = products.find((x) => x.id === product.id);
    if (p && p.productOffering?.id === PLAN_B.id) switched = p;
  }
  if (!switched) fail('the modify order never repointed the product');
  if (switched.previousOffering?.id !== PLAN_A.id || !switched.offeringChangedAt) {
    fail('the product forgot what it was: ' + JSON.stringify(switched).slice(0, 300));
  }
  console.log(`OK the product REMEMBERS: was ${switched.previousOffering.name}, switched`
    + ` ${switched.offeringChangedAt.slice(0, 10)} — same product, same line`);

  /* ---------- the bill: each plan pays for its own days ---------- */
  await ctx.post(`${API}/tmf-api/customerBillManagement/v4/billingRun`, { headers: H(staff) });
  const bills = await (await ctx.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill?limit=50`, { headers: H(cust) })).json();
  const bill = bills.find((b) => b.state === 'new');
  if (!bill) fail('no bill was cut');
  const rates = await (await ctx.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill/${bill.id}/appliedCustomerBillingRate`,
    { headers: H(cust) })).json();

  // the same day-math the run uses
  const changed = new Date(switched.offeringChangedAt.slice(0, 10) + 'T00:00:00Z');
  const daysBefore = Math.round((changed - periodStart) / 86400000);
  const expectedOld = round2(oldMonthly * daysBefore / totalDays);
  const expectedNew = round2(newMonthly * (totalDays - daysBefore) / totalDays);

  const oldLine = rates.find((r) => r.name.includes('(until'));
  const newLine = rates.find((r) => r.name.includes('(from'));
  if (daysBefore > 0) {
    if (!oldLine || Math.abs(Number(oldLine.taxExcludedAmount.value) - expectedOld) > 0.011) {
      fail(`old plan line wrong: wanted ~${expectedOld}, got ${JSON.stringify(oldLine)}`);
    }
  } else if (oldLine) {
    fail('a zero-day old plan earned a line item');
  }
  if (!newLine || Math.abs(Number(newLine.taxExcludedAmount.value) - expectedNew) > 0.011) {
    fail(`new plan line wrong: wanted ~${expectedNew}, got ${JSON.stringify(newLine)}`);
  }
  const expectedTotal = round2((daysBefore > 0 ? expectedOld : 0) + expectedNew);
  if (Math.abs(bill.amountDue.value - expectedTotal) > 0.021) {
    fail(`the bill total is not the prorated sum: wanted ~${expectedTotal}, got ${bill.amountDue.value}`);
  }
  console.log(`OK PRORATED: ${daysBefore > 0
    ? `${PLAN_A.name} ${expectedOld} for ${daysBefore} days + ` : ''}${PLAN_B.name} ${expectedNew}`
    + ` for ${totalDays - daysBefore} days = ${bill.amountDue.value} ${bill.amountDue.unit} —`
    + ' two line items that name their dates');

  /* ========== ACT 3: the LEAVER — cease today, pay for today only ========== */
  const email3 = `leaver-${run}@example.com`;
  const login3 = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email: email3, givenName: 'Lea', familyName: `Ver${run}` } })).json();
  const leaver = await token(ctx, email3, login3.temporaryPassword);
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(leaver), data: {
      productOrderItem: [{ action: 'add', productOffering: PLAN_A }] } });
  let lSvc = null;
  for (let i = 0; i < 30 && !lSvc; i++) {
    await sleep(2000);
    const svcs = await (await ctx.get(
      `${API}/tmf-api/serviceInventory/v4/service?relatedPartyId=${login3.id}`,
      { headers: H(staff) })).json();
    lSvc = (svcs || []).find((sv) => sv.state === 'active') || null;
  }
  if (!lSvc) fail('the leaver\'s service never activated');

  /* the agent ceases the line; the product record must FOLLOW */
  await ctx.post(`${API}/tmf-api/serviceInventory/v4/service/${lSvc.id}/terminate`,
    { headers: H(staff), data: { reason: 'customer leaving' } });
  let closed = null;
  for (let i = 0; i < 20 && !closed; i++) {
    await sleep(2000);
    const products = await (await ctx.get(
      `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${login3.id}&limit=20`,
      { headers: H(staff) })).json();
    closed = (products || []).find((p) => p.status === 'cancelled' && p.terminationDate) || null;
  }
  if (!closed) fail('the ceased service never closed its product — billing would charge a ghost');
  console.log('OK the CEASE closed the product record: cancelled, terminationDate '
    + closed.terminationDate.slice(0, 10) + ' — no ghost line left behind for billing');

  /* the final bill: started today, ended today — ONE day of service */
  await ctx.post(`${API}/tmf-api/customerBillManagement/v4/billingRun`, { headers: H(staff) });
  const lBills = await (await ctx.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill?limit=50`, { headers: H(leaver) })).json();
  const lBill = lBills.find((b) => b.state === 'new');
  if (!lBill) fail('no final bill for the leaver');
  const oneDay = round2(oldMonthly * 1 / totalDays);
  if (Math.abs(lBill.amountDue.value - oneDay) > 0.011) {
    fail(`one day of service must cost one day: wanted ~${oneDay}, got ${lBill.amountDue.value}`);
  }
  console.log(`OK the FINAL BILL: joined and left today — ${lBill.amountDue.value} `
    + `${lBill.amountDue.unit} for exactly one day of ${totalDays}. Leaving is priced as`
    + ' fairly as arriving.');

  console.log('\nALL PRORATION CHECKS PASSED — a mid-month start pays from its first day, a'
    + ' mid-cycle plan change bills each plan for its own days, and a cease bills only to the'
    + ' end date. Segments with dates on every line: the bill explains itself, in every'
    + ' direction.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
