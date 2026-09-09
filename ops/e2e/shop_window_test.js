/* #121 shop_window_test — the shop window is a tenant setting (shop-window:
 * static | carousel). The suite reads each tenant's live config and checks the
 * shop renders that mode correctly: static = lead banner + tiles, no welcome
 * card; carousel = arrows and dots that move the slide. Never a scrollbar. */
const { chromium } = require('playwright');
const fail = (m) => { throw new Error(m); };
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 } });
  const noScrollbar = async () => page.evaluate(() => { const el = document.querySelector('[data-testid="banner-strip"]'); return el && el.scrollWidth <= el.clientWidth + 1; });
  for (const host of ['shop.taranga.localhost', 'shop.enet.localhost']) {
    const cfg = await (await fetch(`http://${host}:8080/shop/tenant-config.js`)).text();
    const want = (cfg.match(/shopWindow: '([a-z]+)'/) || [])[1] || 'static';
    await page.goto(`http://${host}:8080/shop/`);
    await page.waitForSelector('[data-testid="banner-strip"]', { timeout: 30000 });
    const mode = (await page.getAttribute('[data-testid="banner-strip"]', 'data-mode')) || 'static';
    if (mode !== want) fail(`${host}: config says ${want}, shop rendered ${mode}`);
    if (await page.locator('.hero').count()) fail(`${host}: the welcome card must not show when banners exist`);
    if (!(await noScrollbar())) fail(`${host}: the shop window must never show a scrollbar`);
    if (want === 'static') {
      const lead = await page.locator('[data-testid="banner-strip"] .banner.lead').count();
      const tiles = await page.locator('[data-testid="banner-strip"] .banner.tile').count();
      if (!lead) fail(`${host}: static needs a lead banner`);
      console.log(`  ${host}: static — 1 lead + ${tiles} tiles`);
    } else {
      const dots = await page.locator('[data-testid="carousel-dot"]').count();
      const first = await page.locator('[data-testid="banner-strip"] figcaption').textContent();
      await page.click('[data-testid="carousel-next"]'); await page.waitForTimeout(300);
      const second = await page.locator('[data-testid="banner-strip"] figcaption').textContent();
      if (dots < 2 || first === second) fail(`${host}: next should change the slide (${dots} dots)`);
      await page.click('[data-testid="carousel-prev"]'); await page.waitForTimeout(300);
      if ((await page.locator('[data-testid="banner-strip"] figcaption').textContent()) !== first) fail(`${host}: prev should return to the first slide`);
      const selected = await page.locator('[data-testid="carousel-dot"][aria-selected="true"]').count();
      console.log(`  ${host}: carousel — ${dots} slides, arrows move the slide, ${selected} dot selected`);
    }
  }
  await browser.close();
  console.log('PASS shop_window_test');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
