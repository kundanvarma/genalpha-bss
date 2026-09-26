/* Suite #244 — csr_customer_context_test: the customer stays in front of the
 * agent, and an action inside a row belongs to that row.
 *
 * Two tickets off the agent-console UX review (#144), paired because both land
 * in the same two files:
 *
 *   CSR-UX-004 (#148) the customer identity strip. The workspace's top bar was
 *     sticky; the customer header was not, so the one fact an agent must never
 *     lose scrolled away while they acted on services, bills and orders. The
 *     strip is now sticky under the top bar and carries who, what kind of
 *     customer, what state, how to reach them, whether the caller's identity
 *     has been checked on this call, and two numbers that change the next move.
 *
 *   CSR-UX-005 (#149) an action inside a row must apply to that row.
 *     "Upgrade options" was rendered on BOTH branches — on a running service
 *     AND on a commercial product with nothing running under it. The generic
 *     journey moved up to the Services header ("Add or upgrade services"); what
 *     is left on a row is named for that object ("Change plan" on a mobile
 *     line, "Change package" on TV) and only exists where it applies.
 *
 * Position and applicability are the whole point, so both are MEASURED, and
 * both checks are made to fail on purpose inside this run before they are
 * trusted: the strip is switched to position:static and must go off screen,
 * and a generic button is injected into a product row and must be caught.
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const CSR = `${API}/csr/`;
const KC = 'http://localhost:8085';
const SHOTS = process.env.SHOT_DIR || os.tmpdir();
/* The seeded demo family: several services, a product with nothing running
 * under it, and a workspace tall enough that scrolling proves something. */
const CUSTOMER_EMAIL = 'paula@family.example';
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const shot = async (page, name) => {
  const p = path.join(SHOTS, `csr-context-${name}.png`);
  await page.screenshot({ path: p, fullPage: false });
  console.log(`  screenshot ${p}`);
  return p;
};

/** The seeded family's id, off the API with a real agent token — so the suite
 * knows which of the fleet's many Paulas it means before it searches. */
