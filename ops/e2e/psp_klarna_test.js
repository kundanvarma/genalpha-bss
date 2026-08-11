/* Bring-your-own PSP — the Klarna redirect/BNPL flow (PSP-P2). Suite #91.
 *
 *  - OPERATOR MENU: a tenant adds Klarna as a payment provider (secret-refs, not
 *    the keys) alongside the built-in card PSP.
 *  - METHOD PICKER: GET /payment/methods offers card + klarna (klarna flagged
 *    redirect) — the shopper's choice, anonymous for guest checkout.
 *  - SESSION: opening a Klarna session returns where to send the customer.
 *  - CONFIRM (return leg): confirming the approved session mints an AUTHORIZED
 *    payment via Klarna — and it's IDEMPOTENT (return + webhook can't double-book).
 *  - WEBHOOK: the authoritative async confirm is HMAC-verified; a forged
 *    signature is 401.
 *  - CLEANUP: the provider is removed, so the tenant falls back to the global PSP.
 */
const crypto = require('crypto');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const P = '/tmf-api/paymentManagement/v4';
const WEBHOOK_SECRET = 'dev-klarna-webhook-secret'; // matches compose KLARNA_WEBHOOK_SECRET (dev only)
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body, headers) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(headers || {}) },
    ...(body !== undefined ? { body: typeof body === 'string' ? body : JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}

(async () => {
  const staff = await token('demo', 'demo');
  const kai = await token('kai@bss.local', 'kai');
  try {
    /* ---------- 1. operator adds Klarna ---------- */
    const put = await call('PUT', `${P}/paymentProvider`, staff, {
      provider: 'klarna', displayName: 'Klarna', baseUrl: 'http://mock-klarna:8080',
      secretRef: 'KLARNA_API_KEY', webhookSecretRef: 'KLARNA_WEBHOOK_SECRET',
      methods: ['card', 'klarna'], isDefault: false });
    if (put.status !== 200) fail(`configure klarna: ${put.status} ${put.text}`);
    if (put.body.secretRef !== 'KLARNA_API_KEY' || put.body.apiKey) fail('secret leaked — should be a ref');
    ok('OPERATOR MENU: Klarna added as a payment provider (secret-refs, not the keys)');

    /* ---------- 2. method picker (anonymous) ---------- */
    const methods = (await call('GET', `${P}/payment/methods`, null)).body || [];
    const klarna = methods.find((m) => m.method === 'klarna');
    if (!methods.find((m) => m.method === 'card') || !klarna || klarna.redirect !== true) {
      fail('methods should include card + klarna(redirect): ' + JSON.stringify(methods));
    }
    ok(`METHOD PICKER: ${methods.map((m) => m.method).join(' + ')} — klarna flagged redirect`);

    /* ---------- 3. open a session ---------- */
    const sess = await call('POST', `${P}/payment/session`, kai, {
      method: 'klarna', amount: { value: 29.99, unit: 'EUR' }, returnUrl: 'http://localhost:8080/shop/cart' });
    if (sess.status !== 200 || !sess.body.sessionId || !sess.body.redirectUrl) {
      fail(`session: ${sess.status} ${sess.text}`);
    }
    const sessionId = sess.body.sessionId;
    ok(`SESSION: Klarna session ${sessionId.slice(0, 10)}… opened; redirect → ${sess.body.redirectUrl}`);

    /* ---------- 4. confirm (return leg) → AUTHORIZED payment ---------- */
    const conf = await call('POST', `${P}/payment/confirm`, kai, { provider: 'klarna', sessionId });
    if (conf.status !== 200 || conf.body.status !== 'authorized' || conf.body.pspProvider !== 'klarna') {
      fail(`confirm: ${conf.status} ${conf.text}`);
    }
    const paymentId = conf.body.id;
    ok(`CONFIRM: the approved session minted an AUTHORIZED payment via Klarna (${paymentId.slice(0, 8)}…, 29.99 EUR)`);

    /* ---------- 5. idempotency ---------- */
    const again = await call('POST', `${P}/payment/confirm`, kai, { provider: 'klarna', sessionId });
    if (again.status !== 200 || again.body.id !== paymentId) fail('confirm not idempotent: ' + again.text);
    ok('IDEMPOTENT: confirming the same session again returns the SAME payment — no double-book');

    /* ---------- 6. webhook: HMAC-verified, idempotent ---------- */
    const bodyStr = JSON.stringify({ sessionId });
    const ts = Date.now().toString();
    const sig = 't=' + ts + ',v1=' + crypto.createHmac('sha256', WEBHOOK_SECRET).update(ts + '.' + bodyStr).digest('base64url');
    const wh = await call('POST', `${P}/webhook/klarna/genalpha`, null, bodyStr, { 'x-payment-signature': sig });
    if (wh.status !== 200 || wh.body.paymentId !== paymentId) fail(`webhook: ${wh.status} ${wh.text}`);
    const forged = await call('POST', `${P}/webhook/klarna/genalpha`, null, bodyStr, { 'x-payment-signature': 't=1,v1=forged' });
    if (forged.status !== 401) fail(`forged webhook should be 401, got ${forged.status}`);
    ok('WEBHOOK: HMAC-verified async confirm lands the same payment; a forged signature is 401');

    /* ---------- 7. capture on ship (BNPL captures the order at fulfilment) ---------- */
    const cap = await call('PATCH', `${P}/payment/${paymentId}`, staff, { status: 'captured' });
    if (cap.status !== 200 || cap.body.status !== 'captured') fail(`capture: ${cap.status} ${cap.text}`);
    if (!cap.body.settlementRef || !cap.body.settlementRef.startsWith('cap_')) {
      fail('capture should carry Klarna capture_id (cap_…): ' + JSON.stringify(cap.body.settlementRef));
    }
    ok(`CAPTURE: transitioning to captured routed to KLARNA by session — settled ${cap.body.settlementRef}`);

    /* ---------- 8. refund routed back through Klarna ---------- */
    const rf = await call('POST', `${P}/payment/${paymentId}/refund`, staff, { amount: { value: 10.00, unit: 'EUR' }, reason: 'goodwill' });
    if (rf.status !== 200 || !rf.body.refundRef || !rf.body.refundRef.startsWith('ref_')) {
      fail(`refund: ${rf.status} ${rf.text}`);
    }
    ok(`REFUND: 10.00 EUR routed back through KLARNA by session — reference ${rf.body.refundRef}`);

    console.log('\nALL PSP-KLARNA CHECKS PASSED — a tenant offers Klarna alongside cards, the shopper'
      + ' opens a redirect session, the approved session mints an authorized payment (idempotent'
      + ' across the return leg and the webhook), and capture-on-ship + refunds route back through'
      + ' Klarna by the session (not the card rail). Bring-your-own PSP, the redirect way.');
  } finally {
    await call('DELETE', `${P}/paymentProvider/klarna`, staff).catch(() => {});
  }
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
