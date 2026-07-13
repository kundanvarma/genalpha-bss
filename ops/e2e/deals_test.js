/* Deal merchandising: a pricing rule is not just billing math — the shop
 * sells it at every hop, and the guest sees honest indicative pricing.
 *
 *  - a consumer-scoped rule ("10% off Samsung with the plan") is authored as
 *    data (staff API — the console preset and the copilot produce the same)
 *  - the PLAN PAGE advertises it (anonymous teaser) with the consumer-only
 *    note and an "add the deal to cart" one-gesture action
 *  - the CART offers to complete a deal whose partner is missing, groups the
 *    deal's lines under a header, lets the device line pick its colour IN
 *    PLACE, and shows the GUEST an indicative total with the honest note
 *  - fishing hardening: posting a company id anonymously leaks nothing
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();

async function token(request) {
  const res = await request.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const staff = await token(ctx.request);
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };

  const offers = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`, { headers: H })).json();
  const plan = offers.find((o) => o.name === 'GenAlpha Mobile 10 GB');
  const samsung = offers.find((o) => o.name === 'Samsung Galaxy S26');
  if (!plan || !samsung) fail('seeded offerings missing');

  // the deal, authored as data — consumer-scoped like the console preset
  const rule = await (await ctx.request.post(`${API}/tmf-api/policyManagement/v4/policyRule`, {
    headers: H, data: {
      name: `Deal E2E ${run}`, domain: 'pricing', effect: 'adjust', priority: 60, enabled: true,
      condition: JSON.stringify({ and: [
        { in: [plan.id, { var: 'offeringIds' }] },
        { in: [samsung.id, { var: 'offeringIds' }] },
        { '!': { var: 'organizationId' } }] }),
      adjustmentType: 'percent', adjustmentValue: -10,
      message: `10% off Samsung Galaxy S26 with ${plan.name}`,
    } })).json();

  try {
    /* ---------- teaser API: anonymous, scoped, fishing-proof ---------- */
    const teasers = await (await ctx.request.get(
      `${API}/tmf-api/policyManagement/v4/price/teaser?offeringId=${plan.id}`)).json();
    const teaser = teasers.find((x) => x.name === rule.name);
    if (!teaser || teaser.audience !== 'consumer'
        || !(teaser.relatedOfferingIds || []).includes(samsung.id)) {
      fail('teaser missing/unscoped/unlinked: ' + JSON.stringify(teasers).slice(0, 200));
    }
    const fishing = await (await ctx.request.post(
      `${API}/tmf-api/policyManagement/v4/price/indicative`, { data: { context: {
        subtotal: 100, offeringIds: [plan.id], organizationId: 'any-guessed-org' } } })).json();
    if ((fishing.adjustments || []).some((a) => (a.ruleName || '').includes('corporate'))) {
      fail('anonymous fishing surfaced an identity-conditioned rule');
    }
    console.log('OK teaser: anonymous, consumer-scoped, partner linked; fishing leaks nothing');

    /* ---------- plan page: banner + one-gesture deal add ---------- */
    const page = await (await browser.newContext()).newPage();
    await page.goto(`${API}/shop/offering/${plan.id}`);
    await page.locator('[data-testid=offer-promos]', { hasText: 'Samsung' }).waitFor({ timeout: 15000 });
    const promoText = await page.locator('[data-testid=offer-promos]').textContent();
    if (!promoText.includes('private customers only')) fail('consumer note missing on the banner');
    await page.locator('[data-testid=promo-add-deal]').first().click();
    await page.waitForURL('**/cart', { timeout: 15000 });
    await page.locator('[data-testid=deal-group]').waitFor({ timeout: 15000 });
    console.log('OK plan page advertises the deal; one gesture added both, grouped in the cart');

    /* ---------- cart: colour in place, guest indicative pricing ---------- */
    await page.locator('[data-testid=line-config] select').first().waitFor({ timeout: 15000 });
    await page.locator('[data-testid=line-config]').last().locator('select').first()
      .selectOption('Icy Blue');
    await page.waitForTimeout(1800);
    const adjRow = page.locator('[data-testid=price-adjustment]', { hasText: `10% off` });
    await adjRow.first().waitFor({ timeout: 15000 });
    await page.locator('[data-testid=indicative-note]').waitFor({ timeout: 10000 });
    const line = await page.evaluate(async () => {
      const id = localStorage.getItem('bss.shop.cartId');
      const c = await (await fetch('/tmf-api/shoppingCart/v4/shoppingCart/' + id)).json();
      return (c.cartItem || []).find((l) => l.name === 'Samsung Galaxy S26');
    });
    if (line?.characteristics?.color !== 'Icy Blue') {
      fail('cart-line colour pick did not persist: ' + JSON.stringify(line?.characteristics));
    }
    console.log('OK cart: colour picked on the line, guest sees the deal priced with the'
      + ' indicative note');

    /* ---------- the hint: remove the partner, the cart offers it back ---------- */
    await page.locator('.row.dealline', { hasText: 'Samsung' })
      .locator('button', { hasText: 'Remove' }).click();
    // several live deals may mention Samsung — act on OURS specifically
    const ourHint = page.locator('[data-testid=deal-hint]',
      { hasText: `10% off Samsung Galaxy S26 with GenAlpha Mobile 10 GB` });
    await ourHint.first().waitFor({ timeout: 15000 });
    await ourHint.first().locator('[data-testid=deal-hint-add]').click();
    await page.waitForTimeout(1800);
    const names = await page.evaluate(async () => {
      const id = localStorage.getItem('bss.shop.cartId');
      const c = await (await fetch('/tmf-api/shoppingCart/v4/shoppingCart/' + id)).json();
      return (c.cartItem || []).map((l) => `${l.name} x${l.quantity}`);
    });
    if (!names.includes('Samsung Galaxy S26 x1') || names.some((n) => n.endsWith('x2'))) {
      fail('deal hint re-add wrong (must be idempotent): ' + names);
    }
    console.log('OK the cart offered the missing partner back and re-added it idempotently');
    await page.close();
  } finally {
    await ctx.request.delete(`${API}/tmf-api/policyManagement/v4/policyRule/${rule.id}`, { headers: H });
  }

  await browser.close();
  console.log('\nALL DEAL-MERCHANDISING CHECKS PASSED — one rule drives the banner, the'
    + ' one-gesture add, the cart hint, in-place configuration and honest guest pricing.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
