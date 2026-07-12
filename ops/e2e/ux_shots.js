/* UX audit captures: screenshot every channel in realistic, data-full states.
 * Output → the directory given as argv[2]. Not a test — a camera. */
const { chromium } = require('playwright');
const path = require('path');
const OUT = process.argv[2] || '/tmp/ux';
const run = Date.now();
const shot = (page, name) => page.screenshot({ path: path.join(OUT, name + '.png'), fullPage: false });

async function register(page, email, first, last) {
  await page.goto('http://localhost:8080/shop/');
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

(async () => {
  const browser = await chromium.launch();

  // ---------- storefront: guest, configurator, cart, desktop + phone ----------
  const desk = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  await desk.goto('http://localhost:8080/shop/');
  await desk.waitForSelector('.card', { timeout: 15000 });
  await desk.waitForTimeout(1200);
  await shot(desk, '01-shop-home-guest');

  await desk.click('text=GenAlpha One Home & Mobile');
  await desk.waitForSelector('.choice', { timeout: 15000 });
  await desk.waitForTimeout(1200);
  await shot(desk, '02-shop-configurator');

  await register(desk, `ux-${run}@example.com`, 'Uma', `Xperience${run}`);
  await desk.goto('http://localhost:8080/shop/offering', { waitUntil: 'domcontentloaded' }).catch(() => {});
  await desk.goto('http://localhost:8080/shop/');
  await desk.click('text=GenAlpha One Home & Mobile');
  await desk.waitForSelector('.choice', { timeout: 15000 });
  await desk.click('button.primary.big');
  await desk.waitForSelector('.row', { timeout: 15000 });
  await desk.waitForTimeout(1500);
  await shot(desk, '03-shop-cart');

  // phone viewport
  const phone = await (await browser.newContext({ viewport: { width: 390, height: 844 } })).newPage();
  await phone.goto('http://localhost:8080/shop/');
  await phone.waitForSelector('.card', { timeout: 15000 });
  await phone.waitForTimeout(1000);
  await shot(phone, '04-shop-home-phone');

  // nova tenant (white-label)
  await desk.goto('http://shop.nova.localhost:8080/shop/').catch(() => {});
  await desk.waitForTimeout(1500);
  await shot(desk, '05-shop-nova');

  // ---------- admin console: offerings, rules (+dry run), porting ----------
  const con = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  await con.goto('http://localhost:8080/console/');
  await con.waitForSelector('input[name="username"]', { timeout: 20000 });
  await con.fill('input[name="username"]', 'demo');
  await con.fill('input[name="password"]', 'demo');
  await con.click('input[type="submit"], button[type="submit"]');
  await con.waitForSelector('.tab', { timeout: 20000 });
  await con.waitForTimeout(1500);
  await shot(con, '06-console-offerings');
  await con.locator('.tab', { hasText: 'Rules' }).click();
  await con.waitForSelector('#test-order', { timeout: 10000 });
  await con.waitForTimeout(800);
  await shot(con, '07-console-rules');
  await con.locator('.tab', { hasText: 'Porting' }).click();
  await con.waitForTimeout(1200);
  await shot(con, '08-console-porting');
  await con.locator('.tab', { hasText: 'Campaigns' }).click();
  await con.waitForTimeout(1200);
  await shot(con, '09-console-campaigns');

  // ---------- CSR: queue + customer 360 ----------
  const csr = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  await csr.goto('http://localhost:8080/csr/');
  await csr.waitForSelector('input[name="username"]', { timeout: 20000 });
  await csr.fill('input[name="username"]', 'agent-anna');
  await csr.fill('input[name="password"]', 'agent');
  await csr.click('input[type="submit"], button[type="submit"]');
  await csr.waitForSelector('.searchbar', { timeout: 20000 });
  await csr.waitForTimeout(1200);
  await shot(csr, '10-csr-home');
  await csr.fill('.searchbar input', `Xperience${run}`);
  await csr.click('.searchbar button');
  await csr.locator('.rowlink').first().waitFor({ timeout: 15000 });
  await csr.locator('.rowlink').first().click();
  await csr.waitForSelector('h1', { timeout: 15000 });
  await csr.waitForTimeout(2500);
  await shot(csr, '11-csr-360-top');
  await csr.evaluate(() => window.scrollTo(0, document.body.scrollHeight * 0.45));
  await csr.waitForTimeout(600);
  await shot(csr, '12-csr-360-mid');

  // ---------- live flow + guided demo ----------
  const flow = await (await browser.newContext({ viewport: { width: 1600, height: 900 } })).newPage();
  await flow.goto('http://localhost:8080/flow/');
  await flow.waitForTimeout(2500);
  await shot(flow, '13-flow-process');
  await flow.goto('http://localhost:8080/flow/demo.html');
  await flow.waitForTimeout(1500);
  await shot(flow, '14-flow-demo-signedout');

  // ---------- mobile app ----------
  const app = await (await browser.newContext({ viewport: { width: 390, height: 844 } })).newPage();
  await app.goto('http://localhost:8080/app/').catch(() => {});
  await app.waitForTimeout(2500);
  await shot(app, '15-mobile-app');

  await browser.close();
  console.log('shots written to', OUT);
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
