/* A price is built, not typed. Suite #236.
 *
 * Four JSON boxes stood between a commercial product manager and their own
 * prices: what a price is charged per, its window, its algorithm, and the
 * configured choice it rides on. Each is a control now. This suite proves the
 * thing that matters about replacing a payload with a form — that the payload
 * did not change:
 *
 *  - BUILT: a price authored entirely through the controls stores EXACTLY the
 *    JSON the old box would have written, compared key by key against the
 *    payload spelled out here.
 *  - TYPES SURVIVE: a condition that already held a NUMBER still holds that
 *    number after an unrelated edit through the form. The catalog normalises a
 *    specification's own values to strings, but older price conditions hold
 *    real numbers, and a form that re-derived every value from what it
 *    displayed would quietly restringify them.
 *  - UNMODELLED KEYS SURVIVE: an algorithm entry's own `name` — a key no
 *    control shows — is still there after an edit through the form.
 *  - READ BACK: reopening the price shows exactly what is stored.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = '/tmf-api/productCatalogManagement/v4';
const CONSOLE = 'http://localhost:8080/console/';
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
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/* Walk the pager to a row and open it.
 *
 * The desk heading is what says which listing is on screen. Waiting for rows
 * alone is a trap: the previous desk's rows are still in the table while the
 * new desk loads, so the first look finds nothing, the pager advances, and
 * page one — where the row actually is — is never read. */
async function open(page, tab, name) {
  await page.locator('.tab', { hasText: tab }).first().click();
  await page.waitForFunction(
    (t) => ((document.getElementById('resource-title') || {}).textContent || '').includes(t),
    tab, { timeout: 15000 });
  await page.waitForSelector('#listing-body tr', { timeout: 15000 });
  await page.waitForTimeout(400);
  let row = page.locator('#listing-body tr', { hasText: name });
  for (let i = 0; i < 25 && !(await row.count()); i++) {
    if (await page.locator('#next').isDisabled()) break;
    await page.click('#next');
    await page.waitForTimeout(500);
    row = page.locator('#listing-body tr', { hasText: name });
  }
  if (!(await row.count())) fail(`'${name}' never appeared under ${tab}`);
  await row.first().locator('button', { hasText: 'Open' }).click();
  await page.waitForTimeout(800);
}

async function saveAndWait(page) {
  await page.click('#save');
  await page.waitForTimeout(2500);
  const err = page.locator('#editor-error');
  if (await err.count() && !(await err.isHidden())) fail(`the form refused to save: ${await err.textContent()}`);
}

