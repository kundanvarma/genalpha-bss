/* Recurring BNPL — "sign up for Klarna", then the bill charges the token. Suite #102.
 * (§7a of docs/payment-provider-plan.md, built 2026-08-13.)
 *
 *  - SIGN UP: an APPROVED Klarna session tokenizes into a recurring token,
 *    vaulted as a TMF670 bnplToken method owned by the customer — and the
 *    LIST view never exposes the token (resolve-only, machine-side).
 *  - MIT CHARGE: paying with the saved method charges the TOKEN at Klarna —
 *    merchant-initiated, no redirect — minting an AUTHORIZED payment with
 *    pspProvider=klarna and the charge as its session_ref.
 *  - SETTLES BY SESSION: capturing routes the charge to Klarna (cap_…), so the
 *    subledger books it as a BNPL receivable exactly like checkout Klarna.
 *  - GUARDED: tokenizing an unknown session is refused.
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const P = '/tmf-api/paymentManagement/v4';
const PM = '/tmf-api/paymentMethods/v4';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);

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
  await call('PUT', `${P}/paymentProvider`, staff, {
    provider: 'klarna', displayName: 'Klarna', baseUrl: 'http://mock-klarna:8080',
    secretRef: 'KLARNA_API_KEY', webhookSecretRef: 'KLARNA_WEBHOOK_SECRET',
    methods: ['card', 'klarna'], isDefault: false });

  /* ---------- 1. sign up ---------- */
  const sess = await call('POST', `${P}/payment/session`, kai, {
    method: 'klarna', amount: { value: 15.00, unit: 'EUR' }, returnUrl: 'http://localhost:8080/shop/cart' });
  if (sess.status !== 200) fail(`session: ${sess.status} ${sess.text}`);
  await call('POST', `${P}/payment/confirm`, kai, { provider: 'klarna', sessionId: sess.body.sessionId });

  const vault = await call('POST', `${P}/payment/vaultRecurring`, kai,
    { provider: 'klarna', sessionId: sess.body.sessionId });
  if (vault.status !== 200 || !vault.body.id) fail(`vaultRecurring: ${vault.status} ${vault.text}`);
  const methodId = vault.body.id;
  ok(`SIGN UP: the approved session tokenized and vaulted as method ${String(methodId).slice(0, 8)}…`
    + ` ("${vault.body.label}")`);

  const mine = (await call('GET', `${PM}/paymentMethod`, kai)).body || [];
  const saved = mine.find((m) => m.id === methodId);
  if (!saved) fail('the vaulted method is not in the customer list');
  if (JSON.stringify(saved).includes('krt_')) fail('the LIST view leaked the recurring token');
  ok('LIST-SAFE: the method lists for its owner WITHOUT the token — resolve-only, machine-side');

  /* ---------- 2. the bill charges the token ---------- */
  const pay = await call('POST', `${P}/payment`, kai, {
    description: 'Monthly bill — recurring Klarna', amount: { unit: 'EUR', value: 42.50 },
    paymentMethod: { '@type': 'savedPaymentMethod', id: methodId } });
  if (pay.status >= 300) fail(`token charge: ${pay.status} ${pay.text}`);
  const pm = pay.body.paymentMethod || {};
  if (pay.body.status !== 'authorized' || pay.body.pspProvider !== 'klarna'
      || pm['@type'] !== 'bnplToken') {
    fail(`token charge shape wrong: status=${pay.body.status} psp=${pay.body.pspProvider} `
      + `method=${JSON.stringify(pm)}`);
  }
  ok(`MIT CHARGE: 42.50 EUR charged against the vaulted token — merchant-initiated, no redirect,`
    + ` pspProvider=klarna, label "${(pay.body.paymentMethod||{}).label}"`);

  /* ---------- 3. settles by session (BNPL books apply) ---------- */
  const cap = await call('PATCH', `${P}/payment/${pay.body.id}`, staff, { status: 'captured' });
  if (cap.status !== 200 || !(cap.body.settlementRef || '').startsWith('cap_')) {
    fail(`capture: ${cap.status} ${cap.text}`);
  }
  ok(`SETTLES BY SESSION: capture routed to Klarna by the charge reference — ${cap.body.settlementRef}`
    + ' (the subledger books it as the BNPL receivable, per suite #93)');

  /* ---------- 4. guarded ---------- */
  const badVault = await call('POST', `${P}/payment/vaultRecurring`, kai,
    { provider: 'klarna', sessionId: 'ks_does_not_exist' });
  if (badVault.status < 400) fail('tokenizing an unknown session should fail: ' + badVault.status);
  ok('GUARDED: an unknown session cannot be tokenized');

  console.log('\nALL RECURRING-BNPL CHECKS PASSED — sign up once at Klarna, the vault holds only the'
    + ' provider\'s token (never listed back), the monthly bill charges it merchant-initiated, and'
    + ' settlement books through the same honest BNPL receivable path.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
