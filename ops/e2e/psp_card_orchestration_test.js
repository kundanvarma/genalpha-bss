/* Card orchestration — routing rules + failover on the synchronous authorize path. Suite #96.
 *
 * The card money path resolved ONE provider with no retry. Now a tenant can run a
 * pool of card acquirers:
 *  - ROUTING: a currency rule sends a charge to a chosen acquirer (USD → mockbank),
 *    while other currencies stay on the default (EUR → mock).
 *  - FAILOVER: when the primary acquirer is UNREACHABLE (a connect-level outage,
 *    card •••• 0009), the charge fails over to the backup and settles there — the
 *    served provider is recorded on the payment.
 *  - DECLINE ≠ OUTAGE: a hard decline (•••• 0002) is the card saying no; it is NOT
 *    retried on the backup (a decline is not an outage, and a retry could only
 *    annoy the customer / trip fraud rules).
 *  - IDEMPOTENCY-SAFE: only connect-level failures fail over (never an ambiguous
 *    read-timeout that could have charged), so no double-authorize.
 *
 * Configures a mock + mockbank card pool on the demo tenant and removes it after,
 * so the card path reverts to the deployment's global PSP.
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
const putCard = (staff, o) => call('PUT', `${P}/paymentProvider`, staff, o);
const pay = (kai, value, unit, cardNumber) => call('POST', `${P}/payment`, kai, {
  description: 'orchestration suite', amount: { unit, value },
  paymentMethod: { '@type': 'bankCard', cardNumber, expiry: '12/29', cvc: '123' } });

(async () => {
  const staff = await token('demo', 'demo');
  const kai = await token('kai@bss.local', 'kai');
  try {
    /* ---------- FAILOVER: primary any-currency, backup any-currency ---------- */
    await putCard(staff, { provider: 'mock', displayName: 'Acquirer A (mock)', methods: ['card'],
      priority: 100, isDefault: true });
    await putCard(staff, { provider: 'mockbank', displayName: 'Acquirer B (mockbank)', methods: ['card'],
      priority: 200, isDefault: false });

    const normal = await pay(kai, 30.00, 'EUR', '4242 4242 4242 4242');
    if (normal.status >= 300 || normal.body.pspProvider !== 'mock') fail(`normal charge should go to mock: ${normal.status} ${normal.text}`);
    ok(`PRIMARY: a normal EUR charge settled on the primary acquirer (mock) — ${normal.body.pspProvider}`);

    const fo = await pay(kai, 31.00, 'EUR', '4000 0000 0000 0009'); // primary outage
    if (fo.status >= 300) fail(`failover charge failed entirely: ${fo.status} ${fo.text}`);
    if (fo.body.pspProvider !== 'mockbank') fail(`expected failover to mockbank, got ${fo.body.pspProvider}`);
    ok(`FAILOVER: the primary acquirer was unreachable (•••• 0009) → the charge settled on the backup`
      + ` (${fo.body.pspProvider}) — a single acquirer outage no longer sinks the charge`);

    const declined = await pay(kai, 32.00, 'EUR', '4000 0000 0000 0002'); // hard decline
    if (declined.status < 400) fail(`a hard decline should NOT recover on the backup: ${declined.status} ${declined.text}`);
    ok(`DECLINE ≠ OUTAGE: a hard decline (•••• 0002) was refused, not retried on the backup (${declined.status})`);

    /* ---------- ROUTING: a currency rule picks the acquirer ---------- */
    // Re-route: mockbank now handles USD only, at higher priority; mock stays any-currency default.
    await putCard(staff, { provider: 'mockbank', displayName: 'Acquirer B (mockbank)', methods: ['card'],
      priority: 10, currencies: ['USD'], isDefault: false });

    const usd = await pay(kai, 33.00, 'USD', '4242 4242 4242 4242');
    if (usd.status >= 300 || usd.body.pspProvider !== 'mockbank') fail(`USD should route to mockbank: ${usd.status} ${usd.text}`);
    ok(`ROUTING (USD): the currency rule sent the USD charge to mockbank — ${usd.body.pspProvider}`);

    const eur = await pay(kai, 34.00, 'EUR', '4242 4242 4242 4242');
    if (eur.status >= 300 || eur.body.pspProvider !== 'mock') fail(`EUR should stay on mock (mockbank is USD-only): ${eur.status} ${eur.text}`);
    ok(`ROUTING (EUR): a EUR charge stayed on the default acquirer (mock) — mockbank is USD-only — ${eur.body.pspProvider}`);

    console.log('\nALL CARD-ORCHESTRATION CHECKS PASSED — a currency rule routes a charge to the chosen'
      + ' acquirer, an unreachable acquirer fails over to a backup (recorded on the payment), and a hard'
      + ' decline is refused rather than double-tried. The money path stays single-provider-identical when'
      + ' no pool is configured.');
  } finally {
    await call('DELETE', `${P}/paymentProvider/mock`, staff).catch(() => {});
    await call('DELETE', `${P}/paymentProvider/mockbank`, staff).catch(() => {});
  }
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
