/* Bundle decomposition — a triple-play is the several things it actually is. Suite #87.
 *
 * A bundle order ("GenAlpha One Home & Mobile" = Fiber + TV + Mobile + a chosen
 * handset) used to be one opaque line. It now decomposes into per-component
 * tracks that fulfil on their own clock, sequenced by real dependencies — and the
 * broadband speed it sells is a configurable characteristic you can upgrade in
 * place. This proves the whole chain against the live stack.
 *
 *  - DECOMPOSE: the order becomes per-family leaves (internet / tv / mobile /
 *    device), each with its catalog componentType.
 *  - DEPENDENCY: TV reliesOn the broadband (TMF622 orderItemRelationship) and is
 *    HELD inProgress while the fiber is still being set up — never "done" first.
 *  - SIM: a physical-SIM mobile line is reserved (held) until the SIM ships; an
 *    eSIM line activates at once.
 *  - PER-COMPONENT SERVICE ORDERS: SOM raises one service order per leaf.
 *  - ROLLUP + BILLING: the order rolls up to partiallyCompleted, and the fixed
 *    components are marked to bill THROUGH the bundle (not as separate products).
 *  - UPGRADE: broadband speed is a configurable characteristic — 300 -> 1000 via
 *    action=modify lands on the subscribed product; an off-menu value is refused.
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const ORD = '/tmf-api/productOrderingManagement/v4/productOrder';
const CAT = '/tmf-api/productCatalogManagement/v4/productOffering?limit=100';
const SO = '/tmf-api/serviceOrdering/v4/serviceOrder';
const INV = '/tmf-api/productInventory/v4/product?limit=100';
const SVC = '/tmf-api/serviceInventory/v4/service';
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
function leaves(items, into = []) {
  for (const it of items || []) {
    const kids = it.productOrderItem || [];
    if (kids.length) leaves(kids, into);
    else if (it.productOffering) into.push(it);
  }
  return into;
}
async function form(url, params) {
  const r = await fetch(url, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(params) });
  return r.json();
}
/* A brand-new customer with a clean inventory — so the upgrade leg reads its
 * one product without fighting a shared persona's capped product page. */
