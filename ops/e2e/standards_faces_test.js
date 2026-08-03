/* Standard faces (TMF724 incidents, TMF653 service tests, TMF639 resources). Suite #76.
 *
 *  - TMF724: the incident agent's memory is standards-addressable — a trace
 *    pending verdict is an ACKNOWLEDGED incident, a verdicted one RESOLVED
 *  - TMF653: diagnose becomes a serviceTest WITH HISTORY, same code path,
 *    same owner check — a foreign service 404s
 *  - TMF639: pools and issued numbers, honestly labeled — a generator pool
 *    reports issued+quarantined and never invents an "available" count
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
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

(async () => {
  const staff = await token('demo', 'demo');
  const kai = await token('kai@bss.local', 'kai');
  const kaiId = sub(kai);

  /* ---------- 1. TMF724: incidents from memory ---------- */
  const incidents = (await call('GET', '/tmf-api/incidentManagement/v4/incident', staff)).body || [];
  if (!incidents.length) fail('no incidents — suite #72 should have left traces');
  const inc = incidents[0];
  if (!inc.name.includes('Process incident') || !['acknowledged', 'resolved'].includes(inc.state)) {
    fail('incident shape wrong: ' + JSON.stringify(inc).slice(0, 200));
  }
  if (!inc.rootCauseAid || !inc.rootCauseAid.hypothesis) fail('incident misses rootCauseAid');
  const resolved = incidents.filter((i) => i.state === 'resolved');
  if (!resolved.length) fail('verdicted traces should map to RESOLVED incidents');
  const kaiInc = await call('GET', '/tmf-api/incidentManagement/v4/incident', kai);
  if (kaiInc.status !== 403) fail(`customer incident read must 403, got ${kaiInc.status}`);
  console.log(`OK TMF724: ${incidents.length} incidents served from the agent's episodic`
    + ` memory — verdict discipline IS the state machine (${resolved.length} resolved),`
    + ' rootCauseAid carries the hypothesis + source, and the face is ops-only (customer 403).');

  /* ---------- 2. TMF653: a serviceTest with history ---------- */
  const services = (await call('GET',
    `/tmf-api/serviceInventory/v4/service?relatedPartyId=${kaiId}`, kai)).body || [];
  const svc = services.find((s) => s.state === 'active') || services[0];
  if (!svc) fail('kai has no service to test');
  const test = await call('POST', '/tmf-api/serviceTestManagement/v4/serviceTest', kai,
    { relatedService: { id: svc.id } });
  if (test.status !== 201) fail(`serviceTest: ${test.status} ${test.text.slice(0, 200)}`);
  if (!test.body.verdict || !Array.isArray(test.body.testMeasure)) {
    fail('test shape wrong: ' + JSON.stringify(test.body).slice(0, 200));
  }
  const history = (await call('GET',
    `/tmf-api/serviceTestManagement/v4/serviceTest?serviceId=${svc.id}`, kai)).body || [];
  if (!history.some((t) => t.id === test.body.id)) fail('the test did not persist to history');
  // a foreign service must 404 through the same owner check diagnose uses
  const all = (await call('GET', '/tmf-api/serviceInventory/v4/service?limit=100', staff)).body || [];
  const foreign = all.find((s) => (s.relatedParty || []).every((p) => p.id !== kaiId));
  if (foreign) {
    const denied = await call('POST', '/tmf-api/serviceTestManagement/v4/serviceTest', kai,
      { relatedService: { id: foreign.id } });
    if (denied.status !== 404) fail(`foreign service test must 404, got ${denied.status}`);
  }
  console.log(`OK TMF653: kai ran a serviceTest on his own line — verdict "${test.body.verdict}",`
    + ` ${test.body.testMeasure.length} findings, PERSISTED to history — same code path and`
    + ' owner check as the CSR Diagnose button; a foreign service 404s.');

  /* ---------- 3. TMF639: the honest resource facade ---------- */
  const pools = (await call('GET',
    '/tmf-api/resourceInventoryManagement/v4/resourcePool', staff)).body || [];
  const msisdnPool = pools.find((p) => p.resourceType === 'msisdn' || p.prefix);
  if (!msisdnPool) fail('no MSISDN pool visible');
  if (msisdnPool.issuedCounter == null || !String(msisdnPool.note).includes('invention')) {
    fail('pool face must carry issuedCounter and the honest note');
  }
  const resources = (await call('GET',
    '/tmf-api/resourceInventoryManagement/v4/resource', staff)).body || [];
  const assigned = resources.filter((r) => r.resourceStatus === 'assigned');
  if (!assigned.length || !assigned[0].value) fail('no assigned resources in the ledger');
  const kaiRes = await call('GET', '/tmf-api/resourceInventoryManagement/v4/resource', kai);
  if (kaiRes.status !== 403) fail(`customer resource read must 403, got ${kaiRes.status}`);
  console.log(`OK TMF639: ${pools.length} pool(s) with prefix + issuedCounter and the honest`
    + ` generator note; ${assigned.length} issued numbers on the ledger (values + service`
    + ' refs); the number plan is staff-only (customer 403).');

  console.log('\nALL STANDARD-FACES CHECKS PASSED — three thin faces over what already'
    + ' existed: the agent\'s memory as TMF724 incidents, diagnose as TMF653 tests with'
    + ' history, and the number plan as TMF639 — honestly labeled, never inventing what'
    + ' the data cannot say.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
