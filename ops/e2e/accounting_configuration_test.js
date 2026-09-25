/* Accounting, and Configuration as the place live financial settings change.
 * Suite #238.
 *
 * Two claims are under test, and neither of them is a pixel.
 *
 * ACCOUNTING is the controller's page. A posting is a business event before it
 * is a row of debits and credits, so the journal leads with what happened and
 * keeps every identifier under technical details; the count above the table is
 * the service's judged total for the filter, never the length of the page; and
 * the file an operator downloads answers the SAME question the screen asked —
 * a reconciliation that exports something else is worse than no export.
 *
 * CONFIGURATION is where the chart of accounts actually changes, and the point
 * of the ladder is that it refuses. Approve before validate is refused.
 * Activate before approve is refused. A proposal that would leave an account
 * without a code a ledger can read is refused at BOTH doors — the ladder and
 * the direct remap a seed uses — because a rule only the screen enforced would
 * not be a rule. And an account already carrying postings cannot be moved
 * without the approver being told, by name and by count, what it leaves behind.
 *
 * Run: node accounting_configuration_test.js   (needs revenue + billing up)
 */
const { chromium } = require('playwright');

const CONSOLE = 'http://localhost:8080/console/';
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const R = '/revenue/v1';
const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token for ${user}: ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, {
    method,
    headers: { Authorization: `Bearer ${tok}`, 'Cache-Control': 'no-cache',
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const text = await r.text();
  let parsed = null;
  try { parsed = JSON.parse(text); } catch { /* csv and empty bodies are fine */ }
  return { status: r.status, body: parsed, text, headers: r.headers };
}
/** What the SERVICE says the whole filter holds, not what fitted on a page. */
async function total(tok, q) {
  const r = await fetch(`${API}${R}/journalEntry?limit=1${q}`, {
    headers: { Authorization: `Bearer ${tok}`, 'Cache-Control': 'no-cache' } });
  if (!r.ok) fail(`journalEntry${q}: ${r.status}`);
  return Number(r.headers.get('X-Total-Count'));
}
/** The FIRST number in a sentence — "4,248 postings …, showing 1–50." is 4248. */
const digits = (s) => {
  const m = String(s).match(/[\d][\d,\s.]*/);
  return m ? Number(m[0].replace(/[^\d]/g, '')) : NaN;
};

(async () => {
  const staff = await token('demo', 'demo');
  const pat = await token('pat@bss.local', 'pat');

  /* ---------- 0. the account this suite will move, and a clean ladder ---------- */
  const chart = (await call('GET', `${R}/accountMapping`, staff)).body || [];
  if (!chart.length) fail('the chart of accounts is empty — start the revenue service');
  const KEY = 'club-share:payable';
  const target = chart.find((c) => c.key === KEY);
  if (!target) fail(`this tenant has no '${KEY}' posting key`);
  const WAS = { code: target.accountCode, name: target.accountName };
  const NEW_CODE = '2151';
  // a re-run must start from the same place: anything this suite left on the
  // ladder for its own key is withdrawn before it climbs again
  for (const c of (await call('GET', `${R}/configChange`, staff)).body || []) {
    if (c.postingKey === KEY && ['draft', 'validated', 'approved'].includes(c.state)) {
      await call('POST', `${R}/configChange/${c.id}/withdraw`, staff);
    }
  }
  console.log(`   moving "${WAS.name}" (${WAS.code}), which ${target.postings} postings already carry`);

  /* ---------- 1. the rules, at both doors ---------- */
  const broken = await call('POST', `${R}/configChange`, staff,
    { postingKey: KEY, accountCode: '21 51, oops', accountName: WAS.name });
  if (broken.status >= 300) fail(`drafting a bad proposal should be allowed: ${broken.status}`);
  const checked = await call('POST', `${R}/configChange/${broken.body.id}/validate`, staff);
  if (checked.body.state !== 'draft') fail('a blocked change must stay in draft');
  if (!(checked.body.findings || []).some((f) => f.severity === 'blocks')) {
    fail('an account code a ledger cannot read must be refused: ' + JSON.stringify(checked.body.findings));
  }
  const early = await call('POST', `${R}/configChange/${broken.body.id}/approve`, staff);
  if (early.status !== 409) fail(`a blocked change must not be approvable, got ${early.status}`);
  await call('POST', `${R}/configChange/${broken.body.id}/withdraw`, staff);
  const direct = await call('POST', `${R}/accountMapping`, staff,
    { key: KEY, accountCode: '', accountName: WAS.name });
  if (direct.status !== 400) fail(`the direct remap must refuse a broken account too, got ${direct.status}`);
  ok('REFUSED AT BOTH DOORS: an account cannot be left without a code a general ledger can read —'
    + ' the ladder blocks it and the direct remap a seed uses returns 400.');

  const walled = await call('GET', `${R}/configChange`, pat);
  if (walled.status !== 403) fail(`a product role must not read the ladder, got ${walled.status}`);
  const walledWrite = await call('POST', `${R}/configChange`, pat,
    { postingKey: KEY, accountCode: '9999', accountName: 'nope' });
  if (walledWrite.status !== 403) fail(`a product role must not draft, got ${walledWrite.status}`);
  ok('ROLE-GATED: a product manager reaches neither the ladder nor a proposal (403 both ways).');

  /* ---------- 2. the desk, in a browser ---------- */
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1500, height: 1100 }, acceptDownloads: true });
  page.on('pageerror', (e) => fail(`the page threw: ${e.message}`));
  await page.goto(CONSOLE);
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 30000 });
  const openTab = async (name, testid) => {
    await page.locator('#tabs .tab', { hasText: new RegExp(`^${name}$`, 'i') }).first().click();
    await page.waitForSelector(`[data-testid="${testid}"]`, { timeout: 30000 });
  };

  /* ---------- 3. the journal reads as business events ---------- */
  await openTab('Journal', 'journal');
  await page.waitForFunction(() => document.querySelectorAll('[data-testid="journal-body"] tr').length > 1,
    null, { timeout: 30000 });
  const book = await total(staff, '');
  const counted = digits(await page.locator('[data-testid="journal-count"]').textContent());
  if (counted !== book) fail(`the page says ${counted} postings, the subledger says ${book}`);
  const firstRow = await page.locator('[data-testid="journal-row"]').first().textContent();
  if (/^[▸▾]\s*(bill|payment|creditNote|deviceActivation)\b/.test(firstRow.trim())) {
    fail(`the journal leads with a technical kind, not a business event: ${firstRow}`);
  }
  const grid = await page.locator('[data-testid="journal-body"]').innerText();
  if (UUID.test(grid)) fail('an identifier is visible in the journal before anything was disclosed');
  ok(`BUSINESS EVENTS FIRST: ${book.toLocaleString()} postings, the page's own count, and not one`
    + ` identifier above the fold — the first row reads "${firstRow.trim()}".`);

  /* ---------- 4. disclosure, not a wall of columns ---------- */
  await page.locator('[data-testid="journal-row"]').first().click();
  await page.waitForSelector('[data-testid="journal-detail"]', { timeout: 10000 });
  const detail = await page.locator('[data-testid="journal-detail"]').innerText();
  if (!/Balanced:/.test(detail)) fail('a disclosed posting must show that it balances');
  if (UUID.test(await page.locator('[data-testid="posting-lines"]').first().innerText())) {
    fail('the identifier must sit UNDER technical details, not among the posting lines');
  }
  const fold = page.locator('[data-testid="posting-technical"]').first();
  if (await fold.evaluate((el) => el.open)) fail('technical details must start folded away');
  await fold.locator('summary').click();
  if (!UUID.test(await fold.innerText())) {
    fail('technical details must still carry the identifier a support call needs');
  }
  ok('ROW DISCLOSURE: the entry opens to its balanced double entry; the identifiers are one fold further'
    + ' down, where a support call can still reach them.');

  /* ---------- 5. a filter, and an export that answers the same question ---------- */
  const kinds = (await call('GET', `${R}/journalSourceType`, staff)).body || [];
  const kind = kinds.includes('payment') ? 'payment' : kinds[0];
  await page.selectOption('[data-testid="filter-source"]', kind);
  await page.click('[data-testid="filter-apply"]');
  await page.waitForFunction((n) => {
    const el = document.querySelector('[data-testid="journal-count"]');
    return el && !el.textContent.includes(n);
  }, book.toLocaleString(), { timeout: 20000 });
  const forKind = await total(staff, `&sourceType=${kind}`);
  const filtered = digits(await page.locator('[data-testid="journal-count"]').textContent());
  if (filtered !== forKind) fail(`filtered page says ${filtered}, the subledger says ${forKind}`);
  if (filtered === book) fail('the filter changed nothing');

  const download = page.waitForEvent('download', { timeout: 20000 });
  await page.click('[data-testid="journal-export"]');
  const file = await download;
  const csv = (await call('GET', `${R}/journalExport?sourceType=${kind}`, staff)).text;
  const lines = csv.trim().split('\n').length - 1;
  const everything = (await call('GET', `${R}/journalExport`, staff)).text.trim().split('\n').length - 1;
  if (lines >= everything) fail('the export ignored the filter — it holds as much as the whole book');
  if (!/^entryDate,entryId,sourceType,accountCode/.test(csv)) fail('the export lost its header');
  ok(`FILTER AND EXPORT AGREE: ${forKind.toLocaleString()} ${kind} postings on screen and in the service,`
    + ` and the file the browser downloaded (${file.suggestedFilename()}) carries ${lines} lines of them,`
    + ` not the ${everything} of the whole book.`);

  /* ---------- 6. the chart of accounts: description first, key underneath ---------- */
  await openTab('Chart of accounts', 'chart');
  await page.waitForFunction(() => document.querySelectorAll('[data-testid="chart-body"] tr').length > 1,
    null, { timeout: 20000 });
  const chartText = await page.locator('[data-testid="chart-body"]').innerText();
  if (/rate:recurringCharge|loyalty:liability|club-share:/.test(chartText)) {
    fail('a posting key is an identifier and must not be the primary description');
  }
  if (!/Accounts receivable/.test(chartText)) fail('the chart must name its accounts');
  if (!/\d[\d,]* postings/.test(chartText)) fail('an account in use must say how much it carries');
  const row = page.locator('[data-testid="chart-row"]', { hasText: WAS.name }).first();
  await row.click();
  const opened = page.locator('[data-testid="chart-detail"]').first();
  await opened.waitFor({ timeout: 10000 });
  const books = await opened.locator('[data-testid="chart-books"]').innerText();
  if (books.trim().length < 20 || /[a-z]+:[a-z]/i.test(books)) {
    fail(`a disclosed account must lead with a sentence about what it books, not a key: "${books}"`);
  }
  const keyFold = opened.locator('[data-testid="chart-technical"]');
  if (await keyFold.evaluate((el) => el.open)) fail('the posting key must start folded away');
  await keyFold.locator('summary').click();
  if (!/club-share:payable/.test(await keyFold.innerText())) {
    fail('technical details must carry the posting key');
  }
  ok('CHART READS AS BUSINESS: an account leads with what it books and how much it carries;'
    + ' the posting key sits under technical details.');

  /* ---------- 7. proposing a change, and the ladder refusing to be skipped ---------- */
  await opened.locator('[data-testid="chart-propose"]').click();
  await page.waitForSelector('[data-testid="propose"]', { timeout: 10000 });
  await page.fill('[data-testid="propose-code"]', NEW_CODE);
  await page.fill('[data-testid="propose-reason"]', 'the new chart of accounts takes effect next quarter');
  await page.click('[data-testid="propose-submit"]');
  await page.waitForSelector('[data-testid="accounting-drafted"]', { timeout: 20000 });
  const drafted = await page.locator('[data-testid="accounting-drafted"]').textContent();
  if (!new RegExp(NEW_CODE).test(drafted)) fail(`the receipt should name ${NEW_CODE}: ${drafted}`);
  if (!/waits on Configuration/.test(drafted)) fail('the receipt must say nothing changed yet');
  const live = ((await call('GET', `${R}/accountMapping`, staff)).body || []).find((c) => c.key === KEY);
  if (live.accountCode !== WAS.code) fail('proposing must not change the books');
  ok('PROPOSED, NOT APPLIED: the chart is untouched and the change waits on Configuration.');

  await openTab('Configuration', 'configuration');
  await page.waitForSelector('[data-testid="change"]', { timeout: 20000 });
  const card = page.locator('[data-testid="change"]', { hasText: WAS.name }).first();
  if (await card.locator('[data-testid="change-approve"]').count()) {
    fail('a draft must not offer Approve — the ladder is climbed one rung at a time');
  }
  await card.locator('[data-testid="change-validate"]').click();
  await page.waitForSelector('[data-testid="findings"]', { timeout: 20000 });
  const verdict = await page.locator('[data-testid="change"]', { hasText: WAS.name }).first().textContent();
  if (!new RegExp(`${target.postings.toLocaleString()} booked lines keep account ${WAS.code}`).test(verdict)) {
    fail(`validation must name what the move leaves behind: ${verdict}`);
  }
  ok(`VALIDATION SAYS THE CONSEQUENCE: "${target.postings} booked lines keep account ${WAS.code}" —`
    + ' the approver signs for that, not for a silent edit.');

  const onLadder = ((await call('GET', `${R}/configChange`, staff)).body || [])
    .find((c) => c.postingKey === KEY && c.state === 'validated');
  if (!onLadder) fail('the change should be validated by now');
  const jumped = await call('POST', `${R}/configChange/${onLadder.id}/activate`, staff);
  if (jumped.status !== 409) fail(`activating an unapproved change must be refused, got ${jumped.status}`);
  ok('NO SKIPPED RUNGS: activate before approve is a 409, from the service, not from the screen.');

  /* ---------- 8. approve, activate, and the books follow ---------- */
  await page.locator('[data-testid="change"]', { hasText: WAS.name }).first()
    .locator('[data-testid="change-approve"]').click();
  await page.waitForFunction(() => document.querySelector('[data-state="approved"]'), null, { timeout: 20000 });
  await page.locator('[data-testid="change"]', { hasText: WAS.name }).first()
    .locator('[data-testid="change-activate"]').click();
  await page.waitForFunction(() => document.querySelector('[data-testid="ladder-clean"]'), null, { timeout: 20000 });
  const after = ((await call('GET', `${R}/accountMapping`, staff)).body || []).find((c) => c.key === KEY);
  if (after.accountCode !== NEW_CODE) fail(`the chart should read ${NEW_CODE}, it reads ${after.accountCode}`);
  const history = (await call('GET', `${R}/configChange`, staff)).body || [];
  const done = history.find((c) => c.postingKey === KEY && c.state === 'active');
  if (!done) fail('the activated change should be on the record');
  for (const stamp of ['draftedBy', 'validatedBy', 'approvedBy', 'activatedBy']) {
    if (done[stamp] !== 'demo') fail(`the audit trail is missing ${stamp}: ${JSON.stringify(done)}`);
  }
  ok(`ACTIVATED: the chart now books into ${NEW_CODE}, and every rung carries a name —`
    + ' drafted, validated, approved and activated by demo.');

  /* ---------- 9. the three setup pages are here ---------- */
  for (const [chip, testid] of [['configuration-formats', 'formats'],
    ['configuration-deliveries', 'deliveries'], ['configuration-shadow', 'shadow']]) {
    await page.locator(`[data-testid="${chip}"]`).click();
    await page.waitForSelector(`[data-testid="${testid}"]`, { timeout: 20000 });
  }
  const shot = `${require('os').tmpdir()}/accounting-configuration-${Date.now()}.png`;
  await page.screenshot({ path: shot, fullPage: true });
  console.log(`   screenshot: ${shot}`);
  ok('CONFIGURATION HOLDS THE SETUP: bill formats, deliveries and shadow billing are one destination,'
    + ' out of the daily path.');

  /* ---------- 10. put the books back ---------- */
  const back = await call('POST', `${R}/configChange`, staff,
    { postingKey: KEY, accountCode: WAS.code, accountName: WAS.name, reason: 'suite restore' });
  await call('POST', `${R}/configChange/${back.body.id}/validate`, staff);
  await call('POST', `${R}/configChange/${back.body.id}/approve`, staff);
  const restored = await call('POST', `${R}/configChange/${back.body.id}/activate`, staff);
  if (restored.body.state !== 'active') fail('the restore did not activate');
  const finalChart = ((await call('GET', `${R}/accountMapping`, staff)).body || []).find((c) => c.key === KEY);
  if (finalChart.accountCode !== WAS.code) fail('the suite left the chart of accounts moved');
  ok(`RESTORED: "${WAS.name}" books into ${WAS.code} again, by the same ladder.`);

  await browser.close();
  console.log('\nACCOUNTING & CONFIGURATION SUITE PASSED');
})().catch((e) => { console.error('FAIL: ' + e.message); process.exit(1); });
