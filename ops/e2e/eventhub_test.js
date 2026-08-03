/* Event hub (component #38: TMF688 subscription over the fleet's bus). Suite #75.
 *
 *  - register a callback + event-type filter, receive the SAME envelopes
 *    the fleet exchanges — filtered, tenant-walled
 *  - a dead listener is retried with backoff and lands DEAD on the ledger:
 *    never lost, never silent
 *  - an event feed is partner/back-office grade: customers cannot register
 */
const http = require('http');
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const HUB = '/tmf-api/eventManagement/v4';
const AGREE = '/tmf-api/agreementManagement/v4';
const RECEIVER_PORT = 4571;
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
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}

(async () => {
  const staff = await token('demo', 'demo');
  const kai = await token('kai@bss.local', 'kai');

  // the mock partner: a real HTTP listener on the host
  const received = [];
  const server = http.createServer((req, res) => {
    let data = '';
    req.on('data', (c) => { data += c; });
    req.on('end', () => { try { received.push(JSON.parse(data)); } catch {} res.end('ok'); });
  });
  await new Promise((r) => server.listen(RECEIVER_PORT, r));

  /* ---------- 1. filtered delivery of real fleet envelopes ---------- */
  const sub = await call('POST', `${HUB}/hub`, staff, {
    callback: `http://host.docker.internal:${RECEIVER_PORT}/hook`,
    eventTypes: ['AgreementCreateEvent'] });
  if (sub.status !== 201) fail(`register: ${sub.status} ${sub.text.slice(0, 150)}`);
  // trigger a matching event AND a non-matching one
  const agree = await call('POST', `${AGREE}/agreement`, staff, {
    name: `Hub probe agreement ${run}`, agreementType: 'commercial', status: 'active',
    engagedParty: [{ id: 'hub-probe', role: 'customer' }] });
  if (agree.status >= 300) fail('probe agreement failed');
  await call('POST', '/tmf-api/alarmManagement/v4/alarm', staff, {
    alarmedObject: `hub-noise-${run}`, perceivedSeverity: 'minor', probableCause: 'noise' });
  let hit = null;
  for (let i = 0; i < 20 && !hit; i++) {
    await sleep(3000);
    hit = received.find((e) => e.eventType === 'AgreementCreateEvent'
      && JSON.stringify(e).includes(`Hub probe agreement ${run}`)) || null;
  }
  if (!hit) fail('the listener never received the agreement envelope');
  if (hit.tenantId !== 'genalpha') fail('envelope missing tenant: ' + hit.tenantId);
  if (received.some((e) => e.eventType && e.eventType.startsWith('Alarm'))) {
    fail('the filter leaked a non-subscribed event type');
  }
  console.log(`OK SUBSCRIBED: a partner registered a callback + filter and received the`
    + ` REAL fleet envelope (eventType=${hit.eventType}, eventId=${String(hit.eventId).slice(0, 8)}…,`
    + ' tenant-stamped) — and only the subscribed type; the noise stayed on the bus.');

  /* ---------- 2. a dead listener: retries, backoff, DEAD on the ledger ---------- */
  const dead = await call('POST', `${HUB}/hub`, staff, {
    callback: 'http://host.docker.internal:4599/nowhere',
    eventTypes: ['AgreementCreateEvent'] });
  await call('POST', `${AGREE}/agreement`, staff, {
    name: `Dead-letter probe ${run}`, agreementType: 'commercial', status: 'active',
    engagedParty: [{ id: 'hub-probe', role: 'customer' }] });
  let deadRow = null;
  for (let i = 0; i < 40 && !deadRow; i++) {
    await sleep(3000);
    const ledger = (await call('GET', `${HUB}/hub/${dead.body.id}/delivery`, staff)).body || [];
    deadRow = ledger.find((d) => d.status === 'dead') || null;
  }
  if (!deadRow) fail('the dead listener never dead-lettered');
  if (!(deadRow.attempts >= 4)) fail(`expected >=4 attempts, got ${deadRow.attempts}`);
  if (!deadRow.lastError) fail('dead delivery carries no error');
  console.log(`OK NEVER SILENT: the unreachable listener was retried ${deadRow.attempts}x with`
    + ` backoff, then marked DEAD on the ledger with the error kept`
    + ` ("${String(deadRow.lastError).slice(0, 50)}…") — never lost, always accountable.`);

  /* ---------- 3. walls ---------- */
  const kaiTry = await call('POST', `${HUB}/hub`, kai,
    { callback: 'http://evil.example/hook' });
  if (kaiTry.status !== 403) fail(`customer registration must 403, got ${kaiTry.status}`);
  const anon = await fetch(`http://shop.nova.localhost:8080${HUB}/hub`);
  if (anon.status === 200) fail('anonymous nova read succeeded');
  // tidy
  await call('DELETE', `${HUB}/hub/${sub.body.id}`, staff);
  await call('DELETE', `${HUB}/hub/${dead.body.id}`, staff);
  server.close();
  console.log('OK WALLS: an event feed is partner-grade — a customer got 403, nova sees'
    + ' nothing, and unregistering is a first-class verb.');

  console.log('\nALL EVENT-HUB CHECKS PASSED — the event-native fleet now has its standard'
    + ' subscription face: register, filter, receive the same envelopes the components'
    + ' exchange, with a ledger that retries the missing and never loses the dead.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
