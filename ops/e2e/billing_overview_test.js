/* The Billing & Revenue overview, and payments and collections as their own
 * workflows. Suite #236.
 *
 * The paper's priority rule is the thing under test, not the pixels. A screen
 * can render every number correctly and still be wrong, because what an
 * operator does next is decided by what is loud, what is merely visible, what
 * is left calm, and what is folded away. So this suite asserts the ranking:
 *
 *  - HEADLINE IS THE BOOK: the overdue count on the landing page is the
 *    service's judged total, not a count of whatever fitted on one page. This
 *    tenant holds thousands of bills and a list serves a hundred at a time, so
 *    a page-counted figure is off by a factor and still looks plausible.
 *  - PROMINENT AND ACTIONABLE: overdue, unapplied cash and a failed run render
 *    as exceptions and lead somewhere a person can act.
 *  - CLEAN, NOT ALARMING: a zero exception reads "✓ …" and leads nowhere.
 *  - A VISIBLE QUEUE: disputes, cases and arrangements are listed, and are not
 *    styled as exceptions.
 *  - CALM, NEVER A WARNING: outstanding-not-yet-due and the run's status carry
 *    no border and none of the exception colour.
 *  - DRILL-DOWN ONLY: history is closed until it is asked for.
 *  - IN THAT ORDER: attention above queue above calm above history.
 *  - PAYMENTS: an unapplied payment is stated as the exception it is, with the
 *    actions that resolve it, and an action with no endpoint is disabled and
 *    says why rather than pretending.
 *  - COLLECTIONS: a case aggregates the six things the paper names, each
 *    either its facts or a sentence saying there are none.
 */
const { chromium } = require('playwright');

const CONSOLE = 'http://localhost:8080/console/';
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const BILLING = '/tmf-api/customerBillManagement/v4';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

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
/** What the service says is true of the whole book, not of one page. */
async function total(situation, tok) {
  const q = situation ? `&situation=${situation}` : '';
  const r = await fetch(`${API}${BILLING}/billSituation?limit=1${q}`, {
    headers: { Authorization: `Bearer ${tok}`, 'Cache-Control': 'no-cache' } });
  if (!r.ok) fail(`billSituation ${situation || 'all'}: ${r.status}`);
  return Number(r.headers.get('X-Total-Count'));
}
const digits = (s) => Number(String(s).replace(/[^\d]/g, ''));
const yOf = async (loc) => (await loc.first().boundingBox()).y;

