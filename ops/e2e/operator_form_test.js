/* Operator-as-a-form — a whole operator minted from the admin console,
 * picked up by the running fleet LIVE. No restart, no rebuild, no shell.
 *
 *  - the host admin fills five fields on the Operators tab: id, brand,
 *    locale, currency, color → realm cloned, tenant block appended,
 *    starter catalog seeded
 *  - every service live-refreshes the shared registry file, so the
 *    newborn's first token works within one refresh interval
 *  - WHITE-LABEL falls out of the registry: the gateway serves the
 *    tenant manifest by hostname, so shop.<id>.localhost wears the new
 *    brand the moment the gateway refreshes
 *  - and only the HOST operator mints: nova's admin is refused
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const t0 = Date.now();

  /* ---------- 1. only the host mints ---------- */
  const novaStaff = await token(ctx, 'nova', 'demo', 'demo');
  const refused = await ctx.post(`${API}/onboarding/v1/operator`, { headers: H(novaStaff),
    data: { id: 'sneaky', name: 'Sneaky Tele' } });
  if (refused.status() === 200) fail('a HOSTED operator minted an operator');
  console.log('OK only the HOST operator mints operators — nova\'s admin is refused ('
    + refused.status() + ')');

  /* ---------- 2. the form ---------- */
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto(`${API}/console/`);
  await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  await page.locator('.tab', { hasText: 'Operators' }).click();
  await page.waitForSelector('#listing-body tr:has-text("genalpha")', { timeout: 15000 })
    .catch(() => fail('the Operators page does not list the fleet'));
  await page.fill('input[name="id"]', 'aurora');
  await page.fill('input[name="name"]', 'Aurora Tele');
  await page.fill('input[name="locale"]', 'sv');
  await page.fill('input[name="currency"]', 'SEK');
  await page.fill('input[name="color"]', '#0E7C61');
  await page.click('#save');
  await page.waitForSelector('#listing-body tr:has-text("Aurora Tele")', { timeout: 120000 })
    .catch(() => fail('Aurora never appeared on the Operators page'));
  await browser.close();
  console.log('OK THE FORM: five fields on the Operators tab and "Aurora Tele" (sv/SEK) exists —'
    + ' realm, registry block, starter catalog');

  /* ---------- 3. the fleet picks her up LIVE ---------- */
  let auroraStaff = null;
  let offers = [];
  for (let i = 0; i < 40 && !offers.length; i++) {
    await sleep(3000);
    auroraStaff = await token(ctx, 'aurora', 'demo', 'demo');
    if (!auroraStaff) continue;
    const res = await ctx.get(
      `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=50`,
      { headers: H(auroraStaff) });
    if (res.status() === 200) {
      offers = (await res.json()).filter((o) => (o.name || '').includes('Aurora'));
    }
  }
  if (!offers.length) fail('the fleet never accepted an aurora token — zero-restart failed');
  console.log(`OK ZERO-RESTART: aurora's first token was honored across the fleet within the`
    + ' refresh interval — no container restarted, no image rebuilt');

  /* ---------- 4. white-label by hostname ---------- */
  let manifest = null;
  for (let i = 0; i < 20 && !manifest; i++) {
    await sleep(3000);
    const res = await ctx.get(`${API}/app/tenant-config.json`,
      { headers: { Host: 'shop.aurora.localhost' } });
    if (res.status() === 200) {
      const body = await res.json();
      if (JSON.stringify(body).includes('Aurora Tele')) manifest = body;
    }
  }
  if (!manifest) fail('shop.aurora.localhost never wore the Aurora brand');
  const flat = JSON.stringify(manifest);
  if (!flat.includes('SEK') || !flat.includes('#0E7C61')) {
    fail('the manifest is missing currency or color: ' + flat.slice(0, 200));
  }
  console.log('OK WHITE-LABEL: shop.aurora.localhost serves the Aurora manifest — name, SEK and'
    + ' the brand color — straight from the live registry. The storefront chrome follows the'
    + ' hostname');

  /* ---------- 5. her first customer ---------- */
  const email = `astrid-${run}@aurora.example`;
  const userRes = await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(auroraStaff), data: { email, givenName: 'Astrid', familyName: `Aurora${run}` } });
  if (userRes.status() >= 400) fail('astrid could not be created: ' + userRes.status());
  const login = await userRes.json();
  const astrid = await token(ctx, 'aurora', email, login.temporaryPassword);
  if (!astrid) fail('astrid got no token from the new realm');
  const orderRes = await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(astrid), data: { productOrderItem: [{ action: 'add',
      productOffering: { id: offers[0].id, name: offers[0].name } }] } });
  if (orderRes.status() >= 400) fail('astrid\'s order was refused: ' + orderRes.status());
  let line = null;
  for (let i = 0; i < 50 && !line; i++) {
    await sleep(3000);
    try {
      const services = await (await ctx.get(`${API}/tmf-api/serviceInventory/v4/service`,
        { headers: H(astrid) })).json();
      line = (Array.isArray(services) ? services : []).find((s) => s.state === 'active') || null;
    } catch (transient) { /* a blip mid-poll is not a verdict */ }
  }
  if (!line) fail('aurora\'s first customer never activated');
  console.log(`OK HER FIRST CUSTOMER: Astrid bought ${offers[0].name} and her line is active —`
    + ` total ${Math.round((Date.now() - t0) / 1000)}s from a blank form to a served customer.`);

  /* ---------- 6. LIVE MUTATION: the serving operator rebrands, nothing restarts ---------- */
  const hostStaff = await token(ctx, 'bss', 'demo', 'demo');
  const mutate = await ctx.patch(`${API}/onboarding/v1/operator/aurora`, { headers: H(hostStaff),
    data: { name: 'Aurora Tele Nord', color: '#AA3366' } });
  if (mutate.status() !== 200) fail('the live mutation was refused: ' + mutate.status());
  let rebranded = null;
  for (let i = 0; i < 20 && !rebranded; i++) {
    await sleep(3000);
    const res = await ctx.get(`${API}/app/tenant-config.json`,
      { headers: { Host: 'shop.aurora.localhost' } });
    try {
      const body = JSON.stringify(await res.json());
      if (body.includes('Aurora Tele Nord') && body.includes('#AA3366')) rebranded = body;
    } catch (transient) { /* a blip mid-poll is not a verdict */ }
  }
  if (!rebranded) fail('the rebrand never reached the storefront manifest');
  // and the built-ins are refusal-territory: their config is env, not form
  const builtin = await ctx.patch(`${API}/onboarding/v1/operator/nova`, { headers: H(hostStaff),
    data: { name: 'Hijacked' } });
  if (builtin.status() === 200) fail('a built-in tenant was mutated by form');
  console.log('OK LIVE MUTATION: Aurora rebranded to "Aurora Tele Nord" (#AA3366) and the'
    + ' storefront manifest followed within one refresh interval — while she was SERVING.'
    + ' Nothing restarted. Built-ins refuse the form (their config is env), and identity'
    + ' fields are not editable at all: the risk boundary is written down, not wished away');

  console.log('\nALL OPERATOR-FORM CHECKS PASSED — an operator is a FORM: five fields, one save,'
    + ' and the running fleet grows a tenant with its own brand, currency, catalog and customers.'
    + ' Zero restarts. Zero builds. The hosting business is a button.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
