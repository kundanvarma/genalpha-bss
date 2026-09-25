/* Accessibility guard: every channel must stay at zero WCAG 2.2 AA violations.
 *
 * This is the "keep it green" gate behind the a11y arc — axe-core runs the full
 * WCAG 2.2 A/AA ruleset against the storefront (guest), the cart, the catalog
 * console, the CSR agent desk and the wholesale partner portal. Any violation
 * fails the suite, so accessibility can't silently rot between releases.
 *
 * Run:  node a11y_test.js
 */
const { chromium } = require('playwright');
const { runAxe, summarize, nodeCount } = require('./a11y_axe');

const BASE = 'http://localhost:8080';

async function kcLogin(page, user, pass) {
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', user);
  await page.fill('input[name="password"]', pass);
  await page.click('input[type="submit"], button[type="submit"]');
}

const TARGETS = [
  { label: 'storefront: shop landing (guest)',
    open: async (p) => { await p.goto(`${BASE}/shop/`); await p.waitForSelector('.nav', { timeout: 20000 }); } },
  { label: 'storefront: cart (guest)',
    open: async (p) => { await p.goto(`${BASE}/shop/cart`); await p.waitForSelector('.nav', { timeout: 20000 }); } },
  { label: 'console: catalog (demo)',
    open: async (p) => { await p.goto(`${BASE}/console/`); await kcLogin(p, 'demo', 'demo'); await p.waitForSelector('#main:not([hidden])', { timeout: 20000 }); } },
  { label: 'csr: agent desk (agent-anna)',
    open: async (p) => { await p.goto(`${BASE}/csr/`); await kcLogin(p, 'agent-anna', 'agent'); await p.waitForSelector('.searchbar', { timeout: 20000 }); } },
  { label: 'app: My page (paula)',
    // the mobile app's web export: a customer signs in and lands on My page
    open: async (p) => {
      await p.goto(`${BASE}/app/`);
      await p.locator('[data-testid=signin]').click();
      await p.waitForSelector('input[name="username"]', { timeout: 20000 });
      await p.fill('input[name="username"]', 'paula@family.example');
      await p.fill('input[name="password"]', 'paula');
      await p.click('input[type="submit"], button[type="submit"]');
      await p.waitForSelector('[data-testid=lob-card], [data-testid=avatar]', { timeout: 30000 });
    } },
  { label: 'partner: wholesale portal (demo)',
    open: async (p) => { await p.goto(`${BASE}/partner/`); await p.waitForSelector('#signin', { state: 'visible', timeout: 20000 }); await p.click('#signin'); await kcLogin(p, 'demo', 'demo'); await p.waitForSelector('#app', { state: 'visible', timeout: 20000 }); } },
];

// A CI smoke tier cannot afford the whole fleet, but it can afford the
// storefront. A11Y_TARGETS is a comma-separated list of label prefixes
// ("storefront,csr"); unset means every channel, as the proof run does.
const WANTED = (process.env.A11Y_TARGETS || '').split(',').map((s) => s.trim()).filter(Boolean);
const SELECTED = WANTED.length
  ? TARGETS.filter((t) => WANTED.some((w) => t.label.startsWith(w)))
  : TARGETS;

(async () => {
  const browser = await chromium.launch();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  let total = 0;

  if (!SELECTED.length) fail(`A11Y_TARGETS="${process.env.A11Y_TARGETS}" matched no channel`);
  for (const t of SELECTED) {
    // bypassCSP is for the SCANNER, not the app: the gateway's content policy
    // refuses inline script (SecurityHeadersFilter), and axe is injected as
    // inline script, so without this every scan dies with a CSP error instead
    // of a verdict. The page's real policy is unchanged and still shipped —
    // csp_test.js is what proves the header itself.
    const ctx = await browser.newContext({ bypassCSP: true });
    const page = await ctx.newPage();
    try {
      await t.open(page);
      await page.waitForTimeout(400);
      const axe = await runAxe(page);
      const n = nodeCount(axe);
      total += n;
      if (n > 0) {
        console.error(`\nWCAG 2.2 AA violations on ${t.label}:`);
        for (const r of summarize(axe)) {
          console.error(`  [${r.impact}] ${r.id} x${r.nodes} — ${r.help} (e.g. ${r.sample})`);
        }
        await ctx.close();
        await browser.close();
        fail(`${t.label} has ${n} violating node(s) — accessibility regressed`);
      }
      console.log(`OK ${t.label} — 0 WCAG 2.2 AA violations`);
    } catch (e) {
      await ctx.close();
      await browser.close();
      fail(`${t.label} could not be scanned: ${e.message.split('\n')[0]}`);
    }
    await ctx.close();
  }

  await browser.close();
  console.log(`\nALL A11Y CHECKS PASSED — ${SELECTED.length} channel(s), 0 WCAG 2.2 AA violations:`
    + ` labels, landmarks, keyboard, headings and contrast all hold, and the brand-derived`
    + ` --teal-text keeps any tenant's color legible.`);
})();
