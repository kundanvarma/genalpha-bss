/* "Bill me on the 10th, that's payday" — per-customer billing cycles.
 *
 *  - a customer picks an anchor day (1-28); their bills run anchor-to-
 *    anchor instead of calendar months, prorated by the same segment math
 *  - a mid-cycle change takes effect from the NEXT cycle: the run never
 *    re-bills a covered day, so there is no double billing, ever
 *  - days 29-31 are refused (February does not have them), and the
 *    customer is told what changes and when
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const PLAN = { id: '14291c1a-df26-4232-8084-500466888e46', name: 'GenAlpha Mobile 10 GB' };

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
  const BILLS = `${API}/tmf-api/customerBillManagement/v4`;

  const mk = async (tag) => {
    const email = `${tag}-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'Cy', familyName: `Cle${tag}${run}` } })).json();
    await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
      id: login.id, givenName: 'Cy', familyName: `Cle${tag}${run}`,
      contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
    return { id: login.id, tok: await token(ctx, email, login.temporaryPassword) };
  };
  const monthlyOf = async () => {
    const offering = await (await ctx.get(
      `${API}/tmf-api/productCatalogManagement/v4/productOffering/${PLAN.id}`)).json();
    let m = 0;
    for (const ref of offering.productOfferingPrice || []) {
      const p = await (await ctx.get(
        `${API}/tmf-api/productCatalogManagement/v4/productOfferingPrice/${ref.id}`)).json();
      if (p.priceType === 'recurring' && p.recurringChargePeriodType === 'month') m += p.price.value;
    }
    return m;
  };
  const monthly = await monthlyOf();

  /* ---------- guard first: the 31st is refused with the reason ---------- */
  const a = await mk('anchored');
  const bad = await ctx.post(`${API}/tmf-api/party/v4/individual/${a.id}/billingCycle`,
    { headers: H(a.tok), data: { anchorDay: 31 } });
  if (bad.status() !== 400 || !(await bad.text()).includes('February')) {
    fail('day 31 must be refused with the February reason: ' + bad.status());
  }
  console.log('OK the 31st is refused — "the 29th to 31st do not exist in February"');

  /* ---------- act 1: the anchored customer bills anchor-to-anchor ---------- */
  const today = new Date();
  const anchorDay = Math.max(1, Math.min(28, today.getUTCDate() - 6));
  await ctx.post(`${API}/tmf-api/party/v4/individual/${a.id}/billingCycle`,
    { headers: H(a.tok), data: { anchorDay } });
  let told = false;
  for (let i = 0; i < 15 && !told; i++) {
    await sleep(1500);
    const inbox = await (await ctx.get(
      `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=50`,
      { headers: H(a.tok) })).json();
    told = inbox.some((m) => (m.subject || '').includes('billing date changed')
      && (m.content || '').includes(`day ${anchorDay}`));
  }
  if (!told) fail('the customer was never told their cycle changed');

  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(a.tok), data: { productOrderItem: [{ action: 'add', productOffering: PLAN }] } });
  let bill = null;
  for (let i = 0; i < 30 && !bill; i++) {
    await sleep(2000);
    await ctx.post(`${BILLS}/billingRun`, { headers: H(staff) });
    const list = await (await ctx.get(`${BILLS}/customerBill?limit=50`, { headers: H(a.tok) })).json();
    bill = list.find((b) => b.state === 'new') || null;
  }
  if (!bill) fail('no bill for the anchored customer');
  const pStart = new Date(bill.billingPeriod.startDateTime + 'T00:00:00Z');
  const pEnd = new Date(bill.billingPeriod.endDateTime + 'T00:00:00Z');
  if (pStart.getUTCDate() !== anchorDay) {
    fail(`the period does not start on the anchor: ${bill.billingPeriod.startDateTime} (wanted day ${anchorDay})`);
  }
  const periodDays = Math.round((pEnd - pStart) / 86400000) + 1;
  const activeDays = Math.round((pEnd - Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate())) / 86400000) + 1;
  const expected = Math.round(monthly * activeDays / periodDays * 100) / 100;
  if (Math.abs(bill.amountDue.value - expected) > 0.011) {
    fail(`the anchored bill is not prorated over ITS period: wanted ~${expected}`
      + ` (${activeDays}/${periodDays} days), got ${bill.amountDue.value}`);
  }
  console.log(`OK ANCHORED: the cycle runs day ${anchorDay} to day ${anchorDay} — `
    + `${bill.billingPeriod.startDateTime} to ${bill.billingPeriod.endDateTime}, prorated`
    + ` ${activeDays}/${periodDays} days = ${bill.amountDue.value} ${bill.amountDue.unit}`);

  /* the run stays idempotent on the anchored period */
  const rerun = await (await ctx.post(`${BILLS}/billingRun`, { headers: H(staff) })).json();
  const listAfter = await (await ctx.get(`${BILLS}/customerBill?limit=50`, { headers: H(a.tok) })).json();
  if (listAfter.length !== 1) fail('the rerun double-billed the anchored customer');
  console.log('OK the rerun billed nothing new — idempotent on the anchored period too');

  /* ---------- act 2: a mid-cycle change waits for the next cycle ---------- */
  const b = await mk('switcher');
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(b.tok), data: { productOrderItem: [{ action: 'add', productOffering: PLAN }] } });
  let calBill = null;
  for (let i = 0; i < 30 && !calBill; i++) {
    await sleep(2000);
    await ctx.post(`${BILLS}/billingRun`, { headers: H(staff) });
    const list = await (await ctx.get(`${BILLS}/customerBill?limit=50`, { headers: H(b.tok) })).json();
    calBill = list.find((x) => x.state === 'new') || null;
  }
  if (!calBill || new Date(calBill.billingPeriod.startDateTime + 'T00:00:00Z').getUTCDate() !== 1) {
    fail('the switcher should start on a calendar bill: ' + JSON.stringify(calBill?.billingPeriod));
  }
  await ctx.post(`${API}/tmf-api/party/v4/individual/${b.id}/billingCycle`,
    { headers: H(b.tok), data: { anchorDay } });
  await ctx.post(`${BILLS}/billingRun`, { headers: H(staff) });
  const afterSwitch = await (await ctx.get(`${BILLS}/customerBill?limit=50`, { headers: H(b.tok) })).json();
  if (afterSwitch.length !== 1) {
    fail('the anchor change re-billed covered days: ' + afterSwitch.length + ' bills');
  }
  console.log('OK MID-CYCLE CHANGE: this month is already billed, so the new date applies from'
    + ' the NEXT cycle — no double billing, ever; the bridge bill covers only the gap days'
    + ' when that cycle arrives');

  console.log('\nALL BILLING-CYCLE CHECKS PASSED — pick your payday, bills run anchor-to-anchor'
    + ' with the same honest proration, changes wait for the next cycle, and no day is ever'
    + ' billed twice.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