(async () => {
  const tok = await token();
  const book = await total(null, tok);
  const counts = {};
  for (const s of ['overdue', 'disputed', 'outstanding', 'paid', 'writtenOff']) counts[s] = await total(s, tok);
  const page1 = (await api(`${BILLING}/billSituation?limit=100`, tok)) || [];
  if (!page1.length) fail('no bills on this tenant — run the billing seeds first');
  const cases = (await api(`${BILLING}/collectionCase`, tok)) || [];
  const parked = (await api(`${BILLING}/remittance/unapplied`, tok)) || [];
  const runs = (await api(`${BILLING}/billingRun`, tok)) || [];
  const runFailed = runs.length && /fail|error/i.test(runs[0].status || '');
  console.log(`   the book: ${book} bills, ${counts.overdue} overdue, ${counts.disputed} disputed,`
    + ` ${cases.length} cases, ${parked.length} parked payments`);

  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1500, height: 1100 } });
  page.on('pageerror', (e) => fail(`the page threw: ${e.message}`));
  await page.goto(CONSOLE);
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });

  /* ---------- the overview ---------- */
  // "Overview" is a tab of the Billing & Revenue department; other departments
  // have their own, so anchor the match rather than matching a substring
  await page.locator('#tabs .tab', { hasText: /^overview$/i }).first().click();
  await page.waitForSelector('[data-testid="billing-overview"]', { timeout: 30000 });
  await page.waitForFunction(
    () => !/Reading the book/.test(document.querySelector('[data-testid="billing-overview"]').textContent),
    null, { timeout: 30000 });

  /* ---------- headline is the book, not a page ---------- */
  // The defect this guards: counting the rows one list call returns and
  // printing the answer as the desk's headline. On this tenant that reads
  // 103 overdue when 3 815 are overdue — plausible, actionable, and wrong.
  const overdueBox = page.locator('[data-testid="attention-overdue"]');
  if (counts.overdue > 0) {
    const said = digits(await overdueBox.locator('b').innerText());
    if (said !== counts.overdue) {
      fail(`the overview says ${said} overdue; the service judges ${counts.overdue} of ${book} bills`
        + (said <= page1.length ? ' — that is a count of one page, not of the book' : ''));
    }
    if (counts.overdue <= page1.length) {
      console.log('   NOTE: fewer overdue bills than fit on one page, so this check cannot'
        + ' tell a page-count from a book-count on today\'s data');
    }
    ok(`HEADLINE IS THE BOOK: ${said} overdue of ${book} bills, the service's own total`);
  }
  const allSaid = digits(await page.locator('[data-testid="billing-overview"] > p').first().innerText());
  if (allSaid !== book) fail(`the overview says ${allSaid} bills on the book; the service counts ${book}`);

  /* ---------- prominent and actionable, clean when there is nothing ---------- */
  const expected = {
    'attention-overdue': counts.overdue,
    'attention-unapplied': parked.length,
    'attention-run': runFailed ? 1 : 0,
  };
  let cleanProved = 0;
  let loudProved = 0;
  for (const [testid, n] of Object.entries(expected)) {
    const box = page.locator(`[data-testid="${testid}"]`);
    if (!(await box.count())) fail(`${testid} is not on the page at all`);
    const state = await box.getAttribute('data-state');
    const text = (await box.innerText()).trim();
    const disabled = await box.isDisabled();
    if (n === 0) {
      if (state !== 'clean') fail(`${testid} counts nothing but renders as "${state}": "${text}"`);
      if (!text.startsWith('✓')) fail(`a zero count on ${testid} reads "${text}" instead of a clean tick`);
      if (/overdue |failed|attention|problem|error/i.test(text.replace(/^✓ /, ''))
        && !/^✓ (No |All |The last billing run finished)/.test(text)) {
        fail(`a clean ${testid} is phrased as trouble: "${text}"`);
      }
      if (!disabled) fail(`${testid} is clean but still offers somewhere to go`);
      cleanProved += 1;
    } else {
      if (state !== 'exception') fail(`${testid} counts ${n} but renders as "${state}"`);
      if (disabled) fail(`${testid} is an exception with no way in: "${text}"`);
      loudProved += 1;
    }
  }
  if (!cleanProved) fail('nothing on this tenant is clean, so "a zero reads as clean" went unproven — seed a quiet situation');
  if (!loudProved) fail('nothing on this tenant is an exception, so "loud and actionable" went unproven');
  ok(`PROMINENT AND ACTIONABLE: ${loudProved} loud and each leads somewhere; CLEAN, NOT ALARMING: ${cleanProved} read as a tick and lead nowhere`);

  /* ---------- a visible queue, and not an alarm ---------- */
  // a case is work while it owes money and has not been written off: a cure
  // resets the ladder to "current" rather than marking a closed state, so
  // counting by state alone reports cleared cases as open
  const openCases = cases.filter((c) => c.state !== 'writtenOff'
    && Number((c.overdueBalance || {}).value || 0) > 0);
  const wantQueue = { disputes: counts.disputed, cases: openCases.length,
    arrangements: cases.filter((c) => (c.holds || {}).promiseToPay).length };
  const exceptionColour = await overdueBox.evaluate((el) => getComputedStyle(el).borderColor);
  for (const [key, n] of Object.entries(wantQueue)) {
    const item = page.locator(`[data-testid="queue-${key}"]`);
    if (n === 0) {
      if (await item.count()) fail(`the queue lists ${key} with nothing in it`);
      continue;
    }
    if (!(await item.count())) fail(`${n} ${key} are waiting and the queue does not show them`);
    // the count is its own element; the row also carries an amount, so
    // reading digits off the whole row would splice the two together
    const shown = digits(await item.locator('xpath=preceding-sibling::b').innerText());
    if (shown !== n) fail(`the queue says ${shown} ${key}; the API counts ${n}`);
    const border = await item.evaluate((el) => getComputedStyle(el.parentElement).borderColor);
    if (border === exceptionColour) fail(`${key} is queued work but is styled like an exception`);
  }
  ok(`A VISIBLE QUEUE: ${Object.entries(wantQueue).filter(([, n]) => n).map(([k, n]) => `${n} ${k}`).join(', ') || 'nothing waiting'} — listed, not alarmed`);

  /* ---------- calm status, never a warning ---------- */
  for (const testid of ['calm-outstanding', 'calm-paid', 'calm-run']) {
    const el = page.locator(`[data-testid="${testid}"]`);
    if (!(await el.count())) fail(`${testid} is missing`);
    const style = await el.evaluate((n) => {
      const s = getComputedStyle(n);
      return { border: s.borderTopWidth, colour: s.color, background: s.backgroundColor };
    });
    if (parseFloat(style.border) > 0) fail(`${testid} is boxed like an exception`);
    if (style.colour === exceptionColour) fail(`${testid} is painted in the exception colour`);
    const words = (await el.innerText()).trim();
    if (/overdue|failed|urgent|action required/i.test(words)) fail(`a calm line raises an alarm: "${words}"`);
  }
  const outstandingSaid = digits(await page.locator('[data-testid="calm-outstanding"]').innerText());
  if (outstandingSaid !== counts.outstanding) {
    fail(`"outstanding and not yet due" says ${outstandingSaid}; the service counts ${counts.outstanding}`);
  }
  ok(`CALM, NEVER A WARNING: ${counts.outstanding} outstanding and ${counts.paid} paid, stated without colour or border`);

  /* ---------- drill-down only ---------- */
  const history = page.locator('[data-testid="overview-history"]');
  if (await history.evaluate((el) => el.open)) fail('history is open on arrival; the paper puts it behind a drill-down');
  const hidden = await history.locator('ul').isVisible();
  if (hidden) fail('the historical list is visible without opening the drill-down');
  await history.locator('summary').click();
  await page.waitForTimeout(200);
  if (!(await history.locator('ul').isVisible())) fail('the drill-down does not open');
  ok('DRILL-DOWN ONLY: earlier runs and settled money stay folded until asked for');

  /* ---------- in that order ---------- */
  const order = [
    ['attention', await yOf(page.locator('[data-testid="attention"]'))],
    ['queue', await yOf(page.locator('[data-testid="queue"], [data-testid="queue-empty"]'))],
    ['calm', await yOf(page.locator('[data-testid="calm-outstanding"]'))],
    ['history', await yOf(history)],
  ];
  for (let i = 1; i < order.length; i += 1) {
    if (order[i][1] <= order[i - 1][1]) fail(`${order[i][0]} sits above ${order[i - 1][0]} on the page`);
  }
  ok(`IN THAT ORDER: ${order.map(([k]) => k).join(' → ')}`);

  const overviewText = await page.locator('[data-testid="billing-overview"]').innerText();
  if (UUID.test(overviewText)) fail(`the overview shows an identifier as primary text: ${overviewText.match(UUID)[0]}`);

  /* ---------- payments ---------- */
  await page.locator('#tabs .tab', { hasText: /^payments$/i }).first().click();
  await page.waitForSelector('[data-testid="payments-desk"]', { timeout: 30000 });
  await page.waitForFunction(
    () => !/Reading payments/.test(document.querySelector('[data-testid="payments-desk"]').textContent),
    null, { timeout: 30000 });

  if (parked.length) {
    const row = page.locator('[data-testid="unapplied-row"]').first();
    const said = (await row.innerText()).replace(/\n/g, ' · ');
    // the exception is stated in money and words, not as a status code
    if (!/received/.test(said) || !/no bill claims it/.test(said)) {
      fail(`an unapplied payment is not stated as the exception it is: "${said}"`);
    }
    const amount = parked[0].amount || {};
    const unit = amount.unit || '';
    if (unit && !said.includes(unit)) fail(`the row states no currency: "${said}"`);
    if (!(await row.locator('[data-testid="unapplied-match"]').count())) fail('no way to match the payment to a bill');
    if (!(await row.locator('[data-testid="unapplied-find"]').count())) fail('no way to find the customer');
    const refund = row.locator('[data-testid="unapplied-refund"]');
    if (!(await refund.isDisabled())) fail('refund is offered, but billing serves no refund for unapplied cash');
    const why = await refund.getAttribute('title');
    if (!why || why.length < 20) fail('the refund action is disabled without saying why');
    ok(`PAYMENTS: "${said.slice(0, 80)}" — match and find offered, refund disabled: "${why.slice(0, 60)}…"`);
    // a bill number that cannot exist must be refused in words, not posted
    await row.locator('input[name="billNo"]').fill('NO-SUCH-BILL-0');
    await row.locator('[data-testid="unapplied-match"]').click();
    await page.waitForTimeout(800);
    const answer = (await row.innerText()).replace(/\n/g, ' ');
    if (!/No bill on the book is numbered/.test(answer)) {
      fail(`matching an unknown bill number said "${answer.slice(-90)}" instead of naming the problem`);
    }
    ok('PAYMENTS: an unknown bill number is refused in words before anything is posted');
  } else {
    const clean = (await page.locator('[data-testid="unapplied-clean"]').innerText()).trim();
    if (!clean.startsWith('✓')) fail(`no unapplied cash, and the page says "${clean}"`);
    ok(`PAYMENTS: nothing unapplied, and it reads "${clean}"`);
  }

  /* ---------- collections ---------- */
  await page.locator('#tabs .tab', { hasText: /^collections$/i }).first().click();
  await page.waitForSelector('[data-testid="collections-desk"]', { timeout: 30000 });
  if (openCases.length) {
    await page.waitForFunction(
      () => document.querySelectorAll('[data-testid="case-link"]').length > 0, null, { timeout: 30000 });
    const first = (await page.locator('[data-testid="collections-body"] tr').first().innerText()).replace(/\n/g, ' · ');
    if (UUID.test(first)) fail(`a case row leads with an identifier: "${first}"`);
    // Every customer the party service can name must be named on the page.
    // Not a fraction — some seeded cases genuinely have no party record, and
    // "this customer" is the honest answer for those. What this catches is a
    // hundred lookups fired at once: the ones that lose the race come back
    // null and get written over a name that was there all along.
    await page.waitForTimeout(4000);
    const rows = await page.locator('[data-testid="collections-body"] tr').all();
    const sample = openCases.slice(0, 12);
    let checked = 0;
    for (let i = 0; i < sample.length && i < rows.length; i += 1) {
      const partyId = ((sample[i].relatedParty || [])[0] || {}).id;
      if (!partyId) continue;
      const person = await api(`/tmf-api/party/v4/individual/${partyId}`, tok);
      const realName = person && [person.givenName, person.familyName].filter(Boolean).join(' ');
      if (!realName) continue;
      const shown = (await rows[i].locator('[data-testid="case-link"]').innerText()).trim();
      if (/^(this customer|…)$/.test(shown)) {
        fail(`the party service names this customer "${realName}" and the case row says "${shown}"`);
      }
      if (/\d{10,}/.test(shown)) fail(`a case names a customer with a seed number in it: "${shown}"`);
      checked += 1;
    }
    if (!checked) fail('no case in the sample has a party the service can name, so naming went unproven');
    ok(`COLLECTIONS: ${checked} sampled cases each name the customer the party service names`);
    await page.locator('[data-testid="case-link"]').first().click();
    await page.waitForSelector('[data-testid="collection-case"]', { timeout: 30000 });
    await page.waitForFunction(
      () => !/Reading the case/.test(document.querySelector('[data-testid="collection-case"]').textContent),
      null, { timeout: 30000 });
    const six = ['case-debt', 'case-commitments', 'case-stage', 'case-communications', 'case-restrictions', 'case-risk'];
    const summary = [];
    const state = (await page.locator('[data-testid="collection-case"] p').first().innerText()).toLowerCase();
    if (/^(current|reminded|warned|restricted|suspended|terminated|writtenoff)\b/.test(state)) {
      fail(`the case states its rung as the service's key: "${state}"`);
    }
    for (const part of six) {
      const el = page.locator(`[data-testid="${part}"]`);
      if (!(await el.count())) fail(`a case does not aggregate ${part.replace('case-', '')}`);
      const words = (await el.innerText()).trim();
      const body = words.split('\n').slice(1).join(' ').trim();
      if (!body) fail(`${part} is an empty box; it should say plainly that there is none`);
      summary.push(`${part.replace('case-', '')}: ${body.slice(0, 34)}`);
    }
    // a suspended customer is cut off, whatever the service list holds; the
    // case may not say "nothing is restricted" in the same breath
    const rung = (await page.locator('[data-testid="case-stage"]').innerText()).toLowerCase();
    const held = (await page.locator('[data-testid="case-restrictions"]').innerText()).toLowerCase();
    if (/suspend|restrict|terminat/.test(rung) && /nothing is restricted/.test(held)) {
      fail('the case says the service is suspended and that nothing is restricted');
    }
    // likewise for the letters: a case that records a warning may not also
    // say, flatly, that nothing has been sent
    const talk = (await page.locator('[data-testid="case-communications"]').innerText()).toLowerCase();
    if (/last warned/.test(rung) && /^what we have said\s+nothing has been sent/.test(talk)) {
      fail('the case records a warning and says nothing has been sent about the debt');
    }
    // the debt panel may not print the same figure twice under two words
    const debt = (await page.locator('[data-testid="case-debt"]').innerText());
    const figures = debt.match(/[\d.,]+\s+[A-Z]{3}/g) || [];
    if (figures.length > 1 && new Set(figures).size === 1) {
      fail(`the debt is stated twice as the same figure: "${debt.replace(/\n/g, ' · ')}"`);
    }
    const headline = (await page.locator('[data-testid="collection-case"] h2').innerText()).trim();
    if (UUID.test(headline) || !headline) fail(`the case is headed "${headline}" rather than by a customer`);
    ok(`COLLECTIONS: "${headline}" — ${summary.join(' | ')}`);
  } else {
    const clean = (await page.locator('[data-testid="collections-clean"]').innerText()).trim();
    if (!clean.startsWith('✓')) fail(`no cases, and the page says "${clean}"`);
    ok(`COLLECTIONS: no cases open, and it reads "${clean}"`);
  }

  await browser.close();
  console.log('\nbilling overview, payments and collections: the priority rule holds.');
})().catch((e) => { console.error('FAILED:', e.message); process.exit(1); });
