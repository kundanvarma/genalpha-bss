/* Dynamic pricing as DATA: pricing rules authored in the policy component adjust
 * a price at compute time, with no redeploy. We price a €100 subtotal, then:
 *   1. add a "15% off for everyone" rule  → €85;
 *   2. add a "10% off for verified customers" rule → stacks to €76.50 when the
 *      context says verified, stays €85 when it doesn't;
 *   3. disable both → back to €100.
 * The rules appeared and vanished with rows — the price moved without a deploy. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const run = Date.now();

async function token(request) {
  const res = await request.post(KC, {
    form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' },
  });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const near = (a, b) => Math.abs(Number(a) - Number(b)) < 0.005;

  const t = await token(ctx.request);
  const H = { Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' };
  const POLICY = `${API}/tmf-api/policyManagement/v4`;
  const price = async (context) =>
    (await (await ctx.request.post(`${POLICY}/price`, { headers: H, data: { context } })).json());

  // Baseline: no enabled pricing rules → price is the subtotal, untouched.
  const base = await price({ subtotal: 100 });
  if (!near(base.total, 100)) fail('baseline price should be 100, got ' + base.total);
  console.log('OK baseline: €100 with no pricing rules');

  // 1. "15% off for everyone" — authored as data.
  const r1 = await ctx.request.post(`${POLICY}/policyRule`, {
    headers: H,
    data: {
      name: `E2E launch 15% off ${run}`, domain: 'pricing', effect: 'adjust', priority: 10, enabled: true,
      condition: JSON.stringify({ '==': [1, 1] }), adjustmentType: 'percent', adjustmentValue: -15,
      message: 'Launch offer (15% off)',
    },
  });
  if (r1.status() !== 201) fail('pricing rule create should be 201, got ' + r1.status());
  const id1 = (await r1.json()).id;
  const p1 = await price({ subtotal: 100 });
  if (!near(p1.total, 85)) fail('after 15% off, price should be 85, got ' + p1.total);
  if (!p1.adjustments.some((a) => near(a.amount, -15))) fail('missing -15 adjustment line');
  console.log('OK 15%-off rule authored → €100 becomes €85 (no redeploy)');

  // 2. "10% off for verified customers" — stacks, but only when verified.
  const r2 = await ctx.request.post(`${POLICY}/policyRule`, {
    headers: H,
    data: {
      name: `E2E verified 10% off ${run}`, domain: 'pricing', effect: 'adjust', priority: 20, enabled: true,
      condition: JSON.stringify({ var: 'verifiedIdentity' }), adjustmentType: 'percent', adjustmentValue: -10,
      message: 'Verified-customer discount (10% off)',
    },
  });
  if (r2.status() !== 201) fail('second pricing rule create failed: ' + r2.status());
  const id2 = (await r2.json()).id;

  const verified = await price({ subtotal: 100, verifiedIdentity: true });
  if (!near(verified.total, 76.5)) fail('verified should stack to 76.50, got ' + verified.total);
  console.log('OK verified customer stacks both rules → €76.50');

  const notVerified = await price({ subtotal: 100, verifiedIdentity: false });
  if (!near(notVerified.total, 85)) fail('non-verified should be 85 only, got ' + notVerified.total);
  console.log('OK non-verified customer gets only the launch rule → €85 (rule is conditional)');

  // 3. Disable both — a row change — and the price returns to base.
  await ctx.request.patch(`${POLICY}/policyRule/${id1}`, { headers: H, data: { enabled: false } });
  await ctx.request.patch(`${POLICY}/policyRule/${id2}`, { headers: H, data: { enabled: false } });
  const off = await price({ subtotal: 100, verifiedIdentity: true });
  if (!near(off.total, 100)) fail('after disabling, price should be 100, got ' + off.total);
  console.log('OK both rules disabled → price back to €100 — NO redeploy');

  // 4. The customer SEES the rules: re-enable the launch discount, put an
  //    offering in a fresh customer's cart, and the cart previews the adjusted
  //    price — the same engine the bill will use, shown before checkout.
  await ctx.request.patch(`${POLICY}/policyRule/${id1}`, { headers: H, data: { enabled: true } });
  const offerings = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`, { headers: H })).json();
  const plain = offerings.find((o) => !o.requiresVerifiedIdentity && !o.isBundle
    && (o.productOfferingPrice || []).length);
  if (!plain) fail('no plain priced offering for the cart preview');
  const ctxC = await browser.newContext();
  const page = await ctxC.newPage();
  await page.goto('http://localhost:8080/shop/');
  await page.click('.who >> text=Sign in');
  await page.waitForSelector('a[href*="registration"], input[name="username"]', { timeout: 20000 });
  await page.click('a[href*="registration"]');
  await page.waitForSelector('input[name="email"]');
  await page.fill('input[name="firstName"]', 'Pricia');
  await page.fill('input[name="lastName"]', `Preview${run}`);
  await page.fill('input[name="email"]', `pricia-${run}@example.com`);
  await page.fill('input[name="password"]', 'Passw0rd!');
  await page.fill('input[name="password-confirm"]', 'Passw0rd!');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.nav', { timeout: 20000 });
  await page.click(`text=${plain.name}`);
  await page.click('button.primary.big');            // add to cart → navigates to /cart
  const adjRow = page.locator('[data-testid="price-adjustment"]');
  await adjRow.waitFor({ timeout: 15000 });
  const adjText = await adjRow.textContent();
  if (!adjText.includes('Launch offer')) fail('cart preview missing the rule label, got: ' + adjText);
  const adjTotal = await page.locator('[data-testid="adjusted-total"]').textContent();
  console.log('OK the cart previews the pricing rule before checkout:', adjText.trim(), '→', adjTotal.trim());
  await ctx.request.patch(`${POLICY}/policyRule/${id1}`, { headers: H, data: { enabled: false } });

  await ctx.request.delete(`${POLICY}/policyRule/${id1}`, { headers: H });
  await ctx.request.delete(`${POLICY}/policyRule/${id2}`, { headers: H });
  await browser.close();
  console.log('\nPRICING E2E PASSED — dynamic pricing is data: rules move the price without a redeploy.');
})();
