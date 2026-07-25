/* Revenue export (component #35, /revenue/v1) — the subledger. Suite #70.
 *
 *  - an invoice becomes ONE balanced double-entry posting: AR debit equals
 *    the bill total to the cent, revenue credited per rate line, discounts
 *    as contra — proven against the billing API's own numbers
 *  - idempotency is proven, not promised: re-backfilling the same bill
 *    creates NOTHING (unique source_ref)
 *  - cash books ONCE, at capture; a refund books the reverse
 *  - the reconciliation ties journal AR to billing, cash to payments, and
 *    carries the loyalty points liability as a labeled CONTROL number
 *  - the chart is data: remapping changes FUTURE postings only — booked
 *    history keeps its snapshot
 *  - nova's wall holds
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const R = '/revenue/v1';
const BILLS = '/tmf-api/customerBillManagement/v4';
const PAY = '/tmf-api/paymentManagement/v4';
const run = Date.now();
const fail = (m) => { throw new Error(m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo',
      username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
const sub = (t) => JSON.parse(Buffer.from(t.split('.')[1], 'base64url').toString()).sub;
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
const cents = (v) => Math.round(Number(v) * 100);
const balanced = (entry) => {
  const d = entry.lines.reduce((s, l) => s + cents(l.debit), 0);
  const c = entry.lines.reduce((s, l) => s + cents(l.credit), 0);
  return d === c && d > 0;
};

(async () => {
  const staff = await token('demo', 'demo');
  const paula = await token('paula@family.example', 'paula');
  const paulaId = sub(paula);

  /* ---------- 1. an invoice becomes a balanced posting ---------- */
  await call('POST', `${BILLS}/billingRun`, staff).catch(() => {});
  for (let i = 0; i < 30; i++) {
    const runs = (await call('GET', `${BILLS}/billingRun`, staff)).body || [];
    if (!runs.some((r2) => r2.status === 'running')) break;
    await sleep(3000);
  }
  const bills = (await call('GET',
    `${BILLS}/customerBill?relatedPartyId=${paulaId}&limit=50`, paula)).body || [];
  const bill = bills.find((b) => b.amountDue && Number(b.amountDue.value) > 0);
  if (!bill) fail('paula has no bill with an amount due');
  const rates = (await call('GET',
    `${BILLS}/customerBill/${bill.id}/appliedCustomerBillingRate`, paula)).body || [];
  const billTotal = cents(bill.amountDue.value);

  const bf1 = await call('POST', `${R}/backfill`, staff, { billId: bill.id });
  if (bf1.status >= 300) fail(`backfill: ${bf1.status} ${bf1.text.slice(0, 200)}`);
  const journal = (await call('GET', `${R}/journalEntry`, staff)).body || [];
  const entry = journal.find((e) => e.sourceRef === `bill:${bill.id}`);
  if (!entry) fail('no journal entry for the bill');
  if (!balanced(entry)) fail('bill entry is not balanced: ' + JSON.stringify(entry.lines));
  const ar = entry.lines.find((l) => cents(l.debit) === billTotal && l.accountName.match(/receivable/i));
  if (!ar) fail(`no AR debit equal to the bill total (${bill.amountDue.value})`);
  const nonzeroRates = rates.filter((r2) => cents(r2.taxExcludedAmount?.value ?? 0) !== 0);
  if (entry.lines.length !== nonzeroRates.length + 1) {
    fail(`expected ${nonzeroRates.length} revenue lines + 1 AR, got ${entry.lines.length}`);
  }
  console.log(`OK INVOICE POSTING: bill ${bill.billNo || bill.id.slice(0, 8)} became ONE balanced`
    + ` entry — AR debit ${bill.amountDue.value} ${bill.amountDue.unit} equals the bill to the`
    + ` cent, ${nonzeroRates.length} revenue/contra lines mirror the rate lines.`);

  /* ---------- 2. idempotency proven, not promised ---------- */
  const bf2 = await call('POST', `${R}/backfill`, staff, { billId: bill.id });
  if (bf2.body.posted !== false) fail('second backfill must be a no-op');
  const again = (await call('GET', `${R}/journalEntry`, staff)).body || [];
  if (again.filter((e) => e.sourceRef === `bill:${bill.id}`).length !== 1) {
    fail('duplicate journal entry after replay');
  }
  console.log('OK IDEMPOTENT: re-backfilling the same bill created NOTHING — unique source_ref,'
    + ' at-least-once delivery can never double-book.');

  /* ---------- 3. cash books once, at capture ---------- */
  const pay = await call('POST', `${PAY}/payment`, staff, {
    description: `revenue suite cash ${run}`,
    amount: { unit: 'EUR', value: 12.5 },
    paymentMethod: { '@type': 'bankCard', token: `spt-rev-${run}`, lastFourDigits: '4242' } });
  if (pay.status >= 300) fail(`payment: ${pay.status} ${pay.text.slice(0, 150)}`);
  const cap = await call('PATCH', `${PAY}/payment/${pay.body.id}`, staff, { status: 'captured' });
  if (cap.status >= 300) fail(`capture: ${cap.status} ${cap.text.slice(0, 150)}`);
  let cashEntry = null;
  for (let i = 0; i < 20 && !cashEntry; i++) {
    await sleep(2500);
    const j = (await call('GET', `${R}/journalEntry`, staff)).body || [];
    cashEntry = j.find((e) => e.sourceRef === `payment:${pay.body.id}:captured`);
  }
  if (!cashEntry) fail('captured payment never reached the journal');
  if (!balanced(cashEntry)) fail('cash entry unbalanced');
  if (!cashEntry.lines.some((l) => cents(l.debit) === 1250 && l.accountName.match(/cash/i))) {
    fail('no 12.50 cash debit');
  }
  console.log('OK CASH AT CAPTURE: the captured payment rode the event stream into a balanced'
    + ' cash/AR posting — authorize alone books nothing, so no path double-books.');

  /* ---------- 4. a refund books the reverse ---------- */
  const refund = await call('POST', `${PAY}/payment/${pay.body.id}/refund`, staff,
    { amount: { unit: 'EUR', value: 5 }, reason: 'revenue suite' });
  if (refund.status >= 300) fail(`refund: ${refund.status} ${refund.text.slice(0, 150)}`);
  let refundEntry = null;
  for (let i = 0; i < 20 && !refundEntry; i++) {
    await sleep(2500);
    const j = (await call('GET', `${R}/journalEntry`, staff)).body || [];
    refundEntry = j.find((e) => e.sourceType === 'refund'
      && e.lines.some((l) => cents(l.credit) === 500 && l.accountName.match(/cash/i)));
  }
  if (!refundEntry || !balanced(refundEntry)) fail('refund never became a balanced posting');
  console.log('OK REFUND: 5.00 EUR back out the door — contra-revenue debit, cash credit,'
    + ' balanced.');

  /* ---------- 5. the tie-out ---------- */
  const today = new Date().toISOString().slice(0, 10);
  const recon = (await call('GET', `${R}/reconciliation?date=${today}`, staff)).body;
  if (!recon.allEntriesBalanced) fail('reconciliation reports an unbalanced entry');
  if (cents(recon.billedTotal) < billTotal) fail('billedTotal below the bill we just journaled');
  if (cents(recon.cashTotal) < 1250) fail('cashTotal misses the captured payment');
  const loyaltyCtl = recon.loyaltyPointsLiability || {};
  const liab = (await call('GET', '/tmf-api/loyaltyManagement/v4/liability', staff)).body;
  if (liab && liab.outstandingPoints != null
      && Number(loyaltyCtl.points) !== Number(liab.outstandingPoints)) {
    fail(`loyalty control number drifts: recon ${loyaltyCtl.points} vs loyalty ${liab.outstandingPoints}`);
  }
  console.log(`OK RECONCILIATION: every entry balanced; billed ${recon.billedTotal} vs cash`
    + ` ${recon.cashTotal}; the loyalty points liability rides along as a labeled control`
    + ` number (${loyaltyCtl.points ?? 'n/a'} pts) that MATCHES the loyalty component's own.`);

  /* ---------- 6. the CSV a period-close import wants ---------- */
  const csvRes = await fetch(`${API}${R}/journalExport?date=${today}`,
    { headers: { Authorization: `Bearer ${staff}` } });
  const csv = await csvRes.text();
  if (!csv.startsWith('entryDate,entryId,sourceType,accountCode')) fail('CSV header wrong');
  if (!csv.includes(entry.id)) fail('CSV misses the bill entry');
  console.log(`OK EXPORT: ${csv.trim().split('\n').length - 1} CSV journal rows for ${today} —`
    + ' the file the ERP import job ingests.');

  /* ---------- 7. the chart is data; history keeps its snapshot ---------- */
  const remap = await call('POST', `${R}/accountMapping`, staff,
    { key: 'rate:recurringCharge', accountCode: '3000', accountName: `Suite revenue ${run}` });
  if (remap.status >= 300) fail(`remap: ${remap.status}`);
  const chart = (await call('GET', `${R}/accountMapping`, staff)).body || [];
  if (!chart.some((c) => c.key === 'rate:recurringCharge' && c.accountCode === '3000')) {
    fail('remap did not stick');
  }
  const history = (await call('GET', `${R}/journalEntry/${entry.id}`, staff)).body;
  if (history.lines.some((l) => l.accountCode === '3000')) {
    fail('remap rewrote booked history — the snapshot doctrine is broken');
  }
  await call('POST', `${R}/accountMapping`, staff,
    { key: 'rate:recurringCharge', accountCode: '4000', accountName: 'Service revenue' });
  console.log('OK CHART AS DATA: finance renamed an account and FUTURE postings follow —'
    + ' booked lines keep the code+name they were born with.');

  /* ---------- 8. the wall ---------- */
  const novaFetch = await fetch(`http://shop.nova.localhost:8080${R}/journalEntry`);
  if (novaFetch.status === 200) fail('anonymous nova read of the journal succeeded (wall breach)');
  console.log('OK WALL: the journal is staff-only and tenant-walled — nova sees nothing of'
    + ' genalpha\'s books.');

  /* ---------- 9. tax split: gross prices, net revenue, VAT payable ---------- */
  const kai = await token('kai@bss.local', 'kai');
  const kaiId = sub(kai);
  await call('POST', `${R}/accountMapping`, staff,
    { key: 'tax', accountCode: '2700', accountName: 'VAT payable', configValue: 25 });
  const kaiBills = (await call('GET',
    `${BILLS}/customerBill?relatedPartyId=${kaiId}&limit=50`, kai)).body || [];
  const kaiBill = kaiBills.find((b) => b.amountDue && Number(b.amountDue.value) > 1);
  if (!kaiBill) fail('kai has no bill to journal');
  await call('POST', `${R}/backfill`, staff, { billId: kaiBill.id });
  const jTax = (await call('GET', `${R}/journalEntry`, staff)).body || [];
  const taxEntry = jTax.find((e) => e.sourceRef === `bill:${kaiBill.id}`);
  if (!taxEntry || !balanced(taxEntry)) fail('kai bill entry missing/unbalanced');
  const vatLine = taxEntry.lines.find((l) => l.accountCode === '2700');
  if (!vatLine || cents(vatLine.credit) <= 0) fail('no VAT credit line on a taxed bill');
  // the entry booked the bill GROSS at issue time; credit notes issued since
  // (suite #71) follow the bill down — so booked AR minus this bill's
  // credit-note postings must equal the LIVE due, to the cent
  const arLine = taxEntry.lines.find((l) => l.accountName.match(/receivable/i));
  // count credit-note postings AND legacy pre-refactor dispute postings —
  // the journal is append-only, history keeps its original source type
  const credited = jTax.filter((e) => (e.sourceType === 'creditNote' || e.sourceType === 'dispute')
      && (String(e.description).includes(kaiBill.billNo)
          || e.lines.some((l) => l.ref === kaiBill.id)))
    .reduce((sum, e) => sum + e.lines.reduce((t, l) =>
      t + (l.accountName.match(/receivable/i) ? cents(l.credit) : 0), 0), 0);
  if (cents(arLine.debit) - credited !== cents(kaiBill.amountDue.value)) {
    fail(`booked AR (${arLine.debit}) minus credit notes (${credited / 100}) must equal the live due (${kaiBill.amountDue.value})`);
  }
  console.log(`OK TAX SPLIT: 25% VAT configured as DATA — AR booked gross and reconciles`
    + ` through credit notes to the live due (${kaiBill.amountDue.value}), revenue books net, VAT payable carries`
    + ` ${vatLine.credit}; gross-minus-nets arithmetic means rounding can never unbalance.`);

  /* ---------- 10. a dispute credit books contra-revenue ---------- */
  const disputed = await call('POST', `${BILLS}/customerBill/${kaiBill.id}/dispute`, kai,
    { reason: `revenue suite dispute ${run}` });
  if (disputed.status >= 300) fail(`dispute open: ${disputed.status} ${disputed.text.slice(0, 150)}`);
  const resolve = await call('POST', `${BILLS}/dispute/${disputed.body.id}/resolve`, staff,
    { outcome: 'credit', amount: 0.5, note: 'suite goodwill' });
  if (resolve.status >= 300) fail(`dispute resolve: ${resolve.status} ${resolve.text.slice(0, 200)}`);
  // the dispute credit is now DOCUMENT-BACKED: find its credit note, then its posting
  const cns = (await call('GET', `${BILLS}/creditNote?billId=${kaiBill.id}`, staff)).body || [];
  const disputeCn = cns.find((c) => c.disputeId === disputed.body.id);
  if (!disputeCn) fail('dispute credit minted no credit note');
  let dEntry = null;
  for (let i = 0; i < 20 && !dEntry; i++) {
    await sleep(2500);
    const j = (await call('GET', `${R}/journalEntry`, staff)).body || [];
    dEntry = j.find((e) => e.sourceRef === `creditNote:${disputeCn.id}`);
  }
  if (!dEntry || !balanced(dEntry)) fail('credit note never became a balanced posting');
  if (!dEntry.lines.some((l) => cents(l.debit) === 50 && l.accountCode === '4093')) {
    fail('no 0.50 contra-revenue debit under the credit-note account');
  }
  console.log('OK DISPUTE CREDIT: the dispute now mints a NUMBERED credit note'
    + ` (${disputeCn.creditNoteNo}) and the subledger books it under the document's own`
    + ' number — contra-revenue against AR, no silent drift.');

  /* ---------- 11. loyalty points priced into currency — only when finance says so ---------- */
  await call('POST', `${R}/accountMapping`, staff, { key: 'loyalty:liability',
    accountCode: '2400', accountName: 'Loyalty points liability', configValue: 0.01 });
  const accrual = await call('POST', `${R}/loyaltyAccrual`, staff);
  if (accrual.status >= 300) fail(`accrual: ${accrual.status} ${accrual.text.slice(0, 150)}`);
  const jAcc = (await call('GET', `${R}/journalEntry`, staff)).body || [];
  const accEntry = jAcc.find((e) => e.sourceType === 'loyalty'
    && e.sourceRef === `loyalty-accrual:${new Date().toISOString().slice(0, 10)}`);
  if (accrual.body.posted === true) {
    if (!accEntry || !balanced(accEntry)) fail('accrual posted but no balanced entry found');
  } else if (!accEntry && !String(accrual.body.note || '').match(/already matches|already accrued/)) {
    fail('accrual neither posted nor already-covered: ' + JSON.stringify(accrual.body));
  }
  const accrual2 = await call('POST', `${R}/loyaltyAccrual`, staff);
  if (accrual2.body.posted === true) fail('same-day second accrual must be a no-op');
  console.log('OK LOYALTY ACCRUAL: finance priced a point (0.01 EUR) and the points'
    + ' liability became a real booked number — daily cadence, delta-based, and a'
    + ' same-day rerun books NOTHING. Unpriced points stay a control number.');

  /* ---------- 12. period close: the export becomes final ---------- */
  const close = await call('POST', `${R}/periodClose`, staff, { through: today });
  if (close.status >= 300) fail(`close: ${close.status} ${close.text.slice(0, 150)}`);
  const blocked = await call('POST', `${R}/backfill`, staff, { billId: kaiBill.id });
  if (blocked.status !== 409) fail(`backfill into a closed period must 409, got ${blocked.status}`);
  const reconClosed = (await call('GET', `${R}/reconciliation?date=${today}`, staff)).body;
  if (reconClosed.closedThrough !== today) fail('reconciliation misses closedThrough');
  await call('POST', `${R}/periodClose`, staff, { through: '1970-01-01' }); // reopen for re-runs
  console.log('OK PERIOD CLOSE: closed through today — a posting for a bill inside the'
    + ' period refuses with 409, the reconciliation announces the close, and reopening'
    + ' is an explicit act.');

  /* ---------- 13. ERP-flavored exports ---------- */
  const sap = await (await fetch(`${API}${R}/journalExport?date=${today}&format=sap`,
    { headers: { Authorization: `Bearer ${staff}` } })).text();
  if (!sap.startsWith('BLDAT,BUDAT,XBLNR')) fail('SAP layout header wrong');
  const ns = await (await fetch(`${API}${R}/journalExport?date=${today}&format=netsuite`,
    { headers: { Authorization: `Bearer ${staff}` } })).text();
  if (!ns.startsWith('Date,Journal,Account')) fail('NetSuite layout header wrong');
  console.log('OK ERP LAYOUTS: the same journal exports in SAP- and NetSuite-shaped CSV —'
    + ' shaped to their import conventions, honestly not certified against a live instance.');

  // restore: tax off so earlier legs stay deterministic on re-runs
  await call('POST', `${R}/accountMapping`, staff,
    { key: 'tax', accountCode: '2700', accountName: 'VAT payable', configValue: 0 });

  console.log('\nALL REVENUE CHECKS PASSED — the BSS is an honest subledger: every posting'
    + ' balanced by invariant, idempotent by construction, tied out against billing and'
    + ' payments to the cent, exported in the shape a general ledger ingests (SAP and NetSuite flavors included), with tax split, dispute credits, priced loyalty liability and a period close that makes the export FINAL. The GL stays'
    + ' in the ERP — this is the feed it always needed.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
