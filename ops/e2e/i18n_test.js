/* i18n + multi-currency E2E: ONE storefront build serves a Norwegian operator
 * in Norwegian with NOK and an English operator in English with EUR — locale
 * and currency ride the tenant manifest, prices carry their own unit all the
 * way from the catalog. Keycloak login speaks each realm's language too. */
const { chromium } = require('playwright');

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

  await browser.close();
  console.log('\nALL I18N CHECKS PASSED — one build, per-tenant language and currency.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
