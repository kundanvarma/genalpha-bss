/* Console door gate — a shop customer's SSO session must NEVER reach the back office. Suite #104.
 *
 * Same-realm single sign-on: a customer who logs into the shop has a live
 * Keycloak session, so opening the staff console silently completes the OIDC
 * flow AS THE CUSTOMER (no re-prompt). The danger: the baseline customer
 * composite legitimately holds billing:read, ordering:write, service:read… —
 * so per-tab role gates alone would let a customer see Reporting/Orders. The
 * fix is a DOOR gate: the console refuses anyone who holds only customer
 * baseline roles, before any tab or datum renders.
 *
 *  - CUSTOMER BLOCKED: shop login → console → the wrong-persona guard, ZERO
 *    tabs, ZERO data rows, a "switch account" escape.
 *  - STAFF PASSES: a staff login → console renders the full workspace.
 */
const { chromium } = require('playwright');
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);

async function shopLogin(page, user, pass) {
  await page.goto('http://localhost:8080/shop/');
  await page.locator('.who >> text=Sign in').click();
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', user);
  await page.fill('input[name="password"]', pass);
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.nav', { timeout: 20000 });
}

(async () => {
  const browser = await chromium.launch();
  try {
    /* ---------- 1. a CUSTOMER carried in by SSO is blocked ---------- */
    const cctx = await browser.newContext();
    const cshop = await cctx.newPage();
    await shopLogin(cshop, 'kai@bss.local', 'kai'); // kai = the demo CUSTOMER persona
    const ccon = await cctx.newPage();
    await ccon.goto('http://localhost:8080/console/');
    await ccon.waitForTimeout(4000);
    const c = await ccon.evaluate(() => ({
      guard: !!document.querySelector('[data-testid=wrong-persona]'),
      sw: !!document.querySelector('[data-testid=switch-account]'),
      tabs: document.querySelectorAll('.tab').length,
      rows: document.querySelectorAll('#listing-body tr').length,
      signin: document.getElementById('signin')?.hidden === false,
    }));
    if (c.signin) fail('the console showed its own sign-in — SSO did not carry the session, test inconclusive');
    if (!c.guard || !c.sw) fail('the wrong-persona guard did not render for a carried-over customer');
    if (c.tabs !== 0) fail(`a customer saw ${c.tabs} back-office tab(s) — LEAK`);
    if (c.rows !== 0) fail(`a customer saw ${c.rows} data row(s) — LEAK`);
    ok('CUSTOMER BLOCKED: shop SSO carried kai into the console → guard shown, ZERO tabs, ZERO data, switch offered');

    /* ---------- 2. STAFF still get the full console ---------- */
    const sctx = await browser.newContext();
    const sshop = await sctx.newPage();
    await shopLogin(sshop, 'demo', 'demo'); // demo = staff
    const scon = await sctx.newPage();
    await scon.goto('http://localhost:8080/console/');
    await scon.waitForSelector('.tab', { timeout: 20000 });
    const s = await scon.evaluate(() => ({
      tabs: document.querySelectorAll('.tab').length,
      guard: !!document.querySelector('[data-testid=wrong-persona]'),
    }));
    if (s.guard) fail('a STAFF member was wrongly blocked by the door gate');
    if (s.tabs < 5) fail(`staff saw only ${s.tabs} tabs — the console did not render`);
    ok(`STAFF PASSES: the staff session renders the full console (${s.tabs} tabs), no guard`);

    /* ---------- 3. the CARE PORTAL (csr-console) blocks a customer too ---------- */
    const kctx = await browser.newContext();
    const kshop = await kctx.newPage();
    await shopLogin(kshop, 'kai@bss.local', 'kai');
    const kcsr = await kctx.newPage();
    await kcsr.goto('http://localhost:8080/csr/');
    await kcsr.waitForTimeout(4500);
    const kc = await kcsr.evaluate(() => ({
      guard: !!document.querySelector('[data-testid=wrong-persona]'),
      leak: document.body.innerText.includes('Customers') && document.body.innerText.includes('Tickets'),
    }));
    if (!kc.guard || kc.leak) fail('the CSR care portal did not block a carried-over customer');
    ok('CARE PORTAL: the csr-console also refuses a shop customer at the door — no customer 360, no tickets');

    console.log('\nALL CONSOLE-DOOR-GATE CHECKS PASSED — same-realm SSO can carry a shop session into the'
      + ' staff portal, but a customer is refused at the door (no tabs, no data), while staff pass'
      + ' unchanged. Hiding a tab was ergonomics; this is the boundary.');
  } finally {
    await browser.close();
  }
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
