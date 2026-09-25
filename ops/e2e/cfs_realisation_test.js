/* The catalog declares, the orchestrator realises, and the two must agree. Suite #229.
 *
 * Catalog-to-provisioning step 2. A customer-facing service now lists the
 * resource-facing services it needs (each naming a TMF634 resource spec and
 * carrying a `required` flag on the edge); at order time the orchestrator
 * records what it actually realised on the service (TMF638 supportingService)
 * and references the resource spec from every issued resource (TMF639); the
 * live gate ops/arch/cfs_check.py goes red when the two disagree. This proves
 * the whole loop against the live stack with fixtures authored through the API:
 *
 *  - OBEYS (step 3): a mobile line whose CFS declares only the number RFS is
 *    ordered; its service lists the number realisation matched to the RFS,
 *    with vendor and the drawn number, the issued number references the
 *    resource spec — and NO SIM is provisioned, because none was declared.
 *  - OBEYS FORWARD: declaring the SIM RFS makes the next order provision one.
 *  - GATE BITES: a REQUIRED RFS on a seam the environment never calls for at
 *    order time (equipment) is never realised; cfs_check.py exits 2 naming it.
 *  - RECONCILED: marking that RFS optional turns the gate green again — the
 *    fix is catalog data, not code.
 *  - GATE CLEAN after the fixtures are gone (their services stay; a service
 *    whose CFS is gone carries no evidence and is skipped, counted).
 */
const { execFileSync } = require('node:child_process');
const path = require('node:path');

const API = 'http://localhost:8080';
const KCB = 'http://localhost:8085';
const KC = `${KCB}/realms/bss/protocol/openid-connect/token`;
const CAT = '/tmf-api/productCatalogManagement/v4';
const SCAT = '/tmf-api/serviceCatalogManagement/v4';
const RCAT = '/tmf-api/resourceCatalogManagement/v4';
const ORD = '/tmf-api/productOrderingManagement/v4/productOrder';
const SO = '/tmf-api/serviceOrdering/v4/serviceOrder';
const SVC = '/tmf-api/serviceInventory/v4/service';
const RES = '/tmf-api/resourceInventoryManagement/v4/resource';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const run = Date.now();
const tag = `SUITE${run}`;

async function form(url, params) {
  const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(params) });
  return r.json();
}
async function token(user, pass) {
  const j = await form(KC, { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass });
  if (!j.access_token) fail(`token(${user}) refused`);
  return j.access_token;
}
async function call(method, p, tok, body) {
  const r = await fetch(API + p, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}), 'Cache-Control': 'no-cache' },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
async function created(method, p, tok, body) {
  const r = await call(method, p, tok, body);
  if (r.status !== 201 && r.status !== 200) fail(`${method} ${p}: ${r.status} ${r.text.slice(0, 200)}`);
  return r.body;
}
async function freshCustomer() {
  const admin = (await form(`${KCB}/realms/master/protocol/openid-connect/token`, { grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: 'admin' })).access_token;
  const uname = `e2e-rfs-${run}@example.com`;
  const areq = (m, p, body) => fetch(`${KCB}/admin/realms/bss${p}`, { method: m, headers: { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}) });
  await areq('POST', '/users', { username: uname, email: uname, enabled: true, emailVerified: true, firstName: 'Rfs', lastName: 'Tester', credentials: [{ type: 'password', value: 'Passw0rd!', temporary: false }] });
  const users = await (await areq('GET', `/users?username=${encodeURIComponent(uname)}`)).json();
  const roles = await (await areq('GET', '/roles')).json();
  const cust = roles.find((r) => r.name === 'customer');
  if (cust) await areq('POST', `/users/${users[0].id}/role-mappings/realm`, [cust]);
  const tok = (await form(KC, { grant_type: 'password', client_id: 'bss-demo', username: uname, password: 'Passw0rd!' })).access_token;
  await call('POST', '/tmf-api/party/v4/individual', tok, { givenName: 'Rfs', familyName: 'Tester' });
  return tok;
}
function gate() {
  try { return { code: 0, out: execFileSync('python3', [path.join(__dirname, '..', 'arch', 'cfs_check.py')], { encoding: 'utf8' }) }; }
  catch (e) { return { code: e.status, out: `${e.stdout || ''}${e.stderr || ''}` }; }
}
const findings = (out) => out.split('\n').filter((l) => l.startsWith('  - '));
async function serviceOrderFor(orderId, staff) {
  for (let i = 0; i < 20; i++) {
    const sos = (await call('GET', `${SO}?productOrderId=${orderId}`, staff)).body || [];
    if (sos.length) return sos[0];
    await sleep(1500);
  }
  fail(`no service order for product order ${orderId} in 30s`);
}
async function serviceFor(soId, cust) {
  for (let i = 0; i < 10; i++) {
    const s = ((await call('GET', `${SVC}?limit=100`, cust)).body || []).find((x) => x.serviceOrderId === soId);
    if (s) return s;
    await sleep(1000);
  }
  fail(`no inventory row for service order ${soId}`);
}
const seamChar = (seam) => ({ name: 'seam', valueType: 'string', configurable: false, serviceSpecCharacteristicValue: [{ value: seam, isDefault: true }] });
const edge = (rfs, required) => ({ id: rfs.id, href: rfs.href, name: rfs.name, '@referredType': 'ServiceSpecification', relationshipType: 'reliesOn',
  serviceSpecRelationshipCharacteristic: [{ name: 'required', valueType: 'boolean', serviceSpecCharacteristicValue: [{ value: String(required) }] }] });