async function seededCustomer() {
  const r = await fetch(`${KC}/realms/bss/protocol/openid-connect/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: 'agent-anna', password: 'agent' }),
  });
  if (!r.ok) fail(`token for agent-anna: ${r.status}`);
  const tok = (await r.json()).access_token;
  const res = await fetch(`${API}/tmf-api/party/v4/individual?limit=50&q=${encodeURIComponent(CUSTOMER_EMAIL)}`,
    { headers: { Authorization: `Bearer ${tok}`, 'X-Channel': 'care' } });
  if (!res.ok) fail(`party search: ${res.status} (is party-account up?)`);
  const hits = (await res.json()).filter((c) => (c.contactMedium || [])
    .some((m) => m.characteristic && m.characteristic.emailAddress === CUSTOMER_EMAIL));
  if (!hits.length) fail(`no customer at ${CUSTOMER_EMAIL} — is the demo data seeded?`);
  return hits[0];
}

/* The agent console signs in as an AGENT (agent-anna / agent), never demo/demo,
 * and a fresh context ALWAYS meets Keycloak — so wait for the form
 * unconditionally. Counting it first races the redirect and skips sign-in. */
async function agentLogin(page) {
  await page.goto(CSR);
  await page.waitForSelector('input[name="username"]', { timeout: 30000 });
  await page.fill('input[name="username"]', 'agent-anna');
  await page.fill('input[name="password"]', 'agent');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.searchbar', { timeout: 40000 });
}

/** Where the strip sits relative to the viewport and to the sticky top bar. */
const stripGeometry = (page) => page.evaluate(() => {
  const strip = document.querySelector('[data-testid="cust-strip"]');
  const bar = document.querySelector('header.top');
  if (!strip || !bar) return null;
  const s = strip.getBoundingClientRect();
  const b = bar.getBoundingClientRect();
  return {
    top: Math.round(s.top), bottom: Math.round(s.bottom), height: Math.round(s.height),
    barBottom: Math.round(b.bottom), viewport: window.innerHeight,
    scrolled: Math.round(window.scrollY),
    // an element hidden behind the bar or off screen is not "visible" whatever CSS says
    identityVisible: (() => {
      const el = document.querySelector('[data-testid="identity-state"]');
      if (!el) return false;
      const r = el.getBoundingClientRect();
      return r.top >= b.bottom - 1 && r.bottom <= window.innerHeight && r.height > 0;
    })(),
  };
});

/** Every row-level action that does not belong to the object whose row it is in. */
const misplacedActions = (page) => page.evaluate(() => {
  const bad = [];
  const rows = [...document.querySelectorAll('[data-testid="services-list"] .row.svc')];
  for (const row of rows) {
    const kind = row.getAttribute('data-row-kind');
    const product = row.getAttribute('data-row-product');
    const name = (row.querySelector('strong') || {}).textContent || '(unnamed)';
    for (const b of row.querySelectorAll('button')) {
      const tid = b.getAttribute('data-testid') || '';
      const label = (b.textContent || '').trim();
      // a plan/package change needs BOTH a running service and the product it realises
      if (tid.startsWith('csr-upgrade-options-')) {
        if (kind !== 'service') bad.push(`"${label}" on "${name.trim()}", a row with no running service`);
        else if (tid.slice('csr-upgrade-options-'.length) !== product) {
          bad.push(`"${label}" on "${name.trim()}" points at product ${tid.slice('csr-upgrade-options-'.length)}, but the row is ${product}`);
        }
      }
      // a line check needs a line
      if (tid === 'csr-diagnose' && kind !== 'service') bad.push(`"Diagnose" on "${name.trim()}", which has no running service`);
      // the generic wording is the review's own example of a promise a row cannot keep
      if (/^upgrade options$/i.test(label)) bad.push(`the generic "Upgrade options" is still on the row "${name.trim()}"`);
    }
  }
  return bad;
});

/** What each row offers, for the record and for the screenshot to be readable. */
const rowShapes = (page) => page.evaluate(() => [...document.querySelectorAll('[data-testid="services-list"] .row.svc')]
  .map((row) => ({
    name: ((row.querySelector('strong') || {}).textContent || '').trim(),
    kind: row.getAttribute('data-row-kind'),
    actions: [...row.querySelectorAll('button')].map((b) => (b.textContent || '').trim()).filter(Boolean),
  })));

(async () => {
  const browser = await chromium.launch();
  const page = await (await browser.newContext({ viewport: { width: 1500, height: 820 } })).newPage();

  const who = await seededCustomer();
  await agentLogin(page);

  // An agent reaches a customer by searching, so the suite does too — but it
  // knows WHICH customer it means. A fleet that has run suites for a day is
  // full of people called Paula, and "the first link on the page" (recent
  // customer chips are links too) lands on whoever was opened last. This suite
  // needs the seeded family: the one whose workspace is long enough that
  // scrolling proves something.
  await page.fill('[data-testid="cust-search"]', CUSTOMER_EMAIL);
  await page.waitForSelector('[data-testid="search-results"]', { timeout: 30000 });
  const hit = page.locator(`[data-testid="search-results"] a[href*="/customer/${who.id}"]`).first();
  await hit.waitFor({ timeout: 20000 })
    .catch(() => fail(`searching for ${CUSTOMER_EMAIL} did not offer ${who.givenName} ${who.familyName} (${who.id})`));
  await hit.click();
  await page.waitForSelector('[data-testid="services-list"]', { timeout: 40000 });
  await page.waitForSelector('[data-testid="cust-strip"]', { timeout: 20000 });
  await page.waitForTimeout(2500);
  const customerUrl = page.url();

  /* ---------------- 1. the strip carries what a decision needs --------------- */
  const strip = await page.locator('[data-testid="cust-strip"]').innerText();
  for (const want of [
    ['strip-name', 'the customer\'s name'],
    ['strip-ref', 'the account reference and consumer/business'],
    ['strip-status', 'the customer status'],
    ['identity-state', 'the identity-verification state'],
    ['strip-contact', 'primary contact details'],
    ['strip-services', 'active services'],
    ['strip-balance', 'outstanding balance'],
  ]) {
    if (!(await page.locator(`[data-testid="cust-strip"] [data-testid="${want[0]}"]`).count())) {
      fail(`the strip carries no ${want[1]} ([data-testid="${want[0]}"])`);
    }
  }
  const ref = (await page.locator('[data-testid="strip-ref"]').innerText()).trim();
  if (!/^(Consumer|Business)/.test(ref)) fail(`the strip must say consumer or business, it says "${ref}"`);
  console.log(`OK the strip says: ${strip.replace(/\s*\n\s*/g, ' · ').slice(0, 150)}`);

  // identity verification is the state the review asked to be highly visible
  const idState = await page.getAttribute('[data-testid="identity-state"]', 'data-identity');
  if (idState !== 'unverified') fail(`a fresh session must start unverified, this one says "${idState}"`);
  console.log('OK identity starts "not verified" — the state is in the strip, not in a sub-page');

  /* ---------------- 2. it survives scrolling — measured, and proven to fail --- */
  const atTop = await stripGeometry(page);
  if (!atTop) fail('no strip or no top bar on the page');
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(600);
  const scrolled = await stripGeometry(page);
  if (scrolled.scrolled < 300) fail(`the workspace did not scroll far enough to prove anything (${scrolled.scrolled}px) — the page is shorter than the viewport`);
  if (scrolled.top < 0 || scrolled.bottom > scrolled.viewport) {
    fail(`after scrolling ${scrolled.scrolled}px the strip is at ${scrolled.top}..${scrolled.bottom} of a ${scrolled.viewport}px viewport — it left the screen`);
  }
  if (scrolled.top < scrolled.barBottom - 2) {
    fail(`the strip (top ${scrolled.top}) is under the sticky top bar (bottom ${scrolled.barBottom}) — it is covered, not visible`);
  }
  if (!scrolled.identityVisible) fail('the identity-verification state is not visible after scrolling');
  console.log(`OK after scrolling ${scrolled.scrolled}px the strip is still on screen at ${scrolled.top}..${scrolled.bottom}, clear of the top bar (${scrolled.barBottom})`);
  await shot(page, 'strip-after-scroll');

  // the gate, made to fail on purpose: take the stickiness away and the same
  // measurement must go red. A check never seen failing is not a check.
  await page.evaluate(() => { document.querySelector('[data-testid="cust-strip"]').style.position = 'static'; });
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(400);
  const broken = await stripGeometry(page);
  if (broken.top >= 0 && broken.bottom <= broken.viewport) {
    fail('RED CHECK DID NOT GO RED: with position:static the strip still measures as on screen — the measurement proves nothing');
  }
  console.log(`OK proven red on purpose: position:static puts the strip at ${broken.top}..${broken.bottom} of ${broken.viewport} — off screen, exactly the reported bug`);
  await shot(page, 'strip-static-red');
  await page.evaluate(() => { document.querySelector('[data-testid="cust-strip"]').style.position = ''; });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(300);

  /* ---------------- 3. the identity check is made, and logged ---------------- */
  await page.click('[data-testid="identity-verify"]');
  await page.waitForSelector('[data-testid="identity-confirm"]', { timeout: 10000 });
  await page.selectOption('[data-testid="identity-how-pick"]', { index: 1 });
  const how = await page.inputValue('[data-testid="identity-how-pick"]');
  await page.click('[data-testid="identity-confirm"]');
  await page.waitForSelector('[data-testid="identity-state"][data-identity="verified"]', { timeout: 15000 });
  const howShown = (await page.locator('[data-testid="identity-how"]').innerText()).trim();
  if (!howShown.includes(how)) fail(`the strip must say WHAT was checked; it says "${howShown}" after checking ${how}`);
  console.log(`OK identity verified by "${how}" — the strip says so, and the check is logged as an interaction`);

  /* ---------------- 4. the customer survives moving between areas ------------ */
  const nameAtStart = (await page.locator('[data-testid="strip-name"]').innerText()).trim();
  for (const area of ['services', 'activity', 'billing', 'overview']) {
    await page.click(`[data-testid="area-${area}"]`);
    await page.waitForSelector(`[data-testid="area-view-${area}"]`, { timeout: 20000 });
    await page.waitForTimeout(400);
    const here = (await page.locator('[data-testid="strip-name"]').innerText()).trim();
    if (here !== nameAtStart) fail(`moving to ${area} changed the customer in the strip: "${nameAtStart}" → "${here}"`);
    if (!page.url().startsWith(customerUrl.split('#')[0])) fail(`moving to ${area} left the customer: ${page.url()}`);
    const still = await page.getAttribute('[data-testid="identity-state"]', 'data-identity');
    if (still !== 'verified') fail(`the identity check did not survive the move to ${area} (${still})`);
  }
  console.log(`OK "${nameAtStart}" and the identity check survive Overview → Services → Activity → Billing & account`);

  /* ---------------- 5. #149: no action on a row it does not apply to --------- */
  await page.click('[data-testid="area-services"]');
  await page.waitForSelector('[data-testid="area-view-services"]', { timeout: 20000 });
  await page.waitForTimeout(1200);

  const shapes = await rowShapes(page);
  const productRows = shapes.filter((r) => r.kind === 'product');
  const serviceRows = shapes.filter((r) => r.kind === 'service');
  if (!productRows.length) fail('this customer has no product row without a running service — the bug cannot be shown here; seed one');
  if (!serviceRows.length) fail('this customer has no running service — there is nothing a row action could apply to');
  // a seeded family carries dozens of rows; a dozen is enough to read
  for (const r of [...productRows, ...serviceRows].slice(0, 12)) {
    console.log(`   ${r.kind.padEnd(7)} ${r.name.slice(0, 42).padEnd(44)} ${r.actions.join(' / ') || '(none)'}`);
  }

  const misplaced = await misplacedActions(page);
  if (misplaced.length) fail('an action is on a row it does not apply to:\n    - ' + misplaced.join('\n    - '));
  console.log(`OK ${shapes.length} rows, ${productRows.length} of them with no running service — every action on every row applies to that row`);

  // the customer-level home for the generic journey
  if (!(await page.locator('[data-testid="add-or-upgrade"]').count())) {
    fail('the generic upgrade journey has no customer-level entry point ("Add or upgrade services")');
  }
  await page.click('[data-testid="add-or-upgrade"] > summary');
  await page.waitForTimeout(400);
  const picks = await page.locator('[data-testid^="csr-upgrade-pick-"]').count();
  console.log(`OK "Add or upgrade services" sits above the list and offers ${picks} object(s) to change plus ordering something new`);
  await shot(page, 'services-rows');

  // the object-named action on a row that HAS a running service
  const rowButtons = await page.evaluate(() => [...document.querySelectorAll('[data-testid^="csr-upgrade-options-"]')]
    .map((b) => ({ label: b.textContent.trim(), row: b.closest('.row.svc').querySelector('strong').textContent.trim() })));
  const labelled = [...new Set(rowButtons.map((b) => `${b.row} → "${b.label}"`))];
  if (!rowButtons.length) fail('no row offers a plan change at all — the journey has disappeared rather than moved');
  for (const b of rowButtons) {
    if (!/^Change (plan|package)$/.test(b.label)) fail(`a row action must name what it does to THAT object; "${b.row}" offers "${b.label}"`);
  }
  console.log(`OK ${rowButtons.length} row action(s), each named for its object: ${labelled.slice(0, 8).join(' · ')}`);

  // the same gate, made to fail on purpose: put the old button back by hand
  await page.evaluate(() => {
    const row = document.querySelector('[data-testid="services-list"] .row.svc[data-row-kind="product"]');
    const b = document.createElement('button');
    b.setAttribute('data-testid', 'csr-upgrade-options-injected');
    b.textContent = 'Upgrade options';
    row.querySelector('.rowend').appendChild(b);
  });
  const caught = await misplacedActions(page);
  if (caught.length < 2) {
    fail('RED CHECK DID NOT GO RED: an "Upgrade options" button put back on a product row was not caught — the check proves nothing');
  }
  console.log(`OK proven red on purpose: the reported bug, re-injected, is caught — ${caught.map((c) => `"${c}"`).join('; ')}`);
  await page.evaluate(() => document.querySelector('[data-testid="csr-upgrade-options-injected"]').remove());
  if ((await misplacedActions(page)).length) fail('removing the injected button did not put the page back');

  /* ---------------- 6. the answer lands under the object it is about --------- */
  const changer = page.locator('[data-testid^="csr-upgrade-options-"]').first();
  if (!(await changer.count())) fail('no plan-change action after the reload');
  const opened = (await changer.textContent()).trim();
  await changer.click();
  // the desk puts a refusal in a toast and beside the block that raised it;
  // a suite that only says "timeout" makes the next agent guess
  await page.waitForSelector('[data-testid="upgrade-card"]', { timeout: 40000 }).catch(async () => {
    const said = await page.locator('[data-testid="toast"], [data-testid="error-services"]').allTextContents();
    fail(`"${opened}" produced no options card: ${said.join(' | ') || 'no error shown either'}`);
  });
  await page.waitForTimeout(1000);
  const place = await page.evaluate(() => {
    const list = document.querySelector('[data-testid="services-list"]');
    const kids = [...list.children];
    const card = document.querySelector('[data-testid="upgrade-card"]');
    const row = document.querySelector('[data-testid^="csr-upgrade-options-"]').closest('.row.svc');
    return { card: kids.indexOf(card), row: kids.indexOf(row), total: kids.length,
      said: (card.innerText || '').split('\n')[0].slice(0, 70) };
  });
  if (place.card < 0 || place.row < 0) fail('the options card or its row is not a child of the services list');
  if (place.card !== place.row + 1) {
    fail(`the options card sits at ${place.card} and its row at ${place.row} of ${place.total}`
      + ' — like the diagnosis report (#142), an answer belongs under the row that asked for it');
  }
  console.log(`OK the options card lands directly under its own row (${place.row} → ${place.card} of ${place.total}): "${place.said}"`);
  await shot(page, 'options-in-place');

  await browser.close();
  console.log('\nPASS csr_customer_context_test — the customer strip survives the scroll and every area (#148); '
    + 'no action sits on a row it does not apply to, the generic journey lives at customer level, '
    + 'and its answer lands under the row that asked (#149). Both checks proven red on purpose.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
