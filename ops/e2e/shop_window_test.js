/* #121 shop_window_test — the shop window is a tenant setting.
 * taranga: static (lead banner + tiles, no scrollbar). enet: carousel (arrows,
 * dots, next/prev move the slide, no scrollbar). Both from tenants.yml. */
const { chromium } = require('playwright');
const fail = (m) => { throw new Error(m); };
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 } });
  const noScrollbar = async () => page.evaluate(() => { const el = document.querySelector('[data-testid="banner-strip"]'); return el && el.scrollWidth <= el.clientWidth + 1; });

  await page.goto('http://shop.taranga.localhost:8080/shop/');
  await page.waitForSelector('[data-testid="banner-strip"]', { timeout: 30000 });
  const modeT = await page.getAttribute('[data-testid="banner-strip"]', 'data-mode');
  const lead = await page.locator('[data-testid="banner-strip"] .banner.lead').count();
  const tiles = await page.locator('[data-testid="banner-strip"] .banner.tile').count();
  if (modeT === 'carousel' || !lead || !(await noScrollbar())) fail(`taranga should be static without a scrollbar: mode=${modeT} lead=${lead}`);
  if (await page.locator('.hero').count()) fail('the welcome card must not show when banners exist');
  console.log(`  taranga: static — 1 lead + ${tiles} tiles, no scrollbar, no welcome card`);

  await page.goto('http://shop.enet.localhost:8080/shop/');
  await page.waitForSelector('[data-testid="banner-strip"][data-mode="carousel"]', { timeout: 30000 });
  const dots = await page.locator('[data-testid="carousel-dot"]').count();
  const first = await page.locator('[data-testid="banner-strip"] figcaption').textContent();
  await page.click('[data-testid="carousel-next"]');
  await page.waitForTimeout(300);
  const second = await page.locator('[data-testid="banner-strip"] figcaption').textContent();
  if (dots < 2 || first === second) fail(`carousel next should change the slide (${dots} dots): "${first}" -> "${second}"`);
  await page.click('[data-testid="carousel-prev"]');
  await page.waitForTimeout(300);
  if ((await page.locator('[data-testid="banner-strip"] figcaption').textContent()) !== first) fail('prev should return to the first slide');
  if (!(await noScrollbar())) fail('carousel must never show a scrollbar');
  const selected = await page.locator('[data-testid="carousel-dot"][aria-selected="true"]').count();
  console.log(`  enet: carousel — ${dots} slides, arrows move the slide, ${selected} dot selected, no scrollbar`);
  await browser.close();
  console.log('PASS shop_window_test');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
