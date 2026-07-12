/* Record the guided demo as a video: drives /flow/demo.html exactly like
 * demo_test.js, but with Playwright video capture on — the output lands in
 * ops/e2e/recordings/ as .webm, ready for ffmpeg conversion to the README GIF.
 * The run is real; the video shows the acts going green and Live Flow lighting. */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  const dir = path.join(__dirname, 'recordings');
  fs.mkdirSync(dir, { recursive: true });
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: 1600, height: 900 },
    recordVideo: { dir, size: { width: 1600, height: 900 } },
  });
  const page = await ctx.newPage();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };

  await page.goto('http://localhost:8080/flow/demo.html');
  await page.waitForTimeout(1500);                       // opening frame
  await page.click('#signin');
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', 'demo');
  await page.fill('input[name="password"]', 'demo');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('#playall:not([disabled])', { timeout: 20000 });
  await page.waitForTimeout(1500);                       // settle on the cockpit

  await page.click('#playall');
  for (const n of [1, 2, 3, 4, 5]) {
    await page.waitForSelector(`#act${n}.ok`, { timeout: 120000 }).catch(async () => {
      const log = await page.locator(`#log${n}`).textContent();
      fail(`act ${n} not green — ${log.trim().slice(0, 150)}`);
    });
    // keep the finished act in view
    await page.locator(`#act${n}`).scrollIntoViewIfNeeded();
    console.log(`act ${n} green`);
  }
  await page.waitForSelector('#fin', { state: 'visible', timeout: 10000 });
  await page.locator('#fin').scrollIntoViewIfNeeded();
  await page.waitForTimeout(4000);                       // linger on the finale

  await ctx.close();                                     // flushes the video
  const video = await page.video().path();
  const out = path.join(dir, 'guided-demo.webm');
  fs.copyFileSync(video, out);
  fs.rmSync(video);
  console.log('VIDEO:', out);
  await browser.close();
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
