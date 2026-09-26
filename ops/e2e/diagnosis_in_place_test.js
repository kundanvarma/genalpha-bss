/* Suite #241 — the diagnosis report belongs to the line it is about.
 *
 * Reported from the demo box: an agent clicks Diagnose on the TV service and
 * the answer appears at the FOOT of the services card, below every other line.
 * On a customer with several services it lands off screen entirely, so the
 * agent reads a verdict without seeing which service it judged.
 *
 * The report always knew its service; nothing used it. This suite pins the
 * DOM position: the report must be the next sibling of the row whose own
 * Diagnose button was clicked — not somewhere in the same card, not at the
 * end. Position is the whole point, so position is what is asserted.
 *
 * Proven to fail: rendering the report only at the foot of the list (the
 * behaviour as reported) puts it at index 7 with its row at index 1.
 */
const { chromium } = require('playwright');

const CSR = 'http://localhost:8080/csr/';
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

(async () => {
  const browser = await chromium.launch();
  const page = await (await browser.newContext()).newPage({ viewport: { width: 1500, height: 1050 } });

  await page.goto(CSR);
  // A fresh context always meets the IdP. Checking for the form without
  // waiting races the redirect: count() is 0 while the browser is still on
  // its way to Keycloak, the sign-in is skipped, and the desk never appears.
  await page.waitForSelector('input[name="username"]', { timeout: 30000 });
  await page.fill('input[name="username"]', 'agent-anna');
  await page.fill('input[name="password"]', 'agent');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.searchbar', { timeout: 40000 });

  // An agent reaches a customer by searching, so the suite does too.
  await page.fill('[data-testid="cust-search"]', 'Paula');
  await page.waitForTimeout(2500);
  const hit = page.locator('a[href*="/customer/"], [data-testid="cust-result"]').first();
  if (!(await hit.count())) fail('the search found no customer named Paula — is the demo data seeded?');
  await hit.click();
  await page.waitForSelector('[data-testid="services-list"]', { timeout: 40000 });
  await page.waitForTimeout(2500);

  const buttons = page.locator('[data-testid="csr-diagnose"]');
  const n = await buttons.count();
  if (n < 2) fail(`placement is only meaningful with several services; this customer has ${n} diagnosable`);
  console.log(`OK ${n} diagnosable services — diagnosing the FIRST, so a foot-of-list report would be far away`);

  await buttons.first().click();
  await page.waitForSelector('[data-testid="csr-diagnosis"]', { timeout: 40000 });
  await page.waitForTimeout(1200);

  const placement = await page.evaluate(() => {
    const list = document.querySelector('[data-testid="services-list"]');
    const kids = [...list.children];
    const report = document.querySelector('[data-testid="csr-diagnosis"]');
    const row = document.querySelector('[data-testid="csr-diagnose"]').closest('.row.svc');
    return { report: kids.indexOf(report), row: kids.indexOf(row), total: kids.length,
      said: (report.innerText || '').split('\n')[0].slice(0, 60) };
  });
  if (placement.report < 0 || placement.row < 0) fail('report or its row is not a child of the services list');
  if (placement.report !== placement.row + 1) {
    fail(`the report sits at ${placement.report} and its service row at ${placement.row} of ${placement.total}`
      + ' — it must be directly under the row that was diagnosed');
  }
  console.log(`OK the report is directly under its own row (${placement.row} → ${placement.report} of ${placement.total})`);
  console.log(`OK it names the service it judged: "${placement.said}"`);

  // The verdict must still say WHICH service, so a screenshot is self-explaining.
  if (!/:/.test(placement.said)) fail('the report does not name its service');

  await browser.close();
  console.log('PASS diagnosis_in_place_test');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
