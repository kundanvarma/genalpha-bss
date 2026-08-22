/* Refresh the README screenshots in docs/img against the CURRENT UI.
 * Seven shots: live-flow (architecture), live-flow-process (process view),
 * storefront-genalpha, storefront-nova, console-campaigns, csr-copilot,
 * mobile-app. Every frame is the live system — nothing staged. */
const { chromium } = require('playwright');
const path = require('path');

const IMG = path.resolve(__dirname, '../../docs/img');
const shot = (page, name, opts = {}) =>
  page.screenshot({ path: path.join(IMG, name + '.png'), ...opts });

async function login(page, user, pass) {
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', user);
  await page.fill('input[name="password"]', pass);
  await page.click('input[type="submit"], button[type="submit"]');
}

(async () => {
  const browser = await chromium.launch();

  /* ---------- storefronts (guest, no login) ---------- */
  {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const page = await ctx.newPage();
    await page.goto('http://localhost:8080/shop/');
    await page.waitForSelector('.card', { timeout: 20000 });
    await page.waitForTimeout(2500);   // images settle
    await shot(page, 'storefront-genalpha');
    console.log('· storefront-genalpha');
    await page.goto('http://shop.nova.localhost:8080/shop/');
    await page.waitForSelector('.card', { timeout: 20000 });
    await page.waitForTimeout(2500);
    await shot(page, 'storefront-nova');
    console.log('· storefront-nova');
    await ctx.close();
  }

  /* ---------- Live Flow: architecture + process views ---------- */
  {
    const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 } });
    const page = await ctx.newPage();
    await page.goto('http://localhost:8080/flow/');
    await page.waitForTimeout(6000);   // events stream in, components light
    await shot(page, 'live-flow');
    console.log('· live-flow');
    // the process view: a toggle/tab if present, else the same page
    const proc = page.locator('text=/Process|Journeys|Business/i').first();
    if (await proc.count()) { await proc.click().catch(() => {}); await page.waitForTimeout(4000); }
    await shot(page, 'live-flow-process');
    console.log('· live-flow-process');
    await ctx.close();
  }

  /* ---------- console: Campaigns tab ---------- */
  {
    const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 } });
    const page = await ctx.newPage();
    await page.goto('http://localhost:8080/console/');
    await login(page, 'demo', 'demo');
    await page.waitForSelector('.tab', { timeout: 20000 });
    const tab = page.locator('.tab', { hasText: 'Campaigns' }).first();
    await tab.click();
    await page.waitForTimeout(3500);
    await shot(page, 'console-campaigns');
    console.log('· console-campaigns');
    await ctx.close();
  }

  /* ---------- CSR: customer 360 with the copilot ---------- */
  {
    const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 } });
    const page = await ctx.newPage();
    await page.goto('http://localhost:8080/csr/');
    await login(page, 'agent-anna', 'agent');
    await page.waitForSelector('.searchbar', { timeout: 20000 });
    await page.fill('.searchbar input', 'Paula');
    await page.click('.searchbar button');
    const hit = page.locator('.rowlink', { hasText: 'Paula' }).first();
    await hit.waitFor({ timeout: 15000 });
    await hit.click();
    await page.waitForSelector('h1', { timeout: 15000 });
    // let the copilot summary render if it streams in
    await page.waitForTimeout(6000);
    await shot(page, 'csr-copilot');
    console.log('· csr-copilot');
    await ctx.close();
  }

  /* ---------- mobile app (phone viewport) ---------- */
  {
    const ctx = await browser.newContext({
      viewport: { width: 400, height: 860 },
      isMobile: true, hasTouch: true,
    });
    const page = await ctx.newPage();
    await page.goto('http://localhost:8080/app/');
    const signin = page.locator('text=Sign in').first();
    if (await signin.count()) await signin.click().catch(() => {});
    await login(page, 'paula@family.example', 'paula');
    await page.waitForSelector('#root', { timeout: 25000 });
    await page.waitForTimeout(5000);   // adaptive home composes
    await shot(page, 'mobile-app');
    console.log('· mobile-app');
    await ctx.close();
  }

  await browser.close();
  console.log('ALL SCREENSHOTS REFRESHED →', IMG);
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
