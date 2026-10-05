/* The search box searches the catalogue, and says so when it does not. Suite #250.
 *
 * Ticket #160. The box said "Filter this page…" and did exactly that: it
 * narrowed the twenty rows already loaded. With 56 offerings over six pages,
 * searching for something on page five while standing on page one answered
 *
 *     0 of 56 total
 *
 * which reads precisely like "it does not exist". That is how `samsung x` —
 * created successfully, genuinely in the catalogue — was reported missing.
 * Combined with #161 (nothing takes you to what you just created) it is how a
 * product manager concludes their work was lost.
 *
 * A second defect, found while fixing this one and worse than the ticket knew:
 * the catalogue's own `name` filter answered with rows that did not match.
 * Federated legacy offerings were appended to every list AFTER the query, so
 * they ignored the filter — searching for "samsung" came back with two
 * Heritage DSL lines. A search that invents matches is worse than one that
 * finds none.
 *
 * What this proves, end to end:
 *
 *  - FOUND, NOT ON THIS PAGE. A fixture offering is created with a name that
 *    sorts it away from page one, then searched for from page one. It must be
 *    found. This is the exact failure from the ticket.
 *  - THE COUNT TELLS THE TRUTH. On a searching list the result reads as a
 *    number of MATCHES, never "0 of 56 total".
 *  - A PAGE-FILTER LIST OWNS ITS LIMIT. A list that cannot search server-side
 *    says "no match on this page — N more pages not searched" rather than
 *    implying it looked everywhere.
 *  - SEARCH DOES NOT INVENT. `q` for a term only the legacy estate matches
 *    returns the legacy rows; `q` for a term only the native catalogue matches
 *    returns no legacy rows. The overlay answers the question that was asked.
 *  - EXACT STAYS EXACT. TMF630's `name` is still an exact match, so a machine
 *    integration that relied on it is unaffected, and asking for both at once
 *    is refused rather than silently resolved.
 *
 * HONEST LIMITS: `q` searches the NAME. Description, category and
 * characteristic search are not built, and this suite does not pretend they
 * are. Only the three catalogue lists search server-side; every other list
 * keeps page-local filtering and now says so.
 *
 * Everything this suite creates, it deletes.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = '/tmf-api/productCatalogManagement/v4';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
// 'zz' sorts last, so the fixture is never on page one of a list this size
const tag = `zzSearch${Date.now()}`;

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  const j = await r.json();
  if (!j.access_token) fail(`token(${user}) refused`);
  return j.access_token;
}
async function call(method, p, tok, body) {
  const r = await fetch(API + p, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}),
      'Cache-Control': 'no-cache' },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* a refusal need not be JSON */ }
  return { status: r.status, body: json, text };
}
async function created(method, p, tok, body) {
  const r = await call(method, p, tok, body);
  if (r.status !== 201 && r.status !== 200) fail(`${method} ${p}: ${r.status} ${r.text.slice(0, 200)}`);
  return r.body;
}

