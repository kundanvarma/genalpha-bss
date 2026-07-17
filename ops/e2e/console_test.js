/* End-to-end: sign in to the catalog console through Keycloak, verify the
 * seeded GenAlpha One bundle renders, edit it to check the pickers populate,
 * then create + delete a throwaway offering via the new form controls. */
const { chromium } = require('playwright');

const CONSOLE = 'http://localhost:8080/console/';
const run = Date.now();

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };

  // 1. Login through Keycloak
  await page.goto(CONSOLE);
  await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  console.log('OK login, signed in as:', await page.locator('#username').textContent());

  // 2. Offerings table shows the bundle with isBundle=yes
  await page.waitForSelector('#listing-body tr');
  // the catalog accumulates run-unique offerings from other suites — the
  // bundle may live pages deep; walk the pager to it
  let bundleRow = page.locator('#listing-body tr', { hasText: 'GenAlpha One Home & Mobile' });
  for (let hop = 0; hop < 30 && !(await bundleRow.count()); hop++) {
    if (await page.locator('#next').isDisabled()) break;
    await page.click('#next');
    await page.waitForTimeout(400);
    bundleRow = page.locator('#listing-body tr', { hasText: 'GenAlpha One Home & Mobile' });
  }
  if (!(await bundleRow.count())) fail('bundle row not visible in Product Offerings');
  const cells = await bundleRow.locator('td').allTextContents();
  if (cells[2] !== 'yes') fail(`isBundle column expected "yes", got "${cells[2]}"`);
  console.log('OK bundle row:', cells.slice(0, 4).join(' | '));

  // 3. Edit the bundle: the COMPOSER renders components AND the choice group
  await bundleRow.locator('button', { hasText: 'Edit' }).click();
  await page.waitForFunction(() =>
    document.querySelectorAll('[data-composer-row]').length >= 5);
  const componentRows = await page.locator('[data-composer-row="component"]').count();
  const choiceRows = await page.locator('[data-composer-row="choice"]').count();
  // 3 mandatory + optional Sports Pass as component rows; the phone choice as its own row
  if (componentRows !== 4) fail(`expected 4 component rows, got ${componentRows}`);
  if (choiceRows !== 1) fail(`expected 1 choice-group row, got ${choiceRows}`);
  const roles = [];
  for (let i = 0; i < componentRows; i++) {
    roles.push(await page.locator('[data-composer-row="component"]').nth(i)
      .locator('select').nth(1).inputValue());
  }
  if (roles.filter((r) => r === 'optional').length !== 1) {
    fail('exactly one optional add-on (Sports Pass) expected, roles: ' + roles);
  }
  const isBundleChecked = await page.locator('input[name="isBundle"]').isChecked();
  if (!isBundleChecked) fail('isBundle checkbox not checked when editing the bundle');
  console.log('OK composer: 4 components (Sports Pass optional) + the phone choice group as rows');

  // Saving from the console must not destroy the phone choice group (an
  // API-managed entry the form cannot render).
  await page.click('#save');
  await page.waitForSelector('#editor-title:has-text("New")', { timeout: 10000 });
  const bundleAfter = await page.evaluate(async () => {
    const res = await authFetch(
      '/tmf-api/productCatalogManagement/v4/productOffering?limit=100&isBundle=true');
    return (await res.json())[0];
  });
  const choice = (bundleAfter.bundledProductOffering || []).find((e) => Array.isArray(e.options));
  if (!choice || choice.options.length !== 3) {
    fail('choice group lost on console save: ' + JSON.stringify(bundleAfter.bundledProductOffering));
  }
  console.log('OK console save preserves the phone choice group (3 options)');

  // 3b. AUTHOR a bundle with a choice group entirely in the UI — no JSON
  await page.fill('input[name="name"]', `E2E Composer Bundle ${run}`);
  await page.locator('input[name="isBundle"]').check();
  await page.selectOption('select[name="productOfferingTerm"]', '12');
  await page.locator('[data-composer-add="component"]').click();
  await page.locator('[data-composer-row="component"]').last().locator('select').first()
    .selectOption({ label: 'GenAlpha Fiber 1000' });
  await page.locator('[data-composer-add="choice"]').click();
  const newChoice = page.locator('[data-composer-row="choice"]').last();
  await newChoice.locator('input[type="text"], input:not([type])').first().fill('Pick your plan');
  await newChoice.locator('input[type="number"]').nth(0).fill('1');
  await newChoice.locator('input[type="number"]').nth(1).fill('2');
  await newChoice.locator('select[multiple]').selectOption([
    { label: 'GenAlpha Mobile 10 GB' }, { label: 'GenAlpha Mobile 50 GB' }]);
  await page.click('#save');
  let authored = null;
  for (let i = 0; i < 10 && !authored; i++) {
    await page.waitForTimeout(1000);
    authored = await page.evaluate(async (name) => {
      const res = await authFetch('/tmf-api/productCatalogManagement/v4/productOffering?limit=100');
      return (await res.json()).find((o) => o.name === name) || null;
    }, `E2E Composer Bundle ${run}`);
  }
  if (!authored) {
    const err = await page.locator('.error, #form-error').first().textContent().catch(() => '(no error shown)');
    fail('composer bundle never saved — form says: ' + err);
  }
  const authoredChoice = (authored.bundledProductOffering || []).find((e) => Array.isArray(e.options));
  const authoredFixed = (authored.bundledProductOffering || []).find((e) =>
    e.bundledProductOfferingOption?.numberRelOfferLowerLimit === 1);
  if (!authored.isBundle || !authoredFixed || !authoredChoice
      || authoredChoice.numberRelOfferLowerLimit !== 1 || authoredChoice.numberRelOfferUpperLimit !== 2
      || authoredChoice.options.length !== 2
      || authored.productOfferingTerm?.[0]?.duration?.amount !== 12) {
    fail('composer-authored bundle wrong: ' + JSON.stringify(authored?.bundledProductOffering));
  }
  await page.evaluate(async (id) => authFetch(
    `/tmf-api/productCatalogManagement/v4/productOffering/${id}`, { method: 'DELETE' }), authored.id);
  console.log('OK a product owner AUTHORED a pick-1-2 bundle + 12-month term in the UI — zero JSON');

  // 4. Prices tab renders Money and charge period — the listing pages at 10
  // rows and the catalog grows, so page forward until the row appears
  await page.locator('.tab', { hasText: 'Prices' }).click();
  const fiberRow = page.locator('#listing-body tr', { hasText: 'Fiber 1000 Monthly' });
  for (let i = 0; i < 8 && !(await fiberRow.count()); i++) {
    await page.waitForTimeout(800);
    if (!(await fiberRow.count()) && !(await page.locator('#next').isDisabled())) {
      await page.click('#next');
    }
  }
  await fiberRow.waitFor({ timeout: 10000 });
  const priceCells = await fiberRow.locator('td').allTextContents();
  if (!priceCells[2].includes('39.99') || !priceCells[2].includes('EUR')) {
    fail(`price column expected "39.99 EUR", got "${priceCells[2]}"`);
  }
  if (priceCells[3] !== 'month') fail(`charge period expected "month", got "${priceCells[3]}"`);
  console.log('OK price row:', priceCells.slice(0, 4).join(' | '));

  // 5. Create a price via the form (money control), then delete it
  await page.fill('input[name="name"]', 'E2E Throwaway Price');
  await page.fill('input[name="priceType"]', 'oneTime');
  await page.fill('.moneyrow input:not(.unit)', '9.99');
  await page.fill('.moneyrow input.unit', 'EUR');
  await page.click('#save');
  // the price list outgrew one listing page — verify (and clean) via the API
  let throwaway = null;
  for (let i = 0; i < 10 && !throwaway; i++) {
    await page.waitForTimeout(1000);
    throwaway = await page.evaluate(async () => {
      const res = await authFetch('/tmf-api/productCatalogManagement/v4/productOfferingPrice?limit=100');
      return (await res.json()).find((p) => p.name === 'E2E Throwaway Price') || null;
    });
  }
  if (!throwaway || Number(throwaway.price?.value) !== 9.99) {
    fail('created price wrong: ' + JSON.stringify(throwaway?.price));
  }
  console.log('OK created via GUI: E2E Throwaway Price | oneTime | 9.99', throwaway.price.unit);
  await page.evaluate(async (id) => authFetch(
    `/tmf-api/productCatalogManagement/v4/productOfferingPrice/${id}`, { method: 'DELETE' }), throwaway.id);
  console.log('OK deleted');

  // 6. Product Stock tab (TMF687 service behind a different API base)
  await page.locator('.tab', { hasText: 'Product Stock' }).click();
  const stockRow = page.locator('#listing-body tr', { hasText: 'Samsung Galaxy S26' });
  await stockRow.waitFor({ timeout: 10000 });
  const stockCells = await stockRow.locator('td').allTextContents();
  if (!/\d+ unit/.test(stockCells[2])) fail(`stocked quantity cell wrong: "${stockCells[2]}"`);
  if (!/\d+ unit/.test(stockCells[4])) fail(`available quantity cell wrong: "${stockCells[4]}"`);
  console.log('OK stock tab:', stockCells.slice(0, 5).join(' | '));

  // 7. Rules tab: the dry-run panel tests the live engine without placing anything
  await page.locator('.tab', { hasText: 'Rules' }).click();
  await page.waitForSelector('#test-order', { timeout: 10000 });
  await page.fill('#test-subtotal', '200');
  await page.click('#test-order');
  await page.waitForFunction(() => /ALLOWED|DENIED/.test(
    document.getElementById('test-result')?.textContent || ''), null, { timeout: 10000 });
  const orderVerdict = await page.locator('#test-result').textContent();
  console.log('OK rules dry-run (order):', orderVerdict.trim());
  await page.click('#test-price');
  await page.waitForFunction(() => /price|total/.test(
    document.getElementById('test-result')?.textContent || ''), null, { timeout: 10000 });
  const priceVerdict = await page.locator('#test-result').textContent();
  if (!/200\.00/.test(priceVerdict)) fail(`dry-run price should mention 200.00, got "${priceVerdict}"`);
  console.log('OK rules dry-run (price):', priceVerdict.trim());

  // 8. Porting tab: back-office view of MNP orders with the cutover action
  await page.locator('.tab', { hasText: 'Porting' }).click();
  const portRow = page.locator('#listing-body tr', { hasText: '+47' });
  await portRow.first().waitFor({ timeout: 10000 });
  const portCells = await portRow.first().locator('td').allTextContents();
  console.log('OK porting tab:', portCells.slice(0, 4).join(' | '));

  // 9. Staff tab: grant Jo the AI tools area via TMF672, verify, revoke.
  await page.locator('.tab', { hasText: 'Staff' }).click();
  await page.waitForSelector('#staff-q', { timeout: 10000 });
  await page.fill('#staff-q', 'jo@bss.local');
  await page.click('#staff-search');
  await page.locator('.staffrow', { hasText: 'jo@bss.local' }).click();
  const aiBox = page.locator('.staffarea:has-text("AI tools") input');
  await aiBox.waitFor({ timeout: 10000 });
  if (await aiBox.isChecked()) fail('Jo should not start with AI tools');
  await aiBox.check();
  await page.locator('.staffstatus', { hasText: 'saved' }).waitFor({ timeout: 15000 });
  const withAi = await page.evaluate(async () => {
    const users = await (await authFetch(
      '/tmf-api/rolesAndPermissionsManagement/v4/user?username=jo@bss.local')).json();
    const perms = await (await authFetch(
      '/tmf-api/rolesAndPermissionsManagement/v4/permission?userId=' + users[0].id)).json();
    return perms.some((p) => p.userRole.name === 'ai:use');
  });
  if (!withAi) fail('grant did not land in the IdP');
  await aiBox.uncheck();
  await page.locator('.staffstatus', { hasText: 'saved' }).waitFor({ timeout: 15000 });
  const stillAi = await page.evaluate(async () => {
    const users = await (await authFetch(
      '/tmf-api/rolesAndPermissionsManagement/v4/user?username=jo@bss.local')).json();
    const perms = await (await authFetch(
      '/tmf-api/rolesAndPermissionsManagement/v4/permission?userId=' + users[0].id)).json();
    return perms.some((p) => p.userRole.name === 'ai:use');
  });
  if (stillAi) fail('revoke did not land in the IdP');
  console.log('OK Staff tab: granted + revoked "AI tools" for Jo through TMF672, verified in the IdP');

  // 10. Role-scoped tabs: a product manager sees the product area — and ONLY it.
  const patCtx = await browser.newContext();
  const pat = await patCtx.newPage();
  await pat.goto('http://localhost:8080/console/');
  await pat.waitForSelector('input[name="username"]', { timeout: 20000 });
  await pat.fill('input[name="username"]', 'pat@bss.local');
  await pat.fill('input[name="password"]', 'pat');
  await pat.click('input[type="submit"], button[type="submit"]');
  await pat.waitForSelector('.tab', { timeout: 20000 });
  const patTabs = (await pat.locator('.tab').allTextContents()).map((t) => t.trim());
  // pat gained ai:use with the Product Copilot (2026-07-13): Copilot and the
  // AI Audit trail ride along — auditability goes with AI power, by design
  const expectPat = ['Product Offerings', 'Product Specifications', 'Product Offering Prices',
    'Product Stock', 'Serviceable Areas', 'Rules', 'Copilot', 'AI Audit'];
  for (const t of expectPat) {
    if (!patTabs.includes(t)) fail(`product persona missing tab "${t}" — got ${patTabs.join(', ')}`);
  }
  for (const t of ['Customer Bills', 'Appointments', 'Campaigns', 'Porting', 'Staff']) {
    if (patTabs.includes(t)) fail(`product persona must NOT see "${t}" — got ${patTabs.join(', ')}`);
  }
  console.log('OK role-scoped console: product-pat sees only', patTabs.join(' | '));

  await browser.close();
  console.log('\nALL CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
