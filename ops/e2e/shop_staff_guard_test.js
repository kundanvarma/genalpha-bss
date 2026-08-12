/* Shop staff-account guard — a staff SSO session must not shop as a customer. Suite #106.
 *
 * The mirror of the console door gate (#104): same-realm single sign-on can
 * carry a STAFF console session into the shop, where a superuser like `demo`
 * (no party_id, no customer role) is not a real shopper. The shop must present
 * such a session as a GUEST-plus-switch, never the customer account UI, and
 * must never place an order under a non-customer identity.
 *
 *  - STAFF GUARDED: demo in the shop → the staff banner + a "switch account"
 *    button, and NO customer nav (My orders) and NO avatar.
 *  - CUSTOMER UNAFFECTED: kai gets the full shop, no banner.
 */
const { chromium } = require('playwright');
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);

async function shopLogin(page, user, pass) {
  await page.goto('http://localhost:8080/shop/');
  await page.locator('.who >> text=Sign in').click();
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', user);
  await page.fill('input[name="password"]', pass);
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForTimeout(3000);
}

(async () => {
  const browser = await chromium.launch();
  try {
    /* ---------- 1. a STAFF account is guarded ---------- */
    const sctx = await browser.newContext();
    const sp = await sctx.newPage();
    await shopLogin(sp, 'demo', 'demo'); // staff superuser: no party_id, no customer role
    const s = await sp.evaluate(() => ({
      banner: !!document.querySelector('[data-testid=staff-in-shop]'),
      sw: !!document.querySelector('[data-testid=switch-account]'),
      myOrders: document.body.innerText.includes('My orders'),
      avatar: !!document.querySelector('[data-testid=avatar]'),
    }));
    if (!s.banner) fail('no staff-in-shop banner for a staff session');
    if (!s.sw) fail('no "switch account" escape offered');
    if (s.myOrders) fail('the customer "My orders" nav showed for a staff account');
    if (s.avatar) fail('the customer avatar showed for a staff account');
    ok('STAFF GUARDED: demo in the shop → banner + switch, NO customer nav, NO avatar');

    /* ---------- 2. a real CUSTOMER is unaffected ---------- */
    const cctx = await browser.newContext();
    const cp = await cctx.newPage();
    await shopLogin(cp, 'kai@bss.local', 'kai');
    const c = await cp.evaluate(() => ({
      banner: !!document.querySelector('[data-testid=staff-in-shop]'),
      myOrders: document.body.innerText.includes('My orders'),
      avatar: !!document.querySelector('[data-testid=avatar]'),
    }));
    if (c.banner) fail('a real customer wrongly saw the staff banner');
    if (!c.myOrders || !c.avatar) fail('a real customer lost their shop UI');
    ok('CUSTOMER UNAFFECTED: kai gets the full shop — My orders, avatar, no banner');

    console.log('\nALL SHOP-STAFF-GUARD CHECKS PASSED — a staff console session carried into the shop by'
      + ' SSO is a guest with a switch prompt, never a customer; a real shopper is untouched.');
  } finally {
    await browser.close();
  }
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
