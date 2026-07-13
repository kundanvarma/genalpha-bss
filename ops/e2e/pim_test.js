/* Hardware & product content (PIM): devices are sold with real imagery and
 * facts, authored without JSON — and the content source is a per-tenant seam.
 *
 *  - genalpha (INTERNAL PIM): device offerings carry gallery + colour-variant
 *    attachments in the document store; specs carry configurable:false facts.
 *    The shop renders the gallery, swaps the hero when the colour changes,
 *    shows phone thumbnails in the bundle picker, and an About table.
 *  - Console: a product owner UPLOADS artwork on the offering form (pat, the
 *    product persona) — TMF667 underneath, zero JSON.
 *  - nova (EXTERNAL PIM): the catalog resolves imagery from the operator's
 *    own PIM (mock-pim container) by product NAME — nothing authored in the
 *    BSS, urls come back /pim/... and render through the gateway.
 *  - Headless throughout: every picture the UI shows is a TMF620 attachment
 *    readable by any client.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const CATALOG = `${API}/tmf-api/productCatalogManagement/v4`;
const run = Date.now();

async function token(request, realm, user, pass) {
  const res = await request.post(
    `http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

// a 1x1 red PNG — the console upload test's "product shot"
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64');

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

  /* ---------- 1. internal PIM, headless: gallery + variants + facts ---------- */
  const staff = await token(ctx.request, 'bss', 'demo', 'demo');
  const H = { Authorization: 'Bearer ' + staff };
  const offers = await (await ctx.request.get(`${CATALOG}/productOffering?limit=100`, { headers: H })).json();
  const samsung = offers.find((o) => o.name === 'Samsung Galaxy S26' && !o.isBundle);
  if (!samsung) fail('Samsung Galaxy S26 not in the catalog');
  const names = (samsung.attachment || []).map((a) => a.name);
  for (const expected of ['gallery-front', 'gallery-back', 'gallery-side']) {
    if (!names.includes(expected)) fail(`Samsung missing ${expected}: ${names}`);
  }
  if (!names.some((n) => n.startsWith('variant-'))) fail('Samsung has no colour variant art');
  if ((samsung.attachment || []).some((a) => String(a.url).startsWith('/pim/'))) {
    fail('genalpha art must come from the INTERNAL store, not the external PIM');
  }
  const spec = await (await ctx.request.get(
    `${CATALOG}/productSpecification/${samsung.productSpecification.id}`, { headers: H })).json();
  const facts = (spec.productSpecCharacteristic || []).filter((c) => c.configurable === false);
  if (!facts.some((c) => c.name === 'display')) fail('spec has no configurable:false facts');
  console.log(`OK internal PIM (headless): ${names.length} attachments (gallery + variants), `
    + `${facts.length} device facts on the spec`);

  /* ---------- 2. the shop: gallery, About table, colour->image ---------- */
  const page = await (await browser.newContext()).newPage();
  await page.goto(`${API}/shop/offering/${samsung.id}`);
  await page.waitForSelector('[data-testid=offer-gallery]', { timeout: 20000 });
  const thumbs = await page.locator('.gallery .thumbs img').count();
  if (thumbs < 3) fail(`expected >=3 gallery thumbnails, got ${thumbs}`);
  await page.locator('[data-testid=device-facts]').waitFor({ timeout: 10000 });
  const factsText = await page.locator('[data-testid=device-facts]').textContent();
  if (!factsText.includes('AMOLED')) fail('About this device table missing the display fact');
  const heroBefore = await page.locator('[data-testid=offer-hero]').getAttribute('src');
  // pick a colour — the hero must follow it to the variant shot
  const colorSelect = page.locator('.charfield', { hasText: 'color' }).locator('select');
  await colorSelect.waitFor({ timeout: 10000 });
  await colorSelect.selectOption('Icy Blue');
  await page.waitForTimeout(400);
  const heroAfter = await page.locator('[data-testid=offer-hero]').getAttribute('src');
  const icyVariant = (samsung.attachment || []).find((a) => a.name === 'variant-Icy Blue');
  if (heroAfter === heroBefore || !icyVariant || heroAfter !== icyVariant.url) {
    fail(`hero did not follow the colour pick: ${heroBefore} -> ${heroAfter}`);
  }
  console.log('OK shop device page: gallery + About table, hero follows the colour pick');

  // the standalone pick lands on the cart LINE as characteristics
  await page.click('button.primary.big');
  await page.waitForURL('**/cart', { timeout: 15000 });
  const cartLine = await page.evaluate(async () => {
    const id = localStorage.getItem('bss.shop.cartId');
    const cart = await (await fetch(`/tmf-api/shoppingCart/v4/shoppingCart/${id}`)).json();
    return (cart.cartItem || [])[0];
  });
  if (cartLine?.characteristics?.color !== 'Icy Blue') {
    fail('cart line missing the colour characteristic: ' + JSON.stringify(cartLine?.characteristics));
  }
  console.log('OK the colour rides the cart line — TMF663 characteristics, not channel state');

  // bundle picker shows the phones as pictures
  const bundle = offers.find((o) => o.name === 'GenAlpha One Home & Mobile');
  if (bundle) {
    await page.goto(`${API}/shop/offering/${bundle.id}`);
    await page.waitForSelector('.option', { timeout: 15000 });
    await page.locator('.optthumb').first().waitFor({ timeout: 10000 });
    const optThumbs = await page.locator('.optthumb').count();
    if (optThumbs < 2) fail(`expected phone thumbnails in the picker, got ${optThumbs}`);
    console.log(`OK bundle configurator: ${optThumbs} option thumbnails — phones are pictures now`);
  }

  /* ---------- 3. console: the product owner uploads artwork (pat) ---------- */
  const consolePage = await (await browser.newContext()).newPage();
  await consolePage.goto(`${API}/console/`);
  await consolePage.waitForSelector('input[name="username"]', { timeout: 20000 });
  await consolePage.fill('input[name="username"]', 'pat@bss.local');
  await consolePage.fill('input[name="password"]', 'pat');
  await consolePage.click('input[type="submit"], button[type="submit"]');
  await consolePage.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  const offeringName = `PIM Test Phone ${run}`;
  await consolePage.fill('#fields input[name="name"]', offeringName);
  await consolePage.fill('[data-testid=art-role]', 'gallery-front');
  await consolePage.setInputFiles('[data-testid=art-file]', {
    name: 'front.png', mimeType: 'image/png', buffer: PNG });
  await consolePage.locator('text=✓ added').waitFor({ timeout: 15000 });
  await consolePage.click('#save');
  await consolePage.waitForTimeout(1500);
  const created = (await (await ctx.request.get(
    `${CATALOG}/productOffering?name=${encodeURIComponent(offeringName)}`, { headers: H })).json())[0];
  const art = created?.attachment?.[0];
  if (!art || art.name !== 'gallery-front' || !art.url) {
    fail('uploaded artwork missing from the offering: ' + JSON.stringify(created?.attachment));
  }
  const img = await ctx.request.get(`${API}${art.url}`);
  if (img.status() !== 200 || !(img.headers()['content-type'] || '').includes('image/png')) {
    fail(`uploaded image not served: HTTP ${img.status()}`);
  }
  console.log('OK console: pat uploaded artwork on the offering form — stored in TMF667, served anonymously');
  // tidy: retire the test offering so the shop stays clean
  await ctx.request.delete(`${CATALOG}/productOffering/${created.id}`, { headers: H });

  /* ---------- 4. bring-your-own-PIM: nova resolves from ITS system ---------- */
  const nova = await token(ctx.request, 'nova', 'demo', 'demo');
  const NH = { Authorization: 'Bearer ' + nova, 'Content-Type': 'application/json' };
  let nordic = (await (await ctx.request.get(
    `${CATALOG}/productOffering?name=${encodeURIComponent('Nordic Phone X')}`, { headers: NH })).json())[0];
  if (!nordic) {
    nordic = await (await ctx.request.post(`${CATALOG}/productOffering`, { headers: NH, data: {
      name: 'Nordic Phone X', lifecycleStatus: 'Active',
      description: 'Flagship phone — content served by Nova\'s own PIM.',
      category: [{ name: 'Devices' }] } })).json();
    nordic = (await (await ctx.request.get(
      `${CATALOG}/productOffering?name=${encodeURIComponent('Nordic Phone X')}`, { headers: NH })).json())[0];
  }
  const pimUrls = (nordic.attachment || []).map((a) => a.url);
  if (!pimUrls.length || !pimUrls.every((u) => String(u).startsWith('/pim/'))) {
    fail('nova offering should resolve imagery from the external PIM: ' + JSON.stringify(pimUrls));
  }
  if (!(nordic.attachment || []).some((a) => a.name === 'variant-Fjord Blue')) {
    fail('external PIM variants missing');
  }
  // and the browser actually renders it through the gateway
  const novaPage = await (await browser.newContext()).newPage();
  await novaPage.goto('http://shop.nova.localhost:8080/shop/offering/' + nordic.id);
  await novaPage.waitForSelector('[data-testid=offer-hero]', { timeout: 20000 });
  const loaded = await novaPage.waitForFunction(() => {
    const el = document.querySelector('[data-testid=offer-hero]');
    return el && el.complete && el.naturalWidth > 0;
  }, { timeout: 15000 }).then(() => true).catch(() => false);
  if (!loaded) fail('external PIM image did not render in the nova shop');
  console.log('OK bring-your-own-PIM: nova\'s catalog resolves imagery from the operator\'s own '
    + 'system by product name — nothing authored in the BSS, rendered through the gateway');

  await browser.close();
  console.log('\nALL PIM CHECKS PASSED — internal content store by default, the operator\'s own '
    + 'PIM by config; galleries, colour variants, device facts; console upload; headless everywhere.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
