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
    '/tmf-api/productCatalogManagement/v4/productOffering?limit=50', kai)).body || [];
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

  console.log('\nALL PROCESS-LAYER CHECKS PASSED — the fleet\'s choreography now has a TMF701'
    + ' face: design intent as data, every flow inspectable with its cross-system timeline,'
    + ' STUCK as a loud state instead of a silent wait, and an operator lever to recover.'
    + ' (Phase 2 gives these failures to an agent with memory.)');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
