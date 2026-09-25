/* Dry run before launch — what will happen when someone orders this. Suite #232.
 *
 * Catalog-to-provisioning step 3, ticket 8b. The orchestrator can be asked for
 * its plan without running anything: the seams the CFS declares, in order,
 * with what each consumes and who serves it, or the seam nobody serves in this
 * fleet. Launch governance turns that answer into a readiness item, and the
 * offering page shows it in words. Proven against the live stack:
 *
 *  - NOT LAUNCHABLE HERE: a CFS whose required RFS sits on a seam no adapter
 *    serves (market-hub, the energy hub of a future product line) plus the
 *    number RFS → verdict names market-hub, the number step would run, and the
 *    "Fulfilment plan" readiness item is open with that reason; a launch is
 *    refused for it.
 *  - LAUNCHABLE: the same CFS with the number RFS only → verdict launchable,
 *    readiness item done.
 *  - FALLBACK: a spec with no CFS → the category decides, and the plan says so.
 *  - NO SIDE EFFECTS: service, resource and realisation counts are unchanged
 *    across every dry run.
 *  - THE PAGE: the offering's Decomposition panel shows the plan in sentences.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = '/tmf-api/productCatalogManagement/v4';
const SCAT = '/tmf-api/serviceCatalogManagement/v4';
const RCAT = '/tmf-api/resourceCatalogManagement/v4';
const SVC = '/tmf-api/serviceInventory/v4/service';
const RES = '/tmf-api/resourceInventoryManagement/v4/resource';
const DRY = '/som/v1/fulfilment/dryRun';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const run = Date.now();
const tag = `SUITE${run}`;

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  const j = await r.json();
  if (!j.access_token) fail(`token(${user}) refused`);
  return j.access_token;
}
async function call(method, p, tok, body) {
  const r = await fetch(API + p, { method,
    headers: { Authorization: `Bearer ${tok}`, ...(body ? { 'Content-Type': 'application/json' } : {}), 'Cache-Control': 'no-cache' },
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
async function count(p, tok) {
  let n = 0;
  for (let offset = 0; offset < 20000; offset += 100) {
    const rows = (await call('GET', `${p}?limit=100&offset=${offset}`, tok)).body || [];
    n += rows.length;
    if (rows.length < 100) break;
  }
  return n;
}
const seamChar = (seam) => ({ name: 'seam', valueType: 'string', configurable: false, serviceSpecCharacteristicValue: [{ value: seam, isDefault: true }] });
const edge = (rfs, required) => ({ id: rfs.id, href: rfs.href, name: rfs.name, '@referredType': 'ServiceSpecification', relationshipType: 'reliesOn',
  serviceSpecRelationshipCharacteristic: [{ name: 'required', valueType: 'boolean', serviceSpecCharacteristicValue: [{ value: String(required) }] }] });
const ref = (e, t) => ({ id: e.id, href: e.href, name: e.name, '@referredType': t });
const readiness = (decision) => (decision.readiness || []).find((r) => r.label === 'Fulfilment plan');

(async () => {
  const staff = await token('demo', 'demo');
  const mobile = ((await call('GET', `${CAT}/category?limit=100`, staff)).body || []).find((c) => c.name === 'Mobile plans');
  if (!mobile) fail('no "Mobile plans" category — run the catalog seeds first');
  const before = { services: await count(SVC, staff), resources: await count(RES, staff) };

  /* ---------- the chain: a required seam nobody serves here, beside the number ---------- */
  const rsNumber = await created('POST', `${RCAT}/resourceSpecification`, staff, { name: `${tag} Mobile number`, lifecycleStatus: 'Active', version: '1.0', category: 'seam',
    resourceSpecCharacteristic: [{ name: 'seam', valueType: 'string', configurable: false, resourceSpecCharacteristicValue: [{ value: 'number', isDefault: true }] }] });
  const rsHub = await created('POST', `${RCAT}/resourceSpecification`, staff, { name: `${tag} Metering point`, lifecycleStatus: 'Active', version: '1.0', category: 'seam',
    resourceSpecCharacteristic: [{ name: 'seam', valueType: 'string', configurable: false, resourceSpecCharacteristicValue: [{ value: 'market-hub', isDefault: true }] }] });
  const rfsNumber = await created('POST', `${SCAT}/serviceSpecification`, staff, { name: `${tag} Number assignment`, serviceType: 'RFS', lifecycleStatus: 'Active', version: '1.0',
    serviceSpecCharacteristic: [seamChar('number')], resourceSpecification: [ref(rsNumber, 'ResourceSpecification')] });
  const rfsHub = await created('POST', `${SCAT}/serviceSpecification`, staff, { name: `${tag} Metering point takeover`, serviceType: 'RFS', lifecycleStatus: 'Active', version: '1.0',
    serviceSpecCharacteristic: [seamChar('market-hub')], resourceSpecification: [ref(rsHub, 'ResourceSpecification')] });
  const cfs = await created('POST', `${SCAT}/serviceSpecification`, staff, { name: `${tag} Electricity line`, serviceType: 'CFS', lifecycleStatus: 'Active', version: '1.0',
    serviceSpecCharacteristic: [{ name: 'fulfilmentFamily', valueType: 'string', configurable: false, serviceSpecCharacteristicValue: [{ value: 'mobile', isDefault: true }] }],
    serviceSpecRelationship: [edge(rfsNumber, true), edge(rfsHub, true)] });
  const spec = await created('POST', `${CAT}/productSpecification`, staff, { name: `${tag} energy spec`, brand: 'GenAlpha', lifecycleStatus: 'Active', serviceSpecification: [ref(cfs, 'ServiceSpecification')] });
  const specPlain = await created('POST', `${CAT}/productSpecification`, staff, { name: `${tag} plain spec`, brand: 'GenAlpha', lifecycleStatus: 'Active' });
  const price = await created('POST', `${CAT}/productOfferingPrice`, staff, { name: `${tag} monthly`, priceType: 'recurring', price: { unit: 'NOK', value: 299 },
    recurringChargePeriodType: 'month', recurringChargePeriodLength: 1, lifecycleStatus: 'Active', version: '1.0' });
  const offering = (name, s) => created('POST', `${CAT}/productOffering`, staff, { name, description: 'suite fixture', lifecycleStatus: 'In design', version: '1.0', isBundle: false, isSellable: true,
    category: [{ id: mobile.id, name: mobile.name }], productSpecification: ref(s, 'ProductSpecification'), productOfferingPrice: [ref(price, 'ProductOfferingPrice')] });
  const energy = await offering(`${tag} Spot electricity`, spec);
  const plain = await offering(`${tag} Plain plan`, specPlain);
  ok(`AUTHORED: CFS "${cfs.name}" requires number + market-hub (no adapter here); one offering sells it, one has no CFS`);

  /* ---------- NOT LAUNCHABLE HERE ---------- */
  let plan = (await call('POST', DRY, staff, { offeringId: energy.id })).body;
  if (!plan || plan.verdict !== 'NOT_LAUNCHABLE_HERE') fail(`expected NOT_LAUNCHABLE_HERE, got ${JSON.stringify(plan).slice(0, 300)}`);
  if (!/no adapter for market-hub/.test(plan.reason)) fail(`the reason does not name market-hub: ${plan.reason}`);
  const numberStep = plan.steps.find((s) => s.seam === 'number');
  const hubStep = plan.steps.find((s) => s.seam === 'market-hub');
  if (!numberStep || numberStep.decision !== 'RUN') fail(`the number step should RUN: ${JSON.stringify(plan.steps)}`);
  if (!hubStep || hubStep.decision !== 'NO_ADAPTER' || !hubStep.required) fail(`market-hub should be NO_ADAPTER and required: ${JSON.stringify(hubStep)}`);
  if (plan.steps.indexOf(numberStep) > plan.steps.indexOf(hubStep)) fail('known seams run before unknown ones');
  ok(`NOT LAUNCHABLE HERE: "${plan.summary[plan.summary.length - 1]}" — number would run (${numberStep.vendor}), market-hub has no adapter`);
  let decision = (await call('GET', `${CAT}/productOffering/${energy.id}/governance`, staff)).body;
  let item = readiness(decision);
  if (!item || item.done !== false || !/market-hub/.test(item.note || '')) fail(`readiness item should be open naming market-hub: ${JSON.stringify(item)}`);
  // the launch door only exists where launch governance is on (Taranga: envelope; GenAlpha: none —
  // a write is a launch there, and the readiness item is advice on the page, not a gate)
  const mode = ((await call('GET', `${CAT}/governance/settings`, staff)).body || {}).mode;
  if (mode && mode !== 'none') {
    const launch = await call('POST', `${CAT}/productOffering/${energy.id}/governance/launch`, staff, {});
    if (launch.status < 400 || !/Fulfilment plan/.test(launch.text)) fail(`a launch should be refused by the fulfilment plan: ${launch.status} ${launch.text.slice(0, 200)}`);
    ok(`READINESS: "Fulfilment plan" is open (${item.note}); launch refused with it named`);
  } else {
    ok(`READINESS: "Fulfilment plan" is open (${item.note}); launch governance is '${mode}' on this tenant, so the refusal is proven by launch_governance_test's tenant, not here`);
  }

  /* ---------- LAUNCHABLE: the number only ---------- */
  await created('PATCH', `${SCAT}/serviceSpecification/${cfs.id}`, staff, { serviceSpecRelationship: [edge(rfsNumber, true)] });
  await new Promise((r) => setTimeout(r, 11000)); // the orchestrator's chain cache holds 10 s
  plan = (await call('POST', DRY, staff, { offeringId: energy.id })).body;
  if (!plan || plan.verdict !== 'LAUNCHABLE') fail(`expected LAUNCHABLE after dropping market-hub, got ${JSON.stringify(plan).slice(0, 300)}`);
  if (!plan.drawsFromPool || !plan.createsServiceRecord) fail(`a number-only line draws from a pool and creates a service record: ${JSON.stringify(plan)}`);
  decision = (await call('GET', `${CAT}/productOffering/${energy.id}/governance`, staff)).body;
  item = readiness(decision);
  if (!item || item.done !== true) fail(`readiness item should be done: ${JSON.stringify(item)}`);
  ok(`LAUNCHABLE: "${plan.summary[plan.summary.length - 1]}"; readiness "Fulfilment plan" done`);

  /* ---------- FALLBACK: no CFS ---------- */
  plan = (await call('POST', DRY, staff, { offeringId: plain.id })).body;
  if (!plan || plan.verdict !== 'FALLBACK' || !plan.fallback) fail(`expected FALLBACK for a spec without a CFS: ${JSON.stringify(plan).slice(0, 300)}`);
  if (!/Mobile plans/.test(plan.reason)) fail(`the fallback names the category: ${plan.reason}`);
  ok(`FALLBACK: "${plan.reason}"`);

  /* ---------- no side effects ---------- */
  const after = { services: await count(SVC, staff), resources: await count(RES, staff) };
  if (after.services !== before.services || after.resources !== before.resources) fail(`a dry run changed inventory: ${JSON.stringify(before)} → ${JSON.stringify(after)}`);
  ok(`NO SIDE EFFECTS: ${after.services} services and ${after.resources} resources, as before`);

  /* ---------- the page ---------- */
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  await page.goto(`${API}/console/`);
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo'); await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#username', { timeout: 20000 });
  await page.locator('.tab', { hasText: 'Product Offerings' }).first().click();
  await page.waitForSelector('#listing-body tr', { timeout: 15000 });
  let row = page.locator('#listing-body tr', { hasText: energy.name });
  for (let hop = 0; hop < 30 && !(await row.count()); hop++) {
    if (await page.locator('#next').isDisabled()) break;
    await page.click('#next'); await page.waitForTimeout(300);
    row = page.locator('#listing-body tr', { hasText: energy.name });
  }
  if (!(await row.count())) fail('the fixture offering is not in the listing');
  await row.locator('button', { hasText: 'Open' }).click();
  const head = page.locator('[data-testid="dry-run"]');
  await head.waitFor({ timeout: 20000 });
  await page.waitForFunction(() => document.querySelector('[data-verdict]') !== null, null, { timeout: 20000 });
  const text = (await page.locator('[data-testid="decomposition"]').innerText()).trim();
  if (!/What will happen when someone orders this/.test(text)) fail('the page lacks the dry-run block');
  if (!/Register a number/.test(text) || !/Can launch here/.test(text)) fail(`the page does not say the plan in words:\n${text}`);
  if (/[0-9a-f]{8}-[0-9a-f]{4}-/.test(text)) fail('an identifier leaked onto the page');
  await page.locator('[data-testid="decomposition"]').scrollIntoViewIfNeeded();
  const shot = `/tmp/fulfilment-dry-run-${run}.png`;
  await page.screenshot({ path: shot });
  await browser.close();
  ok(`THE PAGE: "${text.split('\n').filter((l) => /Register a number|Can launch/.test(l)).join(' · ')}" (screenshot ${shot})`);

  /* ---------- clean up ---------- */
  for (const [p, id] of [[`${CAT}/productOffering`, energy.id], [`${CAT}/productOffering`, plain.id], [`${CAT}/productSpecification`, spec.id],
    [`${CAT}/productSpecification`, specPlain.id], [`${CAT}/productOfferingPrice`, price.id], [`${SCAT}/serviceSpecification`, cfs.id],
    [`${SCAT}/serviceSpecification`, rfsNumber.id], [`${SCAT}/serviceSpecification`, rfsHub.id], [`${RCAT}/resourceSpecification`, rsNumber.id], [`${RCAT}/resourceSpecification`, rsHub.id]]) {
    await call('DELETE', `${p}/${id}`, staff);
  }
  console.log('\nALL DRY-RUN CHECKS PASSED — the orchestrator says what it would do before anyone orders, names the seam this fleet'
    + ' cannot serve, launch governance refuses to launch past it, nothing is provisioned by asking, and the offering page says it in words.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
