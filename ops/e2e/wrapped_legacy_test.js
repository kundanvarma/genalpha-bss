/* The wrapped legacy estate — the overlay strategy earns its receipt. Suite #67.
 *
 *  - FEDERATION: the legacy stack's catalog (envelope-wrapped, PROD_CD-shaped)
 *    appears in the ACP feed as priced TMF620 offerings, legacy-prefixed
 *  - THE SALE: an agent buys a LEGACY-federated offering through the same
 *    ACP checkout — delegated token, real payment, real TMF622 order
 *  - THE HAND-OFF: the order lands in the LEGACY fulfilment queue (its
 *    own WO_NO), while genalpha keeps the engagement record
 *  - THE WORKFORCE ACROSS THE WRAP: the legacy estate's open incident joins
 *    the workforce queue AGE-STAMPED; completing it is verified against the
 *    LEGACY system's own state — 409 while OPEN, done only when CLOSED
 *  - never two writers, all the way down
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const run = Date.now();
const fail = (m) => { throw new Error(m); };

async function form(data) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(data) });
  if (!r.ok) fail(`token: ${r.status} ${await r.text()}`);
  return r.json();
}
async function call(method, path, tok, body, headers = {}) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}

(async () => {
  const staff = (await form({ grant_type: 'password', client_id: 'bss-demo',
    username: 'demo', password: 'demo' })).access_token;

  /* ---------- 1. federation ---------- */
  const feed = (await call('GET', '/acp/product_feed')).body.products;
  const dsl = feed.find((p) => p.id === 'legacy-LGCY-DSL-20')
    || fail('the legacy catalog is not federated into the feed');
  if (dsl.price.amount !== '24.9' && dsl.price.amount !== '24.90') {
    fail('federated price is wrong: ' + dsl.price.amount);
  }
  console.log(`OK FEDERATION: the legacy estate's envelope-wrapped catalog surfaces in the ACP`
    + ` feed as priced TMF620 — "${dsl.title}" at ${dsl.price.amount} ${dsl.price.currency},`
    + ' legacy-prefixed so every downstream knows which estate it came from');

  /* ---------- 2. the agentic sale of a legacy offering ---------- */
  const session = (await call('POST', '/acp/checkout_sessions', null,
    { items: [{ id: dsl.id, quantity: 1 }] })).body;
  if (!session.id) fail('no checkout session for the legacy offering');
  const paula = (await form({ grant_type: 'password', client_id: 'bss-demo',
    username: 'paula@family.example', password: 'paula' })).access_token;
  const delegated = (await form({
    grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange',
    client_id: 'bss-agent', client_secret: 'agent-secret',
    subject_token: paula,
    subject_token_type: 'urn:ietf:params:oauth:token-type:access_token',
    requested_token_type: 'urn:ietf:params:oauth:token-type:access_token' })).access_token;
  const done = await call('POST', `/acp/checkout_sessions/${session.id}/complete`, delegated,
    { payment_data: { token: `spt-legacy-${run}` } }, { 'Idempotency-Key': `wl-${run}` });
  if (done.status !== 200 || !done.body.order) {
    fail(`legacy-offering checkout: ${done.status} ${done.text.slice(0, 200)}`);
  }
  const orderId = done.body.order.id;
  console.log(`OK THE SALE: an agent bought the LEGACY-federated offering through the standard`
    + ` ACP checkout — delegated token, real payment, order ${orderId.slice(0, 8)}…`);

  /* ---------- 3. the hand-off into the legacy queue ---------- */
  const wos = (await call('GET', '/legacy/api/listWorkOrders')).body.resultSet.row;
  const wo = wos.find((w) => w.ORDER_ID === orderId)
    || fail('the order never reached the legacy fulfilment queue');
  console.log(`OK HAND-OFF: the legacy queue holds ${wo.WO_NO} for order ${orderId.slice(0, 8)}…`
    + ` (PROD_CD ${wo.PROD_CD}) — genalpha keeps the engagement record, legacy keeps fulfilment.`
    + ' Never two writers.');

  /* ---------- 4. the workforce works the LEGACY backlog ---------- */
  await call('POST', '/legacy/api/reopenIncident', null, {}); // idempotent re-runs
  const minted = (await call('POST', '/tmf-api/rolesAndPermissionsManagement/v4/user', staff,
    { email: `worker-wrap-${run}@bss.local`, givenName: 'Wrap', familyName: 'Worker' })).body;
  await call('POST', '/tmf-api/rolesAndPermissionsManagement/v4/permission', staff,
    { user: { id: minted.id }, userRole: { name: 'digital-worker' } });
  const worker = (await form({ grant_type: 'password', client_id: 'bss-demo',
    username: minted.username, password: minted.temporaryPassword })).access_token;

  const queue = (await call('GET', '/ai/v1/workforce/tasks', worker)).body;
  const task = queue.find((t) => t.kind === 'legacy-ticket' && t.subjectRef === 'INC-9001')
    || fail('the legacy incident never joined the workforce queue');
  if (!task.summary.includes('asOf') || !task.summary.includes('opened')) {
    fail('legacy data must be AGE-STAMPED: ' + task.summary);
  }
  const claim = await call('POST',
    `/ai/v1/workforce/tasks/${encodeURIComponent(task.id)}/claim`, worker);
  if (claim.status !== 200) fail(`claim legacy task: ${claim.status}`);
  const early = await call('POST',
    `/ai/v1/workforce/tasks/${encodeURIComponent(task.id)}/complete`, worker,
    { outcome: 'fixed (it was not)' });
  if (early.status !== 409) fail(`completing an OPEN legacy incident must 409: ${early.status}`);
  await call('POST', '/legacy/api/closeIncident', null,
    { INC_NO: 'INC-9001', RESOLUTION: 'DSLAM-041 port re-seated; link stable 15m.' });
  const finished = await call('POST',
    `/ai/v1/workforce/tasks/${encodeURIComponent(task.id)}/complete`, worker,
    { outcome: 'Resolved in the legacy system: DSLAM port re-seated.' });
  if (finished.status !== 200) fail(`complete after legacy close: ${finished.status} ${finished.text.slice(0, 200)}`);
  console.log('OK WORKFORCE ACROSS THE WRAP: the legacy incident joined the queue age-stamped;'
    + ' completing it while the LEGACY system said OPEN was refused with 409; only after the'
    + ' incident closed IN THE LEGACY SYSTEM did the task complete — verified completion holds'
    + ' across the wrap.');

  // tidy: fire the worker, reopen nothing (incident stays closed)
  await call('DELETE', `/tmf-api/rolesAndPermissionsManagement/v4/permission/${
    Buffer.from(`${minted.id}~digital-worker`).toString('base64url')}`, staff);

  console.log('\nALL WRAPPED-LEGACY CHECKS PASSED — the overlay is no longer strategy: a legacy'
    + ' estate was federated, sold from, handed fulfilment, and worked by the digital workforce'
    + ' with the legacy system itself as the source of truth. One seam per legacy system,'
    + ' never two writers, receipts all the way down.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
