/* Fixed wholesale console — the owner's authoring desk, end to end in a real
 * browser. Suite #91.
 *
 * An operator holding `wholesale:admin` runs the whole wholesale supply side from
 * the console — no seed script:
 *  - WORKSPACE: the Wholesale desk appears with its panes.
 *  - OWNERS (FC2): onboard an access owner → a TMF651 partnership agreement lands.
 *  - SERVICE SPECS (FC5): a TMF633 CFS reliesOn an RFS — the SID commercial/technical
 *    split, listed in the console.
 *  - ACCESS PRODUCT (FC3+FC5): publish an L2/L3 SKU realised by the CFS → it appears
 *    in the partner portal's catalogue AND its spec references the CFS.
 *  - COVERAGE (FC1): paint a wholesale footprint → queryAccessOptions reflects it.
 *  - SETTLEMENT (FC4): the owner's book renders (both directions).
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = `${API}/tmf-api/productCatalogManagement/v4`;
const SC = `${API}/tmf-api/serviceCatalogManagement/v4/serviceSpecification`;
const SQM = `${API}/tmf-api/serviceQualificationManagement/v4`;
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const ok = (m) => console.log('OK ' + m);
const run = Date.now();

async function token(request) {
  const res = await request.post(KC, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

async function loginConsole(browser) {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto(`${API}/console/`);
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', 'demo');
  await page.fill('input[name="password"]', 'demo');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  await page.waitForSelector('#tabs .tab', { timeout: 10000 });
  return { ctx, page };
}

const clickTab = (page, title) => page.locator('#tabs .tab', { hasText: title }).first().click();

(async () => {
  const browser = await chromium.launch();
  const { ctx, page } = await loginConsole(browser);
  const auth = { Authorization: `Bearer ${await token(ctx.request)}` };
  const jh = { ...auth, 'Content-Type': 'application/json' };
  const OWNER = `SUITE${run % 100000}`;               // unique owner code for this run
  const created = { specs: [], offerings: [], specIds: [], priceIds: [], coverage: [] };

  /* ---------- 1. WORKSPACE ---------- */
  const groups = await page.evaluate(() =>
    [...document.querySelectorAll('#tabs .tabgroup .tabgroup-label')].map((l) => l.textContent));
  if (!groups.includes('Wholesale')) fail('the Wholesale desk is missing: ' + groups.join(' | '));
  const wsTabs = await page.evaluate(() => {
    const g = [...document.querySelectorAll('#tabs .tabgroup')]
      .find((x) => x.querySelector('.tabgroup-label')?.textContent === 'Wholesale');
    return [...g.querySelectorAll('.tab')].map((b) => b.textContent);
  });
  for (const t of ['Access owners', 'Access products', 'Service specs', 'Coverage', 'Settlement']) {
    if (!wsTabs.some((x) => x.includes(t))) fail(`Wholesale desk missing the "${t}" pane: ${wsTabs}`);
  }
  ok(`WORKSPACE: the Wholesale desk shows — ${wsTabs.join(', ')}`);

  /* ---------- 2. OWNERS (FC2) ---------- */
  await clickTab(page, 'Access owners');
  await page.waitForSelector('#wo-create', { timeout: 10000 });
  await page.fill('#wo-name', `${OWNER} Fibre AS`);
  await page.fill('#wo-code', OWNER);
  await page.selectOption('#wo-layer', 'L3-activated');
  await page.click('#wo-create');
  await page.waitForFunction(() => /onboarded/i.test(document.querySelector('#wo-msg')?.textContent || ''), { timeout: 15000 });
  await page.waitForFunction((code) =>
    [...document.querySelectorAll('#wholesale-panel td')].some((td) => td.textContent === code), OWNER, { timeout: 10000 });
  ok(`OWNERS: onboarded ${OWNER} — a TMF651 partnership agreement (accessProvider) landed and shows in the console`);

  /* ---------- 3. SERVICE SPECS (FC5) — author an RFS + a CFS reliesOn it ---------- */
  const rfs = await (await ctx.request.post(SC, { headers: jh, data: {
    name: `${OWNER} bitstream RFS`, serviceType: 'RFS', version: '1.0',
    description: 'Resource-facing bitstream',
    serviceSpecCharacteristic: [{ name: 'handover', serviceSpecCharacteristicValue: [{ value: 'IP' }] }] } })).json();
  created.specIds.push(rfs.id);
  const cfs = await (await ctx.request.post(SC, { headers: jh, data: {
    name: `${OWNER} wholesale access CFS`, serviceType: 'CFS', version: '1.0',
    description: 'Customer-facing wholesale access',
    serviceSpecCharacteristic: [
      { name: 'accessLayer', serviceSpecCharacteristicValue: [{ value: 'L3-activated' }] },
      { name: 'downloadSpeed', configurable: true, serviceSpecCharacteristicValue: [{ value: 500 }, { value: 1000 }] }],
    serviceSpecRelationship: [{ id: rfs.id, name: rfs.name, relationshipType: 'reliesOn', '@referredType': 'ServiceSpecification' }] } })).json();
  created.specIds.push(cfs.id);
  // the console's Service specs tab lists them
  await clickTab(page, 'Service specs');
  await page.waitForSelector('#listing-body', { timeout: 10000 });
  await page.waitForFunction((name) =>
    [...document.querySelectorAll('#listing-body td')].some((td) => td.textContent.includes(name)),
    `${OWNER} wholesale access CFS`, { timeout: 10000 });
  const rel = (cfs.serviceSpecRelationship || [])[0] || {};
  if (rel.relationshipType !== 'reliesOn' || rel.id !== rfs.id) fail('CFS does not reliesOn the RFS: ' + JSON.stringify(cfs.serviceSpecRelationship));
  ok('SERVICE SPECS: a TMF633 CFS (accessLayer + downloadSpeed) reliesOn its RFS — listed in the console');

  /* ---------- 4. ACCESS PRODUCT (FC3+FC5) — publish, realised by the CFS ---------- */
  await clickTab(page, 'Access products');
  await page.waitForSelector('#wp-create', { timeout: 10000 });
  const PRODUCT = `${OWNER} Fibre 1000 (L3)`;
  await page.fill('#wp-name', PRODUCT);
  await page.fill('#wp-owner', OWNER);
  await page.selectOption('#wp-layer', 'L3-activated');
  await page.fill('#wp-bw', '1000');
  await page.fill('#wp-price', '24');
  // the CFS dropdown was populated from the service catalog — pick our CFS
  await page.selectOption('#wp-cfs', { label: `${OWNER} wholesale access CFS` });
  await page.click('#wp-create');
  await page.waitForFunction(() => /published/i.test(document.querySelector('#wp-msg')?.textContent || ''), { timeout: 15000 });
  ok(`ACCESS PRODUCT: published "${PRODUCT}" from the console`);

  // cross-check via the API the partner portal reads: in Wholesale access category, spec references the CFS
  const offs = await (await ctx.request.get(`${CAT}/productOffering?limit=100`, { headers: auth })).json();
  const mine = offs.find((o) => o.name === PRODUCT);
  if (!mine) fail('published product not in the catalogue');
  created.offerings.push(mine.id);
  if (!(mine.category || []).some((c) => c.name === 'Wholesale access')) fail('product not in the Wholesale access category (would show in the retail shop)');
  const spec = await (await ctx.request.get(`${CAT}/productSpecification/${mine.productSpecification.id}`, { headers: auth })).json();
  created.specIds.push(spec.id);
  const ss = (spec.serviceSpecification || [])[0] || {};
  if (ss.id !== cfs.id) fail('the product spec is NOT realised by the CFS — the SID product→service link is broken: ' + JSON.stringify(spec.serviceSpecification));
  const chars = Object.fromEntries((spec.productSpecCharacteristic || []).map((c) => [c.name, (c.productSpecCharacteristicValue || [])[0]?.value]));
  if (chars.accessOwner !== OWNER || chars.accessLayer !== 'L3-activated') fail('product spec chars wrong: ' + JSON.stringify(chars));
  ok('ACCESS PRODUCT: it is in the Wholesale access category (hidden from the retail shop) and its spec is realised by the CFS — the SID product→service link holds');

  /* ---------- 5. COVERAGE (FC1) ---------- */
  await clickTab(page, 'Coverage');
  await page.waitForSelector('#editor:not([hidden])', { timeout: 10000 });
  // the generic create form: fill by field caption
  const setField = async (labelText, value) => {
    const loc = page.locator('#editor label.field', { hasText: labelText }).locator('input,select').first();
    await loc.scrollIntoViewIfNeeded();
    if ((await loc.evaluate((e) => e.tagName)) === 'SELECT') await loc.selectOption(value);
    else await loc.fill(value);
  };
  await setField('Technology', 'fiber');
  await setField('Postcode prefix', '70');
  await setField('Max down', '1000');
  await setField('Access owner', OWNER);
  await setField('Access layer', 'L3-activated');
  await page.click('#editor button[type="submit"], #editor button.primary');
  // queryAccessOptions (the partner portal's read) reflects the painted footprint
  await page.waitForTimeout(1500);
  const q = await (await ctx.request.post(`${SQM}/queryAccessOptions`, { headers: { 'Content-Type': 'application/json' },
    data: { place: [{ postCode: '7001', '@type': 'GeographicAddress' }], technology: 'fiber' } })).json();
  const opts = q.accessOption || q.wholesaleAccessOption || [];
  if (!opts.some((o) => o.accessOwner === OWNER)) fail('painted coverage not reflected by queryAccessOptions: ' + JSON.stringify(opts));
  created.coverage.push('70');
  ok(`COVERAGE: painted ${OWNER} L3 at prefix 70 in the console — queryAccessOptions (the partner portal's read) reflects it`);

  /* ---------- 6. SETTLEMENT (FC4) ---------- */
  await clickTab(page, 'Settlement');
  await page.waitForSelector('#wholesale-panel', { timeout: 10000 });
  await page.waitForFunction(() => {
    const t = document.querySelector('#wholesale-panel')?.textContent || '';
    return t.includes('what retailers owe you') && t.includes('what you owe owners');
  }, { timeout: 10000 });
  ok('SETTLEMENT: the owner\'s book renders both directions — what retailers owe you, and what you owe owners');

  /* ---------- cleanup (best-effort) ---------- */
  for (const id of created.offerings) await ctx.request.delete(`${CAT}/productOffering/${id}`, { headers: auth }).catch(() => {});
  for (const id of [...new Set(created.specIds)]) {
    await ctx.request.delete(`${CAT}/productSpecification/${id}`, { headers: auth }).catch(() => {});
    await ctx.request.delete(`${SC}/${id}`, { headers: auth }).catch(() => {});
  }
  const cov = await (await ctx.request.get(`${SQM}/coverageMap`, { headers: auth })).json();
  for (const c of cov) if (c.accessOwner === OWNER) await ctx.request.delete(`${SQM}/coverageMap/${c.id}`, { headers: auth }).catch(() => {});

  await ctx.close();
  await browser.close();
  console.log('\nALL FIXED-WHOLESALE-CONSOLE CHECKS PASSED — an operator with wholesale:admin runs the'
    + ' whole supply side from the console: onboard an owner, model the CFS/RFS, publish an L2/L3 access'
    + ' product realised by the CFS, paint the footprint, and read the settlement — no seed script.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
