/* The catalog is the decomposition — a CFS decides fulfilment. Suite #228.
 *
 * Catalog-to-provisioning step 1. Until now the service orchestrator decided
 * what an order item IS (a line, an install, an entitlement, a parcel) from the
 * offering's category string — a good table, but code. Now every sellable
 * product spec names a TMF633 customer-facing service (serviceSpecification[0]),
 * the CFS declares its fulfilment family, and the SOM reads THAT first. This
 * proves it against the live stack, with the category deliberately lying:
 *
 *  - CFS WINS: an offering filed under "Mobile plans" whose spec names a TV
 *    entitlement CFS is fulfilled as TV — no number drawn, and its TMF638
 *    inventory row points at the real CFS, not a derived stand-in.
 *  - FALLBACK: the same category with a spec that names no CFS is still a
 *    network line (a number is drawn), so an unfilled catalog changes nothing.
 *  - THE GATE BITES: while the CFS-less spec is sellable, ops/arch/cfs_check.py
 *    goes red and names it; once the fixtures are gone it is clean again.
 *
 * Fixtures are timestamp-named and deleted at the end (the debris sweep would
 * catch them if this suite died half-way).
 */
const { execFileSync } = require('node:child_process');
const path = require('node:path');

const API = 'http://localhost:8080';
const KCB = 'http://localhost:8085';
const KC = `${KCB}/realms/bss/protocol/openid-connect/token`;
const CAT = '/tmf-api/productCatalogManagement/v4';
const SCAT = '/tmf-api/serviceCatalogManagement/v4';
const ORD = '/tmf-api/productOrderingManagement/v4/productOrder';
const SO = '/tmf-api/serviceOrdering/v4/serviceOrder';
const SVC = '/tmf-api/serviceInventory/v4/service';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const run = Date.now();
const tag = `SUITE${run}`;

async function form(url, params) {
  const r = await fetch(url, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(params) });
  return r.json();
}
async function token(user, pass) {
  const j = await form(KC, { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass });
  if (!j.access_token) fail(`token(${user}) refused`);
  return j.access_token;
}
async function call(method, p, tok, body, headers = {}) {
  const r = await fetch(API + p, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}),
      'Cache-Control': 'no-cache', ...headers },
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
/* A brand-new customer with an empty inventory, so "the service this order
 * created" is unambiguous and no shared persona inherits our fixtures. */
