/* Console Home / My Work: the first screen is role-aware.
 *
 *  - demo (the operator) lands on Home: an Attention section, cards in
 *    operator language, and the drafts card opens Product Offerings
 *  - pat (product) sees catalog attention and no money card; finn (finance)
 *    sees money and no catalog card — every card is gated by its tab's role
 *  - quick actions follow the roles (finn gets no "+ New product offering")
 *  - a desk with nothing pending renders the quiet "No action needed" chip,
 *    never an error and never red
 *  - Recent lists the last tabs opened
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

async function loginConsole(browser, user, pass) {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto(`${API}/console/`);
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', user);
  await page.fill('input[name="password"]', pass);
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  await page.waitForSelector('#tabs .tab', { timeout: 10000 });
  return { ctx, page };
}

async function homeReady(page) {
  await page.waitForSelector('[data-testid="home"][data-ready="1"]', { timeout: 20000 });
  return page.evaluate(() => ({
    title: document.getElementById('resource-title').textContent,
    activeTab: document.querySelector('.tab.on')?.textContent,
    sections: [...document.querySelectorAll('#home-panel section')].map((s) => s.dataset.testid),
    cards: [...document.querySelectorAll('[data-testid="home-card"]')].map((c) => ({ kind: c.dataset.kind, text: c.querySelector('.what').textContent })),
    quiet: [...document.querySelectorAll('[data-testid="home-quiet"]')].map((q) => q.textContent),
    actions: [...document.querySelectorAll('[data-testid="home-quick-action"]')].map((b) => b.dataset.action),
    health: [...document.querySelectorAll('[data-testid="home-health-chip"]')].map((c) => c.textContent),
    recent: [...document.querySelectorAll('[data-testid="home-recent-chip"]')].map((c) => c.textContent),
    text: document.getElementById('home-panel').textContent,
  }));
}

const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

(async () => {
  const browser = await chromium.launch();

  /* ---------- 1. demo: lands on Home, Attention first, drafts card opens the catalog ---------- */
  const demo = await loginConsole(browser, 'demo', 'demo');
  let h = await homeReady(demo.page);
  if (h.title !== 'Home') fail('demo should land on Home, landed on ' + h.title);
  if (h.activeTab !== 'Home') fail('the Home tab should be the one that is on: ' + h.activeTab);
  for (const s of ['home-attention', 'home-work', 'home-health', 'home-recent', 'home-quick']) {
    if (!h.sections.includes(s)) fail('Home is missing the ' + s + ' section: ' + h.sections);
  }
  if (UUID.test(h.text)) fail('Home leaks an id: ' + h.text.match(UUID)[0]);
  if (h.text.includes('rror')) fail('Home shows an error on the first screen: ' + h.text.slice(0, 200));
  const demoTabs = await demo.page.evaluate(() => [...document.querySelectorAll('#tabs .tab')].map((b) => b.textContent));
  if (demoTabs.filter((t) => t === 'Home').length !== 1) fail('exactly one Home tab expected: ' + demoTabs.filter((t) => t === 'Home').length);
  console.log('OK DEMO lands on Home — cards: ' + (h.cards.map((c) => c.kind).join(', ') || '(none)')
    + '; quiet: ' + (h.quiet.join(' / ') || '(none)') + '; health: ' + h.health.join(', '));

  // the demo catalog carries drafts (seeded In-design offers) — if not, make one so the card exists
  if (!h.cards.some((c) => c.kind === 'drafts')) {
    const tok = await demo.page.evaluate(() => sessionStorage.getItem('bss.console.token'));
    const r = await demo.ctx.request.post(`${API}/tmf-api/productCatalogManagement/v4/productOffering`, {
      headers: { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' },
      data: { name: 'Home suite draft ' + Date.now(), lifecycleStatus: 'In design', description: 'draft for the Home suite' } });
    if (!r.ok()) fail('could not seed a draft for the Home suite: ' + r.status());
    await demo.page.locator('.tab', { hasText: 'Home' }).first().click();
    h = await homeReady(demo.page);
    if (!h.cards.some((c) => c.kind === 'drafts')) fail('a draft exists but the drafts card is missing: ' + JSON.stringify(h.cards));
  }
  await demo.page.locator('[data-testid="home-card"][data-kind="drafts"] [data-testid="home-open"]').click();
  await demo.page.waitForFunction(() => document.getElementById('resource-title').textContent === 'Product Offerings', { timeout: 10000 });
  await demo.page.waitForSelector('#listing-body tr', { timeout: 15000 });
  const home1 = await demo.page.evaluate(() => document.getElementById('home-panel').hidden);
  if (!home1) fail('the Home panel should hide when a page opens');
  console.log('OK the drafts card opens Product Offerings, with rows, and Home steps aside.');

  // Recent: the page just opened is remembered when Home comes back
  await demo.page.locator('.tab', { hasText: 'Home' }).first().click();
  h = await homeReady(demo.page);
  if (!h.recent.includes('Product Offerings')) fail('Recent should list Product Offerings: ' + h.recent);
  console.log('OK Recent remembers where you were: ' + h.recent.join(', '));

  // quick actions for the whole operator
  for (const a of ['new-offering', 'copilot', 'growth-copilot', 'approvals']) {
    if (!h.actions.includes(a)) fail('demo is missing quick action ' + a + ': ' + h.actions);
  }
  await demo.page.locator('[data-testid="home-quick-action"][data-action="new-offering"]').click();
  await demo.page.waitForFunction(() => document.getElementById('resource-title').textContent === 'Product Offerings'
    && !document.getElementById('editor').hidden, { timeout: 10000 });
  console.log('OK "+ New product offering" opens the catalog with the form ready.');
  await demo.ctx.close();

  /* ---------- 2. pat: catalog attention, no money ---------- */
  const pat = await loginConsole(browser, 'pat@bss.local', 'pat');
  h = await homeReady(pat.page);
  if (h.title !== 'Home') fail('pat should land on Home, landed on ' + h.title);
  if (h.cards.some((c) => ['overdue'].includes(c.kind)) || h.health.some((t) => /bills/.test(t))) fail('pat (product) sees money on Home: ' + JSON.stringify(h));
  if (!h.cards.some((c) => c.kind === 'drafts')) fail('pat should see the catalog drafts card: ' + JSON.stringify(h.cards));
  if (h.actions.includes('growth-copilot')) fail('pat has no marketing desk, yet a "Describe an outreach" action: ' + h.actions);
  if (!h.actions.includes('new-offering')) fail('pat should be offered "+ New product offering": ' + h.actions);
  if (h.text.includes('rror')) fail('pat sees an error on Home: ' + h.text.slice(0, 200));
  console.log('OK PAT (product): catalog cards only — ' + h.cards.map((c) => c.kind).join(', ') + '; actions ' + h.actions.join(', '));
  await pat.ctx.close();

  /* ---------- 3. finn: money only, and the quiet state where nothing is pending ---------- */
  const finn = await loginConsole(browser, 'finn@bss.local', 'finn');
  h = await homeReady(finn.page);
  if (h.title !== 'Home') fail('finn should land on Home, landed on ' + h.title);
  if (h.cards.some((c) => ['drafts', 'past-window', 'approvals'].includes(c.kind)) || h.health.some((t) => /offers/.test(t))) {
    fail('finn (finance) sees catalog on Home: ' + JSON.stringify(h));
  }
  if (h.actions.some((a) => ['new-offering', 'copilot', 'growth-copilot', 'approvals'].includes(a))) fail('finn has catalog/marketing quick actions: ' + h.actions);
  if (!h.health.some((t) => /bills open/.test(t))) fail('finn should see the bills health chip: ' + h.health);
  // nothing waits on finance's decision beyond overdue bills; "My work" is quiet for him either way
  if (!h.quiet.length) fail('finn should see at least one quiet chip (no red where nothing is pending): ' + JSON.stringify(h));
  if (!h.cards.some((c) => c.kind === 'overdue') && !h.quiet.includes('No action needed')) fail('nothing pending must read "No action needed": ' + JSON.stringify(h));
  if (h.text.includes('rror')) fail('finn sees an error on Home: ' + h.text.slice(0, 200));
  console.log('OK FINN (finance): money only — cards ' + (h.cards.map((c) => c.kind).join(', ') || '(none)') + '; quiet: ' + h.quiet.join(' / '));
  await finn.ctx.close();

  await browser.close();
  console.log('\nALL CONSOLE-HOME CHECKS PASSED — the first screen says what needs you,'
    + ' in your words, for your desk only; a quiet desk says so quietly.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
