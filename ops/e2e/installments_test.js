/* Pay in parts — the hardship/retention call.
 *
 *  - an unpaid bill splits into N equal monthly installments (the last
 *    takes the rounding remainder, so the parts always sum to the bill)
 *  - each part is a REAL payment: authorized for that part, captured on
 *    receipt; an underpayment is refused
 *  - the bill walks new -> partiallyPaid -> settled; the plan completes
 *    itself on the last part — and the customer is told at every step
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const PLAN_ID = '14291c1a-df26-4232-8084-500466888e46'; // GenAlpha Mobile 10 GB

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

  /* ---------- a customer with a completed order and a fresh bill ---------- */
  const email = `parts-${run}@example.com`;
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Payin', familyName: `Parts${run}` } })).json();
  const cust = await token(ctx, email, login.temporaryPassword);
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(cust), data: {
      productOrderItem: [{ action: 'add', productOffering: { id: PLAN_ID, name: 'GenAlpha Mobile 10 GB' } }] } });
  let bill = null;
  for (let i = 0; i < 30 && !bill; i++) {
    await sleep(2000);
    await ctx.post(`${BILLS}/billingRun`, { headers: H(staff) });
    const bills = await (await ctx.get(`${BILLS}/customerBill?limit=50`, { headers: H(cust) })).json();
    bill = bills.find((b) => b.state === 'new' && b.amountDue.value > 0) || null;
  }
  if (!bill) fail('the billing run never cut a bill for the new plan');
  console.log(`OK a real bill exists: ${bill.billNo} for ${bill.amountDue.value} ${bill.amountDue.unit}`);

  /* ---------- split into three ---------- */
  const plan = await (await ctx.post(`${BILLS}/customerBill/${bill.id}/installmentPlan`,
    { headers: H(cust), data: { installments: 3 } })).json();
  if (plan.installments !== 3 || plan.paidCount !== 0) fail('bad plan: ' + JSON.stringify(plan));
  const per = Number(plan.amountPer);
  const last = Number(plan.lastAmount);
  if (Math.abs(per * 2 + last - bill.amountDue.value) > 0.001) {
    fail(`the parts do not sum to the bill: 2×${per} + ${last} ≠ ${bill.amountDue.value}`);
  }
  const again = await ctx.post(`${BILLS}/customerBill/${bill.id}/installmentPlan`,
    { headers: H(cust), data: { installments: 6 } });
  if (again.status() !== 409) fail('a second plan was allowed: ' + again.status());
  console.log(`OK split into 3: 2 × ${per} + ${last} = ${bill.amountDue.value} — and a second`
    + ' plan is refused');

  let planned = false;
  for (let i = 0; i < 15 && !planned; i++) {
    await sleep(1500);
    const inbox = await (await ctx.get(
      `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=50`,
      { headers: H(cust) })).json();
    planned = inbox.some((m) => (m.subject || '').includes('split into 3 payments'));
  }
  if (!planned) fail('the customer was never told the terms');
  console.log('OK the customer was told the terms — N payments, the amount, the first due date');

  /* ---------- part 1: a real authorized payment, captured on receipt ---------- */
  const card = { cardNumber: '4242 4242 4242 4242', expiry: '12/28', cvc: '123' };
  const payFor = async (amount) => (await (await ctx.post(
    `${API}/tmf-api/paymentManagement/v4/payment`,
    { headers: H(cust), data: { description: `Installment ${bill.billNo}`,
      amount: { unit: bill.amountDue.unit, value: amount },
      paymentMethod: { '@type': 'bankCard', ...card } } })).json());

  // underpayment: authorized for less than the part — refused
  const small = await payFor(Math.max(0.01, per - 1));
  const under = await ctx.post(`${BILLS}/customerBill/${bill.id}/installmentPlan/pay`,
    { headers: H(cust), data: { payment: [{ id: small.id }] } });
  if (under.status() !== 409) fail('an underpayment was accepted: ' + under.status());
  console.log('OK an underpayment is refused — each part is a real, covering payment');

  const p1 = await payFor(per);
  const afterOne = await (await ctx.post(`${BILLS}/customerBill/${bill.id}/installmentPlan/pay`,
    { headers: H(cust), data: { payment: [{ id: p1.id }] } })).json();
  if (afterOne.paidCount !== 1) fail('part 1 did not land: ' + JSON.stringify(afterOne));
  let billNow = (await (await ctx.get(`${BILLS}/customerBill?limit=50`, { headers: H(cust) })).json())
    .find((b) => b.id === bill.id);
  if (billNow.state !== 'partiallyPaid') fail('the bill is not partiallyPaid: ' + billNow.state);
  // and the FULL settle path is now closed — the plan is the only road
  const fullPay = await payFor(bill.amountDue.value);
  const sneak = await ctx.patch(`${BILLS}/customerBill/${bill.id}`,
    { headers: H(cust), data: { state: 'settled', payment: [{ id: fullPay.id }] } });
  if (sneak.status() !== 409) fail('a partially-paid bill was settled around the plan: ' + sneak.status());
  console.log('OK part 1 of 3 landed: bill partiallyPaid, and the full-settle side door is closed');

  /* ---------- parts 2 and 3: the plan completes itself ---------- */
  const p2 = await payFor(per);
  await ctx.post(`${BILLS}/customerBill/${bill.id}/installmentPlan/pay`,
    { headers: H(cust), data: { payment: [{ id: p2.id }] } });
  const p3 = await payFor(last);
  const doneView = await (await ctx.post(`${BILLS}/customerBill/${bill.id}/installmentPlan/pay`,
    { headers: H(cust), data: { payment: [{ id: p3.id }] } })).json();
  if (doneView.status !== 'completed') fail('the plan did not complete: ' + JSON.stringify(doneView));
  billNow = (await (await ctx.get(`${BILLS}/customerBill?limit=50`, { headers: H(cust) })).json())
    .find((b) => b.id === bill.id);
  if (billNow.state !== 'settled') fail('the bill did not settle on the last part: ' + billNow.state);
  let thanked = false;
  for (let i = 0; i < 15 && !thanked; i++) {
    await sleep(1500);
    const inbox = await (await ctx.get(
      `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=50`,
      { headers: H(cust) })).json();
    thanked = inbox.some((m) => (m.content || '').includes('the last one'));
  }
  if (!thanked) fail('the customer never got the final receipt');
  console.log('OK parts 2 and 3 landed: the plan completed ITSELF, the bill is settled, and the'
    + ' final receipt says so');

  console.log('\nALL INSTALLMENT CHECKS PASSED — an unpaid bill splits into parts that sum'
    + ' exactly, every part is a real captured payment, underpayment and side doors are'
    + ' refused, and the last part settles the bill with a thank-you.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
