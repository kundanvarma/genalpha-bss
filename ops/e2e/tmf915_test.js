/* TMF915 AI management (the control plane's standard face). Suite #81.
 *
 *  - aiModel reports what the audit ledger PROVES has served (plus the one
 *    versioned trained artifact); aiModelContract is each governed scenario
 *    with its real monitoring numbers — calls, tokens, spend, latency, and
 *    every refusal class
 *  - the in-life lever TMF915 exists for: SUSPEND one contract and only
 *    that scenario refuses (403, audited as refused-contract) while the
 *    rest of the fleet keeps answering; reactivate and it answers again
 *  - ai:admin opens: reads stay ai:use; suspending a contract needs the
 *    new authority — proven by a staff user who HAS ai:use and not
 *    ai:admin being refused
 *  - nova sees only nova: genalpha's suspension never crosses the wall
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms';
const AIM = '/tmf-api/aiManagement/v4';
const run = Date.now();
const fail = (m) => { throw new Error(m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(realm, user, pass) {
  const r = await fetch(`${KC}/${realm}/protocol/openid-connect/token`, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo',
      username: user, password: pass }) });
  if (!r.ok) fail(`token(${realm}/${user}): ${r.status}`);
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
const copy = (tok) => call('POST', '/ai/v1/campaignCopy', tok,
  { brief: `TMF915 probe ${run}`, brandName: 'GenAlpha' });

(async () => {
  const staff = await token('bss', 'demo', 'demo');
  const aiUseOnly = await token('bss', 'pat@bss.local', 'pat'); // ai:use, NOT ai:admin
  const kai = await token('bss', 'kai@bss.local', 'kai');

  // make sure the tenant switch is on and the contract starts active
  await call('POST', '/ai/v1/governance/budget', staff, { budgetMicros: 0, enabled: true });
  await call('PATCH', `${AIM}/aiModelContract/campaign-copy`, staff, { state: 'active' });

  /* ---------- 1. the face tells the ledger's truth ---------- */
  const warm = await copy(staff);
  if (warm.status !== 200) fail(`warm-up copy: ${warm.status} ${warm.text.slice(0, 150)}`);
  const contracts = (await call('GET', `${AIM}/aiModelContract`, staff)).body || [];
  const cc = contracts.find((c) => c.id === 'campaign-copy');
  if (!cc || cc.state !== 'active') fail('campaign-copy contract missing or not active');
  if (!(cc.monitoring.calls > 0) || !(cc.monitoring.costMicros > 0)
      || !(cc.monitoring.outcome.ok > 0)) {
    fail('monitoring numbers are not real: ' + JSON.stringify(cc.monitoring).slice(0, 200));
  }
  if (!cc.guardrail || cc.guardrail.tenantKillSwitch !== 'armed') {
    fail('the contract should carry its guardrails');
  }
  const models = (await call('GET', `${AIM}/aiModel`, staff)).body || [];
  const serving = models.find((m) => (m.servedContract || []).includes('campaign-copy'));
  if (!serving) fail('no model claims to have served campaign-copy');
  const churn = models.find((m) => m.id === 'local/churn-logistic');
  if (churn && !(churn.trainingRecord && churn.trainingRecord.sampleCount > 0)) {
    fail('the trained model must carry its training record');
  }
  console.log(`OK THE FACE: ${contracts.length} model contracts projected from the audit`
    + ` ledger — campaign-copy shows ${cc.monitoring.calls} calls, ${cc.monitoring.costMicros}`
    + ` micros spent, avg ${cc.monitoring.avgLatencyMs}ms, outcomes ${JSON.stringify(cc.monitoring.outcome)}`
    + ` — and ${models.length} models the ledger PROVES have served`
    + `${churn ? ', including the trained churn classifier with its training record' : ''}.`
    + ' Nothing registered, everything observed.');

  /* ---------- 2. the lever: suspend ONE contract ---------- */
  const suspended = await call('PATCH', `${AIM}/aiModelContract/campaign-copy`, staff,
    { state: 'suspended', note: `TMF915 suite probe ${run}` });
  if (suspended.status !== 200 || suspended.body.state !== 'suspended') {
    fail(`suspend: ${suspended.status} ${suspended.text.slice(0, 200)}`);
  }
  const refused = await copy(staff);
  if (refused.status !== 403) fail(`suspended contract must 403, got ${refused.status}`);
  const other = await call('POST', '/ai/v1/knowledgeAsk', staff,
    { question: 'How do I read my bill?' });
  if (other.status !== 200) {
    fail(`a DIFFERENT scenario must keep answering, got ${other.status} ${other.text.slice(0, 150)}`);
  }
  await sleep(500);
  const audit = (await call('GET', '/ai/v1/audit', staff)).body || [];
  const evidence = audit.find((a) => a.useCase === 'campaign-copy'
    && a.outcome === 'refused-contract');
  if (!evidence) fail('the refusal must be AUDITED as refused-contract');
  console.log('OK THE LEVER: campaign-copy suspended over the standard face — the next call'
    + ' 403\'d and the refusal is ON THE LEDGER as refused-contract, while knowledgeAsk kept'
    + ' answering. One scenario braked; the fleet unharmed. The tenant kill-switch finally'
    + ' has a scalpel beside it.');

  /* ---------- 3. reactivate ---------- */
  const back = await call('PATCH', `${AIM}/aiModelContract/campaign-copy`, staff,
    { state: 'active', note: 'probe done' });
  if (back.body.state !== 'active') fail('reactivate failed');
  const again = await copy(staff);
  if (again.status !== 200) fail(`reactivated contract must answer, got ${again.status}`);
  if (!back.body.lastDecision || !back.body.lastDecision.decidedAt) {
    fail('the decision trail should survive on the contract');
  }
  console.log('OK THE RETURN: reactivated, answering again, and the contract remembers its'
    + ' last decision — who braked it and why is one GET away.');

  /* ---------- 4. ai:admin is real, and walls hold ---------- */
  const patRead = await call('GET', `${AIM}/aiModelContract`, aiUseOnly);
  if (patRead.status !== 200) fail(`ai:use should read the face, got ${patRead.status}`);
  const patPatch = await call('PATCH', `${AIM}/aiModelContract/campaign-copy`, aiUseOnly,
    { state: 'suspended' });
  if (patPatch.status !== 403) {
    fail(`ai:use WITHOUT ai:admin must not suspend, got ${patPatch.status}`);
  }
  const kaiRead = await call('GET', `${AIM}/aiModelContract`, kai);
  if (kaiRead.status !== 403) fail(`a customer must not read the control plane, got ${kaiRead.status}`);
  const novaStaff = await token('nova', 'demo', 'demo');
  const novaContracts = (await call('GET', `${AIM}/aiModelContract`, novaStaff)).body || [];
  const novaCc = novaContracts.find((c) => c.id === 'campaign-copy');
  if (novaCc && novaCc.lastDecision
      && String(novaCc.lastDecision.note || '').includes(String(run))) {
    fail('genalpha\'s suspension leaked into nova\'s contract');
  }
  console.log('OK THE WALLS: ai:use reads but cannot suspend (the ai:admin seam the'
    + ' governance controller always promised is OPEN — a staff user with ai:use alone'
    + ' got 403), a customer sees nothing, and genalpha\'s decisions never crossed into'
    + ' nova\'s contracts.');

  console.log('\nALL TMF915 CHECKS PASSED — the AI control plane is standards-addressable:'
    + ' models the ledger proves, contracts with real numbers including every refusal'
    + ' class, and the in-life lever that brakes one scenario without stopping the fleet.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
