/* VAS foundation E2E: the catalog category decides FULFILMENT.
 * One order carries Netflix (partner), Secure Net (security) and Device Care
 * (insurance). The SOM must mint an ENTITLEMENT for Netflix (activation code,
 * no phone number), a feature service for Secure Net (active, no resources),
 * and NOTHING for insurance (billing-only — but the product still exists and
 * bills). My page shows the subscriptions & protection card. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
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

const shopToken = (page) => page.evaluate(() => sessionStorage.getItem('bss.shop.token'));

async function apiCall(page, method, path, token, body) {
  return page.evaluate(async ({ method, path, token, body }) => {
    const res = await fetch(path, {
      method,
      headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: res.status, body: await res.json().catch(() => ({})) };
  }, { method, path, token, body });
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

  const offers = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`)).json();
  const by = (n) => offers.find((o) => o.name === n) || fail('missing offering ' + n);
  const netflix = by('Netflix Standard');
  const secure = by('GenAlpha Secure Net');
  const care = by('GenAlpha Device Care');

  const page = await (await browser.newContext()).newPage();
  await register(page, `vera-${run}@example.com`, 'Vera', `Vas${run}`);
  const vera = await shopToken(page);
  const order = await apiCall(page, 'POST', '/tmf-api/productOrderingManagement/v4/productOrder', vera, {
    productOrderItem: [netflix, secure, care].map((o, i) => ({
      id: String(i + 1), action: 'add', quantity: 1,
      productOffering: { id: o.id, name: o.name, '@referredType': 'ProductOffering' } })),
  });
  if (order.status !== 201) fail('VAS order failed: ' + JSON.stringify(order.body));

  // digital order: services appear in seconds
  let svcs = [];
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(2000);
    const res = await apiCall(page, 'GET', '/tmf-api/serviceInventory/v4/service', vera);
    svcs = Array.isArray(res.body) ? res.body : [];
    if (svcs.length >= 2) break;
  }

  const nf = svcs.find((s) => s.name === 'Netflix Standard');
  if (!nf) fail('no Netflix service');
  const code = (nf.serviceCharacteristic || []).find((c) => c.name === 'activationCode')?.value;
  if (!/^NS-[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(code || '')) fail('bad activation code: ' + code);
  if ((nf.supportingResource || []).some((r) => /^\+/.test(r.value))) fail('Netflix got a phone number!');
  console.log('OK partner service: entitlement minted, no number —', nf.name, code);

  const sn = svcs.find((s) => s.name === 'GenAlpha Secure Net');
  if (!sn || sn.state !== 'active') fail('Secure Net feature service missing/inactive');
  if ((sn.supportingResource || []).length) fail('Secure Net should hold no resources');
  console.log('OK security feature: active service, zero resources —', sn.name);

  if (svcs.some((s) => s.name === 'GenAlpha Device Care')) {
    fail('insurance minted a service — it must be billing-only');
  }
  const prods = await apiCall(page, 'GET', '/tmf-api/productInventory/v4/product?status=active', vera);
  if (!(prods.body || []).some?.((p) => p.name === 'GenAlpha Device Care')) {
    fail('insurance product missing from inventory (it must bill)');
  }
  console.log('OK insurance: no service, but the product exists and will bill');

  // the order completed even though one item provisioned nothing
  const orders = await apiCall(page, 'GET', '/tmf-api/productOrderingManagement/v4/productOrder', vera);
  const done = (orders.body || []).find?.((o) => o.id === order.body.id);
  if (done?.state !== 'completed') fail('VAS order did not complete: ' + done?.state);
  console.log('OK the mixed VAS order completed autonomously');

  // My page: the subscriptions & protection card
  await page.click('.nav >> text=My page');
  await page.waitForSelector('[data-testid=vas-card]', { timeout: 15000 });
  const codeShown = await page.locator('[data-testid=activation-code]').first().textContent();
  if (codeShown !== code) fail(`UI shows wrong code: ${codeShown} vs ${code}`);
  await page.locator('[data-testid=feature-row]', { hasText: 'Secure Net' }).waitFor({ timeout: 10000 });
  console.log('OK My page: activation code + security feature on the new card');

  // Family Max carries the VAS group as a "pick up to 3"
  const fm = offers.find((o) => o.name === 'GenAlpha Family Max');
  const fmFull = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering/${fm.id}`)).json();
  const group = (fmFull.bundledProductOffering || [])
    .find((g) => g.name === 'Peace of mind & entertainment');
  if (!group || group.numberRelOfferUpperLimit !== 3) fail('Family Max VAS group missing');
  console.log('OK Family Max offers the VAS group: pick up to 3 (high-end package story)');

  await browser.close();
  console.log('\nALL VAS CHECKS PASSED — category-driven fulfilment: entitlement, feature, billing-only.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
