/* THE SECOND LAYER UNDER innerHTML, PROVEN RATHER THAN ASSUMED.
 *
 * The vanilla consoles still build markup with innerHTML — about 35 places in
 * admin-console, 15 in partner-console. Every interpolation is wrapped in
 * esc(), and the architecture ratchet counts unescaped ones with a baseline of
 * zero (break one on purpose and it goes red, which is how that gate earns its
 * keep). This suite is the layer underneath: even if a value ever did reach the
 * DOM as markup, the gateway's Content-Security-Policy must stop it executing.
 *
 * `script-src 'self'` with NO 'unsafe-inline' is what does that work. The admin
 * console alone adds 'unsafe-eval' for its command palette — that permits eval()
 * from already-loaded scripts and does NOT permit inline script or inline event
 * handlers, which is the distinction this suite pins.
 *
 * HOW NOT TO TEST THIS, learned the hard way: page.evaluate() runs through the
 * DevTools protocol, which bypasses CSP. An earlier version of this check
 * injected the payload with evaluate(), watched it execute, and nearly reported
 * a broken CSP. The discriminator was that eval() also succeeded on the
 * storefront, whose policy has no 'unsafe-eval' at all — so CSP was simply not
 * being enforced on that path. The payload has to arrive as a real document.
 * Here it does: the gateway's own response is fetched and only its BODY is
 * swapped, so the real CSP header still governs the page.
 */
const { chromium } = require('playwright');

const CONSOLE = 'http://console.taranga.localhost:8080/console/';
const SHOP = 'http://shop.taranga.localhost:8080/shop/';

const PAYLOAD = `<!doctype html><html><body>
<div id="out">not-executed</div>
<img src="x" onerror="document.getElementById('out').textContent='INLINE-HANDLER-RAN'">
<script>document.getElementById('out').textContent='INLINE-SCRIPT-RAN';<\/script>
</body></html>`;

/** Load PAYLOAD as a real document at `url`, keeping that response's own headers. */
async function probe(browser, url, route, { stripCsp = false } = {}) {
  const ctx = await browser.newContext({ bypassCSP: false });
  const page = await ctx.newPage();
  const refusals = [];
  page.on('console', (m) => {
    if (/Content Security Policy|Refused/i.test(m.text())) refusals.push(m.text());
  });
  await page.route(route, async (r) => {
    const real = await r.fetch();
    const headers = { ...real.headers(), 'content-type': 'text/html' };
    if (stripCsp) delete headers['content-security-policy'];
    await r.fulfill({ response: real, body: PAYLOAD, headers });
  });
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1800);
  const result = await page.textContent('#out');
  await ctx.close();
  return { result, refusals };
}

(async () => {
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };
  const browser = await chromium.launch();

  // --- the control. Without the header the payload MUST run, or this suite is
  // measuring nothing and would pass against a fleet with no CSP at all.
  const control = await probe(browser, CONSOLE, '**/console/', { stripCsp: true });
  if (control.result === 'not-executed') {
    await browser.close();
    fail('with the CSP header stripped the payload still did not run — this suite '
       + 'cannot tell a working policy from a broken one, so its pass means nothing');
  }
  console.log(`OK control: with no CSP header the payload executes (${control.result})`);

  // --- the admin console: the one page that gets 'unsafe-eval'
  const admin = await probe(browser, CONSOLE, '**/console/');
  if (admin.result !== 'not-executed') {
    await browser.close();
    fail(`the admin console ran injected markup (${admin.result}) — 'unsafe-eval' is `
       + 'meant to permit eval() from loaded scripts, not inline script or handlers');
  }
  if (!admin.refusals.some((r) => /inline event handler/i.test(r))) {
    await browser.close();
    fail('nothing executed, but the browser never said it refused an inline event '
       + 'handler — the page may simply not have reached the payload');
  }
  console.log(`OK admin console: inline script and inline handler both refused `
            + `(${admin.refusals.length} CSP refusals)`);

  // --- the storefront: the strict policy, no eval at all
  const shop = await probe(browser, SHOP, '**/shop/');
  if (shop.result !== 'not-executed') {
    await browser.close();
    fail(`the storefront ran injected markup (${shop.result}) — this is the customer-facing page`);
  }
  console.log('OK storefront: same under the strict policy');

  await browser.close();
  console.log('PASS csp_inline_block_test — a value that reached the DOM as markup still '
            + 'could not execute, on the console that has unsafe-eval and on the shop that does not');
})().catch((e) => { console.error('FAIL: ' + (e && e.stack || e)); process.exit(1); });