(async () => {
  const staff = await token('demo', 'demo');

  /* A specification with the two shapes a price conditions on: a text choice
   * and a numeric one. The catalog refuses a price naming a choice its spec
   * does not declare, so this is what makes the condition legal. */
  const spec = (await call('POST', `${CAT}/productSpecification`, staff, {
    name: `${tag} seats spec`, brand: 'GenAlpha', lifecycleStatus: 'Active',
    productSpecCharacteristic: [
      { name: 'screens', valueType: 'string', configurable: true,
        productSpecCharacteristicValue: [{ value: '1-2' }, { value: '3-4' }, { value: '5+' }] },
      { name: 'seats', valueType: 'number', configurable: true,
        productSpecCharacteristicValue: [{ value: 10 }, { value: 20 }] },
    ],
  })).body;
  if (!spec || !spec.id) fail('could not create the fixture specification');

  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1700, height: 1100 } });
  page.on('pageerror', (e) => console.log('PAGE ERROR:', e.message));
  await page.goto(CONSOLE);
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#username', { timeout: 20000 });

  /* ---------- BUILT: a price, entirely through the controls ---------- */
  await page.locator('.tab', { hasText: 'Prices' }).first().click();
  await page.waitForSelector('input[name="name"]', { timeout: 15000 });
  await page.fill('input[name="name"]', `${tag} seat price`);
  await page.selectOption('select[name="priceType"]', 'recurring');
  await page.fill('.moneyrow input[type="number"]', '99');

  // per unit of: "applies per 1 seat"
  const uom = page.locator('.field-unitofmeasure');
  await uom.locator('input[type="number"]').fill('1');
  await uom.locator('input[list="uom-units"]').fill('seat');

  // the window, on two calendars
  await page.locator('.field-date input[name="validFrom"]').fill('2026-10-01');
  await page.locator('.field-date input[name="validTo"]').fill('2026-10-31');

  // the algorithm: per unit above a threshold
  const algo = page.locator('.field-algorithm');
  await algo.locator('select').first().selectOption('perUnitAbove');
  await page.waitForTimeout(300);
  await algo.locator('input[list="pla-characteristics"]').fill('seats');
  const algoNums = algo.locator('input[type="number"]');
  await algoNums.nth(0).fill('2');
  await algoNums.nth(1).fill('10');

  // applies only when: a text choice, picked from the specification's own values
  const cond = page.locator('.field-pricecondition');
  await cond.locator('button', { hasText: '+ Condition' }).click();
  await page.waitForTimeout(200);
  await cond.locator('input[list="cond-characteristics"]').first().fill('screens');
  await page.waitForTimeout(400);
  await cond.locator('input[list^="cond-values"]').first().fill('5+');

  await page.screenshot({ path: '/tmp/price-builder.png' });
  await saveAndWait(page);

  // by name, never a page scan: the catalog caps a page at 100 and this tenant
  // already has that many prices, so a fresh row lands where a scan never looks
  const price = ((await call('GET', `${CAT}/productOfferingPrice?name=${encodeURIComponent(`${tag} seat price`)}`, staff)).body || [])[0];
  if (!price) fail('the price never saved');

  /* The payload the JSON boxes produced for this input, spelled out. */
  const expectedUnit = { amount: 1, units: 'seat' };
  const expectedAlgo = [{ plaSpecId: 'perUnitAbove', characteristic: 'seats', threshold: 2, unitPrice: 10 }];
  const expectedCond = [{ name: 'screens', productSpecCharacteristicValue: [{ value: '5+' }] }];
  if (!same(price.unitOfMeasure, expectedUnit)) fail(`unitOfMeasure: ${JSON.stringify(price.unitOfMeasure)} != ${JSON.stringify(expectedUnit)}`);
  if (!same(price.pricingLogicAlgorithm, expectedAlgo)) fail(`pricingLogicAlgorithm: ${JSON.stringify(price.pricingLogicAlgorithm)} != ${JSON.stringify(expectedAlgo)}`);
  if (!same(price.prodSpecCharValueUse, expectedCond)) fail(`prodSpecCharValueUse: ${JSON.stringify(price.prodSpecCharValueUse)} != ${JSON.stringify(expectedCond)}`);
  const w = price.validFor || {};
  if (!w.startDateTime || !w.endDateTime) fail(`the window did not reach the wire: ${JSON.stringify(w)}`);
  const start = new Date(w.startDateTime); const end = new Date(w.endDateTime);
  if (start.getDate() !== 1 || start.getMonth() !== 9) fail(`the window starts ${w.startDateTime}, not 1 October locally`);
  if (end.getDate() !== 31 || end.getHours() !== 23) fail(`the window ends ${w.endDateTime}, not the whole of 31 October locally`);
  ok(`BUILT: a price authored through the controls stores the same payload the JSON boxes wrote — per ${expectedUnit.amount} ${expectedUnit.units}, perUnitAbove(seats, 2, 10), when screens is "5+", 1–31 October`);

  /* ---------- TYPES SURVIVE ---------- */
  // as older conditions are actually stored: a real number, not the string a
  // specification's own values are normalised to
  await call('PATCH', `${CAT}/productOfferingPrice/${price.id}`, staff, {
    prodSpecCharValueUse: [{ name: 'screens', productSpecCharacteristicValue: [{ value: 4 }] }],
  });
  await page.reload();
  await page.waitForSelector('#username', { timeout: 20000 });
  await open(page, 'Prices', `${tag} seat price`);
  // change the unit, never the condition
  await page.locator('.field-unitofmeasure input[list="uom-units"]').fill('user');
  await saveAndWait(page);
  const afterEdit = (await call('GET', `${CAT}/productOfferingPrice/${price.id}`, staff)).body || {};
  const held = ((afterEdit.prodSpecCharValueUse || [])[0] || {}).productSpecCharacteristicValue?.[0]?.value;
  if (held !== 4) fail(`the untouched numeric condition came back as ${JSON.stringify(held)}, not the number 4`);
  if ((afterEdit.unitOfMeasure || {}).units !== 'user') fail(`the edit did not reach the wire: ${JSON.stringify(afterEdit.unitOfMeasure)}`);
  ok('TYPES SURVIVE: editing the unit left a condition that held the number 4 holding the number 4, not "4"');

  /* ---------- UNMODELLED KEYS SURVIVE ---------- */
  await call('PATCH', `${CAT}/productOfferingPrice/${price.id}`, staff, {
    pricingLogicAlgorithm: [{ name: 'per seat above two', plaSpecId: 'perUnitAbove', characteristic: 'seats', threshold: 2, unitPrice: 10 }],
  });
  await page.reload();
  await page.waitForSelector('#username', { timeout: 20000 });
  await open(page, 'Prices', `${tag} seat price`);
  const algo2 = page.locator('.field-algorithm');
  await algo2.locator('input[type="number"]').nth(1).fill('12');   // change only the unit price
  await saveAndWait(page);
  const kept = ((await call('GET', `${CAT}/productOfferingPrice/${price.id}`, staff)).body || {}).pricingLogicAlgorithm || [];
  if (kept[0]?.name !== 'per seat above two') fail(`the entry's own name was dropped: ${JSON.stringify(kept)}`);
  if (kept[0]?.unitPrice !== 12) fail(`the edit did not reach the wire: ${JSON.stringify(kept)}`);
  ok(`UNMODELLED KEYS SURVIVE: editing the unit price kept the entry's own name ("${kept[0].name}") that no control shows`);

  /* ---------- READ BACK ---------- */
  await page.reload();
  await page.waitForSelector('#username', { timeout: 20000 });
  await open(page, 'Prices', `${tag} seat price`);
  const back = {
    amount: await page.locator('.field-unitofmeasure input[type="number"]').inputValue(),
    units: await page.locator('.field-unitofmeasure input[list="uom-units"]').inputValue(),
    from: await page.locator('.field-date input[name="validFrom"]').inputValue(),
    to: await page.locator('.field-date input[name="validTo"]').inputValue(),
    algo: await page.locator('.field-algorithm select').first().inputValue(),
    characteristic: await page.locator('.field-algorithm input[list="pla-characteristics"]').inputValue(),
  };
  /* Against the wire, not against a remembered value: the steps above changed
   * the unit and the unit price on purpose, and a control that reads back
   * something other than what is stored is the whole bug this arc removes. */
  const onWire = (await call('GET', `${CAT}/productOfferingPrice/${price.id}`, staff)).body || {};
  const unit = onWire.unitOfMeasure || {};
  const algoOnWire = (onWire.pricingLogicAlgorithm || [])[0] || {};
  if (back.amount !== String(unit.amount) || back.units !== String(unit.units)) {
    fail(`the unit reads back as ${back.amount} ${back.units}, but the wire holds ${unit.amount} ${unit.units}`);
  }
  if (back.from !== '2026-10-01' || back.to !== '2026-10-31') fail(`the window read back as ${back.from} → ${back.to}`);
  if (back.algo !== algoOnWire.plaSpecId || back.characteristic !== String(algoOnWire.characteristic)) {
    fail(`the algorithm reads back as ${back.algo}/${back.characteristic}, but the wire holds ${algoOnWire.plaSpecId}/${algoOnWire.characteristic}`);
  }
  ok(`READ BACK: reopening the price shows exactly what is stored — per ${unit.amount} ${unit.units}, 1–31 October, per unit above a threshold on ${algoOnWire.characteristic}`);

  await browser.close();

  /* ---------- leave the shelf as we found it ---------- */
  await call('DELETE', `${CAT}/productOfferingPrice/${price.id}`, staff);
  await call('DELETE', `${CAT}/productSpecification/${spec.id}`, staff);

  console.log('\nALL PRICE-BUILDER CHECKS PASSED — a price is built from controls and stores the payload the JSON box'
    + ' wrote, a numeric choice stays a number, and a key no control shows survives an edit.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
