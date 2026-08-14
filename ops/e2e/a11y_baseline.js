/* A11y baseline: run the WCAG 2.2 AA ruleset across every channel and print
 * the honest violation count per page. This is the "before" number — the
 * per-portal fixes drive each toward zero, and a11y_test.js then guards it.
 *
 * Run:  node a11y_baseline.js
 */
const { chromium } = require('playwright');
const { runAxe, summarize, nodeCount } = require('./a11y_axe');

const BASE = 'http://localhost:8080';

async function keycloakLogin(page, user, pass) {
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', user);
  await page.fill('input[name="password"]', pass);
  await page.click('input[type="submit"], button[type="submit"]');
}

/* Each target: a label, how to get there, and how to know it's ready. */
const TARGETS = [
  {
    label: 'storefront: shop landing (guest)',
    async open(page) { await page.goto(`${BASE}/shop/`); await page.waitForSelector('.nav', { timeout: 20000 }); },
  },
  {
    label: 'storefront: cart (guest)',
    async open(page) { await page.goto(`${BASE}/shop/cart`); await page.waitForSelector('.nav', { timeout: 20000 }); },
  },
  {
    label: 'console: catalog (demo/demo)',
    async open(page) {
      await page.goto(`${BASE}/console/`);
      await keycloakLogin(page, 'demo', 'demo');
      await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });
    },
  },
  {
    label: 'csr: agent desk (agent-anna)',
    async open(page) {
      await page.goto(`${BASE}/csr/`);
      await keycloakLogin(page, 'agent-anna', 'agent');
      await page.waitForSelector('.searchbar', { timeout: 20000 });
    },
  },
  {
    label: 'partner: portal (demo/demo)',
    async open(page) {
      await page.goto(`${BASE}/partner/`);
      await page.waitForSelector('#signin', { state: 'visible', timeout: 20000 });
      await page.click('#signin'); // the gate → Keycloak
      await keycloakLogin(page, 'demo', 'demo');
      await page.waitForSelector('#app', { state: 'visible', timeout: 20000 });
    },
  },
];

(async () => {
  const browser = await chromium.launch();
  const results = [];
  for (const t of TARGETS) {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    try {
      await t.open(page);
      await page.waitForTimeout(500); // let late renders settle
      const axe = await runAxe(page);
      const rows = summarize(axe);
      const total = nodeCount(axe);
      results.push({ label: t.label, total, rows });
      console.log(`\n=== ${t.label} — ${total} violating node(s), ${rows.length} rule(s) ===`);
      for (const r of rows) {
        console.log(`  [${r.impact.padEnd(8)}] ${r.id.padEnd(28)} x${String(r.nodes).padStart(3)}  ${r.help}`);
      }
    } catch (e) {
      results.push({ label: t.label, total: -1, error: e.message });
      console.log(`\n=== ${t.label} — SCAN FAILED: ${e.message.split('\n')[0]} ===`);
    } finally {
      await ctx.close();
    }
  }
  await browser.close();

  console.log('\n\n########## WCAG 2.2 AA BASELINE ##########');
  let grand = 0;
  for (const r of results) {
    if (r.total < 0) { console.log(`  ${r.label.padEnd(40)}  SCAN FAILED`); continue; }
    grand += r.total;
    console.log(`  ${r.label.padEnd(40)}  ${String(r.total).padStart(4)} nodes`);
  }
  console.log(`  ${''.padEnd(40)}  ----`);
  console.log(`  ${'TOTAL'.padEnd(40)}  ${String(grand).padStart(4)} violating nodes`);
})();
