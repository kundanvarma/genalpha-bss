/* End-to-end: sign in to the catalog console through Keycloak, verify the
 * seeded GenAlpha One bundle renders, edit it to check the pickers populate,
 * then create + delete a throwaway offering via the new form controls. */
const { chromium } = require('playwright');

const CONSOLE = 'http://localhost:8080/console/';

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
  const bundleRow = page.locator('#listing-body tr', { hasText: 'GenAlpha One Home & Mobile' });
  if (!(await bundleRow.count())) fail('bundle row not visible in Product Offerings');
  const cells = await bundleRow.locator('td').allTextContents();
  if (cells[2] !== 'yes') fail(`isBundle column expected "yes", got "${cells[2]}"`);
  console.log('OK bundle row:', cells.slice(0, 4).join(' | '));

  // 3. Edit the bundle: spec picker + bundle multiselect populate
  await bundleRow.locator('button', { hasText: 'Edit' }).click();
  await page.waitForFunction(() =>
    document.querySelectorAll('select[name="bundledProductOffering"] option').length >= 4);
  const selected = await page.locator('select[name="bundledProductOffering"] option:checked').allTextContents();
  // 3 mandatory components + the optional Sports Pass add-on (choice groups are separate)
  if (selected.length !== 4) fail(`expected 4 plain bundled offerings selected, got ${selected.length}`);
  const isBundleChecked = await page.locator('input[name="isBundle"]').isChecked();
  if (!isBundleChecked) fail('isBundle checkbox not checked when editing the bundle');
  const selfDisabled = await page.evaluate(() =>
    [...document.querySelectorAll('select[name="bundledProductOffering"] option')]
      .find((o) => o.text === 'GenAlpha One Home & Mobile')?.disabled);
  if (!selfDisabled) fail('bundle should not be able to contain itself');
  console.log('OK edit form: 3 plain children selected, isBundle checked, self-reference disabled');

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

  // 4. Prices tab renders Money and charge period
  await page.locator('.tab', { hasText: 'Prices' }).click();
  const fiberRow = page.locator('#listing-body tr', { hasText: 'Fiber 1000 Monthly' });
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
  const newRow = page.locator('#listing-body tr', { hasText: 'E2E Throwaway Price' });
  await newRow.waitFor({ timeout: 10000 });
  const newCells = await newRow.locator('td').allTextContents();
  if (!newCells[2].includes('9.99')) fail(`created price shows "${newCells[2]}"`);
  console.log('OK created via GUI:', newCells.slice(0, 3).join(' | '));
  page.on('dialog', (d) => d.accept());
  await newRow.locator('button', { hasText: 'Delete' }).click();
  await newRow.waitFor({ state: 'detached', timeout: 10000 });
  console.log('OK deleted via GUI');

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

  // 9. Role-scoped tabs: a product manager sees the product area — and ONLY it.
  const patCtx = await browser.newContext();
  const pat = await patCtx.newPage();
  await pat.goto('http://localhost:8080/console/');
  await pat.waitForSelector('input[name="username"]', { timeout: 20000 });
  await pat.fill('input[name="username"]', 'pat@bss.local');
  await pat.fill('input[name="password"]', 'pat');
  await pat.click('input[type="submit"], button[type="submit"]');
  await pat.waitForSelector('.tab', { timeout: 20000 });
  const patTabs = (await pat.locator('.tab').allTextContents()).map((t) => t.trim());
  const expectPat = ['Product Offerings', 'Product Specifications', 'Product Offering Prices',
    'Product Stock', 'Serviceable Areas', 'Rules'];
  for (const t of expectPat) {
    if (!patTabs.includes(t)) fail(`product persona missing tab "${t}" — got ${patTabs.join(', ')}`);
  }
  for (const t of ['Customer Bills', 'Appointments', 'Campaigns', 'Porting', 'AI Audit']) {
    if (patTabs.includes(t)) fail(`product persona must NOT see "${t}" — got ${patTabs.join(', ')}`);
  }
  console.log('OK role-scoped console: product-pat sees only', patTabs.join(' | '));

  await browser.close();
  console.log('\nALL CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
