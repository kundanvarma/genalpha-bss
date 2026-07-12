/* Plan change E2E: upgrade/downgrade without losing your number, in BOTH
 * self-service channels. A fresh customer orders plan A, changes to plan B
 * from /shop My services — same MSISDN, product repointed, service renamed,
 * order is a real TMF622 action=modify. Guards proven: a committed plan
 * refuses to change until its window ends; a foreign product is refused.
 * Then B2B: Bianca swaps a member's plan from /biz the same way. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const SHOP = `${API}/shop/`;
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const run = Date.now();

async function register(page, email, first, last) {
  await page.goto(SHOP);
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

  const staffRes = await ctx.request.post(KC, {
    form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  const staff = (await staffRes.json()).access_token;
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };

  // two commitment-free, non-bundle, priced plans from the live catalog
  const offers = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`, { headers: H })).json();
  const prices = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOfferingPrice?limit=100`, { headers: H })).json();
  const priceById = Object.fromEntries(prices.map((p) => [p.id, p]));
  const plans = offers.filter((o) => !o.isBundle && !o.requiresVerifiedIdentity
    && !(o.productOfferingTerm || []).length
    && (o.productOfferingPrice || []).some((r) => priceById[r.id]?.priceType === 'recurring'));
  const planA = plans.find((o) => o.name.includes('Unlimited 5G'));
  const planB = plans.find((o) => o.name.includes('Mobile 10 GB'));
  if (!planA || !planB) fail('need the two seed plans in the catalog');
  console.log('OK plans picked from live catalog:', planA.name, '->', planB.name);

  // --- B2C: fresh customer, plan A live on a number
  const page = await (await browser.newContext()).newPage();
  await register(page, `carl-${run}@example.com`, 'Carl', 'Changer');
  const carl = await shopToken(page);
  const order = await apiCall(page, 'POST', '/tmf-api/productOrderingManagement/v4/productOrder', carl, {
    productOrderItem: [{ action: 'add', productOffering: { id: planA.id, name: planA.name } }],
  });
  if (order.status !== 201) fail('plan A order failed: ' + JSON.stringify(order.body));
  let product = null, service = null;
  for (let i = 0; i < 30 && !service; i++) {
    await page.waitForTimeout(2000);
    const svcs = await apiCall(page, 'GET', '/tmf-api/serviceInventory/v4/service', carl);
    service = (svcs.body || []).find?.((s) => s.state === 'active'
      && (s.supportingResource || []).some((r) => r.value)) || null;
  }
  if (!service) fail('plan A never activated');
  const number = service.supportingResource.find((r) => r.value).value;
  const products = await apiCall(page, 'GET', '/tmf-api/productInventory/v4/product?status=active', carl);
  product = (products.body || []).find((p) => p.productOffering?.id === planA.id);
  if (!product) fail('no active product for plan A');
  console.log('OK Carl live on', planA.name, 'number', number);

  // --- change the plan from /shop My services
  await page.click('.nav >> text=My services');
  await page.waitForSelector('[data-testid=my-number]', { timeout: 15000 });
  await page.click(`[data-testid=change-plan-${product.id}]`);
  await page.waitForSelector('[data-testid=change-plan-form] select', { timeout: 10000 });
  // like-for-like only: the dropdown must offer plans, never devices/add-ons
  const dropdown = await page.locator('[data-testid=change-plan-form] select').textContent();
  if (/Samsung|iPhone|Sports Pass|TV Max/.test(dropdown)) {
    fail('change-plan dropdown offers non-plans: ' + dropdown);
  }
  console.log('OK change-plan dropdown is plans-only (no devices, no add-ons)');
  await page.selectOption('[data-testid=change-plan-form] select', planB.id);
  await page.click('[data-testid=change-plan-form] button.primary');
  await page.waitForSelector('[data-testid=plan-changed]', { timeout: 20000 });
  console.log('OK /shop: plan changed in the UI —', await page.locator('[data-testid=plan-changed]').textContent());

  // same product id, new offering; same service id, new name; SAME number
  const after = await apiCall(page, 'GET', `/tmf-api/productInventory/v4/product/${product.id}`, carl);
  if (after.body.productOffering?.id !== planB.id) fail('product not repointed: ' + JSON.stringify(after.body.productOffering));
  let renamed = null;
  for (let i = 0; i < 10 && !renamed; i++) {
    await page.waitForTimeout(1500);
    const sv = await apiCall(page, 'GET', `/tmf-api/serviceInventory/v4/service`, carl);
    const same = (sv.body || []).find?.((s) => s.id === service.id);
    if (same && same.name === planB.name) renamed = same;
  }
  if (!renamed) fail('service was not renamed to the new plan');
  const numberAfter = renamed.supportingResource.find((r) => r.value).value;
  if (numberAfter !== number) fail(`number changed! ${number} -> ${numberAfter}`);
  console.log('OK same service, same number', number, '— now named', renamed.name);

  // the modify order is on record and completed
  const orders = await apiCall(page, 'GET', '/tmf-api/productOrderingManagement/v4/productOrder', carl);
  const modOrder = (orders.body || []).find?.((o) =>
    (o.productOrderItem || []).some((it) => it.action === 'modify'));
  if (!modOrder || modOrder.state !== 'completed') fail('modify order missing or not completed');
  console.log('OK the change is a real TMF622 modify order, state', modOrder.state);

  // --- guard 1: a committed plan cannot be changed
  const committed = await (await ctx.request.post(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering`, { headers: H, data: {
      name: `E2E Committed Plan ${run}`, lifecycleStatus: 'Active', isBundle: false,
      productOfferingTerm: [{ name: '12-month commitment',
        duration: { amount: 12, units: 'month' } }] } })).json();
  const cOrder = await apiCall(page, 'POST', '/tmf-api/productOrderingManagement/v4/productOrder', carl, {
    productOrderItem: [{ action: 'add', productOffering: { id: committed.id, name: committed.name } }],
  });
  if (cOrder.status !== 201) fail('committed plan order failed: ' + JSON.stringify(cOrder.body));
  let cProduct = null;
  for (let i = 0; i < 30 && !cProduct; i++) {
    await page.waitForTimeout(2000);
    const ps = await apiCall(page, 'GET', '/tmf-api/productInventory/v4/product?status=active', carl);
    cProduct = (ps.body || []).find?.((p) => p.productOffering?.id === committed.id) || null;
  }
  if (!cProduct) fail('committed plan never provisioned');
  const blocked = await apiCall(page, 'POST', '/tmf-api/productOrderingManagement/v4/productOrder', carl, {
    productOrderItem: [{ action: 'modify', product: { id: cProduct.id },
      productOffering: { id: planA.id, name: planA.name } }],
  });
  if (blocked.status === 201) fail('committed plan was allowed to change!');
  if (!String(blocked.body.message).includes('commitment')) fail('wrong refusal: ' + blocked.body.message);
  console.log('OK commitment guard:', blocked.body.message);

  // --- guard 2: you cannot modify someone else's product
  const all = await (await ctx.request.get(
    `${API}/tmf-api/productInventory/v4/product?status=active&limit=100`, { headers: H })).json();
  const carlSub = JSON.parse(Buffer.from(carl.split('.')[1], 'base64').toString()).sub;
  const foreign = all.find((p) => (p.relatedParty || []).every((r) => r.id !== carlSub));
  if (foreign) {
    const denied = await apiCall(page, 'POST', '/tmf-api/productOrderingManagement/v4/productOrder', carl, {
      productOrderItem: [{ action: 'modify', product: { id: foreign.id },
        productOffering: { id: planB.id, name: planB.name } }],
    });
    if (denied.status === 201) fail("modified someone else's product!");
    console.log('OK ownership guard:', denied.body.message);
  } else {
    console.log('SKIP ownership guard: no foreign product in inventory');
  }

  // --- bundle broadband: the fiber COMPONENT of a committed bundle upgrades
  // in place. A bundle decomposes into per-component products at provisioning,
  // so the broadband tier is its own product — and the 12-month commitment
  // binds the BUNDLE offering, not the component, so a tier upgrade is free to
  // proceed while the bundle commitment stands.
  const bundle = offers.find((o) => o.isBundle && (o.bundledProductOffering || [])
    .some((b) => (b.name || '').includes('Fiber')));
  if (!bundle) fail('no fiber bundle in the catalog');
  const fiber = bundle.bundledProductOffering.find((b) => (b.name || '').includes('Fiber'));
  const choice = bundle.bundledProductOffering.find((b) => b['@type'] === 'BundledProductOfferingChoice');
  const fixed = bundle.bundledProductOffering.filter((b) =>
    (b.bundledProductOfferingOption || {}).numberRelOfferLowerLimit === 1);
  // a second broadband tier to move to (throwaway, with a real monthly price)
  const f5Price = await (await ctx.request.post(
    `${API}/tmf-api/productCatalogManagement/v4/productOfferingPrice`, { headers: H, data: {
      name: 'Fiber 500 Monthly', priceType: 'recurring', recurringChargePeriodType: 'month',
      price: { unit: 'EUR', value: 29.99 } } })).json();
  const broadbandCat = (await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/category?limit=100`, { headers: H })).json())
    .find((c) => c.name === 'Broadband');
  const fiber500 = await (await ctx.request.post(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering`, { headers: H, data: {
      name: `E2E Fiber 500 ${run}`, lifecycleStatus: 'Active', isBundle: false,
      category: [{ id: broadbandCat.id, name: 'Broadband', '@referredType': 'Category' }],
      productOfferingPrice: [{ id: f5Price.id, name: f5Price.name }] } })).json();

  const bundleOrder = await apiCall(page, 'POST', '/tmf-api/productOrderingManagement/v4/productOrder', carl, {
    productOrderItem: [{
      id: '1', action: 'add', quantity: 1,
      productOffering: { id: bundle.id, name: bundle.name, '@referredType': 'ProductOffering' },
      productOrderItem: [
        ...fixed.map((b, i) => ({ id: `1.${i + 1}`, action: 'add', quantity: 1,
          productOffering: { id: b.id, name: b.name, '@referredType': 'ProductOffering' } })),
        ...(choice?.default ? [{ id: '1.9', action: 'add', quantity: 1,
          productOffering: { id: choice.default, name: 'phone', '@referredType': 'ProductOffering' } }] : []),
      ],
    }],
  });
  if (bundleOrder.status !== 201) fail('bundle order failed: ' + JSON.stringify(bundleOrder.body));
  let fiberProduct = null;
  for (let i = 0; i < 30 && !fiberProduct; i++) {
    await page.waitForTimeout(2000);
    const ps = await apiCall(page, 'GET', '/tmf-api/productInventory/v4/product?status=active', carl);
    fiberProduct = (ps.body || []).find?.((p) => p.productOffering?.id === fiber.id) || null;
  }
  if (!fiberProduct) fail('bundle fiber component never provisioned');
  console.log('OK Carl on the committed bundle — fiber component is its own product:', fiberProduct.name);

  await page.reload();
  await page.waitForSelector('[data-testid=my-number]', { timeout: 15000 });
  // the bundle renders as a GROUP under "My plan": parent with components nested
  const allProducts = await apiCall(page, 'GET', '/tmf-api/productInventory/v4/product?status=active', carl);
  const bundleProduct = (allProducts.body || []).find?.((p) => p.productOffering?.id === bundle.id);
  const group = await page.locator(`[data-testid=bundle-${bundleProduct.id}]`).textContent();
  if (!group.includes(fiberProduct.name)) fail('bundle does not show its components: ' + group);
  console.log('OK My page shows what is inside the bundle:', bundle.name, '⊃', fiberProduct.name);
  await page.click(`[data-testid=change-plan-${fiberProduct.id}]`);
  await page.waitForSelector('[data-testid=change-plan-form] select', { timeout: 10000 });
  await page.selectOption('[data-testid=change-plan-form] select', fiber500.id);
  await page.click('[data-testid=change-plan-form] button.primary');
  await page.waitForSelector('[data-testid=plan-changed]', { timeout: 20000 });
  const fiberAfter = await apiCall(page, 'GET',
    `/tmf-api/productInventory/v4/product/${fiberProduct.id}`, carl);
  if (fiberAfter.body.productOffering?.id !== fiber500.id) {
    fail('fiber component not repointed: ' + JSON.stringify(fiberAfter.body.productOffering));
  }
  console.log('OK bundle broadband upgraded in place:', fiber.name, '->', fiber500.name,
    '(bundle commitment untouched)');

  // --- the data ladder: Carl is on Mobile 10 GB now. He burns 8 GB, buys a
  // one-time 5 GB top-up, and the meter's allowance grows to 15 THIS month.
  await ctx.request.post(`${API}/tmf-api/usageManagement/v4/usage`, { headers: H, data: {
    usageType: 'Mobile data', usageDate: new Date().toISOString(),
    usageCharacteristic: { value: 8, units: 'GB' },
    relatedParty: [{ id: carlSub, role: 'customer' }],
    productOffering: { id: planB.id, name: planB.name } } });
  const before = await apiCall(page, 'GET', '/tmf-api/usageConsumption/v4/queryUsageConsumption', carl);
  const baseBucket = (before.body.bucket || []).find?.((b) => b.name === 'Mobile data');
  if (!baseBucket || Number(baseBucket.allowedValue) !== 10) {
    fail('plan allowance should be 10 GB: ' + JSON.stringify(baseBucket));
  }
  const topup = offers.find((o) => o.name === 'Data Top-Up 5 GB');
  await apiCall(page, 'POST', '/tmf-api/productOrderingManagement/v4/productOrder', carl, {
    productOrderItem: [{ action: 'add', productOffering: { id: topup.id, name: topup.name } }],
  });
  let boosted = null;
  for (let i = 0; i < 20 && !boosted; i++) {
    await page.waitForTimeout(2000);
    const rep = await apiCall(page, 'GET', '/tmf-api/usageConsumption/v4/queryUsageConsumption', carl);
    const b = (rep.body.bucket || []).find?.((x) => x.name === 'Mobile data');
    if (b && Number(b.allowedValue) === 15) boosted = b;
  }
  if (!boosted) fail('the top-up purchase did not extend the meter');
  console.log(`OK one-time top-up: ${boosted.usedValue}/${boosted.allowedValue} GB — `
    + 'buying 5 GB extra extended this month\'s allowance');

  // --- B2B: Bianca swaps a member's plan from /biz
  const biancaTok = await (await ctx.request.post(KC, { form: {
    grant_type: 'password', client_id: 'bss-biz', username: 'bianca@acme.example', password: 'bianca' } })).json();
  const biancaSub = JSON.parse(Buffer.from(biancaTok.access_token.split('.')[1], 'base64').toString()).sub;
  const org = await (await ctx.request.post(`${API}/tmf-api/party/v4/organization`, {
    headers: H, data: { name: `PlanCo AS ${run}` } })).json();
  await ctx.request.post(`${API}/tmf-api/party/v4/individual`, { headers: H,
    data: { id: biancaSub, givenName: 'Bianca', familyName: 'Boss', organization: { id: org.id } } });
  await ctx.request.patch(`${API}/tmf-api/party/v4/individual/${biancaSub}`, { headers: H,
    data: { organization: { id: org.id } } });
  const mia = await (await ctx.request.post(`${API}/tmf-api/party/v4/individual`, { headers: H,
    data: { givenName: 'Mia', familyName: `Member${run}`, organization: { id: org.id } } })).json();
  const BH = { Authorization: 'Bearer ' + biancaTok.access_token, 'Content-Type': 'application/json' };
  await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, { headers: BH,
    data: { productOrderItem: [{ action: 'add', productOffering: { id: planA.id, name: planA.name } }],
      relatedParty: [{ id: mia.id, role: 'customer' }] } });
  let miaSvc = null;
  for (let i = 0; i < 30 && !miaSvc; i++) {
    await new Promise((r) => setTimeout(r, 2000));
    const svcs = await (await ctx.request.get(
      `${API}/tmf-api/serviceInventory/v4/service?relatedPartyId=${mia.id}`, { headers: H })).json();
    miaSvc = (svcs || []).find((s) => s.state === 'active' && (s.supportingResource || []).some((r) => r.value)) || null;
  }
  if (!miaSvc) fail("Mia's line never activated");
  const miaNumber = miaSvc.supportingResource.find((r) => r.value).value;

  const biz = await (await browser.newContext()).newPage();
  await biz.goto(`${API}/biz/`);
  await biz.waitForSelector('input[name="username"]', { timeout: 20000 });
  await biz.fill('input[name="username"]', 'bianca@acme.example');
  await biz.fill('input[name="password"]', 'bianca');
  await biz.click('input[type="submit"], button[type="submit"]');
  await biz.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  await biz.locator('#swap-member option', { hasText: 'Mia' }).waitFor({ state: 'attached', timeout: 10000 });
  await biz.selectOption('#swap-member', mia.id);
  await biz.locator(`#swap-line option[value="${miaSvc.id}"]`).waitFor({ state: 'attached', timeout: 10000 });
  await biz.selectOption('#swap-line', miaSvc.id);
  await biz.selectOption('#swap-offering', planB.id);
  await biz.click('#swap-plan');
  await biz.locator('#swap-status.ok').waitFor({ timeout: 20000 });
  console.log('OK /biz: Bianca changed Mia\'s plan —', await biz.locator('#swap-status').textContent());

  let miaAfter = null;
  for (let i = 0; i < 10 && !miaAfter; i++) {
    await new Promise((r) => setTimeout(r, 1500));
    const svcs = await (await ctx.request.get(
      `${API}/tmf-api/serviceInventory/v4/service?relatedPartyId=${mia.id}`, { headers: H })).json();
    const same = (svcs || []).find((s) => s.id === miaSvc.id);
    if (same && same.name === planB.name) miaAfter = same;
  }
  if (!miaAfter) fail("Mia's service was not renamed");
  if (miaAfter.supportingResource.find((r) => r.value).value !== miaNumber) fail("Mia's number changed!");
  console.log('OK Mia keeps', miaNumber, '— now on', miaAfter.name);

  // tidy the throwaway catalog entries
  for (const path of [`productOffering/${committed.id}`, `productOffering/${fiber500.id}`,
    `productOfferingPrice/${f5Price.id}`]) {
    await ctx.request.delete(`${API}/tmf-api/productCatalogManagement/v4/${path}`,
      { headers: H }).catch(() => {});
  }

  await browser.close();
  console.log('\nALL PLAN-CHANGE CHECKS PASSED — modify order, same number, commitment + '
    + 'ownership guards, bundle broadband tier upgrade, B2C and B2B.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
