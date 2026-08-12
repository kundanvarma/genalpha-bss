/* BNPL remittance — the provider's payout clears the receivable to cash. Suite #97.
 *
 * Suite #93 proved a Klarna capture books a RECEIVABLE (1100), not cash. This
 * closes the loop: when Klarna pays the merchant out, POST /revenue/v1/remittance
 * books DEBIT 1000 cash / CREDIT 1100 — the receivable drains, the cash line
 * rises, and the tie-out's bnplReceivableTotal (now NET outstanding) goes down.
 *
 *  - CAPTURE: a Klarna payment captured → receivable up by the amount.
 *  - REMIT: the payout books cash/receivable-clear; reconciliation nets down.
 *  - IDEMPOTENT: the same payout reference replayed books NOTHING twice.
 *  - GUARDED: a non-BNPL provider remittance is refused (nothing to clear).
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const P = '/tmf-api/paymentManagement/v4';
const R = '/revenue/v1';
const run = Date.now();
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

(async () => {
  const staff = await token('demo', 'demo');
  const kai = await token('kai@bss.local', 'kai');
  // Klarna offered (idempotent; the seeded demo default)
  await call('PUT', `${P}/paymentProvider`, staff, {
    provider: 'klarna', displayName: 'Klarna', baseUrl: 'http://mock-klarna:8080',
    secretRef: 'KLARNA_API_KEY', webhookSecretRef: 'KLARNA_WEBHOOK_SECRET',
    methods: ['card', 'klarna'], isDefault: false });

  /* ---------- 1. capture → receivable up ---------- */
  const before = (await call('GET', `${R}/reconciliation`, staff)).body;
  const b0 = Number(before.bnplReceivableTotal || 0);

  const sess = await call('POST', `${P}/payment/session`, kai, {
    method: 'klarna', amount: { value: 21.00, unit: 'EUR' }, returnUrl: 'http://localhost:8080/shop/cart' });
  const conf = await call('POST', `${P}/payment/confirm`, kai, { provider: 'klarna', sessionId: sess.body.sessionId });
  if (conf.status !== 200) fail(`confirm: ${conf.status} ${conf.text}`);
  const cap = await call('PATCH', `${P}/payment/${conf.body.id}`, staff, { status: 'captured' });
  if (cap.status !== 200) fail(`capture: ${cap.status} ${cap.text}`);

  let mid = null;
  for (let i = 0; i < 20; i++) {
    await sleep(1000);
    mid = (await call('GET', `${R}/reconciliation`, staff)).body;
    if (Number(mid.bnplReceivableTotal || 0) >= b0 + 21) break;
  }
  if (Number(mid.bnplReceivableTotal || 0) < b0 + 21) {
    fail(`receivable should be up 21 after the capture: ${b0} -> ${mid.bnplReceivableTotal}`);
  }
  ok(`CAPTURE: the 21.00 EUR Klarna capture raised the outstanding receivable (${b0} → ${mid.bnplReceivableTotal})`);

  /* ---------- 2. the payout clears it ---------- */
  const ref = `PAYOUT-${run}`;
  const rem = await call('POST', `${R}/remittance`, staff, {
    provider: 'klarna', reference: ref, amount: { value: 21.00, unit: 'EUR' } });
  if (rem.status !== 200 || rem.body.posted !== true) fail(`remittance: ${rem.status} ${rem.text}`);
  const entry = (await call('GET', `${R}/journalEntry?sourceRef=remittance:klarna:${ref}`, staff)).body || [];
  const lines = (entry[0] || {}).lines || [];
  const cash = lines.find((l) => String(l.accountCode) === '1000');
  const recv = lines.find((l) => String(l.accountCode) === '1100');
  if (!cash || Number(cash.debit) !== 21 || !recv || Number(recv.credit) !== 21) {
    fail('remittance should book DEBIT 1000 / CREDIT 1100 for 21: ' + JSON.stringify(lines));
  }
  const after = (await call('GET', `${R}/reconciliation`, staff)).body;
  if (Number(after.bnplReceivableTotal || 0) !== Number(mid.bnplReceivableTotal) - 21) {
    fail(`outstanding should net DOWN by 21: ${mid.bnplReceivableTotal} -> ${after.bnplReceivableTotal}`);
  }
  if (!after.allEntriesBalanced) fail('subledger unbalanced after the remittance');
  ok(`REMIT: payout ${ref} booked DEBIT 1000 cash / CREDIT 1100 — outstanding netted down`
    + ` (${mid.bnplReceivableTotal} → ${after.bnplReceivableTotal}), every entry balanced`);

  /* ---------- 3. idempotent ---------- */
  const again = await call('POST', `${R}/remittance`, staff, {
    provider: 'klarna', reference: ref, amount: { value: 21.00, unit: 'EUR' } });
  if (again.status !== 200 || again.body.posted !== false) fail('replayed payout should book nothing: ' + again.text);
  const still = (await call('GET', `${R}/journalEntry?sourceRef=remittance:klarna:${ref}`, staff)).body || [];
  if (still.length !== 1) fail('replay created a second entry');
  ok('IDEMPOTENT: the same payout reference replayed booked NOTHING — one entry stands');

  /* ---------- 4. guarded ---------- */
  const wrong = await call('POST', `${R}/remittance`, staff, {
    provider: 'stripe', reference: `X-${run}`, amount: { value: 5, unit: 'EUR' } });
  if (wrong.status !== 400) fail(`a non-BNPL remittance should be 400, got ${wrong.status}`);
  ok('GUARDED: a non-BNPL provider remittance is refused — cards settle at capture, nothing to clear');

  console.log('\nALL BNPL-REMITTANCE CHECKS PASSED — capture books the receivable, the provider payout'
    + ' clears it to cash (idempotent by payout reference), and the tie-out nets the outstanding honestly.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
