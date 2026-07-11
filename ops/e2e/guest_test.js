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

  // Pin every phone's availability so repeated runs never exhaust the shelf
  // (the bundle's default phone must stay in stock for the guest flow).
  const setup = await browser.newContext();
  const staffRes0 = await setup.request.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  const staff0 = (await staffRes0.json()).access_token;
  const stockRows = await (await setup.request.get(
    'http://localhost:8080/tmf-api/productStockManagement/v4/productStock?limit=100',
    { headers: { Authorization: 'Bearer ' + staff0 } })).json();
  for (const row of stockRows) {
    await setup.request.patch(
      `http://localhost:8080/tmf-api/productStockManagement/v4/productStock/${row.id}`,
      { headers: { Authorization: 'Bearer ' + staff0 },
        data: { stockedQuantity: { amount: row.reservedQuantity.amount + 40, units: 'unit' } } });
  }
  console.log('OK stock pinned for all phones');

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
  await page.fill('.shipping input[name="city"]', 'göteborg'); // TMF673 standardizes the casing
  await page.fill('.shipping input[name="country"]', 'SE');
  // Fiber install: serviceable in Göteborg, slot required — guests see both
  await page.locator('.serviceability.ok').waitFor({ timeout: 10000 });
  await page.locator('.slotgrid .option').first().waitFor({ timeout: 10000 });
  await page.locator('.slotgrid .option').first().click();
  console.log('OK guest sees serviceability + picked install slot');

  // TMF671: the guest applies a promo code before having any identity.
  await page.fill('.promobar input', 'WELCOME10');
  await page.click('.promobar button');
  await page.locator('[data-testid="promo-row"]', { hasText: 'WELCOME10' }).waitFor({ timeout: 10000 });
  console.log('OK promo WELCOME10 accepted anonymously, discount shown in cart');
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

  // 5. The discount lands on the real bill: staff completes the order,
  //    the billing run applies the redeemed promotion.
  const token = await page.evaluate(() => sessionStorage.getItem('bss.shop.token'));
  const orders = await page.evaluate(async ({ token }) => {
    const res = await fetch('/tmf-api/productOrderingManagement/v4/productOrder?limit=10',
      { headers: { Authorization: 'Bearer ' + token } });
    return res.json();
  }, { token });
  if (orders[0].promotionCode !== 'WELCOME10') fail('order lost the promo code: ' + JSON.stringify(orders[0]).slice(0, 200));

  // TMF673 standardized the typed 'göteborg' before it reached the order.
  const orderJson = JSON.stringify(orders[0]);
  if (!orderJson.includes('"Göteborg"')) fail('order shipping place not standardized: ' + orderJson.slice(0, 400));
  console.log('OK TMF673 standardized the shipping address (göteborg -> Göteborg) on the order');

  const staffRes = await page.context().request.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  const staff = (await staffRes.json()).access_token;
  const done = await page.context().request.patch(
    `http://localhost:8080/tmf-api/productOrderingManagement/v4/productOrder/${orders[0].id}`,
    { headers: { Authorization: 'Bearer ' + staff }, data: { state: 'completed' } });
  if (done.status() !== 200) fail('staff completion failed: ' + done.status() + ' ' + await done.text());
  const runRes = await page.context().request.post(
    'http://localhost:8080/tmf-api/customerBillManagement/v4/billingRun',
    { headers: { Authorization: 'Bearer ' + staff } });
  if (runRes.status() !== 200) fail('billing run failed: ' + runRes.status());

  const bills = await page.evaluate(async ({ token }) => {
    const res = await fetch('/tmf-api/customerBillManagement/v4/customerBill?limit=10',
      { headers: { Authorization: 'Bearer ' + token } });
    return res.json();
  }, { token });
  if (!bills.length) fail('guest has no bill after the run');
  const rates = await page.evaluate(async ({ token, id }) => {
    const res = await fetch(`/tmf-api/customerBillManagement/v4/customerBill/${id}/appliedCustomerBillingRate`,
      { headers: { Authorization: 'Bearer ' + token } });
    return res.json();
  }, { token, id: bills[0].id });
  const discount = rates.find((r) => (r.name || '').includes('WELCOME10'));
  if (!discount) fail('bill has no WELCOME10 discount line: ' + JSON.stringify(rates).slice(0, 300));
  if (!(discount.taxExcludedAmount.value < 0)) fail('discount is not negative: ' + JSON.stringify(discount));
  const sum = rates.reduce((a, r) => a + r.taxExcludedAmount.value, 0);
  if (Math.abs(sum - bills[0].amountDue.value) > 0.005) {
    fail(`bill total ${bills[0].amountDue.value} != sum of rates ${sum.toFixed(2)}`);
  }
  console.log('OK redeemed promo discounts the bill:', discount.name, discount.taxExcludedAmount.value,
    '| total', bills[0].amountDue.value, bills[0].amountDue.unit);

  await browser.close();
  console.log('\nALL GUEST CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
