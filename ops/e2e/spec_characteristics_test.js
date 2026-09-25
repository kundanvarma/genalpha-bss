/* Characteristics are rows, and the payload is still the payload. Suite #234.
 *
 * A product manager cannot write JSON, and the back office used to ask them to:
 * the specification's characteristics were a raw TMF620 array in a textarea.
 * They are rows now — a name, a kind, its value or values, and whether the
 * customer chooses between them. This suite proves the two things that make
 * that safe rather than merely nicer:
 *
 *  - ROUND TRIP: a specification carrying the shapes the editor does NOT model
 *    (a description, a unit of measure, a default value, a range the tier
 *    pricing reads) is opened and saved untouched, and the stored array comes
 *    back deep-equal. An editor that quietly flattened those would be worse
 *    than the box it replaced.
 *  - AUTHORED: three rows typed by hand — a plain fact, a configurable one with
 *    three choices, and a technical lever picked by its words — are stored in
 *    exactly the shape the JSON box documented.
 *
 * Fixtures are timestamp-named and deleted at the end.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KCB = 'http://localhost:8085';
const CONSOLE = `${API}/console/`;
const CAT = '/tmf-api/productCatalogManagement/v4';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const run = Date.now();
const tag = `SUITE${run}`;

async function token() {
  const r = await fetch(`${KCB}/realms/bss/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' }),
  });
  const j = await r.json();
  if (!j.access_token) fail('staff token refused');
  return j.access_token;
}
async function call(method, p, tok, body) {
  const r = await fetch(API + p, {
    method,
    headers: { Authorization: `Bearer ${tok}`, ...(body ? { 'Content-Type': 'application/json' } : {}), 'Cache-Control': 'no-cache' },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/* Everything the editor does not model, in one specification: a description, a
 * per-value default and type, a unit of measure, and a range. */
const RICH = [
  { name: 'Data', valueType: 'string', configurable: false, productSpecCharacteristicValue: [{ value: '20 GB', isDefault: true }] },
  { name: 'screens', description: 'How many screens play at once', configurable: true,
    productSpecCharacteristicValue: [{ value: '2', valueType: 'number', isDefault: true }, { value: '4', valueType: 'number' }] },
  { name: 'extraProfiles', configurable: true,
    productSpecCharacteristicValue: [{ valueFrom: 1, valueTo: 5, rangeInterval: 'closed', unitOfMeasure: 'profiles' }] },
];

async function openSpec(page, name) {
  await page.goto(CONSOLE);
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  await page.locator('.tab', { hasText: 'Product Specifications' }).first().click();
  await page.waitForSelector('#listing-body tr', { timeout: 15000 });
  // the catalog does not order by recency and pages at ten, so a fresh
  // specification lands anywhere in ~15 pages: walk them all
  for (let hop = 0; hop < 40; hop++) {
    const row = page.locator('#listing-body tr', { hasText: name });
    if (await row.count()) { await row.locator('button', { hasText: 'Open' }).click(); return; }
    const next = page.locator('#next');
    if (!(await next.count()) || await next.isDisabled()) break;
    await next.click(); await page.waitForTimeout(350);
  }
  fail(`specification '${name}' not found in the list`);
}

