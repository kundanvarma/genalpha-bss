/* EMPTY IS NOT UNAVAILABLE, the shop's half (#289).
 *
 * Home reads sixteen things at once and is fail-soft so a bad day never blanks
 * it — but each failure used to become `[]` or `null`, and from those Home
 * built its sentences. With billing down it told a paying customer "Everything
 * looks good"; the Bills page's payment status read `null`, which means
 * "current", so a customer in collections saw no warning at all.
 *
 * Part 1 takes billing away for real (`docker stop bss-billing`) and asserts
 * Home makes no all-clear and Bills does not say "No bills yet". Part 2 brings
 * it back and asserts the notice clears. Part 3 needs the bills to load while
 * the payment-status read fails — the same component serves both, so a stopped
 * container cannot separate them; the browser answers that ONE route with a
 * 503 and everything else goes through the gateway with Paula's real token.
 */
const { chromium } = require('playwright');
const { execSync } = require('child_process');

const SHOP = 'http://localhost:8080/shop/';
const sh = (cmd) => execSync(cmd, { encoding: 'utf8' }).trim();
const healthy = (c) => sh(`docker inspect -f '{{.State.Health.Status}}' ${c}`) === 'healthy';
async function waitHealthy(c) {
  for (let i = 0; i < 60; i++) { if (healthy(c)) return; await new Promise((r) => setTimeout(r, 5000)); }
  throw new Error(`${c} did not come back healthy within 5 minutes`);
}
const ALL_CLEAR = /Everything (looks good|is working)/;

(async () => {
  const fail = (msg) => { throw new Error(msg); };
  if (!healthy('bss-billing')) fail('bss-billing is not healthy before the suite starts — nothing to prove');

  const browser = await chromium.launch();
  const page = await (await browser.newContext()).newPage();
  const health = async () => {
    const el = page.locator('[data-testid="home-health"]');
    // a stopped component costs ~30 s at the gateway's timeout before the read fails — wait past it
    await page.waitForFunction(() => !/Reading your services/.test(document.querySelector('[data-testid="home-health"]')?.innerText || 'Reading your services'), null, { timeout: 75000 });
    return (await el.innerText()).trim();
  };
  try {
    // one retry: right after the storefront is recreated the first redirect to Keycloak can stall
    for (let attempt = 1; ; attempt++) {
      await page.goto(SHOP); await page.click('.who >> text=Sign in');
      try { await page.waitForSelector('input[name="username"]', { timeout: 20000 }); break; } catch (e) { if (attempt === 2) throw e; }
    }
    await page.fill('input[name="username"]', 'paula@family.example');
    await page.fill('input[name="password"]', 'paula');
    await page.click('input[type="submit"], button[type="submit"]');
    await page.waitForSelector('[data-testid="home"]', { timeout: 30000 });

    // ---- 1. billing is down ------------------------------------------------
    sh('docker stop bss-billing');
    console.log('OK stopped bss-billing');
    await page.goto(SHOP); await page.waitForSelector('[data-testid="home"]', { timeout: 30000 });
    const down = await health();
    if (ALL_CLEAR.test(down)) fail(`billing is down and Home still says "${down}"`);
    if (!/could not check/.test(down) && !/need(s)? your attention/.test(down)) {
      fail(`billing is down and Home neither says what it could not check nor raises anything: "${down}"`);
    }
    console.log(`OK Home makes no all-clear with billing down: "${down}"`);

    await page.goto(`${SHOP}bills`);
    await page.waitForFunction(() => !/Loading your bills/.test(document.body.innerText), null, { timeout: 75000 });
    if (await page.locator('[data-testid="bills-empty"]').count()) fail('billing is down and the Bills page says "No bills yet"');
    console.log('OK the Bills page does not say "No bills yet" while billing is down');

    // ---- 2. billing is back ------------------------------------------------
    sh('docker start bss-billing'); await waitHealthy('bss-billing');
    console.log('OK bss-billing is healthy again');
    await page.goto(SHOP); await page.waitForSelector('[data-testid="home"]', { timeout: 30000 });
    const up = await health();
    if (/could not check/.test(up)) fail(`billing is back and Home still says it could not check: "${up}"`);
    console.log(`OK with billing back, Home's notice is gone: "${up}"`);

    // ---- 3. bills load, the payment status does not ------------------------
    await page.route('**/tmf-api/customerBillManagement/v4/collectionCase**', (r) => r.fulfill({ status: 503, body: '{"message":"unavailable"}', contentType: 'application/json' }));
    await page.goto(`${SHOP}bills`);
    const unknown = page.locator('[data-testid="collections-unknown"]');
    await unknown.waitFor({ timeout: 30000 });
    const said = (await unknown.innerText()).trim();
    if (!/payment status/.test(said)) fail(`the notice does not say what is unknown: "${said}"`);
    if (await page.locator('[data-testid="bills-empty"]').count() === 0 && !(await page.locator('text=BILL-').count())) {
      fail('with only the payment status down, the bills themselves did not render');
    }
    console.log(`OK the payment status is "unknown", not "current": "${said}"`);
    await page.unroute('**/tmf-api/customerBillManagement/v4/collectionCase**');
    await page.goto(`${SHOP}bills`);
    await page.waitForFunction(() => !/Loading your bills/.test(document.body.innerText), null, { timeout: 30000 });
    await page.waitForTimeout(2000);
    if (await unknown.count()) fail('the payment status answers again and the notice is still there');
    console.log('OK with the payment status answering, the notice is gone');
  } finally {
    await browser.close();
    try { sh('docker start bss-billing'); } catch (e) { console.error(`could not restart bss-billing: ${e.message}`); }
    await waitHealthy('bss-billing').catch((e) => console.error(e.message));
  }
  console.log('PASS shop_unreachable_test — the shop says what it could not check, and never "all good" about it');
})().catch((e) => { console.error('FAIL: ' + (e && e.message || e)); process.exit(1); });
