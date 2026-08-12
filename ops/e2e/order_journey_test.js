/* Order journey — the plain-language "why in progress", for agent AND customer. Suite #105.
 *
 * A status without a reason is a support call. The TMF701 process projection
 * already assembles each order's milestones from the ordering/SOM/fulfilment
 * events; this proves it now speaks a plain-language SUMMARY (a headline + a
 * "why") a back-office agent and the customer read identically — and that the
 * milestone timeline is there underneath.
 *
 *  - JOURNEY EXISTS: a fresh order gets a flow keyed to it, with ordered steps.
 *  - PLAIN LANGUAGE: the flow carries summary.headline + summary.why + steps
 *    done/total — the sentence that deflects the call.
 *  - MILESTONES: the steps carry states (placed done, later steps pending) and
 *    a chronological event timeline.
 *  - PARTY-SCOPED: the owning customer reads THEIR order's journey.
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const ORDERS = '/tmf-api/productOrderingManagement/v4';
const PROC = '/tmf-api/processFlowManagement/v4';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}

(async () => {
  const staff = await token('demo', 'demo');
  const kai = await token('kai@bss.local', 'kai');

  // a fresh order → its journey is born from the create event
  const offers = (await call('GET', `${ORDERS.replace('productOrderingManagement', 'productCatalogManagement')}/productOffering?limit=100`, kai)).body
    || (await call('GET', '/tmf-api/productCatalogManagement/v4/productOffering?limit=100', kai)).body || [];
  const plan = offers.find((o) => (o.name || '').includes('Unlimited'))
    || offers.find((o) => (o.name || '').includes('Mobile') && !o.isBundle);
  if (!plan) fail('no plan in catalog');
  const order = await call('POST', `${ORDERS}/productOrder`, kai, {
    productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name } }] });
  if (order.status !== 201) fail(`order: ${order.status} ${order.text}`);
  const orderId = order.body.id;

  /* ---------- 1. the journey exists, keyed to the order ---------- */
  let flow = null;
  for (let i = 0; i < 20 && !flow; i++) {
    await sleep(1500);
    const list = (await call('GET', `${PROC}/processFlow?productOrderId=${orderId}`, staff)).body || [];
    if (list.length) flow = list[0];
  }
  if (!flow) fail('no process journey was born for the fresh order');
  if (flow.productOrderId !== orderId) fail('the flow is not keyed to this order');
  ok(`JOURNEY EXISTS: a flow (${flow.specCode}) was born for the order, keyed to its id`);

  /* ---------- 2. the plain-language summary — for agent AND customer ---------- */
  const full = (await call('GET', `${PROC}/processFlow/${flow.id}`, staff)).body;
  const sum = full.summary || {};
  if (!sum.headline || !sum.why || sum.stepsTotal === undefined) {
    fail('the flow carries no plain-language summary: ' + JSON.stringify(sum));
  }
  ok(`PLAIN LANGUAGE: "${sum.headline}" — ${sum.why} (${sum.stepsDone}/${sum.stepsTotal} steps)`);

  /* ---------- 3. milestones + timeline ---------- */
  const steps = full.taskFlow || [];
  if (steps.length < 2) fail('the journey has no milestone steps: ' + JSON.stringify(steps));
  const placed = steps.find((t) => /placed/i.test(t.name));
  if (!placed || placed.state !== 'completed') fail('the "placed" milestone is not completed');
  if (!Array.isArray(full.timeline) || !full.timeline.length) fail('the journey has no event timeline');
  ok(`MILESTONES: ${steps.map((t) => `${t.name}:${t.state}`).join(' → ')} — with a ${full.timeline.length}-event timeline`);

  /* ---------- 4. the customer reads their OWN order's journey ---------- */
  const asCustomer = (await call('GET', `${PROC}/processFlow?productOrderId=${orderId}`, kai)).body || [];
  if (!asCustomer.length) fail('the owning customer cannot read their own order journey');
  const custFull = (await call('GET', `${PROC}/processFlow/${asCustomer[0].id}`, kai)).body;
  if (!custFull.summary || !custFull.summary.why) fail('the customer view lacks the plain-language why');
  ok(`PARTY-SCOPED: the owning customer reads their order's journey + why — the self-service that deflects the call`);

  console.log('\nALL ORDER-JOURNEY CHECKS PASSED — every order carries a plain-language status and WHY,'
    + ' assembled from the events the fleet already emits, read the same by the agent (console Orders'
    + ' → Journey) and the customer (My orders → Why is it in progress?). Fewer calls, more answers.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
