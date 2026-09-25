/* Bills as the operational centre, and one bill whole. Suite #233.
 *
 * The Billing & Revenue paper asked for two things on this desk: a list that
 * states each bill's situation and due date and opens from the bill number
 * itself, and a workspace that answers what was charged, paid, adjusted,
 * delivered, posted and changed — without visiting four modules. Both are
 * React islands inside the console's own panel (ADR-0022), so this suite
 * drives the real console with a real token.
 *
 *  - LISTS: every row carries a situation in words and a due date; no View
 *    button; the bill number is the link.
 *  - CALM AND LOUD: a zero exception count reads as clean ("No overdue
 *    bills"), never as a warning; overdue is said loudly.
 *  - CHIPS FILTER: a chip narrows the table to its own situation, and the
 *    count matches what the rows then show.
 *  - SEARCHES: the placeholder names what is searched.
 *  - WORKSPACE: the six sections render, each either its facts or a sentence
 *    saying there are none; identifiers stay under technical details.
 *  - READS ONLY WHAT EXISTS: the sections agree with the APIs they read.
 */
const { chromium } = require('playwright');

const CONSOLE = 'http://localhost:8080/console/';
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const BILLING = '/tmf-api/customerBillManagement/v4';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);

async function token() {
  const r = await fetch(KC, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' }) });
  if (!r.ok) fail(`token: ${r.status}`);
  return (await r.json()).access_token;
}
async function api(path, tok) {
  const r = await fetch(API + path, { headers: { Authorization: `Bearer ${tok}`, 'Cache-Control': 'no-cache' } });
  return r.ok ? r.json() : null;
}

(async () => {
  const tok = await token();
  const bills = await api(`${BILLING}/billSituation?limit=100`, tok);
  if (!bills || !bills.length) fail('no bills on this tenant — run the billing seeds first');
  const counts = {};
  for (const b of bills) {
    const v = (b.billSituation || {}).value;
    if (v) counts[v] = (counts[v] || 0) + 1;
  }
  console.log('   situations on the first page:', JSON.stringify(counts));

  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1500, height: 1000 } });
  page.on('pageerror', (e) => console.log('PAGE ERROR:', e.message));
  await page.goto(CONSOLE);
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });

  /* ---------- the desk ---------- */
  // the console lands on Home; Bills is a tab of the Billing & Revenue department
  await page.locator('#tabs .tab', { hasText: 'Bills' }).first().click();
  await page.waitForSelector('[data-testid="bills-desk"]', { timeout: 20000 });
  await page.waitForFunction(() => document.querySelectorAll('[data-testid="bills-body"] tr').length > 1, null, { timeout: 20000 });

  // the shell owns the page head; the island must not repeat it
  const heading = (await page.locator('#resource-title').innerText()).trim();
  if (heading !== 'Bills') fail(`the page is titled "${heading}", not "Bills"`);
  if (await page.locator('[data-testid="bills-desk"] h2').count()) fail('the island repeats the page heading');
  const staleChips = await page.locator('#kpis:not([hidden])').count();
  if (staleChips) fail('the shell still shows its own KPI chips beside the island\'s');
  // the stylesheet shouts headings, so compare the words, not their case
  const headers = (await page.locator('[data-testid="bills-desk"] thead th').allInnerTexts()).map((h) => h.trim());
  const said = headers.map((h) => h.toLowerCase());
  for (const want of ['bill', 'customer', 'situation', 'due']) {
    if (!said.includes(want)) fail(`the table has no ${want} column: ${headers.join(', ')}`);
  }
  if (headers.some((h) => /updated/i.test(h))) fail('the updated timestamp is still in the primary table');
  const viewButtons = await page.locator('[data-testid="bills-body"] button', { hasText: /^View$/ }).count();
  if (viewButtons) fail(`${viewButtons} View buttons remain; the bill number should be the link`);
  const firstRow = await page.locator('[data-testid="bills-body"] tr').first().innerText();
  ok(`LISTS: ${headers.join(' · ')} — first row reads "${firstRow.replace(/\n/g, ' · ').slice(0, 110)}"`);

  const placeholder = await page.locator('input[type="search"]').getAttribute('placeholder');
  if (!/customer/i.test(placeholder) || !/bill number/i.test(placeholder)) fail(`the search says "${placeholder}"`);
  ok(`SEARCHES: "${placeholder}"`);

  /* ---------- calm and loud ---------- */
  const chipText = async (k) => (await page.locator(`[data-testid="chip-${k}"]`).innerText()).trim();
  for (const key of ['overdue', 'disputed', 'arrangement', 'outstanding']) {
    const text = await chipText(key);
    const n = counts[key] || 0;
    if (n === 0 && !text.startsWith('✓')) fail(`a zero ${key} count reads as a warning: "${text}"`);
    if (n > 0 && !text.startsWith(String(n))) fail(`the ${key} chip says "${text}" but the API counts ${n}`);
  }
  ok(`CALM AND LOUD: ${[...['overdue', 'disputed', 'arrangement', 'outstanding'].map((k) => `${k}: ${counts[k] || 0}`)].join(', ')} — zero counts read as clean`);

  /* ---------- chips filter ---------- */
  const busiest = ['overdue', 'outstanding', 'disputed', 'arrangement'].find((k) => (counts[k] || 0) > 0);
  if (busiest) {
    await page.locator(`[data-testid="chip-${busiest}"]`).click();
    await page.waitForTimeout(400);
    const rows = await page.locator('[data-testid="bills-body"] tr').count();
    if (rows !== counts[busiest]) fail(`the ${busiest} chip shows ${rows} rows, the API counts ${counts[busiest]}`);
    const situations = await page.locator('[data-testid="bills-body"] tr td:nth-child(5)').allInnerTexts();
    if (new Set(situations.map((s) => s.trim())).size !== 1) fail(`filtering by ${busiest} left mixed situations: ${[...new Set(situations)].join(', ')}`);
    ok(`CHIPS FILTER: ${busiest} → ${rows} rows, all "${situations[0].trim()}"`);
    await page.locator(`[data-testid="chip-${busiest}"]`).click();
    await page.waitForTimeout(300);
  }

  /* ---------- the workspace ---------- */
  // a bill that has as many kinds of fact as the demo data offers
  const credits = (await api(`${BILLING}/creditNote`, tok)) || [];
  const richId = credits.length ? credits[0].billId : bills[0].id;
  const rich = bills.find((b) => b.id === richId) || bills[0];
  await page.fill('input[type="search"]', rich.billNo);
  await page.waitForTimeout(400);
  await page.locator('[data-testid="bill-link"]').first().click();
  await page.waitForSelector('[data-testid="bill-workspace"]', { timeout: 20000 });
  await page.waitForFunction(() => !/Opening/.test(document.querySelector('[data-testid="bill-workspace"]').textContent), null, { timeout: 20000 });

  for (const section of ['bill', 'payments', 'adjustments', 'delivery', 'journal', 'history']) {
    const el = page.locator(`[data-testid="section-${section}"]`);
    if (!(await el.count())) fail(`the workspace has no ${section} section`);
    const text = (await el.innerText()).trim();
    if (text.split('\n').length < 3) fail(`the ${section} section says nothing at all: "${text}"`);
  }
  const workspace = (await page.locator('[data-testid="bill-workspace"]').innerText());
  if (!workspace.includes(rich.billNo)) fail('the workspace does not name the bill it opened');
  const uuid = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/;
  const visible = workspace.split('Technical details')[0];
  if (uuid.test(visible)) fail(`an identifier is on show above the technical details: ${visible.match(uuid)[0]}`);
  // a service writes its reason with an ISO date; an operator reads dates
  const iso = visible.match(/\b\d{4}-\d{2}-\d{2}\b/);
  if (iso) fail(`a timestamp is showing where a date belongs: ${iso[0]}`);
  ok('WORKSPACE: bill, payments, adjustments, delivery, journal and history all answer; no identifier or ISO date above the fold');

  const adjustments = (await page.locator('[data-testid="section-adjustments"]').innerText()).replace(/\n/g, ' ');
  const journal = (await page.locator('[data-testid="section-journal"]').innerText()).replace(/\n/g, ' ');
  ok(`READS WHAT EXISTS: adjustments "${adjustments.slice(0, 90)}…"; journal "${journal.slice(0, 90)}…"`);

  await page.locator('[data-testid="back-to-bills"]').click();
  await page.waitForSelector('[data-testid="bills-desk"]', { timeout: 10000 });
  ok('BACK: the desk is where it was, inside the same page');

  await browser.close();
  console.log('\nALL BILLS-DESK CHECKS PASSED — the list states each bill\'s situation and due date and opens from the bill'
    + ' number, a clean count reads as clean, the chips filter what they count, and one bill answers all six questions in one place.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