(async () => {
  const staff = await token('demo', 'demo');
  const ref = (e, t) => ({ id: e.id, href: e.href, name: e.name, '@referredType': t });
  const mobile = ((await call('GET', `${CAT}/category?limit=100`, staff)).body || []).find((c) => c.name === 'Mobile plans');
  if (!mobile) fail('no "Mobile plans" category — run the catalog seeds first');

  /* ---------- the chain, authored through the API ---------- */
  const rsNumber = await created('POST', `${RCAT}/resourceSpecification`, staff, { name: `${tag} Mobile number`, lifecycleStatus: 'Active', version: '1.0', category: 'seam',
    resourceSpecCharacteristic: [{ name: 'seam', valueType: 'string', configurable: false, resourceSpecCharacteristicValue: [{ value: 'number', isDefault: true }] }] });
  const rfsNumber = await created('POST', `${SCAT}/serviceSpecification`, staff, { name: `${tag} Number assignment`, serviceType: 'RFS', lifecycleStatus: 'Active', version: '1.0',
    serviceSpecCharacteristic: [seamChar('number')], resourceSpecification: [ref(rsNumber, 'ResourceSpecification')] });
  const cfs = await created('POST', `${SCAT}/serviceSpecification`, staff, { name: `${tag} Mobile line`, serviceType: 'CFS', lifecycleStatus: 'Active', version: '1.0',
    serviceSpecCharacteristic: [{ name: 'fulfilmentFamily', valueType: 'string', configurable: false, serviceSpecCharacteristicValue: [{ value: 'mobile', isDefault: true }] }],
    serviceSpecRelationship: [edge(rfsNumber, true)] });
  const spec = await created('POST', `${CAT}/productSpecification`, staff, { name: `${tag} line spec`, brand: 'GenAlpha', lifecycleStatus: 'Active', serviceSpecification: [ref(cfs, 'ServiceSpecification')] });
  const price = await created('POST', `${CAT}/productOfferingPrice`, staff, { name: `${tag} monthly`, priceType: 'recurring', price: { unit: 'NOK', value: 199 },
    recurringChargePeriodType: 'month', recurringChargePeriodLength: 1, lifecycleStatus: 'Active', version: '1.0' });
  const offering = await created('POST', `${CAT}/productOffering`, staff, { name: `${tag} Rfs Line`, description: 'suite fixture', lifecycleStatus: 'Active', version: '1.0', isBundle: false, isSellable: true,
    category: [{ id: mobile.id, name: mobile.name }], productSpecification: ref(spec, 'ProductSpecification'), productOfferingPrice: [ref(price, 'ProductOfferingPrice')] });
  ok(`AUTHORED: CFS "${cfs.name}" declares one required RFS (number → "${rsNumber.name}"); an offering sells it`);

  /* ---------- order it ---------- */
  const cust = await freshCustomer();
  const order = await call('POST', ORD, cust, { description: offering.name, productOrderItem: [{ id: '1', action: 'add', productOffering: { id: offering.id, name: offering.name, '@referredType': 'ProductOffering' } }] });
  if (order.status !== 201) fail(`order: ${order.status} ${order.text.slice(0, 200)}`);
  const so = await serviceOrderFor(order.body.id, staff);
  const svc = await serviceFor(so.id, cust);
  const realisations = (svc.supportingService || []).filter((x) => x.seam);
  const number = realisations.find((x) => x.seam === 'number');
  if (!number) fail(`no number realisation on the service: ${JSON.stringify(svc.supportingService)}`);
  if (number.id !== rfsNumber.id || number.declared !== true) fail(`the number realisation is not matched to the declared RFS: ${JSON.stringify(number)}`);
  if (!number.vendor || !number.externalRef) fail(`the number realisation names no vendor or number: ${JSON.stringify(number)}`);
  // STEP 3: the orchestrator OBEYS the CFS — only the number RFS is declared, so no SIM is provisioned
  const sim = realisations.find((x) => x.seam === 'sim');
  if (sim) fail(`the CFS declares no SIM RFS, yet a SIM was provisioned: ${JSON.stringify(realisations)}`);
  if (realisations.some((x) => x.declared === false)) fail(`a CFS-bearing spec can no longer realise an undeclared seam: ${JSON.stringify(realisations)}`);
  ok(`OBEYS: TMF638 lists ${realisations.length} realisation — number matched to "${rfsNumber.name}" (vendor ${number.vendor}, ${number.externalRef}); no SIM, because none was declared`);

  /* ---------- TMF639: the issued number knows its resource spec ---------- */
  const resources = (await call('GET', `${RES}?serviceId=${svc.id}`, staff)).body || [];
  const issued = resources.find((r) => r.resourceSpecification && r.resourceSpecification.id === rsNumber.id);
  if (!issued) fail(`no issued resource references the resource spec ${rsNumber.id}: ${JSON.stringify(resources).slice(0, 400)}`);
  ok(`RESOURCE: TMF639 resource "${issued.name || issued.value || issued.id}" references resource spec "${rsNumber.name}"`);

  /* ---------- declare more: the SIM RFS (required) and an RFS on a seam the environment never calls for at order time (cpe, required) ---------- */
  const rsSim = await created('POST', `${RCAT}/resourceSpecification`, staff, { name: `${tag} SIM profile`, lifecycleStatus: 'Active', version: '1.0', category: 'seam',
    resourceSpecCharacteristic: [{ name: 'seam', valueType: 'string', configurable: false, resourceSpecCharacteristicValue: [{ value: 'sim', isDefault: true }] }] });
  const rfsSim = await created('POST', `${SCAT}/serviceSpecification`, staff, { name: `${tag} SIM provisioning`, serviceType: 'RFS', lifecycleStatus: 'Active', version: '1.0',
    serviceSpecCharacteristic: [seamChar('sim')], resourceSpecification: [ref(rsSim, 'ResourceSpecification')] });
  const rsCpe = await created('POST', `${RCAT}/resourceSpecification`, staff, { name: `${tag} Customer premises equipment`, lifecycleStatus: 'Active', version: '1.0', category: 'seam',
    resourceSpecCharacteristic: [{ name: 'seam', valueType: 'string', configurable: false, resourceSpecCharacteristicValue: [{ value: 'cpe', isDefault: true }] }] });
  const rfsCpe = await created('POST', `${SCAT}/serviceSpecification`, staff, { name: `${tag} Equipment management`, serviceType: 'RFS', lifecycleStatus: 'Active', version: '1.0',
    serviceSpecCharacteristic: [seamChar('cpe')], resourceSpecification: [ref(rsCpe, 'ResourceSpecification')] });
  await created('PATCH', `${SCAT}/serviceSpecification/${cfs.id}`, staff, { serviceSpecRelationship: [edge(rfsNumber, true), edge(rfsSim, true), edge(rfsCpe, true)] });
  // the orchestrator caches the chain for a short while (RestCatalogClient.CATALOG_TTL_MS = 10 s); an edit reaches the next order after that
  await sleep(11000);
  const order2 = await call('POST', ORD, cust, { description: offering.name, productOrderItem: [{ id: '1', action: 'add', productOffering: { id: offering.id, name: offering.name, '@referredType': 'ProductOffering' } }] });
  if (order2.status !== 201) fail(`second order: ${order2.status} ${order2.text.slice(0, 200)}`);
  const so2 = await serviceOrderFor(order2.body.id, staff);
  const svc2 = await serviceFor(so2.id, cust);
  const seams2 = (svc2.supportingService || []).filter((x) => x.seam).map((x) => x.seam).sort();
  if (!seams2.includes('sim') || !seams2.includes('number')) fail(`declaring the SIM RFS did not make the orchestrator provision a SIM: ${seams2}`);
  if (seams2.includes('cpe')) fail('equipment is never provisioned at order time, yet a cpe realisation appeared');
  ok(`OBEYS FORWARD: with the SIM RFS declared the next order realises ${seams2.join(' + ')} — the catalog changed what the orchestrator does, no code did`);

  /* ---------- the gate sees the disagreement: a REQUIRED RFS no order ever realised ---------- */
  const red = gate();
  if (red.code !== 2) fail(`cfs_check.py should exit 2 on the required cpe RFS nobody realised; got ${red.code}\n${red.out}`);
  const mine = findings(red.out).filter((l) => l.includes(cfs.name));
  if (!mine.some((l) => l.includes("'cpe'") && l.includes('required'))) fail(`the gate did not name CFS "${cfs.name}" and the required cpe seam:\n${red.out}`);
  ok(`GATE BITES: cfs_check.py exits 2 — "${mine.find((l) => l.includes("'cpe'")).trim().slice(0, 130)}…"`);

  /* ---------- reconcile: the equipment RFS is optional — catalog data, not code ---------- */
  await created('PATCH', `${SCAT}/serviceSpecification/${cfs.id}`, staff, { serviceSpecRelationship: [edge(rfsNumber, true), edge(rfsSim, true), edge(rfsCpe, false)] });
  const green = gate();
  const still = findings(green.out).filter((l) => l.includes(cfs.name));
  if (still.length) fail(`the gate still blames "${cfs.name}" after the cpe RFS was made optional:\n${still.join('\n')}`);
  ok(`RECONCILED: marking the equipment RFS optional clears the finding for "${cfs.name}" (gate exit ${green.code}${green.code ? ', other findings belong to other CFS' : ''})`);

  /* ---------- leave the shelf as we found it ---------- */
  for (const [p, id] of [[`${CAT}/productOffering`, offering.id], [`${CAT}/productSpecification`, spec.id], [`${CAT}/productOfferingPrice`, price.id],
    [`${SCAT}/serviceSpecification`, cfs.id], [`${SCAT}/serviceSpecification`, rfsNumber.id], [`${SCAT}/serviceSpecification`, rfsSim.id], [`${SCAT}/serviceSpecification`, rfsCpe.id],
    [`${RCAT}/resourceSpecification`, rsNumber.id], [`${RCAT}/resourceSpecification`, rsSim.id], [`${RCAT}/resourceSpecification`, rsCpe.id]]) {
    await call('DELETE', `${p}/${id}`, staff);
  }
  const after = gate();
  if (findings(after.out).some((l) => l.includes(tag))) fail(`the gate still mentions this run's fixtures after cleanup:\n${after.out}`);
  ok(`GATE CLEAN OF THIS RUN: ${after.out.trim().split('\n').pop().slice(0, 160)}`);

  console.log('\nALL CFS-REALISATION CHECKS PASSED — the CFS declares its resource-facing services, the orchestrator records what it'
    + ' realised on the service and references the resource spec from the issued number, the live gate catches the seam the catalog'
    + ' forgot, and declaring it in the catalog is the fix.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
