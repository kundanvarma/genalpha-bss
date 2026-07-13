/* Family Max — the "complicated product" bundle, proven in the UI and at the
 * order API. In the storefront: multi-select choice groups render as
 * checkboxes with their cardinality enforced live (a third family line can't
 * be ticked, add-to-cart gates on minimums). At the API: TMF622 enforces the
 * same limits server-side — the UI is convenience, the order is law. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const run = Date.now();

async function register(page, email, first, last) {
  await page.goto(`${API}/shop/`);
  await page.click('.who >> text=Sign in');
  await page.waitForSelector('a[href*="registration"], input[name="username"]', { timeout: 20000 });
  await page.click('a[href*="registration"]');
  await page.waitForSelector('input[name="email"]');
  await page.fill('input[name="firstName"]', first);
  await page.fill('input[name="lastName"]', last);
  await page.fill('input[name="email"]', email);
  await page.fill('input[name="password"]', 'Passw0rd!');
  await page.fill('input[name="password-confirm"]', 'Passw0rd!');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.nav', { timeout: 20000 });
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

  const staff = (await (await ctx.request.post(KC, {
    form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json()).access_token;
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };
  const offers = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`, { headers: H })).json();
  const fm = offers.find((o) => o.name === 'GenAlpha Family Max');
  if (!fm) fail('Family Max not seeded');
  const by = (n) => offers.find((o) => o.name.includes(n));
  const [ten, fifty, unl, phone] = [by('Mobile 10 GB'), by('Mobile 50 GB'),
    by('Unlimited 5G'), by('Apple iPhone 17 Pro')];

  // --- UI: the configurator enforces cardinality live
  const page = await (await browser.newContext()).newPage();
  await register(page, `fam-${run}@example.com`, 'Fam', `Max${run}`);
  await page.goto(`${API}/shop/offering/${fm.id}`);
  await page.waitForSelector('.choice', { timeout: 20000 });
  await page.waitForTimeout(2000);

  const linesGroup = page.locator('.choice', { hasText: 'Family lines' });
  await linesGroup.locator('label.option', { hasText: 'Mobile 10 GB' }).click();
  const third = linesGroup.locator('label.option', { hasText: 'Unlimited 5G' }).locator('input');
  if (!(await third.isDisabled())) fail('third family line should be un-tickable at the cap of 2');
  console.log('OK UI: two family lines picked — the third is un-tickable (upper limit 2)');

  // un-tick everything -> below the minimum -> add-to-cart gates
  await linesGroup.locator('label.option', { hasText: 'Mobile 10 GB' }).click();
  await linesGroup.locator('label.option', { hasText: 'Mobile 50 GB' }).click();
  await page.locator('[data-testid=choice-hint]').waitFor({ timeout: 5000 });
  if (!(await page.locator('button.primary.big').isDisabled())) {
    fail('add-to-cart should gate below the group minimum');
  }
  console.log('OK UI: zero lines picked — add-to-cart disabled with a hint (lower limit 1)');

  // --- API: TMF622 enforces the same limits regardless of the UI
  const shopToken = await page.evaluate(() => sessionStorage.getItem('bss.shop.token'));
  const order = (children) => page.evaluate(async ({ token, body }) => {
    const res = await fetch('/tmf-api/productOrderingManagement/v4/productOrder', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    return { status: res.status, body: await res.json().catch(() => ({})) };
  }, { token: shopToken, body: {
    productOrderItem: [{ id: '1', action: 'add', quantity: 1,
      productOffering: { id: fm.id, name: fm.name, '@referredType': 'ProductOffering' },
      productOrderItem: children.map((o, i) => ({ id: `1.${i + 1}`, action: 'add', quantity: 1,
        productOffering: { id: o.id, name: o.name, '@referredType': 'ProductOffering' } })),
    }] } });

  const tooMany = await order([ten, fifty, unl, phone]);
  if (tooMany.status === 201) fail('3 family lines should be refused (max 2)');
  if (!/between 1 and 2/.test(tooMany.body.message)) fail('refusal should cite 1..2: ' + tooMany.body.message);
  console.log('OK API: 3 lines refused —', tooMany.body.message);

  const noPhone = await order([ten, fifty]);
  if (noPhone.status === 201) fail('missing phone choice should be refused');
  console.log('OK API: missing phone refused —', noPhone.body.message);

  const good = await order([ten, fifty, phone]);
  if (good.status !== 201) fail('valid composition refused: ' + JSON.stringify(good.body));
  console.log('OK API: 2 lines + phone accepted — order', good.body.state);

  await browser.close();
  console.log('\nFAMILY MAX CHECKS PASSED — pick-N-of-M enforced in the UI and at the order API.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
