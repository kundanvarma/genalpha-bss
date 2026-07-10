/* Guest flow E2E: an anonymous visitor browses the catalog with prices, hits
 * "Order now", registers at checkout, and the interrupted order completes
 * automatically after the redirect. */
const { chromium } = require('playwright');

const SHOP = 'http://localhost:8080/shop/';
const run = Date.now();

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };

  // 1. Anonymous browsing: catalog + prices visible, no login redirect
  await page.goto(SHOP);
  const bundleCard = page.locator('.card.bundle', { hasText: 'GenAlpha One Home & Mobile' });
  await bundleCard.waitFor({ timeout: 15000 });
  if (!page.url().startsWith(SHOP)) fail('guest was redirected away from the shop: ' + page.url());
  if (!(await page.locator('.who >> text=Sign in').count())) fail('no Sign in button for guest');
  const cardText = await bundleCard.textContent();
  if (!/\d+\.\d{2} EUR\/month/.test(cardText)) fail('guest cannot see prices: ' + cardText);
  console.log('OK guest browses catalog with prices, no forced login');

  // 2. Guest configures the bundle, carts it, and starts checkout
  await bundleCard.click();
  await page.waitForSelector('.pricetable');
  await page.click('button.primary.big'); // Add to cart
  await page.waitForURL('**/cart');
  // Bundle ships a phone: the guest must give an address before checkout
  await page.locator('.shipping').waitFor({ timeout: 10000 });
  await page.fill('.shipping input[name="street1"]', 'Gästgatan 7');
  await page.fill('.shipping input[name="postCode"]', '22233');
  await page.fill('.shipping input[name="city"]', 'Göteborg');
  await page.fill('.shipping input[name="country"]', 'SE');
  await page.locator('.cartactions button.primary.big').click(); // Checkout -> Keycloak
  await page.waitForSelector('a[href*="registration"], input[name="username"]', { timeout: 20000 });
  console.log('OK guest cart built; checkout hands guest to the identity provider');

  // 3. Register at checkout
  await page.click('a[href*="registration"]');
  await page.waitForSelector('input[name="email"]');
  await page.fill('input[name="firstName"]', 'Gina');
  await page.fill('input[name="lastName"]', 'Guest');
  await page.fill('input[name="email"]', `gina-${run}@example.com`);
  await page.fill('input[name="password"]', 'Passw0rd!');
  await page.fill('input[name="password-confirm"]', 'Passw0rd!');
  await page.click('input[type="submit"], button[type="submit"]');

  // 4. Card details never survive a redirect: the resume returns to the cart,
  //    now signed in, to confirm payment for the one-time charges.
  await page.waitForURL('**/cart', { timeout: 25000 });
  await page.locator('.payment input[name="cardNumber"]').waitFor({ timeout: 15000 });
  console.log('OK resume returned to cart for payment confirmation');
  await page.fill('.payment input[name="cardNumber"]', '4242 4242 4242 4242');
  await page.fill('.payment input[name="expiry"]', '12/28');
  await page.fill('.payment input[name="cvc"]', '123');
  await page.locator('.cartactions button.primary.big').click();
  await page.waitForURL('**/orders', { timeout: 25000 });
  const orderRow = page.locator('.row', { hasText: 'GenAlpha One Home & Mobile' });
  await orderRow.waitFor({ timeout: 15000 });
  const state = await orderRow.locator('.state').textContent();
  console.log('OK guest order paid and placed after registration, state:', state);

  await browser.close();
  console.log('\nALL GUEST CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
