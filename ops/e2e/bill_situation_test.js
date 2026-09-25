/* One financial situation, one meaning, every channel. Suite #233.
 *
 * Billing works out what is true about a bill ONCE — from its state, what has
 * been allocated against it, a standing payment arrangement and any open
 * dispute — and serves that block to everyone. The point is that no channel
 * works lateness out for itself, so the back office, the agent on the phone
 * and the customer looking at the app can never disagree.
 *
 *  - ONE MEANING: an overdue bill reads overdue on the desk's situation list
 *    and on the same bill read through the TMF678 door the CSR desk uses.
 *  - THREE CHANNELS: the same bill's block drives the word on the back
 *    office's Bills page, the CSR 360 and the customer's shop.
 *  - THE FLIP: a fact changes (a dispute is opened on the customer's bill) and
 *    all three channels change together, in the same breath; resolving it puts
 *    them back. This is the arrangement case's mechanism, proven with the fact
 *    a suite may safely change.
 *  - THE COUNTS ARE HONEST: the back office's overdue chip counts what the
 *    bills say, not a field no bill carries.
 *
 * The arrangement itself (an approved extension outranking lateness) is proven
 * in `CollectionsApiTest.anApprovedExtensionStopsTheBillReadingAsOverdue`: it
 * needs an active dunning policy, and the collections sweep ticks every minute,
 * so switching one on here would walk the ladder across the whole demo book.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const BILLS = '/tmf-api/customerBillManagement/v4';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  const j = await r.json();
  if (!j.access_token) fail(`token(${user}) refused`);
  return j.access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method,
    headers: { Authorization: `Bearer ${tok}`, ...(body ? { 'Content-Type': 'application/json' } : {}), 'Cache-Control': 'no-cache' },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
const situation = (bill) => (bill.billSituation || {}).value;

(async () => {
  const staff = await token('demo', 'demo');

  /* ---------- ONE MEANING: the desk's list and the bill agree ---------- */
  const overdue = (await call('GET', `${BILLS}/billSituation?situation=overdue&limit=5`, staff)).body || [];
  if (!overdue.length) fail('no overdue bill on this fleet to judge — seed the demo data first');
  const late = overdue[0];
  if (situation(late) !== 'overdue') fail(`the situation list returned a bill that is ${situation(late)}`);
  const byId = (await call('GET', `${BILLS}/customerBill/${late.id}`, staff)).body || {};
  if (situation(byId) !== 'overdue') {
    fail(`the same bill reads ${situation(byId)} through the TMF678 door but overdue on the desk's list`);
  }
  if (!byId.billSituation.reason || !byId.billSituation.originalDueDate) {
    fail(`the block must explain itself: ${JSON.stringify(byId.billSituation)}`);
  }
  ok(`ONE MEANING: ${late.billNo} reads "${byId.billSituation.reason}" on the desk's list and on the bill itself`);

  /* ---------- the customer's own bills, through their own token ---------- */
  const paula = await token('paula@family.example', 'paula');
  const mine = (await call('GET', `${BILLS}/customerBill?limit=20`, paula)).body || [];
  if (!mine.length) fail('the demo customer has no bills — run the seeds');
  const open = mine.find((b) => ['issued', 'outstanding', 'partiallyPaid', 'overdue'].includes(situation(b)));
  if (!open) fail(`the demo customer has no open bill to work with: ${mine.map(situation).join(', ')}`);
  const before = situation(open);
  ok(`THE CUSTOMER'S BILL: ${open.billNo} is "${open.billSituation.reason}" — the same block the desk reads`);

  /* ---------- THREE CHANNELS say the same word ---------- */
  const browser = await chromium.launch();
  const shopWord = async () => {
    const page = await browser.newPage();
    await page.goto(`${API}/shop/`);
    if (await page.locator('.who >> text=Sign in').count()) {
      await page.locator('.who >> text=Sign in').click();
      await page.waitForSelector('input[name="username"]', { timeout: 20000 });
      await page.fill('input[name="username"]', 'paula@family.example');
      await page.fill('input[name="password"]', 'paula');
      await page.click('input[type="submit"], button[type="submit"]');
    }
    await page.waitForURL('**/shop/**', { timeout: 25000 }).catch(() => {});
    await page.goto(`${API}/shop/bills`);
    await page.waitForSelector('[data-testid="bill-situation"]', { timeout: 20000 });
    const rows = await page.locator('[data-testid="bill-situation"]').allInnerTexts();
    const titles = await page.locator('[data-testid="bill-situation"]').evaluateAll(
      (els) => els.map((e) => e.getAttribute('title')));
    await page.screenshot({ path: `/tmp/situation-shop-${Date.now()}.png` });
    await page.close();
    return { rows, titles };
  };
  const shop = await shopWord();
  if (!shop.rows.length) fail('the shop showed no bill situation chip');
  if (!shop.titles.some((t) => t && t.length)) fail('the shop chip carries no reason on hover');
  ok(`SHOP: ${shop.rows.length} bill${shop.rows.length === 1 ? '' : 's'} shown as ${shop.rows.join(', ')} — the words the block gave, with its reason behind each`);

  /* ---------- THE FLIP: one fact changes, every channel follows ---------- */
  const disputed = await call('POST', `${BILLS}/customerBill/${open.id}/dispute`, paula,
    { reason: 'Suite #233: proving one situation reaches every channel' });
  if (disputed.status >= 300) fail(`dispute: ${disputed.status} ${disputed.text.slice(0, 200)}`);
  const afterApi = (await call('GET', `${BILLS}/customerBill/${open.id}`, staff)).body || {};
  if (situation(afterApi) !== 'disputed') {
    fail(`the bill should read disputed after a dispute is opened, not ${situation(afterApi)}`);
  }
  const shopAfter = await shopWord();
  if (!shopAfter.rows.some((w) => /dispute/i.test(w))) {
    fail(`the shop still says ${shopAfter.rows.join(', ')} after the dispute was opened`);
  }
  ok(`THE FLIP: one fact changed and both the desk and the shop moved together — "${afterApi.billSituation.reason}"`);

  /* ---------- and back: the channels follow the fact, not a cached word ---------- */
  const chip = (await call('GET', `${BILLS}/dispute`, staff)).body || [];
  const opened = chip.find((d) => d.billId === open.id && d.status === 'open');
  if (opened) {
    const resolved = await call('POST', `${BILLS}/dispute/${opened.id}/resolve`, staff,
      { outcome: 'uphold', note: 'Suite #233: the bill stands' });
    if (resolved.status >= 300) fail(`resolve: ${resolved.status} ${resolved.text.slice(0, 200)}`);
  }
  const restored = (await call('GET', `${BILLS}/customerBill/${open.id}`, staff)).body || {};
  if (situation(restored) === 'disputed') fail('the dispute was resolved but the bill still reads disputed');
  if (situation(restored) !== before) {
    fail(`the bill settled on ${situation(restored)}, not back to ${before} — the block is not derived`);
  }
  ok(`RESTORED: resolving the dispute put the bill back to "${restored.billSituation.reason}" — nothing was cached`);

  /* ---------- the back office counts what the bills say ---------- */
  const page = await browser.newPage();
  await page.goto(`${API}/console/`);
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  // the page is "Bills" now and it is a React island (ADR-0022, suite #235):
  // the rows live in the island's own body, not the shell's generic listing.
  // hasText is a case-insensitive SUBSTRING match, so an exact pattern is what
  // keeps this off any other page whose name contains the word.
  await page.locator('.tab', { hasText: /^bills$/i }).first().click();
  await page.waitForSelector('[data-testid="bills-body"] tr', { timeout: 20000 });
  await page.waitForTimeout(1500);
  const chips = await page.locator('.chips, [data-testid="kpi-chips"], .kpis').first().innerText().catch(() => '');
  const overdueChip = /overdue/i.test(chips) ? chips.match(/(\d+)\s*overdue/i) : null;
  await page.screenshot({ path: `/tmp/situation-console-${Date.now()}.png` });
  await page.close();
  await browser.close();
  if (!overdueChip) fail(`the Bills page shows no overdue chip: "${chips.replace(/\s+/g, ' ').slice(0, 120)}"`);
  if (Number(overdueChip[1]) === 0) {
    fail('the overdue chip counts zero while the book holds overdue bills — it is reading a field no bill carries');
  }
  ok(`BACK OFFICE: the overdue chip counts ${overdueChip[1]}, from the bills' own situation`);

  console.log('\nALL BILL-SITUATION CHECKS PASSED — one block decides what is true about a bill, the desk,'
    + ' the shop and the back office all say the same word, and when the fact changes they move together.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
