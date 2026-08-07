/* i18n + multi-currency E2E: ONE storefront build serves a Norwegian operator
 * in Norwegian with NOK and an English operator in English with EUR — locale
 * and currency ride the tenant manifest, prices carry their own unit all the
 * way from the catalog. Keycloak login speaks each realm's language too. */
const { chromium, request } = require('playwright');

const NOVA_SHOP = 'http://shop.nova.localhost:8080/shop/';
const GENALPHA_SHOP = 'http://localhost:8080/shop/';

(async () => {
  const browser = await chromium.launch();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

  // --- Nova: Norwegian chrome, NOK prices, Norwegian login
  const nova = await (await browser.newContext()).newPage();
  await nova.goto(NOVA_SHOP);
  await nova.waitForSelector('.nav', { timeout: 20000 });
  const nav = await nova.locator('.nav').textContent();
  for (const label of ['Tilbud', 'Handlekurv', 'Kundeservice']) {
    if (!nav.includes(label)) fail(`Norwegian nav missing "${label}": ${nav}`);
  }
  console.log('OK Nova storefront chrome is Norwegian:', nav.trim().replace(/\s+/g, ' · '));

  await nova.locator('.card', { hasText: 'Nova Unlimited 5G' }).first().waitFor({ timeout: 20000 });
  const card = await nova.locator('.card', { hasText: 'Nova Unlimited 5G' }).first().textContent();
  if (!/kr/.test(card) || !/299/.test(card)) fail('NOK price missing from offer card: ' + card);
  if (/EUR/.test(card)) fail('EUR leaked into the Norwegian shop: ' + card);
  console.log('OK Nova prices render in NOK:', card.match(/[\d\s.,]+kr|kr[\d\s.,]+/)?.[0]?.trim() || '(nb-NO formatted)');

  await nova.click('.who >> text=Logg inn');
  await nova.waitForSelector('input[name="username"]', { timeout: 20000 });
  const login = await nova.locator('body').textContent();
  if (!login.includes('Passord')) fail('Keycloak login is not Norwegian');
  if (!nova.url().includes('/realms/nova/')) fail('login left the nova realm');
  console.log('OK Keycloak sign-in speaks Norwegian for the nova realm (Passord ✓)');

  // --- GenAlpha: untouched — English chrome, EUR prices, English login
  const gen = await (await browser.newContext()).newPage();
  await gen.goto(GENALPHA_SHOP);
  await gen.waitForSelector('.nav', { timeout: 20000 });
  const genNav = await gen.locator('.nav').textContent();
  if (!genNav.includes('Offers') || genNav.includes('Tilbud')) {
    fail('GenAlpha nav should stay English: ' + genNav);
  }
  await gen.locator('.card', { hasText: 'Unlimited 5G' }).first().waitFor({ timeout: 20000 });
  const genCard = await gen.locator('.card', { hasText: 'GenAlpha Mobile Unlimited 5G' }).first().textContent();
  if (!/EUR/.test(genCard)) fail('GenAlpha price should stay in EUR: ' + genCard);
  console.log('OK GenAlpha stays English with EUR — same build, different tenant');

  // --- the manifest is the single switch
  const manifest = await (await gen.context().request.get(
    'http://localhost:8080/app/tenant-config.json', { headers: { Host: 'shop.nova.localhost' } })).json();
  if (manifest.locale !== 'no' || manifest.currency !== 'NOK') {
    fail('app manifest missing locale/currency: ' + JSON.stringify(manifest));
  }
  console.log('OK the mobile app manifest carries locale/currency too:', manifest.locale, manifest.currency);

  // --- B2B parity: the business console speaks Norwegian and bills in NOK
  // birgit's Fjellheim membership is seeded (seed_nova); here we only make
  // sure a NOK company invoice EXISTS to read — order + run if the period
  // has none yet (bills are period-state, not fixture)
  {
    const ctx = await request.newContext();
    const NH = { Authorization: 'Bearer ' + (await (await ctx.post(
      'http://localhost:8085/realms/nova/protocol/openid-connect/token',
      { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json()).access_token,
      'Content-Type': 'application/json' };
    const birgitTok = (await (await ctx.post(
      'http://localhost:8085/realms/nova/protocol/openid-connect/token',
      { form: { grant_type: 'password', client_id: 'bss-demo',
        username: 'birgit@fjellheim.no', password: 'birgit' } })).json()).access_token;
    const bsub = JSON.parse(Buffer.from(
      birgitTok.split('.')[1].padEnd(birgitTok.split('.')[1].length + (4 - birgitTok.split('.')[1].length % 4) % 4, '='),
      'base64').toString()).sub;
    const orgs = await (await ctx.get(
      'http://localhost:8080/tmf-api/party/v4/organization?limit=100', { headers: NH })).json();
    const fjellheim = (orgs || []).find((o) => o.name === 'Fjellheim AS');
    if (!fjellheim) fail('Fjellheim AS not seeded — run seed_nova');
    const orgBills = await (await ctx.get(
      `http://localhost:8080/tmf-api/customerBillManagement/v4/customerBill?relatedPartyId=${fjellheim.id}&limit=5`,
      { headers: NH })).json();
    if (!Array.isArray(orgBills) || !orgBills.length) {
      const novaOffs = await (await ctx.get(
        'http://localhost:8080/tmf-api/productCatalogManagement/v4/productOffering?limit=100',
        { headers: NH })).json();
      const novaPlan = novaOffs.find((o) => /Unlimited|Smart/.test(o.name) && !o.isBundle);
      await ctx.post('http://localhost:8080/tmf-api/productOrderingManagement/v4/productOrder',
        { headers: { Authorization: 'Bearer ' + birgitTok, 'Content-Type': 'application/json' },
          data: { productOrderItem: [{ id: '1', action: 'add', quantity: 1,
            productOffering: { id: novaPlan.id } }],
            relatedParty: [{ id: bsub, role: 'customer' }] } });
      for (let i = 0; i < 15; i++) {
        await new Promise((r) => setTimeout(r, 4000));
        await ctx.post('http://localhost:8080/tmf-api/customerBillManagement/v4/billingRun',
          { headers: NH, data: {} });
        const bills = await (await ctx.get(
          `http://localhost:8080/tmf-api/customerBillManagement/v4/customerBill?relatedPartyId=${fjellheim.id}&limit=5`,
          { headers: NH })).json();
        if (Array.isArray(bills) && bills.length) break;
      }
    }
    await ctx.dispose();
  }
  const biz = await (await browser.newContext()).newPage();
  await biz.goto('http://biz.nova.localhost:8080/biz/');
  await biz.waitForSelector('input[name="username"]', { timeout: 20000 });
  await biz.fill('input[name="username"]', 'birgit@fjellheim.no');
  await biz.fill('input[name="password"]', 'birgit');
  await biz.click('input[type="submit"], button[type="submit"]');
  await biz.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  const bizBody = await biz.locator('body').textContent();
  for (const label of ['BEDRIFT', 'Din organisasjon:', 'Firmafakturaer', 'Bytt abonnement']) {
    if (!bizBody.includes(label)) fail(`Norwegian /biz missing "${label}"`);
  }
  if (!bizBody.includes('Fjellheim')) fail('Fjellheim org missing from /biz');
  await biz.locator('.billrow', { hasText: 'BILL-' }).first().waitFor({ timeout: 15000 });
  const bill = await biz.locator('.billrow').first().textContent();
  if (!/kr/.test(bill) || /EUR/.test(bill)) fail('consolidated invoice not in NOK: ' + bill);
  console.log('OK Norwegian B2B: Birgit\'s bedriftskonsoll in Norwegian, consolidated invoice in NOK');

  await browser.close();
  console.log('\nALL I18N CHECKS PASSED — one build, per-tenant language and currency, B2C and B2B.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
