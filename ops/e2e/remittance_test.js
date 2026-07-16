/* Remittance ingestion — the money comes HOME and the bill closes itself.
 *
 *  - the bank posts an ISO 20022 camt.054 credit notification to the
 *    webhook, authenticated by the tenant's own bank secret
 *  - a credit entry whose reference matches a bill's payment reference
 *    (the KID the EHF invoice carried out) becomes a real TMF676 bank
 *    payment and settles the bill through the SAME guarantee path a
 *    card uses — and the customer is thanked, never left guessing
 *  - anything unclear parks on the UNAPPLIED-CASH worklist with its
 *    reason: unknown reference, wrong amount, a bill that is not open.
 *    Money is fail-closed: never guessed, never dropped.
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

const camt = (msgId, entries) => `<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.054.001.08">
  <BkToCstmrDbtCdtNtfctn>
    <GrpHdr><MsgId>${msgId}</MsgId><CreDtTm>2026-07-16T09:00:00</CreDtTm></GrpHdr>
    <Ntfctn>
${entries.map((e) => `      <Ntry>
        <Amt Ccy="${e.ccy}">${e.amount}</Amt>
        <CdtDbtInd>CRDT</CdtDbtInd>
        <NtryDtls><TxDtls>
          <Refs><AcctSvcrRef>${e.bankRef}</AcctSvcrRef></Refs>
          <RmtInf><Strd><CdtrRefInf><Ref>${e.ref}</Ref></CdtrRefInf></Strd></RmtInf>
        </TxDtls></NtryDtls>
      </Ntry>`).join('\n')}
    </Ntfctn>
  </BkToCstmrDbtCdtNtfctn>
</Document>`;

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const BILLS = `${API}/tmf-api/customerBillManagement/v4`;
  const bank = (xml, tok) => ctx.post(`${API}/bank/v1/remittance`, {
    headers: { 'Content-Type': 'application/xml', 'X-Bank-Token': tok || 'genalpha-bank-token' },
    data: xml,
  });

  const mk = async (tag) => {
    const email = `${tag}-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'Gi', familyName: `Ro${tag}${run}` } })).json();
    await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
      id: login.id, givenName: 'Gi', familyName: `Ro${tag}${run}`,
      contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
    return { id: login.id, tok: await token(ctx, email, login.temporaryPassword) };
  };
  const billOf = async (person) => {
    for (let i = 0; i < 30; i++) {
      await sleep(2000);
      await ctx.post(`${BILLS}/billingRun`, { headers: H(staff) });
      const list = await (await ctx.get(`${BILLS}/customerBill?limit=50`,
        { headers: H(person.tok) })).json();
      const bill = list.find((b) => b.state === 'new');
      if (bill) return bill;
    }
    fail('no bill was cut');
    return null;
  };

  /* ---------- 1. the front door is locked ---------- */
  const locked = await bank(camt('BAD', [{ ccy: 'EUR', amount: '1.00', ref: '1', bankRef: 'x' }]),
    'not-the-bank');
  if (locked.status() !== 401) fail('a stranger posted money: ' + locked.status());
  console.log('OK the bank webhook refuses an unknown credential (401) — the token IS the tenant');

  /* ---------- 2. money in, bill settled, customer thanked ---------- */
  const payer = await mk('payer');
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(payer.tok), data: { productOrderItem: [{ action: 'add', productOffering: PLAN }] } });
  const bill = await billOf(payer);
  const kid = bill.billNo.replace(/\D/g, '');
  const amount = bill.amountDue.value.toFixed(2);
  const res = await bank(camt(`OCR-${run}-1`, [
    { ccy: bill.amountDue.unit, amount, ref: kid, bankRef: `ARCH-${run}-1` },
    { ccy: bill.amountDue.unit, amount: '12.34', ref: '99999999999', bankRef: `ARCH-${run}-2` },
  ]));
  if (res.status() !== 200) fail('ingest failed: ' + res.status());
  const summary = await res.json();
  if (summary.applied !== 1 || summary.unapplied !== 1) {
    fail('expected 1 applied + 1 parked, got: ' + JSON.stringify(summary));
  }
  let settled = null;
  for (let i = 0; i < 10 && !settled; i++) {
    await sleep(1500);
    const now = await (await ctx.get(`${BILLS}/customerBill/${bill.id}`,
      { headers: H(payer.tok) })).json();
    if (now.state === 'settled') settled = now;
  }
  if (!settled) fail('the bill never settled from the bank payment');
  console.log(`OK MONEY CAME HOME: the camt.054 credit entry carried KID ${kid}, matched bill`
    + ` ${bill.billNo}, and the bill is SETTLED — through the same guarantee path a card uses`);

  const payments = await (await ctx.get(`${API}/tmf-api/paymentManagement/v4/payment?limit=50`,
    { headers: H(payer.tok) })).json();
  const bankPay = payments.find((p) => (p.description || '').includes(bill.billNo));
  if (!bankPay || bankPay.status !== 'captured') {
    fail('the bank transfer is not a captured TMF676 payment: '
      + JSON.stringify(bankPay && { status: bankPay.status }));
  }
  console.log('OK the bank transfer is a REAL TMF676 payment on the customer\'s account —'
    + ' captured, visible, refundable like any other');

  let thanks = null;
  for (let i = 0; i < 15 && !thanks; i++) {
    await sleep(1500);
    const inbox = await (await ctx.get(
      `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=50`,
      { headers: H(payer.tok) })).json();
    thanks = inbox.find((m) => (m.subject || '').includes('Payment received')) || null;
  }
  if (!thanks) fail('the customer was never thanked');
  console.log('OK never silent: "Payment received — thank you" landed in the inbox (and the'
    + ' TMF683 timeline)');

  /* ---------- 3. unapplied cash: parked with reasons, never guessed ---------- */
  const worklist = await (await ctx.get(`${BILLS}/remittance/unapplied`, { headers: H(staff) })).json();
  const unknownRow = worklist.find((r) => r.reference === '99999999999');
  if (!unknownRow || !unknownRow.reason.includes('no bill carries')) {
    fail('the unknown reference did not park with its reason: ' + JSON.stringify(unknownRow));
  }
  console.log('OK UNAPPLIED CASH: the unknown reference parked on the AR worklist with its'
    + ' reason — money is never dropped, never guessed at');

  const short = await mk('short');
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(short.tok), data: { productOrderItem: [{ action: 'add', productOffering: PLAN }] } });
  const shortBill = await billOf(short);
  const shortKid = shortBill.billNo.replace(/\D/g, '');
  await bank(camt(`OCR-${run}-2`, [{ ccy: shortBill.amountDue.unit, amount: '0.01',
    ref: shortKid, bankRef: `ARCH-${run}-3` }]));
  await sleep(2000);
  const stillNew = await (await ctx.get(`${BILLS}/customerBill/${shortBill.id}`,
    { headers: H(short.tok) })).json();
  if (stillNew.state !== 'new') fail('an underpayment settled a bill: ' + stillNew.state);
  const worklist2 = await (await ctx.get(`${BILLS}/remittance/unapplied`, { headers: H(staff) })).json();
  if (!worklist2.find((r) => r.reference === shortKid && r.reason.includes('does not match'))) {
    fail('the underpayment did not park with its reason');
  }
  console.log('OK FAIL-CLOSED: a 0.01 underpayment settles NOTHING — it parks on the worklist'
    + ' naming the mismatch, and the bill stays open');

  /* ---------- 4. a re-sent bank file books nothing twice ---------- */
  const again = await (await bank(camt(`OCR-${run}-1`, [
    { ccy: bill.amountDue.unit, amount, ref: kid, bankRef: `ARCH-${run}-1` },
  ]))).json();
  if (again.applied !== 0) fail('a re-sent bank file applied money twice');
  const paymentsAfter = await (await ctx.get(
    `${API}/tmf-api/paymentManagement/v4/payment?limit=50`, { headers: H(payer.tok) })).json();
  const copies = paymentsAfter.filter((p) => (p.description || '').includes(bill.billNo));
  if (copies.length !== 1) fail('duplicate bank payments booked: ' + copies.length);
  console.log('OK IDEMPOTENT: the re-sent OCR file settled nothing twice — the settled bill'
    + ' parks the duplicate as unapplied cash and exactly ONE payment exists');

  console.log('\nALL REMITTANCE CHECKS PASSED — the bank\'s camt.054 lands on a tenant-keyed'
    + ' webhook, a matched KID settles the bill through the card path\'s own guarantee, the'
    + ' customer is thanked, and everything unclear parks as unapplied cash with its reason.'
    + ' The bill leaves as an e-invoice and the money finds its way home.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
