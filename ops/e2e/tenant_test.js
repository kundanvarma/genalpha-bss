/* Multitenancy E2E: two operators share one BSS deployment. Nova Telecom's
 * white-label storefront (shop.nova.localhost) sells Nova's catalog against
 * the nova Keycloak realm; GenAlpha keeps localhost. A Nova customer signs
 * up, orders, and neither operator can see a byte of the other's world. */
const { chromium } = require('playwright');

const NOVA_SHOP = 'http://shop.nova.localhost:8080/shop/';
const API = 'http://localhost:8080';
const NOVA_API_HOST = 'shop.nova.localhost:8080';
const run = Date.now();

async function staffToken(request, realm) {
  const res = await request.post(
    `http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };

  // --- Nova's white-label storefront serves Nova's catalog only
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto(NOVA_SHOP);
  await page.locator('.card', { hasText: 'Nova Unlimited 5G' }).first().waitFor({ timeout: 20000 });
  if (await page.locator('.card', { hasText: 'GenAlpha One Home & Mobile' }).count()) {
    fail("Nova storefront leaked GenAlpha's bundle");
  }
  console.log('OK Nova storefront shows the Nova catalog and none of GenAlpha\'s');

  // --- Sign-in on the Nova host goes to the NOVA realm
  await page.click('.who button'); // "Logg inn" on the Norwegian tenant
  await page.waitForSelector('a[href*="registration"], input[name="username"]', { timeout: 20000 });
  if (!page.url().includes('/realms/nova/')) {
    fail('Nova storefront login left the nova realm: ' + page.url());
  }
  console.log('OK login flow runs against the nova realm');

  // --- Nova customer self-registers and orders the plan
  await page.click('a[href*="registration"]');
  await page.waitForSelector('input[name="email"]');
  await page.fill('input[name="firstName"]', 'Nia');
  await page.fill('input[name="lastName"]', 'Nova');
  await page.fill('input[name="email"]', `nia.${run}@nova.example`);
  await page.fill('input[name="password"]', 'Passw0rd!');
  await page.fill('input[name="password-confirm"]', 'Passw0rd!');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.nav', { timeout: 20000 });
  console.log('OK Nova customer self-registered through the nova realm');

  await page.locator('.card', { hasText: 'Nova Unlimited 5G' }).first().click();
  await page.locator('button', { hasText: 'Legg i handlekurven' }).first().waitFor({ timeout: 15000 });
  await page.locator('button', { hasText: 'Legg i handlekurven' }).first().click();
  await page.click('.nav >> text=Handlekurv'); // the Norwegian tenant, in Norwegian
  await page.locator('.row', { hasText: 'Nova Unlimited 5G' }).first().waitFor({ timeout: 15000 });
  // Plan-only line: no shipping, no install, nothing due now — plain Checkout.
  const checkoutButton = page.locator('button.primary.big');
  const label = (await checkoutButton.textContent()).trim();
  if (label !== 'Til kassen') fail(`expected Norwegian checkout button, got "${label}"`);
  await checkoutButton.click();
  // Success navigates to My orders; surface the storefront's own error if not.
  const orderRow = page.locator('.row', { hasText: 'Nova Unlimited 5G' }).first();
  await orderRow.waitFor({ timeout: 30000 }).catch(async () => {
    fail('checkout did not land on an order: ' + (await page.locator('.error').textContent().catch(() => 'no error shown')));
  });
  const state = await orderRow.locator('.state').textContent();
  console.log('OK Nova order placed through the Nova storefront, state:', state);

  const novaToken = await page.evaluate(() => sessionStorage.getItem('bss.shop.token'));
  const novaOrders = await page.evaluate(async ({ token }) => {
    const res = await fetch('/tmf-api/productOrderingManagement/v4/productOrder?limit=10',
      { headers: { Authorization: 'Bearer ' + token } });
    return res.json();
  }, { token: novaToken });
  if (!novaOrders.length) fail('Nova customer has no orders via API');
  const novaOrderId = novaOrders[0].id;
  console.log('OK Nova order visible to its owner:', novaOrderId.slice(0, 8),
    '| state', novaOrders[0].state);

  // --- Thin SOM: a plan-only (digital) order completes ITSELF — no staff.
  let somState = novaOrders[0].state;
  for (let attempt = 0; attempt < 20 && somState !== 'completed'; attempt++) {
    await page.waitForTimeout(2000);
    const refreshed = await page.evaluate(async ({ token, id }) => {
      const res = await fetch(`/tmf-api/productOrderingManagement/v4/productOrder/${id}`,
        { headers: { Authorization: 'Bearer ' + token } });
      return res.json();
    }, { token: novaToken, id: novaOrderId });
    somState = refreshed.state;
  }
  if (somState !== 'completed') fail('SOM never completed the digital order (state ' + somState + ')');
  console.log('OK SOM auto-completed the digital order — no staff involved');

  // --- Operator isolation, staff level: each realm's staff sees only its tenant
  const setup = await browser.newContext();
  const genalphaStaff = await staffToken(setup.request, 'bss');
  const novaStaff = await staffToken(setup.request, 'nova');

  // TMF641/638: the production layer's records exist, in Nova's tenant only.
  const novaServiceOrders = await (await setup.request.get(
    `${API}/tmf-api/serviceOrdering/v4/serviceOrder?productOrderId=${novaOrderId}`,
    { headers: { Authorization: 'Bearer ' + novaStaff } })).json();
  if (!novaServiceOrders.length || novaServiceOrders[0].state !== 'completed') {
    fail('no completed service order behind the product order: ' + JSON.stringify(novaServiceOrders));
  }
  const genalphaServiceOrders = await (await setup.request.get(
    `${API}/tmf-api/serviceOrdering/v4/serviceOrder?productOrderId=${novaOrderId}`,
    { headers: { Authorization: 'Bearer ' + genalphaStaff } })).json();
  if (genalphaServiceOrders.length) fail("GenAlpha staff can see Nova's service orders");
  console.log('OK TMF641 service orders exist behind the order, tenant-partitioned');

  // TMF685: activation drew Nia's number from NOVA's pool, not GenAlpha's.
  const allNovaServices = await (await setup.request.get(
    `${API}/tmf-api/serviceInventory/v4/service`,
    { headers: { Authorization: 'Bearer ' + novaStaff } })).json();
  const withNumber = allNovaServices.find((sv) =>
    (sv.supportingResource || []).some((r) => String(r.value).startsWith('+46731')));
  if (!withNumber) fail('no nova service carries a +46731 number: ' + JSON.stringify(allNovaServices).slice(0, 300));
  if (allNovaServices.some((sv) => (sv.supportingResource || []).some((r) => String(r.value).startsWith('+46701')))) {
    fail("a nova service drew a number from GenAlpha's pool");
  }
  console.log('OK TMF685 activation assigned a nova-pool MSISDN:',
    withNumber.supportingResource[0].value);

  const genalphaView = await (await setup.request.get(
    `${API}/tmf-api/productOrderingManagement/v4/productOrder?limit=100`,
    { headers: { Authorization: 'Bearer ' + genalphaStaff } })).json();
  if (genalphaView.some((o) => o.id === novaOrderId)) {
    fail("GenAlpha staff can see Nova's order in a list");
  }
  const foreign = await setup.request.get(
    `${API}/tmf-api/productOrderingManagement/v4/productOrder/${novaOrderId}`,
    { headers: { Authorization: 'Bearer ' + genalphaStaff } });
  if (foreign.status() !== 404) fail(`GenAlpha staff fetching Nova's order: expected 404, got ${foreign.status()}`);
  console.log('OK GenAlpha staff cannot see Nova\'s order (list clean, direct fetch 404)');

  const novaView = await (await setup.request.get(
    `${API}/tmf-api/productOrderingManagement/v4/productOrder?limit=100`,
    { headers: { Authorization: 'Bearer ' + novaStaff } })).json();
  if (!novaView.some((o) => o.id === novaOrderId)) fail("Nova staff cannot see Nova's own order");
  // Repeat-safe disjointness: no order may appear in both operators' views.
  const genalphaIds = new Set(genalphaView.map((o) => o.id));
  const overlap = novaView.filter((o) => genalphaIds.has(o.id));
  if (overlap.length) fail('order visible to BOTH operators: ' + overlap[0].id);
  const novaCatalog = await (await setup.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`,
    { headers: { Authorization: 'Bearer ' + novaStaff } })).json();
  // isolation, not size: nothing of GenAlpha's may appear, Nova's flagship must
  const genalphaNames = ['GenAlpha', 'Samsung', 'Apple', 'Netflix'];
  if (!novaCatalog.length
      || novaCatalog.some((o) => genalphaNames.some((g) => o.name.includes(g)))
      || !novaCatalog.some((o) => o.name === 'Nova Unlimited 5G')) {
    fail('Nova staff catalog view wrong: ' + JSON.stringify(novaCatalog.map((o) => o.name)));
  }
  console.log('OK Nova staff sees only its own world:', novaCatalog.length, 'offerings, none of GenAlpha\'s');

  // --- Parties are partitioned too
  const novaParties = await (await setup.request.get(
    `${API}/tmf-api/party/v4/individual?limit=100`,
    { headers: { Authorization: 'Bearer ' + novaStaff } })).json();
  const genalphaParties = await (await setup.request.get(
    `${API}/tmf-api/party/v4/individual?limit=100`,
    { headers: { Authorization: 'Bearer ' + genalphaStaff } })).json();
  if (!novaParties.some((p) => (p.givenName || '').includes('Nia'))) {
    fail('Nova customer party missing from Nova staff view');
  }
  if (genalphaParties.some((p) => (p.givenName || '').includes('Nia'))) {
    fail("GenAlpha staff can see Nova's customer");
  }
  console.log('OK party base partitioned: Nia exists for Nova staff only',
    `(nova ${novaParties.length} vs genalpha ${genalphaParties.length} individuals)`);

  // --- The event stream stays inside the tenant: Nova's order minted a
  // notification for the Nova customer, in the nova tenant.
  let inbox = '[]';
  for (let attempt = 0; attempt < 15; attempt++) {
    inbox = JSON.stringify(await page.evaluate(async ({ token }) => {
      const res = await fetch('/tmf-api/communicationManagement/v4/communicationMessage?limit=50',
        { headers: { Authorization: 'Bearer ' + token } });
      return res.json();
    }, { token: novaToken }));
    if (inbox.includes('Order received')) break;
    await page.waitForTimeout(2000);
  }
  if (!inbox.includes('Order received')) fail('Nova customer never got the Order received notification: ' + inbox.slice(0, 200));
  console.log('OK tenant-tagged event minted Nova\'s notification in the nova tenant');

  // --- The CSR channel is white-labeled too: Nova's agents work Nova's
  // customer base through csr.nova.localhost, against the nova realm.
  const csrCtx = await browser.newContext();
  const csr = await csrCtx.newPage();
  await csr.goto('http://csr.nova.localhost:8080/csr/');
  await csr.waitForSelector('input[name="username"]', { timeout: 20000 });
  if (!csr.url().includes('/realms/nova/')) {
    fail('Nova CSR console login left the nova realm: ' + csr.url());
  }
  await csr.fill('input[name="username"]', 'agent-anna');
  await csr.fill('input[name="password"]', 'agent');
  await csr.click('input[type="submit"], button[type="submit"]');
  await csr.waitForSelector('.searchbar', { timeout: 20000 });
  await csr.fill('.searchbar input', 'Nova');
  await csr.click('.searchbar button');
  await csr.locator('.rowlink', { hasText: 'Nia' }).first().waitFor({ timeout: 15000 });
  console.log('OK Nova agent works Nova\'s customers in the white-labeled CSR console');

  await browser.close();
  console.log('\nALL TENANT ISOLATION CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