(async () => {
  const staff = await token();
  const made = [];
  const spec = async (name, chars) => {
    const s = await call('POST', `${CAT}/productSpecification`, staff,
      { name: `${tag} ${name}`, brand: 'GenAlpha', lifecycleStatus: 'Active', productSpecCharacteristic: chars });
    if (s.status !== 201) fail(`creating '${name}': ${s.status} ${s.text.slice(0, 160)}`);
    made.push(s.body.id);
    return s.body;
  };

  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  page.on('pageerror', (e) => console.log('PAGE ERROR:', e.message));
  await page.goto(CONSOLE);
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });

  /* ---------- 1. the editor is rows, not a textarea ---------- */
  const rich = await spec('rich spec', RICH);
  await openSpec(page, rich.name);
  await page.waitForSelector('[data-testid="characteristics"]', { timeout: 15000 });
  if (await page.locator('textarea[name="productSpecCharacteristic"]').count()) fail('the characteristics JSON box is still there');
  const rows = page.locator('[data-char-row]');
  if ((await rows.count()) !== RICH.length) fail(`expected ${RICH.length} rows, got ${await rows.count()}`);
  const rangeRow = (await rows.nth(2).innerText()).trim();
  if (!/range from 1 to 5 profiles/.test(rangeRow)) fail(`the range is not stated in words: "${rangeRow}"`);
  ok(`ROWS: ${await rows.count()} characteristics as rows; the range reads "${rangeRow}"`);

  /* ---------- 2. opening and saving changes nothing ---------- */
  await page.click('#save');
  await page.waitForTimeout(2000);
  const after = (await call('GET', `${CAT}/productSpecification/${rich.id}`, staff)).body;
  if (!same(after.productSpecCharacteristic, RICH)) {
    fail('saving an untouched specification changed it:\n  before ' + JSON.stringify(RICH) + '\n  after  ' + JSON.stringify(after.productSpecCharacteristic));
  }
  ok('ROUND TRIP: a description, a default, a unit of measure and a range all survive an open-and-save untouched');

  /* ---------- 3. three rows typed by hand ---------- */
  const blank = await spec('authored spec', []);
  await openSpec(page, blank.name);
  await page.waitForSelector('[data-testid="characteristics"]', { timeout: 15000 });
  const addRow = async () => { await page.locator('[data-testid="add-characteristic"]').click(); return page.locator('[data-char-row]').last(); };
  while (await page.locator('[data-char-row]').count()) await page.locator('[data-char-row]').first().locator('button', { hasText: '×' }).click();

  const plain = await addRow();
  await plain.locator('[data-testid="char-name"]').fill('Data');
  await plain.locator('[data-testid="char-value"]').fill('20 GB');

  const choice = await addRow();
  await choice.locator('[data-testid="char-name"]').fill('rooms');
  await choice.locator('[data-testid="char-configurable"]').check();
  await choice.locator('[data-testid="char-value"]').first().fill('1-2');
  for (const v of ['3-4', '5+']) {
    await choice.locator('button', { hasText: '+ value' }).click();
    await choice.locator('[data-testid="char-value"]').last().fill(v);
  }

  // a technical lever: offered by its words, stored by its technical name
  const lever = await addRow();
  const options = await page.locator('#consumed-names option').evaluateAll((els) => els.map((e) => `${e.label}=${e.value}`));
  if (!options.includes('Charging plan=chargingSpecId')) fail(`the levers are not offered by their words: ${options.join(', ')}`);
  await lever.locator('[data-testid="char-name"]').fill('chargingSpecId');
  await lever.locator('[data-testid="char-value"]').fill('rate-plan-basic');
  const said = (await lever.innerText()).trim();
  if (!/read as a charging plan/.test(said)) fail(`the row does not say what the name means: "${said}"`);

  await page.click('#save');
  await page.waitForTimeout(2000);
  const authored = (await call('GET', `${CAT}/productSpecification/${blank.id}`, staff)).body;
  const want = [
    { name: 'Data', valueType: 'string', configurable: false, productSpecCharacteristicValue: [{ value: '20 GB' }] },
    { name: 'rooms', valueType: 'string', configurable: true, productSpecCharacteristicValue: [{ value: '1-2' }, { value: '3-4' }, { value: '5+' }] },
    { name: 'chargingSpecId', valueType: 'string', configurable: false, productSpecCharacteristicValue: [{ value: 'rate-plan-basic' }] },
  ];
  if (!same(authored.productSpecCharacteristic, want)) {
    fail('the authored rows are not the shape the JSON box documented:\n  want ' + JSON.stringify(want) + '\n  got  ' + JSON.stringify(authored.productSpecCharacteristic));
  }
  ok('AUTHORED: a plain fact, a three-choice picker and a technical lever stored exactly as the JSON box documented');

  /* ---------- 4. reopening shows the same rows ---------- */
  await openSpec(page, blank.name);
  await page.waitForSelector('[data-char-row]', { timeout: 15000 });
  const names = await page.locator('[data-testid="char-name"]').evaluateAll((els) => els.map((e) => e.value));
  if (!same(names, ['Data', 'rooms', 'chargingSpecId'])) fail(`reopening shows ${names.join(', ')}`);
  const choiceValues = await page.locator('[data-char-row]').nth(1).locator('[data-testid="char-value"]').evaluateAll((els) => els.map((e) => e.value));
  if (!same(choiceValues, ['1-2', '3-4', '5+'])) fail(`the three choices came back as ${choiceValues.join(', ')}`);
  ok('REOPENED: the rows read back, the three choices intact, no JSON in sight');

  for (const id of made) await call('DELETE', `${CAT}/productSpecification/${id}`, staff);
  await browser.close();
  console.log('\nALL SPEC-CHARACTERISTICS CHECKS PASSED — characteristics are rows a product manager can fill in,'
    + ' the shapes the editor does not model survive untouched, and the payload is still the payload.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
