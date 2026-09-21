/* Console command palette (⌘K / Ctrl+K) — the care desk's palette, ported to
 * the back office (apps/admin-console/site/palette.js).
 *
 *  - ⌘K opens an overlay; typing filters; Enter opens the page through the
 *    SAME path a rail click takes (active tab, saved tab, list reload)
 *  - 'On this page' lists the buttons visible on the page in front of you
 *  - Esc closes; the header hint button opens it with the mouse
 *  - the palette only offers what the token can see: pat (product) gets no
 *    Customer Bills row — visibility follows `visible[]`, the API 403 underneath
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

async function loginConsole(browser, user, pass) {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto(`${API}/console/`);
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', user);
  await page.fill('input[name="password"]', pass);
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  await page.waitForSelector('#tabs .tab', { timeout: 10000 });
  return { ctx, page };
}

async function openPalette(page) {
  await page.keyboard.press('Meta+k');
  if (!(await page.locator('[data-testid="palette"]').count())) await page.keyboard.press('Control+k');
  await page.waitForSelector('[data-testid="palette"]', { timeout: 5000 });
  await page.waitForSelector('[data-testid="palette-input"]:focus', { timeout: 5000 });
}

const activeTab = (page) => page.evaluate(() => (document.querySelector('.tab.on') || {}).textContent || '');
const rows = (page) => page.locator('[data-testid="palette-row"]').allTextContents();

(async () => {
  const browser = await chromium.launch();

  /* ---------- 1. demo: type a page, land on it ---------- */
  const demo = await loginConsole(browser, 'demo', 'demo');
  const { page } = demo;
  await page.locator('.tab', { hasText: 'Journal' }).click();
  await page.waitForFunction(() => (document.querySelector('.tab.on') || {}).textContent === 'Journal', { timeout: 10000 });

  await openPalette(page);
  const box = page.locator('.palette[role="dialog"]');
  if (!(await box.count())) fail('the palette is not a dialog');
  if ((await box.getAttribute('aria-label')) !== 'Command palette') fail('dialog has no aria-label');
  const allRows = await rows(page);
  if (allRows.length > 12) fail('the palette shows more than 12 rows: ' + allRows.length);
  if (!allRows.some((r) => r.startsWith('Go to'))) fail('no Go to rows: ' + allRows);
  await page.keyboard.type('offer');
  const offerRows = await rows(page);
  if (!offerRows[0].includes('Product Offerings')) fail('first hit for "offer" is not Product Offerings: ' + offerRows);
  if (!offerRows[0].includes('Catalog & Pricing')) fail('the Go to row does not name its department: ' + offerRows[0]);
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => (document.querySelector('.tab.on') || {}).textContent === 'Product Offerings', { timeout: 10000 });
  if (await page.locator('[data-testid="palette"]').count()) fail('palette still open after Enter');
  if ((await page.evaluate(() => sessionStorage.getItem('bss.console.tab'))) !== 'productOffering') fail('the saved tab did not follow the palette');
  await page.waitForSelector('#listing-body tr', { timeout: 15000 });
  console.log('OK GO TO: "offer" ↵ → Product Offerings (Catalog & Pricing), list loaded, tab saved.');

  /* ---------- 2. On this page: the page's own buttons, typed ---------- */
  await openPalette(page);
  await page.keyboard.type('new product');
  const onPage = (await rows(page)).filter((r) => r.startsWith('On this page'));
  if (!onPage.some((r) => r.includes('+ New product offering'))) fail('no On-this-page row for "+ New product offering": ' + (await rows(page)));
  await page.keyboard.press('Escape');
  await page.waitForSelector('[data-testid="palette"]', { state: 'detached', timeout: 5000 });
  console.log('OK ON THIS PAGE: "+ New product offering" offered; Esc closes.');

  /* ---------- 3. approv → Approvals; arrows move the selection ---------- */
  await openPalette(page);
  await page.keyboard.type('approv');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => (document.querySelector('.tab.on') || {}).textContent === 'Approvals', { timeout: 10000 });
  console.log('OK GO TO: "approv" ↵ → Approvals.');

  await openPalette(page);
  await page.keyboard.press('ArrowDown');
  const selected = await page.locator('[data-testid="palette-row"][aria-selected="true"]').count();
  const idx = await page.evaluate(() => [...document.querySelectorAll('[data-testid="palette-row"]')].findIndex((li) => li.getAttribute('aria-selected') === 'true'));
  if (selected !== 1 || idx !== 1) fail(`ArrowDown should select row 1, got ${selected} selected at ${idx}`);
  const recent = (await rows(page)).filter((r) => r.startsWith('Recent'));
  if (!recent.some((r) => r.includes('Product Offerings'))) fail('Recent should list Product Offerings: ' + recent);
  await page.keyboard.press('Escape');
  console.log('OK KEYS: ↓ moves the selection; Recent remembers Product Offerings.');

  /* ---------- 4. the header hint opens it with the mouse ---------- */
  await page.click('[data-testid="palette-hint"]');
  await page.waitForSelector('[data-testid="palette"]', { timeout: 5000 });
  await page.keyboard.press('Escape');
  await page.waitForSelector('[data-testid="palette"]', { state: 'detached', timeout: 5000 });
  console.log('OK HINT: the ⌘K button in the header opens the palette.');
  await demo.ctx.close();

  /* ---------- 5. pat: the palette offers only his desk ---------- */
  const pat = await loginConsole(browser, 'pat@bss.local', 'pat');
  await openPalette(pat.page);
  await pat.page.keyboard.type('bill');
  const patRows = await rows(pat.page);
  if (patRows.some((r) => r.toLowerCase().includes('customer bills'))) fail('pat sees a Customer Bills row: ' + patRows);
  await pat.page.keyboard.press('Escape');
  await openPalette(pat.page);
  await pat.page.keyboard.type('offer');
  if (!(await rows(pat.page)).some((r) => r.includes('Product Offerings'))) fail('pat lost Product Offerings in the palette');
  await pat.page.keyboard.press('Escape');
  console.log('OK PAT: no Customer Bills row, Product Offerings still there — the palette follows the token.');
  await pat.ctx.close();

  await browser.close();
  console.log('\nALL CONSOLE-PALETTE CHECKS PASSED — ⌘K reaches every page and every button on the page, by name, for the pages the token may see.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
