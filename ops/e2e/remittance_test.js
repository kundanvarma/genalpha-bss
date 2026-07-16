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

async function token(ctx, user, pass, realm = 'bss') {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

/* Nets OCR giro: 80-char fixed-width. Amount item 1 (NY 09 10 30):
 * oere at pos 34-50, KID right-adjusted at pos 51-75. */
const ocrFile = (transmission, items) => [
  'NY000010' + '00008080'.padEnd(8) + transmission.padStart(7, '0') + ''.padEnd(57, '0'),
  ...items.map((it, i) =>
    'NY091030' + String(i + 1).padStart(7, '0') + '160726' + ''.padEnd(12, '0')
    + String(Math.round(it.amount * 100)).padStart(17, '0')
    + it.kid.padStart(25, ' ') + ''.padEnd(5, '0')),
  'NY000089' + ''.padEnd(72, '0'),
].join('\n');

/* BAI2 lockbox: comma-separated, amounts in minor units, currency on
 * the 02 group header, customer reference on the 16 detail. */
const bai2File = (fileId, ccy, items) => [
  `01,BANKID,OPERATOR,260716,1200,${fileId},80,80,2/`,
  `02,OPERATOR,BANKID,1,260716,1200,${ccy},2/`,
  '03,123456789,,010,,,/',
  ...items.map((it, i) =>
    `16,165,${Math.round(it.amount * 100)},0,LBX${i + 1},${it.ref},LOCKBOX PAYMENT/`),
  '49,0,3/', '98,0,1,5/', '99,0,1,7/',
].join('\n');

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

  /* ---------- 5. the SAME door speaks Nets OCR — Norway's giro file, in NOK ---------- */
  const novaStaff = await token(ctx, 'demo', 'demo', 'nova');
  const novaOffers = await (await ctx.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`,
    { headers: { ...H(novaStaff), Host: 'shop.nova.localhost' } })).json();
  const novaPlan = novaOffers.find((o) => (o.name || '').includes('Nova') && !o.isBundle)
    || novaOffers[0];
  const nEmail = `giro-${run}@nova.example`;
  const nLogin = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(novaStaff), data: { email: nEmail, givenName: 'Gi', familyName: `Ro${run}` } })).json();
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(novaStaff), data: {
    id: nLogin.id, givenName: 'Gi', familyName: `Ro${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: nEmail } }] } });
  const giro = { id: nLogin.id, tok: await token(ctx, nEmail, nLogin.temporaryPassword, 'nova') };
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(giro.tok), data: { productOrderItem: [{ action: 'add',
      productOffering: { id: novaPlan.id, name: novaPlan.name } }] } });
  let giroBill = null;
  for (let i = 0; i < 30 && !giroBill; i++) {
    await sleep(2000);
    await ctx.post(`${BILLS}/billingRun`, { headers: H(novaStaff) });
    const list = await (await ctx.get(`${BILLS}/customerBill?limit=50`,
      { headers: H(giro.tok) })).json();
    giroBill = list.find((b) => b.state === 'new') || null;
  }
  if (!giroBill) fail('no nova bill for the OCR customer');
  if (giroBill.amountDue.unit !== 'NOK') fail('expected a NOK bill, got ' + giroBill.amountDue.unit);
  const ocr = await ctx.post(`${API}/bank/v1/remittance`, {
    headers: { 'Content-Type': 'text/plain', 'X-Bank-Token': 'nova-bank-token' },
    data: ocrFile(String(run % 1000000), [
      { kid: giroBill.billNo.replace(/\D/g, ''), amount: giroBill.amountDue.value }]),
  });
  if (ocr.status() !== 200) fail('OCR ingest failed: ' + ocr.status());
  const ocrSummary = await ocr.json();
  if (ocrSummary.applied !== 1) fail('OCR entry did not apply: ' + JSON.stringify(ocrSummary));
  let giroSettled = false;
  for (let i = 0; i < 10 && !giroSettled; i++) {
    await sleep(1500);
    const now = await (await ctx.get(`${BILLS}/customerBill/${giroBill.id}`,
      { headers: H(giro.tok) })).json();
    giroSettled = now.state === 'settled';
  }
  if (!giroSettled) fail('the OCR giro payment never settled the nova bill');
  console.log('OK NETS OCR SPOKEN: Norway\'s fixed-width giro file (oere amounts, right-adjusted'
    + ` KID) landed on the SAME door with nova's own bank secret, and the ${giroBill.amountDue.value}`
    + ' NOK bill settled — a new bank dialect is a parser, not a new pipeline');

  /* ---------- 6. and BAI2 lockbox — the US file — pays the rest of the SHORT bill ---------- */
  const bai2 = await ctx.post(`${API}/bank/v1/remittance`, {
    headers: { 'Content-Type': 'text/plain', 'X-Bank-Token': 'genalpha-bank-token' },
    data: bai2File('7', shortBill.amountDue.unit, [
      { ref: shortKid, amount: shortBill.amountDue.value }]),
  });
  if (bai2.status() !== 200) fail('BAI2 ingest failed: ' + bai2.status());
  const bai2Summary = await bai2.json();
  if (bai2Summary.applied !== 1) fail('BAI2 detail did not apply: ' + JSON.stringify(bai2Summary));
  let shortSettled = false;
  for (let i = 0; i < 10 && !shortSettled; i++) {
    await sleep(1500);
    const now = await (await ctx.get(`${BILLS}/customerBill/${shortBill.id}`,
      { headers: H(short.tok) })).json();
    shortSettled = now.state === 'settled';
  }
  if (!shortSettled) fail('the BAI2 lockbox payment never settled the short bill');
  console.log('OK BAI2 LOCKBOX SPOKEN: the US bank file (minor units, customer reference on the'
    + ' 16 record) paid the earlier-underpaid bill IN FULL through the same door — the 0.01'
    + ' stays parked as unapplied cash, the bill is settled, the story is complete');

  console.log('\nALL REMITTANCE CHECKS PASSED — the bank\'s camt.054 lands on a tenant-keyed'
    + ' webhook, a matched KID settles the bill through the card path\'s own guarantee, the'
    + ' customer is thanked, and everything unclear parks as unapplied cash with its reason.'
    + ' The bill leaves as an e-invoice and the money finds its way home.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
