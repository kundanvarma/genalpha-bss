/* Process flows + agent memory (component #36, TMF701). Suite #72.
 * Phase 1 legs: the process layer alone — choreography runs the flows,
 * this layer EXPLAINS them, and stuck is a STATE.
 *
 *  1. a digital order projects to a completed processFlow whose tasks
 *     match the spec and whose journal carries the cross-system timeline
 *  2. specs are DATA: shrink an allowance, strand an order (held for
 *     approval — a public-API fault injection), and the sweep marks the
 *     flow FAILED with the owed-time message; the operator lever (PATCH
 *     taskFlow) recovers it
 *  3. walls: customers see only their OWN flows; nova sees nothing
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const P = '/tmf-api/processFlowManagement/v4';
const ORDERS = '/tmf-api/productOrderingManagement/v4';
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

  /* ---------- 1. a completed flow, explained ---------- */
  const offers = (await call('GET',
    '/tmf-api/productCatalogManagement/v4/productOffering?limit=100', kai)).body || [];
  const plan = offers.find((o) => (o.name || '').includes('Unlimited'));
  if (!plan) fail('no digital plan in catalog');
  const order = await call('POST', `${ORDERS}/productOrder`, kai, {
    productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name } }] });
  if (order.status !== 201) fail(`order: ${order.status} ${order.text.slice(0, 150)}`);
  let flow = null;
  for (let i = 0; i < 25 && (!flow || flow.state !== 'completed'); i++) {
    await sleep(2500);
    const flows = (await call('GET',
      `${P}/processFlow?productOrderId=${order.body.id}`, staff)).body || [];
    flow = flows[0] || null;
  }
  if (!flow || flow.state !== 'completed') {
    fail('digital order flow never completed: ' + JSON.stringify(flow).slice(0, 200));
  }
  if (flow.specCode !== 'order-digital') fail('wrong spec picked: ' + flow.specCode);
  if (!flow.taskFlow.every((t) => t.state === 'completed')) {
    fail('tasks not all completed: ' + JSON.stringify(flow.taskFlow));
  }
  const detail = (await call('GET', `${P}/processFlow/${flow.id}`, staff)).body;
  const types = (detail.timeline || []).map((e) => e.eventType);
  if (!types.includes('ProductOrderCreateEvent') || !types.includes('ServiceOrderStateChangeEvent')
      || !types.includes('ProductOrderStateChangeEvent')) {
    fail('timeline misses the cross-system events: ' + types.join(','));
  }
  console.log(`OK EXPLAINED: kai's order projected to a COMPLETED processFlow (spec`
    + ` ${flow.specCode}, ${flow.taskFlow.length} tasks) whose journal stitches the`
    + ` cross-system timeline: ${types.join(' → ')} — choreography ran it, TMF701 explains it.`);

  /* ---------- 2. stuck is a STATE (spec-as-data + public-API fault) ---------- */
  // shrink the physical spec's fulfilment allowance so a stranded order fails fast
  const specs = (await call('GET', `${P}/processFlowSpecification`, staff)).body || [];
  const physical = specs.find((s2) => s2.code === 'order-physical');
  const shrunk = physical.taskFlowSpecification.map((t) =>
    t.code === 'fulfilled' ? { ...t, allowanceSeconds: 8 } : t);
  await call('POST', `${P}/processFlowSpecification`, staff,
    { code: 'order-physical', taskFlowSpecification: shrunk });
  // the fault injection is a PUBLIC order: an item whose product carries a
  // place makes SOM wait for the installer callback — which never comes
  const strand = await call('POST', `${ORDERS}/productOrder`, kai, {
    productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name },
      product: { place: [{ role: 'installation', streetName: `Suite ${run}`, postcode: '111' }] } }] });
  if (strand.status !== 201) fail(`physical order: ${strand.status} ${strand.text.slice(0, 150)}`);
  let stuck = null;
  for (let i2 = 0; i2 < 30 && (!stuck || stuck.state !== 'failed'); i2++) {
    await sleep(3000);
    const flows = (await call('GET',
      `${P}/processFlow?productOrderId=${strand.body.id}`, staff)).body || [];
    stuck = flows[0] || null;
  }
  if (!stuck || stuck.state !== 'failed') {
    fail('stranded physical order never went FAILED: ' + JSON.stringify(stuck).slice(0, 300));
  }
  if (stuck.specCode !== 'order-physical') fail('wrong spec: ' + stuck.specCode);
  const failedTask = stuck.taskFlow.find((t) => t.state === 'failed');
  if (!failedTask || failedTask.code !== 'fulfilled'
      || !String(failedTask.message).includes('owed within')) {
    fail('failed task wrong: ' + JSON.stringify(stuck.taskFlow));
  }
  console.log(`OK STUCK IS A STATE: a physical order with no installer callback (public-API`
    + ` fault injection) went FAILED at '${failedTask.code}' — "${failedTask.message}" — the`
    + ' silent SOM wait is now a loud state on the bus.');

  // the operator lever: mark fulfilment done — the next task opens
  const lever = await call('PATCH', `${P}/processFlow/${stuck.id}/taskFlow/${failedTask.id}`,
    staff, { state: 'completed', message: 'installer confirmed by phone' });
  if (lever.status >= 300) fail(`task lever: ${lever.status} ${lever.text.slice(0, 150)}`);
  const after = lever.body.taskFlow.find((t) => t.id === failedTask.id);
  if (after.state !== 'completed') fail('operator lever did not complete the task');
  if (lever.body.state !== 'inProgress') fail('flow should resume inProgress after the lever');
  // restore the spec
  await call('POST', `${P}/processFlowSpecification`, staff,
    { code: 'order-physical', taskFlowSpecification: physical.taskFlowSpecification });
  console.log('OK THE LEVER: the operator PATCHed the failed taskFlow to completed and the'
    + " flow resumed — TMF701's own recovery handle; the spec's allowance went back to its"
    + ' default (design intent is DATA, edited twice in this suite without a deploy).');

  /* ---------- 4. the agent investigates (P2: episodic memory, L0) ---------- */
  // strand another physical order with the shrunk allowance — this time the
  // failure has an audience: the incident agent on bss.process.events
  await call('POST', `${P}/processFlowSpecification`, staff,
    { code: 'order-physical', taskFlowSpecification: shrunk });
  const strand2 = await call('POST', `${ORDERS}/productOrder`, kai, {
    productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name },
      product: { place: [{ role: 'installation', streetName: `Agent ${run}`, postcode: '111' }] } }] });
  if (strand2.status !== 201) fail(`agent-leg order: ${strand2.status}`);
  let trace = null;
  for (let i3 = 0; i3 < 40 && !trace; i3++) {
    await sleep(3000);
    const incidents = (await call('GET', '/ai/v1/incident', staff)).body || [];
    trace = incidents.find((t) => t.productOrderId === strand2.body.id) || null;
  }
  if (!trace) fail('the agent never investigated the stranded order');
  if (trace.signature !== 'order-physical:fulfilled') fail('wrong signature: ' + trace.signature);
  if (!trace.hypothesis || trace.hypothesis.length < 40) fail('empty hypothesis');
  if (!(Number(trace.confidence) > 0 && Number(trace.confidence) <= 1)) {
    fail('confidence out of range: ' + trace.confidence);
  }
  if (trace.source !== 'llm') fail('P2 diagnoses come from the LLM path: ' + trace.source);
  if (!trace.ticketId) fail('L0 ticket missing');
  const ticket = (await call('GET',
    `/tmf-api/troubleTicket/v4/troubleTicket/${trace.ticketId}`, staff)).body;
  const notes = JSON.stringify(ticket.note || []);
  if (!notes.includes('AGENT DIAGNOSIS')) fail('the diagnosis note is not on the ticket');
  console.log(`OK THE AGENT (L0): the failed task triggered context assembly + a GOVERNED`
    + ` diagnosis — signature ${trace.signature}, confidence ${trace.confidence},`
    + ` ${trace.diagnoseMs}ms — and its ONLY write was a ticket note (ticket`
    + ` ${String(trace.ticketId).slice(0, 8)}…). Episodic trace stored, verdict pending.`);

  /* ---------- 5. the verdict is mandatory, and walled ---------- */
  const noVerdict = await call('POST', `/ai/v1/incident/${trace.id}/verdict`, staff, { note: 'hm' });
  if (noVerdict.status !== 400) fail(`verdict without useful must 400, got ${noVerdict.status}`);
  const kaiVerdict = await call('POST', `/ai/v1/incident/${trace.id}/verdict`, kai,
    { useful: true });
  if (kaiVerdict.status !== 403) fail(`customer verdict must 403, got ${kaiVerdict.status}`);
  const verdict = await call('POST', `/ai/v1/incident/${trace.id}/verdict`, staff,
    { useful: true, note: 'correct — installer job was missing' });
  if (verdict.status >= 300 || verdict.body.verdict !== 'useful') {
    fail('verdict not recorded: ' + verdict.text.slice(0, 150));
  }
  // tidy: restore the spec and complete the stranded flow via the lever
  await call('POST', `${P}/processFlowSpecification`, staff,
    { code: 'order-physical', taskFlowSpecification: physical.taskFlowSpecification });
  const flows2 = (await call('GET',
    `${P}/processFlow?productOrderId=${strand2.body.id}`, staff)).body || [];
  if (flows2[0]) {
    const ft = flows2[0].taskFlow.find((t) => t.state === 'failed');
    if (ft) {
      await call('PATCH', `${P}/processFlow/${flows2[0].id}/taskFlow/${ft.id}`, staff,
        { state: 'completed', message: 'suite tidy-up' });
    }
  }
  console.log('OK THE VERDICT: useful/not is REQUIRED (400 without), staff-only (customer'
    + ' 403), and recorded on the trace — the loop\'s raw material, one verdict per'
    + ' investigation.');

  /* ---------- 6. recurrence earns a runbook (promotion is gated) ---------- */
  // the leg-2 strand was ALSO investigated — verdict it useful (2nd useful)
  const inc0 = (await call('GET', '/ai/v1/incident', staff)).body || [];
  const trace0 = inc0.find((t) => t.productOrderId === strand.body.id);
  if (trace0 && trace0.verdict === 'pending') {
    await call('POST', `/ai/v1/incident/${trace0.id}/verdict`, staff,
      { useful: true, note: 'same cause as before' });
  }
  await call('POST', `${P}/processFlowSpecification`, staff,
    { code: 'order-physical', taskFlowSpecification: shrunk });
  const strand3 = await call('POST', `${ORDERS}/productOrder`, kai, {
    productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name },
      product: { place: [{ role: 'installation', streetName: `Third ${run}`, postcode: '111' }] } }] });
  let trace3 = null;
  for (let i4 = 0; i4 < 40 && !trace3; i4++) {
    await sleep(3000);
    const incidents = (await call('GET', '/ai/v1/incident', staff)).body || [];
    trace3 = incidents.find((t) => t.productOrderId === strand3.body.id) || null;
  }
  if (!trace3) fail('third failure never investigated');
  await call('POST', `/ai/v1/incident/${trace3.id}/verdict`, staff,
    { useful: true, note: 'third occurrence, same cause' });
  let proposed = null;
  for (let i5 = 0; i5 < 10 && !proposed; i5++) {
    await sleep(1500);
    const rbs = (await call('GET', '/ai/v1/runbook', staff)).body || [];
    proposed = rbs.find((r2) => r2.signature === 'order-physical:fulfilled'
      && r2.status === 'proposed') || null;
  }
  if (!proposed) fail('three useful verdicts drafted no runbook');
  if (!proposed.provenance || proposed.provenance.length < 10) fail('runbook lacks provenance');
  // the signature is GOVERNANCE: pat holds ai:use (he can read the library)
  // but not ai:admin — his approval must be refused before staff's succeeds
  const patTok = await token('pat@bss.local', 'pat');
  const patTry = await call('POST', `/ai/v1/runbook/${proposed.id}/approve`, patTok,
    { note: 'pat should not be able to sign this' });
  if (patTry.status !== 403) {
    fail('runbook approval must need ai:admin — pat got ' + patTry.status);
  }
  const approved = await call('POST', `/ai/v1/runbook/${proposed.id}/approve`, staff,
    { note: 'confirmed by ops' });
  if (approved.status >= 300 || approved.body.status !== 'approved') {
    fail('approval failed: ' + approved.text.slice(0, 150));
  }
  console.log(`OK PROMOTION: three human-confirmed traces drafted runbook v${proposed.version}`
    + ' (provenance back to its source traces) — and it needed the HUMAN gate: proposed'
    + ' until ops approved it.');

  /* ---------- 7. THE HEADLINE: auto-diagnosed from memory, no model call ---------- */
  const auditBefore = ((await call('GET', '/ai/v1/audit', staff)).body || [])
    .filter((a) => String(a.useCase || a.use_case).includes('incident-diagnosis')).length;
  const strand4 = await call('POST', `${ORDERS}/productOrder`, kai, {
    productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name },
      product: { place: [{ role: 'installation', streetName: `Fourth ${run}`, postcode: '111' }] } }] });
  let trace4 = null;
  for (let i6 = 0; i6 < 40 && !trace4; i6++) {
    await sleep(3000);
    const incidents = (await call('GET', '/ai/v1/incident', staff)).body || [];
    trace4 = incidents.find((t) => t.productOrderId === strand4.body.id) || null;
  }
  if (!trace4) fail('fourth failure never investigated');
  if (trace4.source !== 'runbook') fail('fourth failure should auto-diagnose: ' + trace4.source);
  if (!String(trace4.hypothesis).startsWith('[runbook')) fail('hypothesis not from the runbook');
  const auditAfter = ((await call('GET', '/ai/v1/audit', staff)).body || [])
    .filter((a) => String(a.useCase || a.use_case).includes('incident-diagnosis')).length;
  if (auditAfter !== auditBefore) {
    fail(`auto-diagnosis must make NO model call: audit ${auditBefore} -> ${auditAfter}`);
  }
  const stats = (await call('GET', '/ai/v1/incident/stats', staff)).body;
  if (!(Number(stats.fromRunbook) >= 1) || !(Number(stats.autoDiagnosedRate) > 0)) {
    fail('stats miss the curve: ' + JSON.stringify(stats));
  }
  console.log(`OK THE HEADLINE: the fourth identical failure was diagnosed FROM MEMORY —`
    + ` source=runbook, zero new model calls (audit ${auditBefore}→${auditAfter}), and the`
    + ` curve exists as data: ${stats.autoDiagnosedRate}% auto-diagnosed`
    + ` (${stats.fromRunbook}/${stats.traces}). A stateless agent cannot produce this number.`);

  /* ---------- 8. revocation: bad memory cannot compound silently ---------- */
  await call('POST', `/ai/v1/runbook/${proposed.id}/revoke`, staff,
    { note: 'suite proves the brake' });
  const strand5 = await call('POST', `${ORDERS}/productOrder`, kai, {
    productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name },
      product: { place: [{ role: 'installation', streetName: `Fifth ${run}`, postcode: '111' }] } }] });
  let trace5 = null;
  for (let i7 = 0; i7 < 40 && !trace5; i7++) {
    await sleep(3000);
    const incidents = (await call('GET', '/ai/v1/incident', staff)).body || [];
    trace5 = incidents.find((t) => t.productOrderId === strand5.body.id) || null;
  }
  if (!trace5) fail('fifth failure never investigated');
  if (trace5.source !== 'llm') fail('after revocation the LLM path must resume: ' + trace5.source);
  // tidy: restore the spec; complete the stranded flows via the lever
  await call('POST', `${P}/processFlowSpecification`, staff,
    { code: 'order-physical', taskFlowSpecification: physical.taskFlowSpecification });
  for (const o of [strand3, strand4, strand5]) {
    const fl = ((await call('GET', `${P}/processFlow?productOrderId=${o.body.id}`, staff)).body || [])[0];
    const ft = fl && fl.taskFlow.find((t) => t.state === 'failed');
    if (ft) {
      await call('PATCH', `${P}/processFlow/${fl.id}/taskFlow/${ft.id}`, staff,
        { state: 'completed', message: 'suite tidy-up' });
    }
  }
  console.log('OK THE BRAKE: revoked the runbook and the fifth failure went back to the'
    + ' governed LLM path — memory is versioned, provenanced, and revocable; a wrong'
    + ' lesson cannot compound silently.');

  /* ---------- 3. walls ---------- */
  const kaiFlows = (await call('GET', `${P}/processFlow`, kai)).body || [];
  if (kaiFlows.some((f) => (f.relatedParty || []).every((p) => p.id !== sub(kai)))) {
    fail('kai can see flows that are not his');
  }
  const allFlows = (await call('GET', `${P}/processFlow`, staff)).body || [];
  const foreign = allFlows.find((f) => (f.relatedParty || []).length
    && f.relatedParty.every((p) => p.id !== sub(kai)));
  if (foreign) {
    const kaiForeign = await call('GET', `${P}/processFlow/${foreign.id}`, kai);
    if (kaiForeign.status !== 404) fail(`kai reading a foreign flow must 404, got ${kaiForeign.status}`);
  }
  const anon = await fetch(`http://shop.nova.localhost:8080${P}/processFlow`);
  if (anon.status === 200) fail('anonymous nova read succeeded');
  console.log('OK WALLS: customers see only their OWN order flows (self-service tracking for'
    + ' free), foreign flows 404, nova sees nothing.');

  console.log('\nALL PROCESS-LAYER CHECKS PASSED — choreography runs the flows, TMF701'
    + ' explains them, and stuck is a loud state. Phase 2 is live: every failure'
    + ' investigated, diagnosed under governance, posted L0 to a ticket, and remembered'
    + ' as a trace with a mandatory human verdict. Phase 3 CLOSED THE LOOP: recurrence'
    + ' plus human approval promotes traces to versioned runbooks, the next occurrence'
    + ' diagnoses from memory with no model call, and revocation is the brake. The'
    + ' learning curve is a suite assertion now.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