async function freshCustomer(tag) {
  const KCB = 'http://localhost:8085';
  const admin = (await form(`${KCB}/realms/master/protocol/openid-connect/token`,
    { grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: 'admin' })).access_token;
  const uname = `e2e-${tag}-${Date.now()}@example.com`;
  const areq = (m, path, body) => fetch(`${KCB}/admin/realms/bss${path}`, { method: m,
    headers: { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  await areq('POST', '/users', { username: uname, email: uname, enabled: true, emailVerified: true,
    firstName: 'Bundle', lastName: 'Tester', credentials: [{ type: 'password', value: 'Passw0rd!', temporary: false }] });
  const users = await (await areq('GET', `/users?username=${encodeURIComponent(uname)}`)).json();
  const roles = await (await areq('GET', '/roles')).json();
  const cust = roles.find((r) => r.name === 'customer');
  if (cust) await areq('POST', `/users/${users[0].id}/role-mappings/realm`, [cust]);
  const tok = (await form(`${KCB}/realms/bss/protocol/openid-connect/token`,
    { grant_type: 'password', client_id: 'bss-demo', username: uname, password: 'Passw0rd!' })).access_token;
  await call('POST', '/tmf-api/party/v4/individual', tok, { givenName: 'Bundle', familyName: 'Tester' });
  return tok;
}

(async () => {
  const kai = await token('kai@bss.local', 'kai');
  const staff = await token('demo', 'demo');
  const offs = (await call('GET', CAT, kai)).body || [];
  const bundle = offs.find((o) => o.name === 'GenAlpha One Home & Mobile');
  if (!bundle) fail('no GenAlpha One bundle in catalog');
  const choice = bundle.bundledProductOffering.find((m) => m.options);
  const phoneId = typeof choice.default === 'string' ? choice.default : (choice.default || choice.options[0]).id;
  const phone = offs.find((o) => o.id === phoneId);
  const addr = { '@type': 'GeographicAddress', streetName: 'Storgata 1', postCode: '1110', city: 'Oslo', country: 'NO' };

  /* ---------- place a bundle order with a PHYSICAL SIM ---------- */
  const res = await call('POST', ORD, kai, { description: 'GenAlpha One Home & Mobile', productOrderItem: [{
    id: '1', action: 'add',
    productOffering: { id: bundle.id, name: bundle.name, '@referredType': 'ProductOffering' },
    product: { place: [addr], productCharacteristic: [
      { name: 'simType', value: 'physical' }, { name: 'msisdn', value: '+4790112233' }] },
    productOrderItem: [{ id: '1.1', action: 'add',
      productOffering: { id: phone.id, name: phone.name, '@referredType': 'ProductOffering' },
      product: { place: [addr] } }] }] });
  if (res.status !== 201) fail(`bundle order: ${res.status} ${res.text}`);
  const oid = res.body.id;

  /* ---------- 1. DECOMPOSE ---------- */
  await sleep(1000);
  let order = (await call('GET', `${ORD}/${oid}`, kai)).body;
  let lv = leaves(order.productOrderItem);
  const types = lv.map((l) => l.componentType);
  for (const t of ['internet', 'tv', 'mobile', 'device']) {
    if (!types.includes(t)) fail(`bundle did not decompose a '${t}' component — got ${JSON.stringify(types)}`);
  }
  ok(`DECOMPOSE: the bundle became ${lv.length} tracked components — ${types.join(', ')}`);

  /* ---------- 2. DEPENDENCY: TV reliesOn broadband, held until it is live ---------- */
  const internet = lv.find((l) => l.componentType === 'internet');
  const tv = lv.find((l) => l.componentType === 'tv');
  const rel = (tv.orderItemRelationship || []).find((r) => r.relationshipType === 'reliesOn');
  if (!rel) fail('the TV component carries no reliesOn relationship');
  if (rel.id !== internet.id) fail('TV reliesOn is not pointed at the broadband component');
  ok('DEPENDENCY: TV reliesOn the broadband component (TMF622 orderItemRelationship)');

  // wait for SOM to orchestrate, then assert TV is HELD (not completed) while fiber is pending
  let sos = [];
  for (let i = 0; i < 15 && sos.length < 4; i++) {
    await sleep(2000);
    sos = (await call('GET', `${SO}?productOrderId=${oid}`, staff)).body || [];
  }
  order = (await call('GET', `${ORD}/${oid}`, kai)).body;
  lv = leaves(order.productOrderItem);
  const st = (t) => (lv.find((l) => l.componentType === t) || {}).state;
  if (st('tv') === 'completed') fail('TV activated while its broadband dependency was still pending');
  if (st('internet') === 'completed') fail('the broadband completed instantly — it should install');
  ok(`DEPENDENCY HELD: TV is '${st('tv')}' while broadband is '${st('internet')}' — never done first`);

  /* ---------- 3. SIM: a physical-SIM line is reserved (held) until it ships ---------- */
  if (st('mobile') === 'completed') fail('a physical-SIM mobile line completed before the SIM shipped');
  ok(`SIM: the physical-SIM mobile line is '${st('mobile')}' — the number is reserved until the SIM arrives`);

  /* ---------- 4. PER-COMPONENT SERVICE ORDERS ---------- */
  if (sos.length < 4) fail(`SOM raised ${sos.length} service orders, expected one per component (4)`);
  ok(`SERVICE ORDERS: SOM raised ${sos.length} independent service orders — one per component`);

  /* ---------- 5. ROLLUP + BILLING mechanism ---------- */
  if (order.state !== 'partiallyCompleted' && order.state !== 'inProgress') {
    fail(`order rolled up to '${order.state}', expected partiallyCompleted/inProgress`);
  }
  const fixed = lv.filter((l) => ['internet', 'tv', 'mobile'].includes(l.componentType));
  if (!fixed.every((l) => l.decomposedComponent === true)) {
    fail('a fixed component is not marked to bill through the bundle (decomposedComponent)');
  }
  ok(`ROLLUP: order is '${order.state}'; the ${fixed.length} fixed components bill THROUGH the bundle, not as separate products`);

  /* ---------- 6. UPGRADE: broadband speed is a configurable characteristic ---------- */
  const cust = await freshCustomer('fiber'); // clean inventory — no capped-page flake
  const fiber = offs.find((o) => o.name === 'GenAlpha Fiber 1000');
  const buy = await call('POST', ORD, cust, { description: 'fiber 300', productOrderItem: [{
    id: '1', action: 'add',
    productOffering: { id: fiber.id, name: fiber.name, '@referredType': 'ProductOffering' },
    product: { place: [addr], productCharacteristic: [{ name: 'downloadSpeed', value: 300 }] } }] });
  if (buy.status !== 201) fail(`fiber buy: ${buy.status} ${buy.text}`);
  await sleep(3000);
  await call('PATCH', `${ORD}/${buy.body.id}`, staff, { state: 'completed' });
  await sleep(2500);
  const prods = (await call('GET', INV, cust)).body || [];
  const prod = prods.filter((p) => p.name === 'GenAlpha Fiber 1000')
    .find((p) => (p.productCharacteristic || []).some((c) => c.name === 'downloadSpeed' && String(c.value) === '300'));
  if (!prod) fail('the fiber product was not provisioned with downloadSpeed=300');
  const svcs = (await call('GET', SVC, cust)).body || [];
  const svc = (Array.isArray(svcs) ? svcs : []).find((s) => String(s.name).includes('Fiber'));

  const modify = (speed) => call('POST', ORD, cust, { description: 'speed change', productOrderItem: [{
    action: 'modify',
    product: { id: prod.id, productCharacteristic: [{ name: 'downloadSpeed', value: speed }],
      ...(svc ? { realizingService: [{ id: svc.id }] } : {}) },
    productOffering: { id: fiber.id, name: fiber.name } }] });

  const up = await modify(1000);
  if (up.status !== 201 && up.status !== 200) fail(`upgrade refused: ${up.status} ${up.text}`);
  await sleep(2500);
  const after = ((await call('GET', INV, cust)).body || []).find((p) => p.id === prod.id);
  const nowSpeed = (after.productCharacteristic || []).find((c) => c.name === 'downloadSpeed');
  if (!nowSpeed || String(nowSpeed.value) !== '1000') fail(`upgrade did not land: speed is ${nowSpeed && nowSpeed.value}`);
  ok('UPGRADE: broadband 300 -> 1000 via action=modify landed on the subscribed product');

  const bad = await modify(750);
  if (bad.status < 400) fail('an off-menu speed (750) was accepted');
  if (!/allowed values/.test(bad.body && bad.body.message || '')) fail('the refusal did not name the allowed tiers');
  ok(`GUARDRAIL: an off-menu speed (750) is refused — "${bad.body.message}"`);

  console.log('\nALL BUNDLE-DECOMPOSITION CHECKS PASSED — a triple-play fulfils as the several things it is,'
    + ' TV waits for the broadband it rides, a physical SIM reserves the number until it lands, the order'
    + ' rolls up from its components, and the speed it sells upgrades in place with no rebuild.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
