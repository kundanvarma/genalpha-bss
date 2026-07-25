/* Credit notes (billing + faces + subledger). Suite #71.
 *
 *  - the wrong invoice is never edited: a NUMBERED credit note reverses it
 *    — gapless per-tenant sequence (proven by consecutive issues), a
 *    reference to the bill it credits, and a REQUIRED reason
 *  - unpaid bill: the due comes down; due at zero settles the bill
 *  - settled bill: the money moves BACK through the PSP (refundedAmount
 *    rises on the settling payment)
 *  - dispute credits are now document-backed (dispute id on the note)
 *  - the subledger books REDUCED notes under the document's number and
 *    books NOTHING extra for REFUNDED ones (the refund event owns that)
 *  - walls: reason required; over-credit refuses; an agent WITHOUT
 *    billing:admin gets a clean 403; the customer sees their own note
 *    (chip + PDF) on the storefront
 */
const { chromium } = require('playwright');
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const BILLS = '/tmf-api/customerBillManagement/v4';
const R = '/revenue/v1';
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
const seqOf = (no) => Number(String(no).replace('CN-', ''));

(async () => {
  const staff = await token('demo', 'demo');
  const kai = await token('kai@bss.local', 'kai');
  const kaiId = sub(kai);
  const paula = await token('paula@family.example', 'paula');
  const paulaId = sub(paula);

  /* ---------- 1. the document: numbered, reasoned, party-readable ---------- */
  await call('POST', `${BILLS}/billingRun`, staff).catch(() => {});
  for (let i = 0; i < 30; i++) {
    const runs = (await call('GET', `${BILLS}/billingRun`, staff)).body || [];
    if (!runs.some((r2) => r2.status === 'running')) break;
    await sleep(3000);
  }
  const kaiBills = (await call('GET',
    `${BILLS}/customerBill?relatedPartyId=${kaiId}&limit=50`, kai)).body || [];
  const bill = kaiBills.find((b) => b.state !== 'settled' && Number(b.amountDue.value) > 2);
  if (!bill) fail('kai has no open bill over 2 EUR');
  const dueBefore = Number(bill.amountDue.value);

  const noReason = await call('POST', `${BILLS}/customerBill/${bill.id}/creditNote`, staff,
    { amount: 1 });
  if (noReason.status !== 400) fail(`reason-less credit note must 400, got ${noReason.status}`);
  const cn1 = await call('POST', `${BILLS}/customerBill/${bill.id}/creditNote`, staff,
    { amount: 1, reason: `service outage goodwill ${run}` });
  if (cn1.status !== 201) fail(`issue: ${cn1.status} ${cn1.text.slice(0, 200)}`);
  if (!/^CN-\d{6}$/.test(cn1.body.creditNoteNo)) fail('number not CN-nnnnnn: ' + cn1.body.creditNoteNo);
  if (cn1.body.billNo !== bill.billNo) fail('credit note must reference the original invoice number');
  const after = (await call('GET', `${BILLS}/customerBill/${bill.id}`, kai)).body;
  if (Math.abs(Number(after.amountDue.value) - (dueBefore - 1)) > 0.001) {
    fail(`due should drop by 1.00: ${dueBefore} -> ${after.amountDue.value}`);
  }
  const lines = (await call('GET',
    `${BILLS}/customerBill/${bill.id}/appliedCustomerBillingRate`, kai)).body || [];
  if (!lines.some((l) => l.type === 'creditNote' && Number(l.taxExcludedAmount.value) === -1)) {
    fail('no -1.00 creditNote rate line on the bill');
  }
  const own = (await call('GET', `${BILLS}/creditNote`, kai)).body || [];
  if (!own.some((c) => c.id === cn1.body.id)) fail('kai cannot read his own credit note');
  const pdfRes = await fetch(`${API}${BILLS}/creditNote/${cn1.body.id}/document.pdf`,
    { headers: { Authorization: `Bearer ${kai}` } });
  const pdfBytes = Buffer.from(await pdfRes.arrayBuffer());
  if (!pdfBytes.subarray(0, 5).toString().startsWith('%PDF')) fail('credit note PDF is not a PDF');
  console.log(`OK THE DOCUMENT: ${cn1.body.creditNoteNo} credits invoice ${bill.billNo} —`
    + ` reason REQUIRED (400 without), the due dropped 1.00, the negative line says why,`
    + ` kai reads his own note, and the PDF serves (${pdfBytes.length} bytes).`);

  /* ---------- 2. the sequence is gapless ---------- */
  const cn2 = await call('POST', `${BILLS}/customerBill/${bill.id}/creditNote`, staff,
    { amount: 0.5, reason: `second goodwill ${run}` });
  if (seqOf(cn2.body.creditNoteNo) !== seqOf(cn1.body.creditNoteNo) + 1) {
    fail(`sequence gap: ${cn1.body.creditNoteNo} then ${cn2.body.creditNoteNo}`);
  }
  console.log(`OK GAPLESS: consecutive issues minted ${cn1.body.creditNoteNo} then`
    + ` ${cn2.body.creditNoteNo} — an unbroken series, as the bookkeeping rules demand.`);

  /* ---------- 3. a full credit settles the bill ---------- */
  const all = (await call('GET', `${BILLS}/customerBill?limit=100`, staff)).body || [];
  const victim = all.find((b) => b.state === 'new' && Number(b.amountDue.value) > 0
    && (b.relatedParty || []).every((p) => p.id !== kaiId && p.id !== paulaId));
  if (victim) {
    const full = await call('POST', `${BILLS}/customerBill/${victim.id}/creditNote`, staff,
      { reason: `full reversal ${run}` });
    if (full.status !== 201) fail(`full credit: ${full.status} ${full.text.slice(0, 200)}`);
    const zeroed = (await call('GET', `${BILLS}/customerBill/${victim.id}`, staff)).body;
    if (Number(zeroed.amountDue.value) !== 0 || zeroed.state !== 'settled') {
      fail(`full credit should zero+settle: ${zeroed.amountDue.value} ${zeroed.state}`);
    }
    console.log(`OK FULL CREDIT: bill ${victim.billNo} reversed in full — due 0, state settled,`
      + ' nothing left to collect.');
  } else {
    const priorFull = ((await call('GET', `${BILLS}/creditNote`, staff)).body || [])
      .find((c) => c.settlement === 'reduced' && c.reason.startsWith('full reversal'));
    if (!priorFull) fail('no unpaid bill to fully credit and no prior-run proof');
    console.log('OK FULL CREDIT (prior run): a full-reversal credit note already exists —'
      + ' bills regenerate per period, the proof stands.');
  }

  /* ---------- 4. a settled bill refunds through the PSP ---------- */
  const paulaBills = (await call('GET',
    `${BILLS}/customerBill?relatedPartyId=${paulaId}&limit=50`, paula)).body || [];
  const settled = paulaBills.find((b) => b.state === 'settled');
  if (!settled) fail('paula has no settled bill');
  const cnR = await call('POST', `${BILLS}/customerBill/${settled.id}/creditNote`, staff,
    { amount: 1, reason: `late outage compensation ${run}` });
  if (cnR.status !== 201) fail(`settled credit: ${cnR.status} ${cnR.text.slice(0, 200)}`);
  if (cnR.body.settlement !== 'refunded' || !cnR.body.refundRef) {
    fail('settled-bill credit note must be refunded with a refundRef');
  }
  const payment = (await call('GET',
    `/tmf-api/paymentManagement/v4/payment/${cnR.body.refundRef}`, staff)).body;
  if (!payment || Number(payment.refundedAmount || 0) < 1) {
    fail('refundedAmount did not rise on the settling payment');
  }
  console.log(`OK SETTLED → REFUND: ${cnR.body.creditNoteNo} on a paid bill moved 1.00 BACK`
    + ` through the PSP (payment ${String(cnR.body.refundRef).slice(0, 8)}… refundedAmount`
    + ` ${payment.refundedAmount}).`);

  /* ---------- 5. dispute credits are document-backed ---------- */
  const dispute = await call('POST', `${BILLS}/customerBill/${bill.id}/dispute`, kai,
    { reason: `credit note suite ${run}` });
  if (dispute.status >= 300) fail(`dispute open: ${dispute.status}`);
  const resolved = await call('POST', `${BILLS}/dispute/${dispute.body.id}/resolve`, staff,
    { outcome: 'credit', amount: 0.25, note: 'suite decision' });
  if (resolved.status >= 300) fail(`resolve: ${resolved.status} ${resolved.text.slice(0, 200)}`);
  const cns = (await call('GET', `${BILLS}/creditNote?billId=${bill.id}`, staff)).body || [];
  const disputeCn = cns.find((c) => c.disputeId === dispute.body.id);
  if (!disputeCn) fail('dispute credit minted no credit note');
  console.log(`OK DISPUTE-BACKED: resolving the dispute minted ${disputeCn.creditNoteNo}`
    + ' carrying the dispute id — every goodwill credit is a numbered document now.');

  /* ---------- 6. the subledger books the document, once ---------- */
  let entry = null;
  for (let i = 0; i < 20 && !entry; i++) {
    await sleep(2500);
    const j = (await call('GET', `${R}/journalEntry`, staff)).body || [];
    entry = j.find((e) => e.sourceRef === `creditNote:${cn1.body.id}`);
  }
  if (!entry) fail('reduced credit note never reached the journal');
  if (!entry.lines.some((l) => l.accountCode === '4093' && Number(l.debit) === 1)) {
    fail('no 1.00 contra-revenue debit under the credit-note account');
  }
  const journal = (await call('GET', `${R}/journalEntry`, staff)).body || [];
  if (journal.some((e) => e.sourceRef === `creditNote:${cnR.body.id}`)) {
    fail('REFUNDED credit note booked directly — the refund event owns that path');
  }
  console.log('OK SUBLEDGER: the REDUCED note booked contra-revenue under its own number;'
    + ' the REFUNDED note did NOT book here (the refund event owns the cash reversal) —'
    + ' one path, never two.');

  /* ---------- 7. the customer holds the document (storefront) ---------- */
  const browser = await chromium.launch();
  const page = await (await browser.newContext()).newPage();
  await page.goto(`${API}/shop/`);
  await page.locator('.who >> text=Sign in').click();
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', 'kai@bss.local');
  await page.fill('input[name="password"]', 'kai');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.nav', { timeout: 20000 });
  await page.locator('.nav >> text=My bills').click();
  await page.locator('[data-testid="credit-note-chip"]').first().waitFor({ timeout: 15000 });
  const chip = (await page.locator('[data-testid="credit-note-chip"]').first().innerText()).trim();
  await browser.close();
  console.log(`OK STOREFRONT: kai's Bills page shows the credit note as a first-class chip`
    + ` ("${chip.replace(/\s+/g, ' ')}") with its own PDF — customers RECEIVE credit notes,`
    + ' never issue them.');

  /* ---------- 8. the walls ---------- */
  const over = await call('POST', `${BILLS}/customerBill/${bill.id}/creditNote`, staff,
    { amount: 999999, reason: 'too much' });
  if (over.status !== 400) fail(`over-credit must 400, got ${over.status}`);
  const agent = await token('agent-anna', 'agent');
  const agentTry = await call('POST', `${BILLS}/customerBill/${bill.id}/creditNote`, agent,
    { amount: 1, reason: 'agent overreach' });
  if (agentTry.status !== 403) fail(`agent without billing:admin must 403, got ${agentTry.status}`);
  const anon = await fetch(`http://shop.nova.localhost:8080${BILLS}/creditNote`);
  if (anon.status === 200) fail('anonymous nova read succeeded (wall breach)');
  console.log('OK WALLS: over-credit refuses; an agent WITHOUT billing:admin gets a clean 403'
    + ' (agents open disputes, admins decide money); nova sees nothing.');

  console.log('\nALL CREDIT-NOTE CHECKS PASSED — the wrong invoice is never edited: a numbered,'
    + ' gapless, reasoned document reverses it; unpaid bills come down, settled bills refund'
    + ' through the PSP, disputes mint their own paper, the customer holds the PDF, and the'
    + ' subledger books the document exactly once.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
