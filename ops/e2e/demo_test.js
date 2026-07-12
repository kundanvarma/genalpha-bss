/* The guided demo must be REAL: /flow/demo.html signs in via Keycloak (PKCE),
 * then "Play the whole story" drives five live acts against the running BSS —
 * order-to-activation, a rule born and retired without a deploy, a price that
 * reacts, keep-your-number, and leave-with-it feeding the churn model. This
 * test plays the whole story and demands every act finishes green. */
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch();
  const page = await (await browser.newContext()).newPage();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };

  await page.goto('http://localhost:8080/flow/demo.html');
  await page.click('#signin');
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', 'demo');
  await page.fill('input[name="password"]', 'demo');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('#playall:not([disabled])', { timeout: 20000 });
  console.log('OK signed in to the demo cockpit (PKCE, bss realm)');

  await page.click('#playall');
  for (const n of [1, 2, 3, 4, 5]) {
    await page.waitForSelector(`#act${n}.ok`, { timeout: 120000 }).catch(async () => {
      const log = await page.locator(`#log${n}`).textContent();
      fail(`act ${n} did not finish green — log: ${log.trim().slice(0, 200)}`);
    });
    const lastLine = (await page.locator(`#log${n} .good`).last().textContent().catch(() => ''));
    console.log(`OK act ${n}:`, (lastLine || '(done)').trim().slice(0, 110));
  }

  await page.waitForSelector('#fin', { state: 'visible', timeout: 10000 });
  console.log('OK finale shown — the story completed end to end');

  await browser.close();
  console.log('\nGUIDED DEMO PASSED — five acts, all real, all green.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