async function freshCustomer() {
  const admin = (await form(`${KCB}/realms/master/protocol/openid-connect/token`,
    { grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: 'admin' })).access_token;
  const uname = `e2e-cfs-${run}@example.com`;
  const areq = (m, p, body) => fetch(`${KCB}/admin/realms/bss${p}`, { method: m,
    headers: { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  await areq('POST', '/users', { username: uname, email: uname, enabled: true, emailVerified: true,
    firstName: 'Cfs', lastName: 'Tester', credentials: [{ type: 'password', value: 'Passw0rd!', temporary: false }] });
  const users = await (await areq('GET', `/users?username=${encodeURIComponent(uname)}`)).json();
  const roles = await (await areq('GET', '/roles')).json();
  const cust = roles.find((r) => r.name === 'customer');
  if (cust) await areq('POST', `/users/${users[0].id}/role-mappings/realm`, [cust]);
  const tok = (await form(KC, { grant_type: 'password', client_id: 'bss-demo', username: uname, password: 'Passw0rd!' })).access_token;
  await call('POST', '/tmf-api/party/v4/individual', tok, { givenName: 'Cfs', familyName: 'Tester' });
  return tok;
}
/* the live gate, as CI runs it */
function gate() {
  try {
    return { code: 0, out: execFileSync('python3', [path.join(__dirname, '..', 'arch', 'cfs_check.py')], { encoding: 'utf8' }) };
  } catch (e) {
    return { code: e.status, out: `${e.stdout || ''}${e.stderr || ''}` };
  }
}
async function serviceOrderFor(orderId, staff) {
  for (let i = 0; i < 20; i++) {
    const sos = (await call('GET', `${SO}?productOrderId=${orderId}`, staff)).body || [];
    if (sos.length) return sos[0];
    await sleep(1500);
  }
  fail(`the SOM raised no service order for product order ${orderId} in 30s`);
}
async function serviceFor(soId, cust) {
  for (let i = 0; i < 10; i++) {
    const mine = (await call('GET', `${SVC}?limit=100`, cust)).body || [];
    const s = mine.find((x) => x.serviceOrderId === soId);
    if (s) return s;
    await sleep(1000);
  }
  fail(`no inventory row for service order ${soId}`);
}
const issued = (s) => (s.supportingResource || []).filter((r) => r['@referredType'] === 'Resource');
const characteristic = (s, name) => ((s.serviceCharacteristic || []).find((c) => c.name === name) || {}).value;

(async () => {
  const staff = await token('demo', 'demo');
  const ref = (e, t) => ({ id: e.id, href: e.href, name: e.name, '@referredType': t });
  const mobile = ((await call('GET', `${CAT}/category?limit=100`, staff)).body || []).find((c) => c.name === 'Mobile plans');
  if (!mobile) fail('no "Mobile plans" category — run the catalog seeds first');

  /* ---------- author the mapping IN THE CATALOG ---------- */
  const cfs = await created('POST', `${SCAT}/serviceSpecification`, staff, {
    name: `${tag} TV entitlement`, description: 'suite fixture: a digital entitlement, never a line',
    version: '1.0', lifecycleStatus: 'Active', serviceType: 'CFS', isBundle: false,
    serviceSpecCharacteristic: [{ name: 'fulfilmentFamily', valueType: 'string', configurable: false,
      serviceSpecCharacteristicValue: [{ value: 'tv', isDefault: true }] }] });
  const specWithCfs = await created('POST', `${CAT}/productSpecification`, staff, {
    name: `${tag} pass spec`, brand: 'GenAlpha', lifecycleStatus: 'Active',
    serviceSpecification: [ref(cfs, 'ServiceSpecification')] });
  const specPlain = await created('POST', `${CAT}/productSpecification`, staff, {
    name: `${tag} plain spec`, brand: 'GenAlpha', lifecycleStatus: 'Active' });
  const price = await created('POST', `${CAT}/productOfferingPrice`, staff, {
    name: `${tag} monthly`, priceType: 'recurring', price: { unit: 'NOK', value: 49 },
    recurringChargePeriodType: 'month', recurringChargePeriodLength: 1, lifecycleStatus: 'Active', version: '1.0' });
  const offering = (name, spec) => created('POST', `${CAT}/productOffering`, staff, {
    name, description: 'suite fixture', lifecycleStatus: 'Active', version: '1.0', isBundle: false, isSellable: true,
    category: [{ id: mobile.id, name: mobile.name }], // the category LIES: it says line
    productSpecification: ref(spec, 'ProductSpecification'),
    productOfferingPrice: [ref(price, 'ProductOfferingPrice')] });
  const cfsPass = await offering(`${tag} Cfs Pass`, specWithCfs);
  const plainPlan = await offering(`${tag} Plain Plan`, specPlain);
  const fixtures = { offerings: [cfsPass.id, plainPlan.id], specs: [specWithCfs.id, specPlain.id], cfs: cfs.id, price: price.id };
  ok(`AUTHORED: CFS "${cfs.name}" (fulfilmentFamily=tv); one spec names it, one names nothing; both sold under "${mobile.name}"`);

  /* ---------- the gate bites while a sellable spec names no CFS ---------- */
  const red = gate();
  if (red.code !== 2) fail(`cfs_check.py should exit 2 with a CFS-less sellable spec on the shelf; got ${red.code}\n${red.out}`);
  // the findings are the "  - <offering> [category] …" lines; a family WARNING
  // about the Cfs Pass (category says line, CFS says tv) is expected and not a finding
  const findings = red.out.split('\n').filter((l) => l.startsWith('  - '));
  if (!findings.some((l) => l.includes(`${tag} Plain Plan`))) fail(`cfs_check.py went red but did not name the offending offering:\n${red.out}`);
  if (findings.some((l) => l.includes(`${tag} Cfs Pass`))) fail('cfs_check.py counted the offering whose spec DOES name a CFS');
  if (!red.out.includes(`WARNING: ${tag} Cfs Pass`)) fail('cfs_check.py did not warn that the Cfs Pass\'s CFS overrides its category');
  ok('GATE BITES: cfs_check.py exits 2, counts the CFS-less offering only, and warns that the other one\'s CFS overrides its category');

  /* ---------- order both as a brand-new customer ---------- */
  const cust = await freshCustomer();
  const order = async (off) => {
    const r = await call('POST', ORD, cust, { description: off.name,
      productOrderItem: [{ id: '1', action: 'add', productOffering: { id: off.id, name: off.name, '@referredType': 'ProductOffering' } }] });
    if (r.status !== 201) fail(`order ${off.name}: ${r.status} ${r.text.slice(0, 200)}`);
    return r.body.id;
  };
  const oCfs = await order(cfsPass);
  const oPlain = await order(plainPlan);
  const soCfs = await serviceOrderFor(oCfs, staff);
  const soPlain = await serviceOrderFor(oPlain, staff);
  const sCfs = await serviceFor(soCfs.id, cust);
  const sPlain = await serviceFor(soPlain.id, cust);

  /* ---------- CFS WINS ---------- */
  if ((sCfs.serviceSpecification || {}).id !== cfs.id) {
    fail(`the CFS-mapped service points at spec ${JSON.stringify(sCfs.serviceSpecification)}, expected the real CFS ${cfs.id}`);
  }
  if (characteristic(sCfs, 'fulfilmentFamily') !== 'tv') fail(`fulfilmentFamily is ${characteristic(sCfs, 'fulfilmentFamily')}, expected tv`);
  if (issued(sCfs).length) fail(`a TV entitlement drew ${issued(sCfs).length} network resource(s): ${JSON.stringify(issued(sCfs))}`);
  ok(`CFS WINS: filed under "${mobile.name}", fulfilled as TV — no number drawn; TMF638 serviceSpecification = the CFS "${cfs.name}"`);

  /* ---------- FALLBACK ---------- */
  if ((sPlain.serviceSpecification || {}).id !== 'svcspec-service' && (sPlain.serviceSpecification || {}).id !== 'svcspec-mobile') {
    fail(`the CFS-less service should carry the derived stand-in spec, got ${JSON.stringify(sPlain.serviceSpecification)}`);
  }
  if (characteristic(sPlain, 'fulfilmentFamily')) fail('a spec that names no CFS must not claim a fulfilmentFamily');
  if (!issued(sPlain).length) fail('the CFS-less "Mobile plans" offering drew no number — the category fallback is gone');
  ok(`FALLBACK: a spec that names no CFS is still decomposed by category — a number (${issued(sPlain)[0].value}) was drawn`);

  /* ---------- leave the shelf as we found it, and the gate green ---------- */
  for (const id of fixtures.offerings) await call('DELETE', `${CAT}/productOffering/${id}`, staff);
  for (const id of fixtures.specs) await call('DELETE', `${CAT}/productSpecification/${id}`, staff);
  await call('DELETE', `${CAT}/productOfferingPrice/${fixtures.price}`, staff);
  await call('DELETE', `${SCAT}/serviceSpecification/${fixtures.cfs}`, staff);
  const green = gate();
  if (green.code !== 0) fail(`cfs_check.py is still red after the fixtures are gone:\n${green.out}`);
  ok(`GATE CLEAN: ${green.out.trim().split('\n').pop()}`);

  console.log('\nALL CFS-DECOMPOSITION CHECKS PASSED — the product spec names its customer-facing service,'
    + ' the CFS names the fulfilment family, the SOM obeys it over the category, the old table still'
    + ' covers a spec that names nothing, and the live gate counts every sellable spec without one.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
