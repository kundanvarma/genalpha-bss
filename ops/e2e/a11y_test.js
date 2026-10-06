/* Accessibility guard: every channel must stay at zero WCAG 2.2 AA violations.
 *
 * This is the "keep it green" gate behind the a11y arc — axe-core runs the full
 * WCAG 2.2 A/AA ruleset against the storefront (guest), the cart, the catalog
 * console, the CSR agent desk and the wholesale partner portal. Any violation
 * fails the suite, so accessibility can't silently rot between releases.
 *
 * THE RULESET IS PINNED, and that is a deliberate choice rather than caution
 * about upgrades. axe-core is the gate's definition of "violation", so an
 * unpinned axe means the bar moves when npm publishes. It did: CI ran
 * `npm i playwright axe-core`, axe-core 4.14.0 appeared, it promoted
 * `label-content-name-mismatch` out of experimental, and three PRs that had
 * been reviewed green went red with no code change — on a page none of them
 * touched. The version now comes from a committed lockfile via `npm ci`, here
 * and in run-all-suites.sh, so a laptop and a runner hold the same code to the
 * same standard, and moving the bar is an edit somebody makes on purpose.
 *
 * 4.14.0 IS NOW ADOPTED (#196), and the three things it found are fixed rather
 * than waived. It promoted `label-content-name-mismatch` (WCAG 2.5.3, Label in
 * Name) out of experimental, and the full sweep came back 56 violating nodes
 * across 6 of these 11 channels:
 *
 *  - THE CSR BRAND LINK, 1 node. Its name was "<tenant> CSR home" over visible
 *    text reading "csr console": a speech-input user says what they can see and
 *    the control does not answer. The name is now built from the link's
 *    CONTENT, with the tenant's brand and "home" clipped from the screen but
 *    present in the name — which satisfies the rule by construction and keeps
 *    working at the narrow viewport where the wordmark is display:none.
 *  - THE 50 SEARCH RESULT ROWS, and the lint was the smaller half. Each row
 *    carried `aria-label="Customer: <name>"`, which REPLACED its content — so a
 *    sighted agent read the name, the number and the status while an agent on a
 *    screen reader heard only the name, fifty times down the list. Dropping the
 *    label restores parity and fixes 2.5.3 at the same time, because the row
 *    already shows its type as visible text.
 *  - A CONTRAST FAILURE ON FIVE FINANCE DESKS, 1 node each, and this one is not
 *    axe's fault at all: `.primary-tab.on` used the raw brand teal on the soft
 *    tint for 4.25:1 where AA needs 4.5:1, while every other `.on` state in
 *    that sheet already used the darker brand-derived token. It had never been
 *    seen because A11Y_TARGETS on pull requests leaves the finance desks out —
 *    see the note on that filter below, which is now printed on every run.
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
  // The Bills desk and the bill workspace are React islands inside the console's
  // own panel (ADR-0022), scanned in their own right because a scan of the
  // landing page proves nothing about a screen mounted later.
  //
  // They are labelled "bills:", NOT "console:", on purpose. The PR smoke tier
  // runs A11Y_TARGETS=storefront,console,csr,partner,app against a slice that
  // seeds the catalog and no billing at all, so a "console:" label would drag
  // these onto every pull request and they would fail for want of data. The
  // nightly full proof runs every target and is where they belong. Locally:
  // A11Y_TARGETS=console,bills.
  { label: 'bills: desk (demo)',
    open: async (p) => {
      await p.goto(`${BASE}/console/`); await kcLogin(p, 'demo', 'demo');
      await p.waitForSelector('#main:not([hidden])', { timeout: 20000 });
      await p.locator('#tabs .tab', { hasText: /^bills$/i }).first().click();
      await p.waitForSelector('[data-testid="bills-desk"]', { timeout: 20000 });
      await p.waitForFunction(() => document.querySelectorAll('[data-testid="bills-body"] tr').length > 1, null, { timeout: 20000 });
    } },
  { label: 'bills: one bill (demo)',
    open: async (p) => {
      await p.goto(`${BASE}/console/`); await kcLogin(p, 'demo', 'demo');
      await p.waitForSelector('#main:not([hidden])', { timeout: 20000 });
      await p.locator('#tabs .tab', { hasText: /^bills$/i }).first().click();
      await p.waitForSelector('[data-testid="bills-desk"]', { timeout: 20000 });
      await p.locator('[data-testid="bill-link"]').first().click();
      await p.waitForSelector('[data-testid="bill-workspace"]', { timeout: 20000 });
      await p.waitForFunction(() => !/Opening/.test(document.querySelector('[data-testid="bill-workspace"]').textContent), null, { timeout: 20000 });
    } },
  // Accounting and Configuration are React islands too (ADR-0022), and they are
  // labelled "accounting:", NOT "console:", on purpose. The PR smoke tier runs
  // A11Y_TARGETS=storefront,console,csr,partner,app against a slice that seeds
  // the catalog and no billing at all, so a "console:" label would drag these
  // onto every pull request and they would time out reaching for a posting that
  // cannot exist there. The nightly full proof runs every target and is where
  // they belong. Locally: A11Y_TARGETS=console,accounting.
  { label: 'accounting: journal (demo)',
    open: async (p) => {
      await p.goto(`${BASE}/console/`); await kcLogin(p, 'demo', 'demo');
      await p.waitForSelector('#main:not([hidden])', { timeout: 20000 });
      await p.locator('#tabs .tab', { hasText: /^journal$/i }).first().click();
      await p.waitForSelector('[data-testid="journal"]', { timeout: 20000 });
      await p.waitForFunction(() => document.querySelectorAll('[data-testid="journal-body"] tr').length > 1, null, { timeout: 20000 });
      // the disclosure is the screen: scan it open, not only closed
      await p.locator('[data-testid="journal-row"]').first().click();
      await p.waitForSelector('[data-testid="journal-detail"]', { timeout: 20000 });
    } },
  { label: 'accounting: chart of accounts (demo)',
    open: async (p) => {
      await p.goto(`${BASE}/console/`); await kcLogin(p, 'demo', 'demo');
      await p.waitForSelector('#main:not([hidden])', { timeout: 20000 });
      await p.locator('#tabs .tab', { hasText: /^chart of accounts$/i }).first().click();
      await p.waitForSelector('[data-testid="chart"]', { timeout: 20000 });
      await p.waitForFunction(() => document.querySelectorAll('[data-testid="chart-body"] tr').length > 1, null, { timeout: 20000 });
    } },
  { label: 'accounting: configuration (demo)',
    open: async (p) => {
      await p.goto(`${BASE}/console/`); await kcLogin(p, 'demo', 'demo');
      await p.waitForSelector('#main:not([hidden])', { timeout: 20000 });
      await p.locator('#tabs .tab', { hasText: /^configuration$/i }).first().click();
      await p.waitForSelector('[data-testid="ladder"]', { timeout: 20000 });
    } },
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

  // A FILTER MUST ANNOUNCE WHAT IT HIDES. A11Y_TARGETS on pull requests is
  // storefront,console,csr,partner,app — and the five finance desk labels begin
  // "bills:" and "accounting:", so they matched nothing and were skipped in
  // silence. A contrast failure sat on all five of them for as long as that
  // list has existed. Those targets still cannot run in the PR tier — they
  // click into a bill and a journal entry, and that tier seeds no finance data
  // — so the nightly full proof, which sets no filter and therefore runs all
  // eleven, is their home. What changes here is that the gap is printed on
  // every run instead of being inferred from a list nobody re-read.
  const skipped = TARGETS.filter((t) => !SELECTED.includes(t));
  if (skipped.length) {
    console.log(`-- A11Y_TARGETS="${process.env.A11Y_TARGETS}" scans ${SELECTED.length}`
      + ` of ${TARGETS.length} channels; NOT scanned here: ${skipped.map((t) => t.label).join(', ')}`);
  }

  // EVERY TARGET IS SCANNED BEFORE ANYTHING FAILS. This used to exit on the
  // first bad channel, which meant a ruleset change — axe-core promoting a rule
  // out of experimental, say — showed you one page, you fixed it, and the next
  // run showed you the next. Eleven targets is eleven round trips to learn the
  // size of what you are adopting. Now the whole sweep runs and the verdict
  // comes once, at the end, with every channel's findings in it.
  const broken = [];
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
          console.error(`  [${r.impact}] ${r.id} x${r.nodes} — ${r.help}`);
          // every offending element, so a failure that only happens on a runner
          // can be read rather than reproduced
          for (const d of (r.detail || []).slice(0, 8)) {
            console.error(`      at ${d.target}`);
            console.error(`         html: ${d.html}`);
            if (d.why) console.error(`         ${d.why}`);
          }
          if ((r.detail || []).length > 8) {
            console.error(`      … and ${r.detail.length - 8} more node(s)`);
          }
        }
        broken.push(`${t.label}: ${n} node(s) — ${summarize(axe).map((r) => `${r.id} x${r.nodes}`).join(', ')}`);
      } else {
        console.log(`OK ${t.label} — 0 WCAG 2.2 AA violations`);
      }
    } catch (e) {
      // a target that cannot even be opened is still a failure, but it must not
      // hide the channels after it
      console.error(`\nFAILED TO SCAN ${t.label}: ${e.message.split('\n')[0]}`);
      broken.push(`${t.label}: could not be scanned`);
    }
    await ctx.close();
  }
  if (broken.length) {
    await browser.close();
    console.error(`\n${broken.length} of ${SELECTED.length} channel(s) failed:`);
    for (const b of broken) console.error(`  - ${b}`);
    fail(`${total} violating node(s) across ${broken.length} channel(s) — accessibility regressed`);
  }

  await browser.close();
  console.log(`\nALL A11Y CHECKS PASSED — ${SELECTED.length} channel(s), 0 WCAG 2.2 AA violations:`
    + ` labels, landmarks, keyboard, headings and contrast all hold, and the brand-derived`
    + ` --teal-text keeps any tenant's color legible.`);
})();
