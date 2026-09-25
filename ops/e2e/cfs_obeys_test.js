/* The catalog decides fulfilment: optional seams, the counted fallback, billing-only. Suite #230.
 *
 * Catalog-to-provisioning step 3, the acceptance suite beside #229 (which proves
 * that a declared RFS runs and an undeclared one does not). This one proves the
 * three rules that make the executor a data-driven thing rather than a table:
 *
 *  - OPTIONAL RULE: a charging RFS marked optional (consumes chargingSpecId) is
 *    skipped for a product that carries no charging plan, and runs — recorded
 *    as an `ocs` realisation — for a product that carries one. Same CFS, two
 *    products, the product decides.
 *  - COUNTED FALLBACK: a product spec that names no CFS is still fulfilled by
 *    the old category table, and the service says so with a realisation on
 *    seam `category-fallback`; `ratchet.sh --live` goes red on it (the debt
 *    may only fall) and green again once that service is ceased.
 *  - BILLING-ONLY: a product spec naming the seeded "Billing-only product" CFS
 *    creates no service and no service order; the order item completes so
 *    billing rates it. Nothing to provision is a catalog fact.
 *  - GATE CLEAN of this run after the fixtures are gone.
 *
 * Fixtures are timestamp-named and deleted at the end.
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
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const run = Date.now();
const tag = `SUITE${run}`;
const ARCH = path.join(__dirname, '..', 'arch');

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
  const uname = `e2e-obeys-${run}@example.com`;
  const areq = (m, p, body) => fetch(`${KCB}/admin/realms/bss${p}`, { method: m, headers: { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}) });
  await areq('POST', '/users', { username: uname, email: uname, enabled: true, emailVerified: true, firstName: 'Obeys', lastName: 'Tester', credentials: [{ type: 'password', value: 'Passw0rd!', temporary: false }] });
  const users = await (await areq('GET', `/users?username=${encodeURIComponent(uname)}`)).json();
  const roles = await (await areq('GET', '/roles')).json();
  const cust = roles.find((r) => r.name === 'customer');
  if (cust) await areq('POST', `/users/${users[0].id}/role-mappings/realm`, [cust]);
  const tok = (await form(KC, { grant_type: 'password', client_id: 'bss-demo', username: uname, password: 'Passw0rd!' })).access_token;
  await call('POST', '/tmf-api/party/v4/individual', tok, { givenName: 'Obeys', familyName: 'Tester' });
  return tok;
}
function script(file, args = []) {
  try { return { code: 0, out: execFileSync(file.endsWith('.py') ? 'python3' : 'bash', [path.join(ARCH, file), ...args], { encoding: 'utf8', cwd: path.join(__dirname, '..', '..') }) }; }
  catch (e) { return { code: e.status, out: `${e.stdout || ''}${e.stderr || ''}` }; }
}
const findings = (out) => out.split('\n').filter((l) => l.startsWith('  - '));
async function serviceOrderFor(orderId, staff, expect = true) {
  for (let i = 0; i < 20; i++) {
    const sos = (await call('GET', `${SO}?productOrderId=${orderId}`, staff)).body || [];
    if (sos.length) return sos[0];
    await sleep(1500);
  }
  if (expect) fail(`no service order for product order ${orderId} in 30s`);
  return null;
}
async function serviceFor(soId, cust) {
  for (let i = 0; i < 10; i++) {
    const s = ((await call('GET', `${SVC}?limit=100`, cust)).body || []).find((x) => x.serviceOrderId === soId);
    if (s) return s;
    await sleep(1000);
  }
  fail(`no inventory row for service order ${soId}`);
}
async function orderItemState(orderId, cust) {
  for (let i = 0; i < 20; i++) {
    const o = (await call('GET', `${ORD}/${orderId}`, cust)).body || {};
    const item = (o.productOrderItem || [])[0] || {};
    if (['completed', 'partiallyCompleted'].includes(item.state) || ['completed', 'partiallyCompleted'].includes(o.state)) return item.state || o.state;
    await sleep(1500);
  }
  const o = (await call('GET', `${ORD}/${orderId}`, cust)).body || {};
  return ((o.productOrderItem || [])[0] || {}).state || o.state;
}
const seams = (svc) => (svc.supportingService || []).filter((x) => x.seam).map((x) => x.seam);
const seamChar = (seam) => ({ name: 'seam', valueType: 'string', configurable: false, serviceSpecCharacteristicValue: [{ value: seam, isDefault: true }] });
const edge = (rfs, required, consumes) => ({ id: rfs.id, href: rfs.href, name: rfs.name, '@referredType': 'ServiceSpecification', relationshipType: 'reliesOn',
  serviceSpecRelationshipCharacteristic: [
    { name: 'required', valueType: 'boolean', serviceSpecCharacteristicValue: [{ value: String(required) }] },
    { name: 'consumes', valueType: 'array', serviceSpecCharacteristicValue: [{ value: consumes.join(',') }] }] });
const charOf = (name, value) => ({ name, configurable: false, productSpecCharacteristicValue: [{ value, isDefault: true }] });

(async () => {
  const staff = await token('demo', 'demo');
  const ref = (e, t) => ({ id: e.id, href: e.href, name: e.name, '@referredType': t });
  const cats = (await call('GET', `${CAT}/category?limit=100`, staff)).body || [];
  const mobile = cats.find((c) => c.name === 'Mobile plans');
  const insurance = cats.find((c) => c.name === 'Insurance');
  if (!mobile || !insurance) fail('run the catalog seeds first: Mobile plans and Insurance categories are needed');
  const billingOnly = ((await call('GET', `${SCAT}/serviceSpecification?limit=100`, staff)).body || []).find((s) => s.name === 'Billing-only product' && s.serviceType === 'CFS');
  if (!billingOnly) fail('the seeded CFS "Billing-only product" is missing — run seed_service_specifications.py');
  const fixtures = { offerings: [], specs: [], prices: [], sspecs: [], rspecs: [] };
  const rspec = async (name, seam) => { const r = await created('POST', `${RCAT}/resourceSpecification`, staff, { name: `${tag} ${name}`, lifecycleStatus: 'Active', version: '1.0', category: 'seam',
    resourceSpecCharacteristic: [{ name: 'seam', valueType: 'string', configurable: false, resourceSpecCharacteristicValue: [{ value: seam, isDefault: true }] }] }); fixtures.rspecs.push(r.id); return r; };
  const rfs = async (name, seam, rs) => { const r = await created('POST', `${SCAT}/serviceSpecification`, staff, { name: `${tag} ${name}`, serviceType: 'RFS', lifecycleStatus: 'Active', version: '1.0',
    serviceSpecCharacteristic: [seamChar(seam)], resourceSpecification: [ref(rs, 'ResourceSpecification')] }); fixtures.sspecs.push(r.id); return r; };
  const price = await created('POST', `${CAT}/productOfferingPrice`, staff, { name: `${tag} monthly`, priceType: 'recurring', price: { unit: 'NOK', value: 149 }, recurringChargePeriodType: 'month', recurringChargePeriodLength: 1, lifecycleStatus: 'Active', version: '1.0' });
  fixtures.prices.push(price.id);
  const sell = async (name, spec, category) => { const o = await created('POST', `${CAT}/productOffering`, staff, { name: `${tag} ${name}`, description: 'suite fixture', lifecycleStatus: 'Active', version: '1.0', isBundle: false, isSellable: true,
    category: [{ id: category.id, name: category.name }], productSpecification: ref(spec, 'ProductSpecification'), productOfferingPrice: [ref(price, 'ProductOfferingPrice')] }); fixtures.offerings.push(o.id); return o; };
  const specOf = async (name, extra) => { const s = await created('POST', `${CAT}/productSpecification`, staff, { name: `${tag} ${name}`, brand: 'GenAlpha', lifecycleStatus: 'Active', ...extra }); fixtures.specs.push(s.id); return s; };
  const cust = await freshCustomer();
  const order = async (off) => { const r = await call('POST', ORD, cust, { description: off.name, productOrderItem: [{ id: '1', action: 'add', productOffering: { id: off.id, name: off.name, '@referredType': 'ProductOffering' } }] });
    if (r.status !== 201) fail(`order ${off.name}: ${r.status} ${r.text.slice(0, 200)}`); return r.body.id; };

  /* ---------- OPTIONAL RULE ---------- */
  const rsNumber = await rspec('Mobile number', 'number');
  const rsOcs = await rspec('Online-charging subscriber', 'ocs');
  const rfsNumber = await rfs('Number assignment', 'number', rsNumber);
  const rfsOcs = await rfs('Charging subscriber', 'ocs', rsOcs);
  const cfs = await created('POST', `${SCAT}/serviceSpecification`, staff, { name: `${tag} Mobile line`, serviceType: 'CFS', lifecycleStatus: 'Active', version: '1.0',
    serviceSpecCharacteristic: [{ name: 'fulfilmentFamily', valueType: 'string', configurable: false, serviceSpecCharacteristicValue: [{ value: 'mobile', isDefault: true }] }],
    serviceSpecRelationship: [edge(rfsNumber, true, ['msisdn']), edge(rfsOcs, false, ['chargingSpecId'])] });
  fixtures.sspecs.push(cfs.id);
  const plain = await specOf('plain line spec', { serviceSpecification: [ref(cfs, 'ServiceSpecification')] });
  const charged = await specOf('charged line spec', { serviceSpecification: [ref(cfs, 'ServiceSpecification')], productSpecCharacteristic: [charOf('chargingSpecId', 'rate-plan-basic')] });
  const plainOff = await sell('Plain Line', plain, mobile);
  const chargedOff = await sell('Charged Line', charged, mobile);
  ok(`AUTHORED: CFS "${cfs.name}" — number required, charging optional (consumes chargingSpecId); two products, one with a charging plan`);
  await sleep(11000); // the executor's chain cache holds 10 s

  const sPlain = await serviceFor((await serviceOrderFor(await order(plainOff), staff)).id, cust);
  if (!seams(sPlain).includes('number')) fail(`plain line drew no number: ${JSON.stringify(sPlain.supportingService)}`);
  if (seams(sPlain).includes('ocs')) fail(`plain line was charged although the product carries no charging plan: ${JSON.stringify(seams(sPlain))}`);
  const sCharged = await serviceFor((await serviceOrderFor(await order(chargedOff), staff)).id, cust);
  if (!seams(sCharged).includes('ocs')) fail(`charged line realised no ocs seam: ${JSON.stringify(sCharged.supportingService)}`);
  const ocs = sCharged.supportingService.find((x) => x.seam === 'ocs');
  ok(`OPTIONAL RULE: plain product → ${seams(sPlain).join(' + ')}; product with a charging plan → ${seams(sCharged).join(' + ')} (ocs by ${ocs.vendor}, ${ocs.externalRef || 'no external ref'})`);

  /* ---------- COUNTED FALLBACK ---------- */
  const orphan = await specOf('spec without a pattern', {});
  const orphanOff = await sell('Old-style Line', orphan, mobile);
  const soOrphan = await serviceOrderFor(await order(orphanOff), staff);
  const sOrphan = await serviceFor(soOrphan.id, cust);
  const fb = (sOrphan.supportingService || []).find((x) => x.seam === 'category-fallback');
  if (!fb) fail(`a spec with no CFS should be fulfilled by the category fallback and say so: ${JSON.stringify(sOrphan.supportingService)}`);
  const red = script('ratchet.sh', ['--live']);
  if (red.code === 0 || !/category fallback/i.test(red.out)) fail(`ratchet.sh --live should be red on a fallback-fulfilled service; got ${red.code}\n${red.out.slice(-600)}`);
  ok(`COUNTED FALLBACK: the old-style line carries a category-fallback realisation (${fb.externalRef || 'category'}) and ratchet.sh --live is red: "${(red.out.split('\n').find((l) => /category fallback/i.test(l)) || '').trim().slice(0, 120)}"`);
  const ceased = await call('POST', `${SVC}/${sOrphan.id}/terminate`, staff, { reason: 'cease' });
  if (ceased.status >= 300) fail(`could not cease the fallback service: ${ceased.status} ${ceased.text.slice(0, 160)}`);
  const green = script('ratchet.sh', ['--live']);
  if (green.code !== 0) fail(`ratchet.sh --live still red after the fallback service was ceased:\n${green.out.slice(-600)}`);
  ok('COUNTED FALLBACK CLEARS: ceasing the service takes it out of the debt; ratchet.sh --live exit 0');

  /* ---------- BILLING-ONLY ---------- */
  const cover = await specOf('device cover spec', { serviceSpecification: [ref(billingOnly, 'ServiceSpecification')] });
  const coverOff = await sell('Device Cover', cover, insurance);
  const coverOrder = await order(coverOff);
  const state = await orderItemState(coverOrder, cust);
  const soCover = await serviceOrderFor(coverOrder, staff, false);
  if (soCover) fail(`a billing-only product raised a service order: ${JSON.stringify(soCover).slice(0, 200)}`);
  const mine = ((await call('GET', `${SVC}?limit=100`, cust)).body || []).filter((s) => (s.name || '').includes('Device Cover'));
  if (mine.length) fail(`a billing-only product created a service record: ${JSON.stringify(mine[0]).slice(0, 200)}`);
  if (!['completed', 'partiallyCompleted'].includes(state)) fail(`the billing-only order item did not complete (state ${state})`);
  ok(`BILLING-ONLY: "${coverOff.name}" (CFS "Billing-only product", zero RFS) — no service order, no service, item ${state}: nothing to provision, billing rates it`);

  /* ---------- leave the shelf as we found it ---------- */
  for (const id of fixtures.offerings) await call('DELETE', `${CAT}/productOffering/${id}`, staff);
  for (const id of fixtures.specs) await call('DELETE', `${CAT}/productSpecification/${id}`, staff);
  for (const id of fixtures.prices) await call('DELETE', `${CAT}/productOfferingPrice/${id}`, staff);
  for (const id of fixtures.sspecs.reverse()) await call('DELETE', `${SCAT}/serviceSpecification/${id}`, staff);
  for (const id of fixtures.rspecs) await call('DELETE', `${RCAT}/resourceSpecification/${id}`, staff);
  const after = script('cfs_check.py');
  if (findings(after.out).some((l) => l.includes(tag))) fail(`the gate still mentions this run's fixtures after cleanup:\n${after.out}`);
  ok(`GATE CLEAN OF THIS RUN: ${after.out.trim().split('\n').pop().slice(0, 160)}`);

  console.log('\nALL CFS-OBEYS CHECKS PASSED — an optional RFS runs only when the product calls for it, a spec without a pattern is'
    + ' fulfilled by the counted fallback and the ratchet says so until the service is gone, and a billing-only pattern provisions nothing.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
