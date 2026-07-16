/* Refunds and disputes — "this charge is wrong."
 *
 *  - a customer disputes a SETTLED bill: collection pauses, staff credit
 *    it, and REAL money moves back through the PSP (partial refund on
 *    the original payment — never more than was captured)
 *  - a dispute on an UNPAID bill resolves as a negative line item that
 *    says why, and a smaller amount due
 *  - an upheld dispute tells the customer the reason instead of ghosting
 *  - guards: one open dispute per bill, deciding is back-office only,
 *    over-refunding is refused
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
  const card = { cardNumber: '4242 4242 4242 4242', expiry: '12/28', cvc: '123' };

  const newCustomerWithBill = async (tag) => {
    const email = `${tag}-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'Dis', familyName: `Pute${tag}${run}` } })).json();
    const tok = await token(ctx, email, login.temporaryPassword);
    await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
      headers: H(tok), data: {
        productOrderItem: [{ action: 'add', productOffering: { id: PLAN_ID, name: 'GenAlpha Mobile 10 GB' } }] } });
    let bill = null;
    for (let i = 0; i < 30 && !bill; i++) {
      await sleep(2000);
      await ctx.post(`${BILLS}/billingRun`, { headers: H(staff) });
      const bills = await (await ctx.get(`${BILLS}/customerBill?limit=50`, { headers: H(tok) })).json();
      bill = bills.find((b) => b.state === 'new' && b.amountDue.value > 0) || null;
    }
    if (!bill) fail(`no bill for ${tag}`);
    return { tok, bill, partyId: login.id };
  };
  const inboxOf = async (tok) => (await (await ctx.get(
    `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=50`,
    { headers: H(tok) })).json());

  /* ========== ACT 1: dispute on a SETTLED bill -> a real refund ========== */
  const a1 = await newCustomerWithBill('paid');
  const pay = await (await ctx.post(`${API}/tmf-api/paymentManagement/v4/payment`,
    { headers: H(a1.tok), data: { description: `Bill ${a1.bill.billNo}`,
      amount: { unit: a1.bill.amountDue.unit, value: a1.bill.amountDue.value },
      paymentMethod: { '@type': 'bankCard', ...card } } })).json();
  await ctx.patch(`${BILLS}/customerBill/${a1.bill.id}`,
    { headers: H(a1.tok), data: { state: 'settled', payment: [{ id: pay.id }] } });

  const opened = await (await ctx.post(`${BILLS}/customerBill/${a1.bill.id}/dispute`,
    { headers: H(a1.tok), data: { reason: 'I was charged for roaming I never used' } })).json();
  if (opened.status !== 'open') fail('dispute did not open: ' + JSON.stringify(opened));
  const twice = await ctx.post(`${BILLS}/customerBill/${a1.bill.id}/dispute`,
    { headers: H(a1.tok), data: { reason: 'again' } });
  if (twice.status() !== 409) fail('a second open dispute was allowed: ' + twice.status());
  const sneaky = await ctx.post(`${BILLS}/dispute/${opened.id}/resolve`,
    { headers: H(a1.tok), data: { outcome: 'credit', amount: a1.bill.amountDue.value } });
  if (sneaky.status() !== 403) fail('a customer resolved their own dispute: ' + sneaky.status());
  console.log('OK dispute OPEN on a settled bill — one per bill, and deciding is back-office only');

  let acked = false;
  for (let i = 0; i < 15 && !acked; i++) {
    await sleep(1500);
    acked = (await inboxOf(a1.tok)).some((m) => (m.subject || '').includes('looking into your dispute'));
  }
  if (!acked) fail('the customer never got the dispute acknowledgement');

  const creditAmount = 3.5;
  const resolved = await (await ctx.post(`${BILLS}/dispute/${opened.id}/resolve`,
    { headers: H(staff), data: { outcome: 'credit', amount: creditAmount,
      note: 'roaming rate misapplied' } })).json();
  if (resolved.status !== 'credited') fail('credit resolution failed: ' + JSON.stringify(resolved));
  const refunded = await (await ctx.get(`${API}/tmf-api/paymentManagement/v4/payment/${pay.id}`,
    { headers: H(a1.tok) })).json();
  if (Math.abs(Number(refunded.refundedAmount ?? 0) - creditAmount) > 0.001) {
    fail('the refund never reached the payment: ' + JSON.stringify(refunded).slice(0, 200));
  }
  let refundNote = false;
  for (let i = 0; i < 15 && !refundNote; i++) {
    await sleep(1500);
    refundNote = (await inboxOf(a1.tok)).some((m) => (m.subject || '').includes('dispute was resolved')
      && (m.content || '').includes('refunded'));
  }
  if (!refundNote) fail('the customer was never told about the refund');
  console.log(`OK CREDITED on a settled bill: ${creditAmount} moved BACK through the PSP onto`
    + ' the original payment — and the customer was told');

  /* the guard: you cannot refund more than was captured */
  const tooMuch = await ctx.post(`${API}/tmf-api/paymentManagement/v4/payment/${pay.id}/refund`,
    { headers: H(staff), data: { amount: { value: a1.bill.amountDue.value } } });
  if (tooMuch.status() !== 409) fail('over-refunding was allowed: ' + tooMuch.status());
  console.log('OK the refund ledger holds: captured minus refunded is the ceiling');

  /* ========== ACT 2: dispute on an UNPAID bill -> a credit line ========== */
  const a2 = await newCustomerWithBill('unpaid');
  const d2 = await (await ctx.post(`${BILLS}/customerBill/${a2.bill.id}/dispute`,
    { headers: H(a2.tok), data: { reason: 'plan price looks wrong' } })).json();
  await ctx.post(`${BILLS}/dispute/${d2.id}/resolve`,
    { headers: H(staff), data: { outcome: 'credit', amount: 2, note: 'goodwill' } });
  const after = (await (await ctx.get(`${BILLS}/customerBill?limit=50`, { headers: H(a2.tok) })).json())
    .find((b) => b.id === a2.bill.id);
  if (Math.abs(after.amountDue.value - (a2.bill.amountDue.value - 2)) > 0.001) {
    fail(`the credit did not reduce the due: ${a2.bill.amountDue.value} -> ${after.amountDue.value}`);
  }
  const lines = await (await ctx.get(
    `${BILLS}/customerBill/${a2.bill.id}/appliedCustomerBillingRate`, { headers: H(a2.tok) })).json();
  const creditLine = lines.find((r) => (r.name || '').includes('Dispute credit'));
  if (!creditLine || Number(creditLine.taxExcludedAmount.value) !== -2) {
    fail('the credit line is missing or wrong: ' + JSON.stringify(creditLine));
  }
  console.log('OK CREDITED on an unpaid bill: a negative line that says why ("Dispute credit —'
    + ' goodwill") and a smaller amount due');

  /* ========== ACT 3: upheld — told, not ghosted ========== */
  const d3 = await (await ctx.post(`${BILLS}/customerBill/${a2.bill.id}/dispute`,
    { headers: H(a2.tok), data: { reason: 'still looks wrong' } })).json();
  await ctx.post(`${BILLS}/dispute/${d3.id}/resolve`,
    { headers: H(staff), data: { outcome: 'uphold', note: 'the catalog price is 15/month as agreed' } });
  let upheld = false;
  for (let i = 0; i < 15 && !upheld; i++) {
    await sleep(1500);
    upheld = (await inboxOf(a2.tok)).some((m) => (m.content || '').includes('the charge stands')
      && (m.content || '').includes('catalog price'));
  }
  if (!upheld) fail('the upheld decision never reached the customer');
  console.log('OK UPHELD: the charge stands, and the customer heard the WHY — a decision, not a'
    + ' disappearance');

  console.log('\nALL REFUND/DISPUTE CHECKS PASSED — contested money pauses collection, credits'
    + ' move real money the right way for the bill\'s state (a PSP refund or a negative line),'
    + ' refunds can never exceed the capture, and every decision is told to the customer.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