(async () => {
  const staff = await token('demo', 'demo');
  const made = [];
  let browser = null;
  try {
    const offering = await created('POST', `${CAT}/productOffering`, staff, {
      name: `${tag} Needle`, description: 'the thing the operator cannot find',
      lifecycleStatus: 'Active', version: '1.0', isBundle: false, isSellable: true });
    made.push([`${CAT}/productOffering`, offering.id]);

    /* ---------- the API: search finds, and does not invent ---------- */

    const q = async (term, extra = '') => {
      const r = await call('GET', `${CAT}/productOffering?limit=20&q=${encodeURIComponent(term)}${extra}`, staff);
      if (r.status !== 200) fail(`q=${term}: ${r.status} ${r.text.slice(0, 160)}`);
      return r.body;
    };

    const needle = await q(tag);
    if (!needle.some((o) => o.id === offering.id)) {
      fail('the catalogue search cannot find an offering that is in the catalogue');
    }
    ok(`q finds the fixture by a fragment of its name (${needle.length} row)`);

    const upper = await q(tag.toUpperCase());
    if (!upper.some((o) => o.id === offering.id)) fail('the search is case-sensitive, which no search box is');
    const middle = await q(tag.slice(2, 8));
    if (!middle.some((o) => o.id === offering.id)) fail('the search only matches a prefix, not a fragment');
    ok('it is case-insensitive and matches a fragment, not only a prefix');

    // the overlay seam: it must answer the question that was asked
    const legacyTerm = await q('heritage');
    const nativeTerm = await q(tag);
    const legacyRows = (rows) => rows.filter((o) => String(o.id).startsWith('legacy-'));
    if (!legacyRows(legacyTerm).length) fail('a term the legacy estate matches returned no legacy rows');
    if (legacyRows(nativeTerm).length) {
      fail('searching for a native-only term answered with legacy rows'
        + ' — the overlay is ignoring the filter, which is how "samsung" returned two Heritage DSL lines');
    }
    ok('the federated estate answers the search it matches, and stays out of the one it does not');

    // TMF630 is untouched
    const exact = await call('GET', `${CAT}/productOffering?limit=5&name=${encodeURIComponent(tag)}`, staff);
    if ((exact.body || []).length !== 0) fail('`name` stopped being an exact match — a machine integration would break');
    const both = await call('GET', `${CAT}/productOffering?limit=5&q=a&name=b`, staff);
    if (both.status !== 400) fail(`asking for q and name together returned ${both.status}, not a refusal`);
    ok('`name` is still exact, and asking for both at once is refused rather than guessed');

    /* ---------- the console: the box, and the count ---------- */

    browser = await chromium.launch();
    const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });
    const signIn = async () => {
      if (await page.locator('input[name="username"]').count()) {
        await page.fill('input[name="username"]', 'demo');
        await page.fill('input[name="password"]', 'demo');
        await page.click('input[type="submit"], button[type="submit"]');
      }
      await page.waitForSelector('#username', { timeout: 40000 });
    };
    const openTab = async (label) => {
      await page.evaluate(() => sessionStorage.removeItem('bss.console.tab')).catch(() => {});
      await page.goto(`${API}/console/`);
      await signIn();
      await page.locator('.tab', { hasText: label }).first().click();
      await page.waitForSelector('#listing-body tr', { timeout: 30000 });
    };
    await page.goto(`${API}/console/`);
    await page.waitForSelector('input[name="username"], #username', { timeout: 40000 });
    await signIn();

    // Product Offerings: a searching list, standing on page one.
    // The listing is ordered by ID, not by name, so a fixture cannot be placed
    // on a later page by naming it — the honest way to find a row that is NOT
    // on page one is to ask the API for page two and take one.
    const pageOne = (await call('GET', `${CAT}/productOffering?limit=20&offset=0`, staff)).body || [];
    const pageTwo = (await call('GET', `${CAT}/productOffering?limit=20&offset=20`, staff)).body || [];
    const firstPageIds = new Set(pageOne.map((o) => o.id));
    const offPageOne = pageTwo.find((o) => o.name && !firstPageIds.has(o.id)
      && pageOne.every((p) => !String(p.name || '').includes(o.name)));
    if (!offPageOne) fail('the catalogue is one page deep here — the defect needs a second page to exist');

    await openTab('Product Offerings');
    if (await page.locator('#listing-body tr', { hasText: offPageOne.name }).count()) {
      fail('the chosen row is on page one after all — this would prove nothing');
    }
    ok(`"${offPageOne.name}" is not on page one — the row the old box could never find`);

    await page.fill('#list-search', offPageOne.name);
    await page.waitForFunction((name) => {
      const b = document.getElementById('listing-body');
      return b && b.innerText.includes(name);
    }, offPageOne.name, { timeout: 25000 }).catch(() => {});
    if (!(await page.locator('#listing-body tr', { hasText: offPageOne.name }).count())) {
      fail('searching from page one did not reach an offering on a later page'
        + ' — this is the defect: it reads as "it does not exist"');
    }
    const total = await page.locator('#total').innerText();
    if (/^0 of /.test(total)) fail(`the count still reads "${total}", the sentence that means "it is not there"`);
    if (!/match/i.test(total)) fail(`the count reads "${total}" — a searching list should report matches`);
    ok(`found from page one, and the count reads "${total}"`);

    const placeholder = await page.locator('#list-search').getAttribute('placeholder');
    if (/this page/i.test(placeholder)) fail(`the box still promises "${placeholder}" while searching everything`);
    ok(`the box says "${placeholder}"`);

    // A list that CANNOT search server-side must own that in the result — but
    // only when there IS something it did not look at. On a single-page list
    // "0 of 8 total" is the truth: it searched everything there is. So the
    // assertion needs a page-filtering list that genuinely spans pages.
    let owned = null;
    for (const label of [/^CPQ rules$/, /^Audiences$/, /^Rules$/, /^Decisions$/]) {
      await openTab(label);
      // the same signal the operator has: is there a next page to go to?
      const pager = await page.locator('#page-info').innerText().catch(() => '');
      const multiPage = !(await page.locator('#next').isDisabled().catch(() => true));
      await page.fill('#list-search', 'zzzznothingmatchesthis');
      await page.waitForTimeout(800);
      const text = await page.locator('#total').innerText();
      if (!multiPage) {
        // one page: "0 of N total" is honest, and must NOT claim more pages
        if (/more page/.test(text)) fail(`a single-page list claims unsearched pages: "${text}"`);
        continue;
      }
      if (/^0 of /.test(text)) {
        fail(`a page-filtering list still says "${text}" — it means "not on this page" and says "not at all"`);
      }
      if (!/this page/i.test(text)) fail(`a multi-page page-filtering list reads "${text}" without owning its limit`);
      owned = `${text}  (pager: ${pager})`;
      break;
    }
    if (!owned) fail('no page-filtering list on this fleet spans more than one page — the rung cannot be proven here');
    ok(`a page-filtering list says "${owned}"`);

    console.log('\nPASS list_search_test — the box searches the catalogue, and owns it where it cannot');
  } finally {
    if (browser) await browser.close();
    for (const [path, id] of made.reverse()) {
      await call('DELETE', `${path}/${id}`, staff).catch(() => {});
    }
    console.log('fixtures removed');
  }
})().catch((e) => { console.error('\nFAIL ' + e.message); process.exit(1); });
