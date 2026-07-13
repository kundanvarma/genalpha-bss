/* Mobile app E2E (web target): a new customer signs up in the app, buys a
 * digital plan with one tap, the SOM activates it with a real number, and
 * the adaptive Home recomposes around what they now own. */
const { chromium } = require('playwright');

const APP = 'http://localhost:8080/app/';
const run = Date.now();

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };

  // 1. Cold start: tenant manifest brands the sign-in screen
  await page.goto(APP);
  await page.locator('text=MyGenAlpha').first().waitFor({ timeout: 20000 });
  console.log('OK app boots with the tenant brand');

  // 2. Register through the tenant IdP (PKCE in the same window on web)
  await page.locator('[data-testid="signin"]').click();
  await page.waitForSelector('a[href*="registration"], input[name="username"]', { timeout: 20000 });
  await page.click('a[href*="registration"]');
  await page.waitForSelector('input[name="email"]');
  await page.fill('input[name="firstName"]', 'Appy');
  await page.fill('input[name="lastName"]', 'User');
  await page.fill('input[name="email"]', `appy-${run}@example.com`);
  await page.fill('input[name="password"]', 'Passw0rd!');
  await page.fill('input[name="password-confirm"]', 'Passw0rd!');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.locator('[data-testid="empty-home"]').waitFor({ timeout: 30000 });
  console.log('OK registered; adaptive Home shows the empty state');

  // 3. Fresh customer: recommendations fill the screen
  await page.locator('[data-testid="recs-card"]').waitFor({ timeout: 15000 });
  console.log('OK recommendations module composed onto Home');

  // 4. One-tap buy of a digital plan from Shop
  await page.locator('text=Shop').last().click();
  const buy = page.locator('[data-testid="buy-GenAlpha-Mobile-Unlimited-5G"]');
  await buy.waitFor({ timeout: 20000 });
  await buy.click();
  await page.locator('[data-testid="shop-message"]').waitFor({ timeout: 20000 });
  console.log('OK plan ordered from the app');

  // 5. The SOM completes it and Home recomposes: LOB card with a real number
  await page.locator('text=Home').last().click();
  let msisdn = null;
  for (let attempt = 0; attempt < 20 && !msisdn; attempt++) {
    await page.waitForTimeout(2500);
    await page.locator('text=Usage').last().click(); // bounce to refocus Home
    await page.locator('text=Home').last().click();
    const el = page.locator('[data-testid="msisdn"]').first();
    if (await el.count()) msisdn = (await el.textContent()).trim();
  }
  if (!msisdn || !msisdn.startsWith('+46701')) {
    fail('no GenAlpha-pool number appeared on Home: ' + msisdn);
  }
  console.log('OK SOM activated the plan; Home shows the LOB card with', msisdn);

  // 5b. MyJio parity on mobile: SIM self-care + one-tap top-up on the card
  await page.locator('[data-testid="sim-row"]').first().waitFor({ timeout: 10000 });
  await page.locator('[data-testid="app-show-puk"]').first().click();
  const appPuk = await page.locator('[data-testid="app-puk"]').first().textContent({ timeout: 10000 });
  if (!/PUK \d{8}/.test(appPuk)) fail('app PUK reveal wrong: ' + appPuk);
  console.log('OK app SIM care:', appPuk, '— revealed in one tap');
  await page.locator('[data-testid="app-topup"]').first().waitFor({ timeout: 10000 });
  console.log('OK one-tap top-up offered on the usage card');

  // 6. Help: the order event became a notification
  await page.locator('text=Help').last().click();
  await page.locator('[data-testid="inbox-card"]').waitFor({ timeout: 10000 });
  let seen = false;
  for (let attempt = 0; attempt < 10 && !seen; attempt++) {
    seen = (await page.locator('text=Order received').count()) > 0;
    if (!seen) {
      await page.waitForTimeout(2000);
      await page.locator('text=Home').last().click();
      await page.locator('text=Help').last().click();
    }
  }
  if (!seen) fail('Order received notification never reached the app inbox');
  console.log('OK the event stream reached the app inbox');

  await browser.close();
  console.log('\nALL APP CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
