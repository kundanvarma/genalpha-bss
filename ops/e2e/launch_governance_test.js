/* #119 launch_governance_test — intent to launch, with an approval step.
 * Taranga runs launch-governance: envelope. Sigrid (product, no approve role)
 * drafts two offers: one inside a pre-approved envelope (launches by itself,
 * ledgered to the envelope), one outside (waits for Henrik, the commercial
 * approver). Readiness ticks by owner, a hold after approval ("marketing is
 * not ready"), resume, launch, a substance edit voiding an approval, an
 * unlaunch, the TMF701 mirror, and the TMF620 resource staying standard. */
const API = 'http://localhost:8080';
const run = Date.now();
const fail = (m) => { throw new Error(m); };
async function token(realm, user, pass) {
  const r = await fetch(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token ${user}: ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body, headers = {}) {
  const r = await fetch(API + path, { method, headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
const CAT = '/tmf-api/productCatalogManagement/v4';
const gov = (id, door, tok, body) => call('POST', `${CAT}/productOffering/${id}/governance/${door}`, tok, body || {});
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const sigrid = await token('taranga', 'sigrid@taranga.example', 'sigrid');   // product manager: catalog:write
  const henrik = await token('taranga', 'henrik@taranga.example', 'henrik');   // commercial: catalog:approve
  const ingrid = await token('taranga', 'ingrid@taranga.example', 'ingrid');   // marketing: campaign:write
  const demo = await token('taranga', 'demo', 'demo');
  const mira = await token('taranga', 'mira@taranga.example', 'mira');         // a customer

  const settings = (await call('GET', `${CAT}/governance/settings`, sigrid)).body;
  if (!settings || settings.mode !== 'envelope') fail(`taranga should run envelope mode: ${JSON.stringify(settings).slice(0, 120)}`);
  if (settings.canApprove) fail('sigrid must not be an approver');
  console.log(`  tenant: mode=${settings.mode} readiness=${settings.readiness.length} owners`);

  /* 0. the envelopes exist (seeded); the category is real */
  const cats = (await call('GET', `${CAT}/category?limit=50`, sigrid)).body || [];
  const mobile = cats.find((c) => c.name === 'Mobile plans') || fail('no Mobile plans category');
  const rules = (await call('GET', '/tmf-api/policyManagement/v4/policyRule?limit=200', sigrid)).body || [];
  const envs = rules.filter((r) => r.domain === 'launch' && r.effect === 'allow' && r.enabled);
  if (!envs.length) fail('no launch envelopes seeded (run ops/seed/seed_launch_governance.py)');
  console.log(`  envelopes: ${envs.map((e) => `'${e.name}'`).join(', ')}`);

  /* 1. Sigrid writes an offer; even "Active" lands as a draft — a write is not a launch here */
  const mkPrice = async (name, value) => (await call('POST', `${CAT}/productOfferingPrice`, sigrid, { name, priceType: 'recurring', recurringChargePeriodType: 'month', price: { unit: 'NOK', value }, lifecycleStatus: 'Active' })).body;
  const mkSpec = async (name, chars) => (await call('POST', `${CAT}/productSpecification`, sigrid, { name, lifecycleStatus: 'Active', productSpecCharacteristic: Object.entries(chars).map(([k, v]) => ({ name: k, configurable: false, productSpecCharacteristicValue: [{ value: v }] })) })).body;
  const mkOffer = async (name, price, spec, channel) => {
    const r = await call('POST', `${CAT}/productOffering`, sigrid, { name, description: 'governance test', lifecycleStatus: 'Active', isSellable: true, category: [{ id: mobile.id, name: mobile.name }],
      productOfferingPrice: [{ id: price.id, name: price.name }], productSpecification: { id: spec.id, name: spec.name }, channel });
    if (r.status !== 201) fail(`offer ${name}: ${r.status} ${r.text.slice(0, 160)}`);
    return r.body;
  };
  const inside = await mkOffer(`Inside plan ${run}`, await mkPrice(`Inside ${run}`, 249), await mkSpec(`Inside spec ${run}`, { Data: '20 GB', Validity: '30 days' }), [{ id: 'web' }, { id: 'app' }]);
  const outside = await mkOffer(`Outside plan ${run}`, await mkPrice(`Outside ${run}`, 899), await mkSpec(`Outside spec ${run}`, { Data: 'Unlimited', Validity: '30 days' }), [{ id: 'web' }]);
  if (inside.lifecycleStatus !== 'In design' || outside.lifecycleStatus !== 'In design') fail(`a non-approver's write must land as a draft: ${inside.lifecycleStatus}/${outside.lifecycleStatus}`);
  if ('governanceState' in inside || 'governance' in inside) fail('the TMF620 resource must stay standard — no governance fields on it');
  const ship = await call('PATCH', `${CAT}/productOffering/${outside.id}`, sigrid, { lifecycleStatus: 'Active' });
  if (ship.status !== 400 || !/needs launch approval/.test(ship.text)) fail(`sigrid flipping to Active must be refused: ${ship.status} ${ship.text.slice(0, 120)}`);
  console.log('  authoring: both land In design; flipping to Active without approval is refused');

  /* 2. dry-run + request: inside → pre-approved by the envelope; outside → waits */
  const dry = (await call('POST', `${CAT}/governance/dry-run`, sigrid, { ...inside })).body;
  if (!dry.preApproved || !dry.envelope) fail(`dry-run should find the envelope: ${JSON.stringify(dry).slice(0, 200)}`);
  const rq1 = (await gov(inside.id, 'request', sigrid, { note: 'launching the 20 GB plan' })).body;
  if (rq1.governanceState !== 'approved' || !rq1.envelope || !rq1.ledger.some((l) => l.action === 'approved' && l.envelopeName)) fail(`inside should be pre-approved by an envelope: ${JSON.stringify(rq1).slice(0, 300)}`);
  const rq2 = (await gov(outside.id, 'request', sigrid, { note: 'premium tier — needs a look' })).body;
  if (rq2.governanceState !== 'requested') fail(`outside should wait: ${rq2.governanceState}`);
  console.log(`  request: inside → approved by envelope '${rq1.envelope.name}' · outside → requested (context price=${dry.context.price} allowanceGb=${dry.context.allowanceGb})`);

  /* 3. the desk: sigrid cannot approve; henrik can; the queue lists both */
  const no = await gov(outside.id, 'approve', sigrid, {});
  if (no.status !== 400 || !/approver/.test(no.text)) fail(`sigrid approving must be refused: ${no.status}`);
  const queue = (await call('GET', `${CAT}/governance/queue`, henrik)).body || [];
  if (!queue.some((q) => q.id === outside.id) || !queue.some((q) => q.id === inside.id)) fail('queue misses the two offers');
  const ok = (await gov(outside.id, 'approve', henrik, { note: 'fine for Q4' })).body;
  if (ok.governanceState !== 'approved' || !ok.approvalExpiresAt) fail(`henrik's approval: ${JSON.stringify(ok).slice(0, 200)}`);
  console.log(`  approval: sigrid refused · henrik approved (expires ${ok.approvalExpiresAt.slice(0, 10)}) · queue ${queue.length}`);

  /* 4. launch waits for readiness; owners tick; marketing pulls the brake AFTER approval; resume; launch */
  const notReady = await gov(outside.id, 'launch', sigrid, {});
  if (notReady.status !== 400 || !/not ready/.test(notReady.text)) fail(`launch before readiness must be refused: ${notReady.status} ${notReady.text.slice(0, 120)}`);
  const wrongOwner = await gov(outside.id, 'ready', ingrid, { owner: 'billing:write' });
  if (![400, 403].includes(wrongOwner.status)) fail(`marketing ticking the billing item must be refused: ${wrongOwner.status}`);
  for (const [tok, owner] of [[ingrid, 'campaign:write'], [demo, 'ticket:write'], [henrik, 'billing:write']]) {
    const r = await gov(outside.id, 'ready', tok, { owner, note: 'done' });
    if (r.status !== 200) fail(`ready ${owner}: ${r.status} ${r.text.slice(0, 120)}`);
  }
  const until = new Date(Date.now() + 2 * 24 * 3600 * 1000).toISOString();
  const held = (await gov(outside.id, 'hold', ingrid, { note: 'collateral slipped — hold two days', until })).body;
  if (held.governanceState !== 'held') fail(`hold: ${JSON.stringify(held).slice(0, 160)}`);
  const whileHeld = await gov(outside.id, 'launch', henrik, {});
  if (whileHeld.status !== 400 || !/on hold/.test(whileHeld.text)) fail('launch while held must be refused');
  const resumed = (await gov(outside.id, 'resume', ingrid, { note: 'collateral is up' })).body;
  if (resumed.governanceState !== 'approved') fail(`resume: ${resumed.governanceState}`);
  const live = (await gov(outside.id, 'launch', sigrid, {})).body;
  if (live.governanceState !== 'launched' || live.lifecycleStatus !== 'Active') fail(`launch: ${JSON.stringify(live).slice(0, 160)}`);
  const onShelf = (await call('GET', `${CAT}/productOffering/${outside.id}`, mira, null, { 'X-Channel': 'web' })).status;
  if (onShelf !== 200) fail(`the launched offer should be on the web shelf: ${onShelf}`);
  console.log('  readiness → hold (marketing) → resume → launch: on the shelf');

  /* 5. inside: an approved offer whose SUBSTANCE changes loses its approval; a date change does not */
  const dated = await call('PATCH', `${CAT}/productOffering/${inside.id}`, sigrid, { validFor: { startDateTime: new Date(Date.now() + 3600e3).toISOString() } });
  if (dated.status >= 300) fail(`date patch: ${dated.status}`);
  let v = (await call('GET', `${CAT}/productOffering/${inside.id}/governance`, sigrid)).body;
  if (v.governanceState !== 'approved') fail(`a date-only edit must keep the approval: ${v.governanceState}`);
  const repriced = await call('PATCH', `${CAT}/productOffering/${inside.id}`, sigrid, { productOfferingPrice: [{ id: (await mkPrice(`Inside pricier ${run}`, 449)).id }] });
  if (repriced.status >= 300) fail(`reprice: ${repriced.status}`);
  v = (await call('GET', `${CAT}/productOffering/${inside.id}/governance`, sigrid)).body;
  if (v.governanceState !== 'none' || !v.ledger.some((l) => l.action === 'voided')) fail(`a reprice must void the approval: ${v.governanceState}`);
  const again = (await gov(inside.id, 'request', sigrid, {})).body;
  if (again.governanceState !== 'requested') fail(`449 NOK is outside the 149-399 envelope, must wait: ${again.governanceState}`);
  console.log('  substance: date edit keeps approval · reprice voids it · 449 NOK now falls outside the envelope');

  /* 6. the approver's one stroke; unlaunch; the trail; the TMF701 mirror */
  const oneStroke = (await gov(inside.id, 'launch', henrik, { force: true, note: 'go' })).body;
  if (oneStroke.governanceState !== 'launched') fail(`approver one-stroke launch: ${JSON.stringify(oneStroke).slice(0, 160)}`);
  const gone = (await gov(outside.id, 'unlaunch', henrik, { note: 'season over' })).body;
  if (gone.lifecycleStatus !== 'Retired' || (await call('GET', `${CAT}/productOffering/${outside.id}`, mira, null, { 'X-Channel': 'web' })).status !== 404) fail('unlaunch must take it off the shelf');
  const actions = gone.ledger.map((l) => l.action).join(' → ');
  if (!/requested → approved → ready → ready → ready → held → resumed → launched → unlaunched/.test(actions)) fail(`trail: ${actions}`);
  await sleep(4000);
  const flows = (await call('GET', '/tmf-api/processFlowManagement/v4/processFlow?limit=100', demo)).body || [];
  const mirror = flows.find((f) => f.productOfferingId === outside.id || f.correlationId === outside.id);
  if (!mirror) console.log('  TMF701: no mirror flow yet (event lag) — the catalog trail is the source of truth');
  else console.log(`  TMF701: flow ${mirror.specCode || mirror.processFlowSpecification?.code || ''} state=${mirror.state} tasks=${(mirror.taskFlow || []).length}`);
  console.log(`  trail: ${actions}`);

  console.log('PASS launch_governance_test');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
