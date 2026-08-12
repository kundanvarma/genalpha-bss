/* PSP-P4 — a SECOND redirect provider (PayPal) + orchestration failover. Suite #94.
 *
 *  - PAYPAL: the redirect seam is not Klarna-special. A tenant offers PayPal; a
 *    session → confirm mints an AUTHORIZED payment via PayPal; capture + refund
 *    route back through PayPal by session. Unlike Klarna (BNPL), PayPal settles
 *    to the merchant at capture — so it is CASH, not a receivable (see suite #93).
 *  - FAILOVER: with the chosen redirect provider unreachable, checkout survives —
 *    createSession fails over to another configured redirect provider and names
 *    the one that ACTUALLY served (`failedOverFrom`), so the confirm leg and the
 *    records stay truthful.
 *
 * Restores the demo PSP menu (Klarna working) on the way out.
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const P = '/tmf-api/paymentManagement/v4';
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
const putProvider = (staff, o) => call('PUT', `${P}/paymentProvider`, staff, o);
const KLARNA_OK = { provider: 'klarna', displayName: 'Klarna', baseUrl: 'http://mock-klarna:8080',
  secretRef: 'KLARNA_API_KEY', webhookSecretRef: 'KLARNA_WEBHOOK_SECRET', methods: ['card', 'klarna'], isDefault: false };
const PAYPAL_OK = { provider: 'paypal', displayName: 'PayPal', baseUrl: 'http://mock-paypal:8080',
  secretRef: 'PAYPAL_API_KEY', methods: ['paypal'], isDefault: false };

(async () => {
  const staff = await token('demo', 'demo');
  const kai = await token('kai@bss.local', 'kai');
  try {
    await putProvider(staff, KLARNA_OK);
    await putProvider(staff, PAYPAL_OK);

    /* ---------- 1. PayPal as a second redirect provider ---------- */
    const methods = (await call('GET', `${P}/payment/methods`, null)).body || [];
    const pp = methods.find((m) => m.method === 'paypal');
    if (!pp || pp.redirect !== true) fail('paypal not offered as a redirect method: ' + JSON.stringify(methods));
    ok(`METHOD: PayPal offered alongside ${methods.map((m) => m.method).join(' + ')} — flagged redirect`);

    const sess = await call('POST', `${P}/payment/session`, kai, {
      method: 'paypal', amount: { value: 42.00, unit: 'EUR' }, returnUrl: 'http://localhost:8080/shop/cart' });
    if (sess.status !== 200 || sess.body.provider !== 'paypal' || !sess.body.redirectUrl) fail(`paypal session: ${sess.status} ${sess.text}`);
    ok(`SESSION: PayPal order ${sess.body.sessionId.slice(0, 10)}… — redirect → ${sess.body.redirectUrl}`);

    const conf = await call('POST', `${P}/payment/confirm`, kai, { provider: 'paypal', sessionId: sess.body.sessionId });
    if (conf.status !== 200 || conf.body.status !== 'authorized' || conf.body.pspProvider !== 'paypal') fail(`paypal confirm: ${conf.status} ${conf.text}`);
    const payId = conf.body.id;
    ok(`CONFIRM: the approved PayPal order minted an AUTHORIZED payment (${payId.slice(0, 8)}…, 42.00 EUR)`);

    const cap = await call('PATCH', `${P}/payment/${payId}`, staff, { status: 'captured' });
    if (cap.status !== 200 || !(cap.body.settlementRef || '').startsWith('PPCAP')) fail(`paypal capture: ${cap.status} ${cap.text}`);
    ok(`CAPTURE: routed to PayPal by session — settled ${cap.body.settlementRef}`);

    const rf = await call('POST', `${P}/payment/${payId}/refund`, staff, { amount: { value: 12.00, unit: 'EUR' }, reason: 'goodwill' });
    if (rf.status !== 200 || !(rf.body.refundRef || '').startsWith('PPREF')) fail(`paypal refund: ${rf.status} ${rf.text}`);
    ok(`REFUND: 12.00 EUR routed back through PayPal — reference ${rf.body.refundRef}`);

    /* ---------- 2. FAILOVER: the chosen provider is down → checkout survives ---------- */
    // Point Klarna at a dead port; PayPal stays up. A klarna-method session must
    // fail over to PayPal and say so.
    await putProvider(staff, { ...KLARNA_OK, baseUrl: 'http://mock-klarna:9' });
    const fo = await call('POST', `${P}/payment/session`, kai, {
      method: 'klarna', amount: { value: 15.00, unit: 'EUR' }, returnUrl: 'http://localhost:8080/shop/cart' });
    if (fo.status !== 200) fail(`failover session: ${fo.status} ${fo.text}`);
    if (fo.body.provider !== 'paypal' || fo.body.failedOverFrom !== 'klarna') {
      fail('expected failover klarna→paypal, got: ' + JSON.stringify(fo.body));
    }
    ok(`FAILOVER: Klarna unreachable → the session was served by PayPal (failedOverFrom=klarna) —`
      + ' checkout survived a provider outage, and the response names who served');

    // and the failed-over session confirms against the provider that served
    const foConf = await call('POST', `${P}/payment/confirm`, kai, { provider: fo.body.provider, sessionId: fo.body.sessionId });
    if (foConf.status !== 200 || foConf.body.pspProvider !== 'paypal') fail(`failover confirm: ${foConf.status} ${foConf.text}`);
    ok('FAILOVER CONFIRM: the served provider (PayPal) is what the confirm leg used — records stay truthful');

    console.log('\nALL PSP-PAYPAL/ORCHESTRATION CHECKS PASSED — a second redirect provider proves the seam is'
      + ' not Klarna-special, and createSession fails over to a backup provider when the chosen one is down.');
  } finally {
    // restore the demo menu (Klarna working); leave PayPal offered too
    await putProvider(staff, KLARNA_OK).catch(() => {});
    await putProvider(staff, PAYPAL_OK).catch(() => {});
  }
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
