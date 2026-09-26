/* The rules an offering is priced and blocked by, read back on the offering. Suite #245.
 *
 * Ticket #157. A rule attaches to an offering through its own Item field, so the
 * attachment is one-way: open the offering and nothing told you a rule pointed at
 * it. A product manager could put 50 NOK off a phone and then find no trace of it
 * on the phone. This suite proves the reverse read, and proves it is the SECOND
 * of the ticket's two options — the one the teaser could not give:
 *
 *  - WHY THE TEASER WAS NOT ENOUGH: two rules name the offering, one pricing and
 *    one blocking. The anonymous teaser sees one of them (enabled pricing only);
 *    the offering's own read sees both.
 *  - THE CONSTRAINT THAT MATTERS: the new read is NOT the shop window. Anonymous
 *    is 401, a signed-in caller without policy:read is 403 — the same 403 the
 *    rules page's own list gives that caller — while the teaser stays open at
 *    200, unchanged. A negotiated company rate cannot leak through this door.
 *  - NO TERMS, ONLY NAMES: the read carries no `condition`. Which rules touch the
 *    offering, never on what terms; the rules page still owns the terms.
 *  - THE BLIND SPOT, OWNED: the rule form shows its Item field for ONE rule kind,
 *    so a negotiated company rate discounts this offering without ever naming it.
 *    Neither read can list it — and the panel says that on the page, in both its
 *    full and its empty state, rather than implying the list is every rule.
 *  - THE PANEL: the offering form lists both rules by NAME with what each does in
 *    words and its state, carries no identifier, and owns what it cannot see.
 *  - THE FORM STILL SAVES: a new field on a form is one save away from breaking
 *    it — Save changes round-trips the offering untouched and writes no panel.
 *  - THE POINT OF OPTION B: the pricing rule is DISABLED and the panel still
 *    lists it, marked "Switched off" — while the teaser now sees nothing at all.
 *    That is the read the ticket exists for and the one option A cannot give.
 *  - THE WAY TO THE RULE: "Open rule" lands on the Rules page, on that rule.
 *  - NOTHING TO SHOW: with the rules gone the panel says so in a sentence.
 *
 * Everything this suite creates, it deletes.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = '/tmf-api/productCatalogManagement/v4';
const POL = '/tmf-api/policyManagement/v4';
const REFERENCING = `${POL}/policyRule/referencing`;
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const run = Date.now();
const tag = `SUITE${run}`;

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
  const made = [];   // [path, id] — deleted at the end whatever happens above
  let browser = null;
  try {
    /* ---------- a fixture offering and the two rules that name it ---------- */
    const price = await created('POST', `${CAT}/productOfferingPrice`, staff, { name: `${tag} monthly`,
      priceType: 'recurring', price: { unit: 'NOK', value: 399 }, recurringChargePeriodType: 'month',
      recurringChargePeriodLength: 1, lifecycleStatus: 'Active', version: '1.0' });
    made.push([`${CAT}/productOfferingPrice`, price.id]);
    const offering = await created('POST', `${CAT}/productOffering`, staff, { name: `${tag} Rule-read-back phone`,
      description: 'suite fixture', lifecycleStatus: 'In design', version: '1.0', isBundle: false, isSellable: true,
      productOfferingPrice: [{ id: price.id, href: price.href, name: price.name, '@referredType': 'ProductOfferingPrice' }] });
    made.push([`${CAT}/productOffering`, offering.id]);

    // a PRICING rule, authored exactly as the console's "Price: discount when the
    // cart has an item" writes it — the id lives inside the condition
    const discount = await created('POST', `${POL}/policyRule`, staff, {
      name: `${tag} Web purchase discount`, domain: 'pricing', effect: 'adjust', priority: 10, enabled: true,
      condition: JSON.stringify({ in: [offering.id, { var: 'offeringIds' }] }),
      adjustmentType: 'amount', adjustmentValue: -50, message: `Get 50 NOK off the ${tag} phone in our webshop.` });
    made.push([`${POL}/policyRule`, discount.id]);
    // a BLOCKING rule — invisible to the teaser at any state, which is half of
    // why the ticket chose option B
    const cap = await created('POST', `${POL}/policyRule`, staff, {
      name: `${tag} Two per household`, domain: 'order', effect: 'deny', priority: 20, enabled: true,
      condition: JSON.stringify({ '>': [{ var: `quantityByOffering.${offering.id}` }, 2] }),
      message: 'You can order at most two of these.' });
    made.push([`${POL}/policyRule`, cap.id]);
    // A LIVE PRICING RULE THAT NAMES NO OFFERING. The rule form only shows its Item
    // field for "Price: discount when the cart has an item"; a negotiated company
    // rate is conditioned on the COMPANY, so it discounts this offering's price
    // without ever mentioning it. Nothing can list it on the offering, and the
    // panel has to say so rather than imply the list is everything. Its condition
    // names an organization that does not exist, so it moves no real price.
    const companyRate = await created('POST', `${POL}/policyRule`, staff, {
      name: `${tag} Negotiated company rate`, domain: 'pricing', effect: 'adjust', priority: 30, enabled: true,
      condition: JSON.stringify({ '==': [{ var: 'organizationId' }, `${tag}-no-such-org`] }),
      adjustmentType: 'percent', adjustmentValue: -20, message: 'Negotiated rate for one company.' });
    made.push([`${POL}/policyRule`, companyRate.id]);
    ok(`AUTHORED: an offering with a 50 NOK pricing rule and a two-per-household blocking rule naming it,`
      + ` beside a live company rate that discounts it without naming it`);

    /* ---------- why the teaser was not enough ---------- */
    const teasers = (await call('GET', `${POL}/price/teaser?offeringId=${offering.id}`, null)).body || [];
    if (teasers.length !== 1 || !teasers[0].name.includes('Web purchase discount')) {
      fail(`the teaser should see the one enabled pricing rule, saw ${JSON.stringify(teasers)}`);
    }
    let seen = (await call('GET', `${REFERENCING}?offeringId=${offering.id}`, staff)).body || [];
    if (seen.length !== 2) fail(`the offering's own read should see both rules, saw ${JSON.stringify(seen)}`);
    const byName = (n) => seen.find((r) => r.name.includes(n));
    if (!byName('Web purchase discount') || !byName('Two per household')) fail(`both rules by name: ${JSON.stringify(seen)}`);
    if (byName('Two per household').effect !== 'deny') fail('the blocking rule should say it denies');
    if (Number(byName('Web purchase discount').adjustmentValue) !== -50) fail('the adjustment should come through as -50');
    ok(`WHY THE TEASER WAS NOT ENOUGH: it sees 1 rule (enabled pricing only); the offering's read sees 2, blocking included`);

    /* ---------- the blind spot, and it is the read's own, not a bug ---------- */
    if (seen.some((r) => r.name.includes('Negotiated company rate'))) {
      fail('a rule that never names the offering cannot honestly be listed on it');
    }
    if (teasers.some((t) => t.name.includes('Negotiated company rate'))) fail('the company rate must not reach the shop window either');
    ok(`THE BLIND SPOT: a live company rate discounts this offering without naming it, so neither read lists it — the panel has to say so`);

    /* ---------- the constraint that matters: this door is not the shop window ---------- */
    const anon = await call('GET', `${REFERENCING}?offeringId=${offering.id}`, null);
    if (anon.status !== 401) fail(`anonymous must be refused, got ${anon.status}`);
    const anonTeaser = await call('GET', `${POL}/price/teaser?offeringId=${offering.id}`, null);
    if (anonTeaser.status !== 200) fail(`the teaser must stay anonymous and open, got ${anonTeaser.status}`);
    const noRuleRole = await token('agent-anna', 'agent');            // service:*/porting:* — no policy:read
    const gated = await call('GET', `${REFERENCING}?offeringId=${offering.id}`, noRuleRole);
    const rulesPage = await call('GET', `${POL}/policyRule?limit=1`, noRuleRole);
    if (gated.status !== 403) fail(`a caller without policy:read must get 403, got ${gated.status}`);
    if (rulesPage.status !== gated.status) {
      fail(`the panel's gate must be the rules page's gate: page ${rulesPage.status} vs panel ${gated.status}`);
    }
    ok(`THE GATE: anonymous 401, a caller without policy:read 403 — the same ${rulesPage.status} the rules page gives them — while the teaser stays 200 anonymous`);

    /* ---------- names, never terms ---------- */
    if (seen.some((r) => 'condition' in r)) fail(`the read must not carry the condition: ${JSON.stringify(seen[0])}`);
    if (seen.some((r) => typeof r.enabled !== 'boolean')) fail('every row must carry its enabled state');
    ok(`NO TERMS, ONLY NAMES: each row carries name, effect, adjustment, state and href — and no condition`);

    /* ---------- the panel ---------- */
    browser = await chromium.launch();
    const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });
    await page.goto(`${API}/console/`);
    // a fresh context always meets Keycloak: wait for the field, do not test for it
    await page.waitForSelector('input[name="username"]', { timeout: 30000 });
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
    await page.waitForSelector('#username', { timeout: 30000 });

    const openTheOffering = async () => {
      // The form is a DRAWER over the listing: with the previous one still sliding,
      // the row's Open button is never "visible, enabled and stable" and the click
      // times out. A reload is the honest reset — drawer shut, page 1, no filter —
      // and it proves the panel renders on a cold load too. The session cookie
      // means Keycloak usually waves us through; handle it when it does not.
      // The console remembers its last tab in sessionStorage, so after "Open rule"
      // a reload boots onto the Platform desk and the Catalog page is not even in
      // the tab row. Forget the tab and every open starts from the same place.
      await page.evaluate(() => sessionStorage.removeItem('bss.console.tab')).catch(() => { /* still on the IdP */ });
      await page.goto(`${API}/console/`);
      if (await page.locator('input[name="username"]').count()) {
        await page.fill('input[name="username"]', 'demo');
        await page.fill('input[name="password"]', 'demo');
        await page.click('input[type="submit"], button[type="submit"]');
      }
      await page.waitForSelector('#username', { timeout: 30000 });
      await page.locator('.tab', { hasText: 'Product Offerings' }).first().click();
      await page.waitForSelector('#listing-body tr', { timeout: 20000 });
      // 55 offerings at 20 a page: page until the fixture shows. Each hop WAITS FOR
      // THE ROWS TO TURN OVER — a fixed pause let the previous page's DOM answer
      // "is it here?", and the late fetch then pulled the row out from under the click
      const rows = () => page.locator('#listing-body tr', { hasText: offering.name });
      for (let hop = 0; hop < 30 && !(await rows().count()); hop++) {
        if (await page.locator('#next').isDisabled()) break;
        const before = await page.locator('#listing-body').innerText();
        await page.click('#next');
        await page.waitForFunction((prev) => (document.getElementById('listing-body')?.innerText || '') !== prev,
          before, { timeout: 20000 }).catch(() => { /* an identical page is still a page */ });
      }
      if (!(await rows().count())) fail('the fixture offering is not in the listing');
      await rows().first().locator('[data-testid="row-open"]').click();
      const panel = page.locator('[data-testid="offering-rules"]');
      await panel.waitFor({ timeout: 20000 });
      await page.waitForFunction(() => {
        const p = document.querySelector('[data-testid="offering-rules"]');
        return p && !/appear here|read right now|Save the offering/.test(p.innerText);
      }, null, { timeout: 20000 });
      await panel.scrollIntoViewIfNeeded();
      return panel;
    };

    let panel = await openTheOffering();
    let text = (await panel.innerText()).trim();
    if (!text.includes('Web purchase discount')) fail(`the panel does not name the pricing rule:\n${text}`);
    if (!text.includes('Two per household')) fail(`the panel does not name the blocking rule:\n${text}`);
    if (!/Changes the price/.test(text) || !/a flat 50 off/.test(text)) fail(`the panel does not say the adjustment in words:\n${text}`);
    // a rule stores a bare number: the panel must not mint a currency for it
    if (/\b(EUR|NOK|USD|GBP)\b/.test(text.replace(/“[^”]*”/g, ''))) fail(`the panel invented a currency the rule does not carry:\n${text}`);
    if (!/Blocks the order/.test(text)) fail(`the panel does not say the blocking rule blocks:\n${text}`);
    if (!/Live/.test(text)) fail(`the panel does not say the rules are live:\n${text}`);
    if (text.includes('Negotiated company rate')) fail(`the panel listed a rule that never names this offering:\n${text}`);
    // the limit is on the page, not only in a document nobody opens
    if (!/Only rules that name this offering/.test(text) || !/without naming one/.test(text)) {
      fail(`the panel does not own its blind spot — a reader would think this list is every rule:\n${text}`);
    }
    if (/[0-9a-f]{8}-[0-9a-f]{4}-/.test(text)) fail(`an identifier leaked onto the panel:\n${text}`);
    if (await panel.locator('input, select, textarea').count()) fail('the panel is a window, not a second editor — it must carry no input');
    ok(`THE PANEL: "${text.split('\n').filter((l) => /discount|household/.test(l)).join(' · ')}" — no identifier, no input`);

    /* ---------- a new field on a form is a save away from breaking it ---------- */
    // The panel is a field like any other as far as the editor is concerned; if it
    // ever returned a value from get(), that value would be POSTed as part of the
    // offering. Press the form's own Save and check the offering came through it
    // unchanged — and that nothing named after the panel was written to it.
    await page.locator('#save').click();
    await page.waitForFunction(() => !document.getElementById('editor')?.classList.contains('open'),
      null, { timeout: 20000 }).catch(() => { /* asserted on the stored row below */ });
    await page.waitForTimeout(1200);
    const saved = (await call('GET', `${CAT}/productOffering/${offering.id}`, staff)).body || {};
    if (saved.name !== offering.name) fail(`saving the form changed the offering's name: ${saved.name}`);
    if (saved.lifecycleStatus !== offering.lifecycleStatus) fail(`saving changed the lifecycle: ${saved.lifecycleStatus}`);
    if ('offeringRules' in saved) fail('the read-only panel wrote itself into the offering');
    if ((saved.productOfferingPrice || []).length !== 1) fail('saving the form lost the offering’s price');
    ok(`THE FORM STILL SAVES: Save changes round-trips the offering untouched, and the panel writes nothing of its own`);

    /* ---------- the point of option B: disabled, and still listed ---------- */
    // exactly what the Rules page's own row action does
    await created('PATCH', `${POL}/policyRule/${discount.id}`, staff, { enabled: false });
    const teasersOff = (await call('GET', `${POL}/price/teaser?offeringId=${offering.id}`, null)).body || [];
    if (teasersOff.length !== 0) fail(`a disabled rule should vanish from the teaser, it still shows ${JSON.stringify(teasersOff)}`);
    seen = (await call('GET', `${REFERENCING}?offeringId=${offering.id}`, staff)).body || [];
    if (seen.length !== 2) fail(`the offering's read must keep a disabled rule, saw ${JSON.stringify(seen)}`);
    if (byName('Web purchase discount').enabled !== false) fail('the disabled rule must report enabled:false');

    panel = await openTheOffering();
    text = (await panel.innerText()).trim();
    if (!text.includes('Web purchase discount')) fail(`a disabled rule must stay listed:\n${text}`);
    if (!/Switched off/.test(text)) fail(`the disabled rule is not marked as switched off:\n${text}`);
    const offRow = panel.locator('[data-testid="offering-rule"][data-enabled="false"]');
    if ((await offRow.count()) !== 1) fail('exactly one row should be marked disabled');
    if (!(await offRow.innerText()).includes('Web purchase discount')) fail('the wrong row is marked disabled');
    const shot = `/tmp/offering-rules-panel-${run}.png`;
    await page.screenshot({ path: shot });
    ok(`OPTION B: the rule is switched off — the teaser now shows nothing, the panel still lists it, marked "Switched off" (screenshot ${shot})`);

    /* ---------- the way to the rule ---------- */
    await offRow.locator('button', { hasText: 'Open rule' }).click();
    await page.waitForFunction(() => /Rules/.test(document.getElementById('crumb')?.textContent || ''), null, { timeout: 15000 });
    // the crumb turns over synchronously but the rows are a fetch away: waiting for
    // "#listing-body tr" would be satisfied by the offerings still standing there
    await page.waitForFunction((name) => (document.getElementById('listing-body')?.innerText || '').includes(name),
      `${tag} Web purchase discount`, { timeout: 20000 });
    const crumb = await page.locator('#crumb').innerText();
    const landed = await page.locator('#listing-body').innerText();
    if (!landed.includes('Web purchase discount')) fail(`"Open rule" did not land on the rule:\n${landed.slice(0, 400)}`);
    if (landed.includes('Two per household')) fail('the Rules page should be narrowed to the rule that was clicked');
    ok(`THE WAY TO THE RULE: "Open rule" lands on "${crumb}", narrowed to that one rule`);

    /* ---------- nothing to show says so, and says what it cannot see ---------- */
    for (const id of [discount.id, cap.id]) await call('DELETE', `${POL}/policyRule/${id}`, staff);
    panel = await openTheOffering();
    text = (await panel.innerText()).trim();
    if (await panel.locator('[data-testid="offering-rule"]').count()) fail('the rules were deleted; the panel still lists one');
    if (!/No rule names this offering/.test(text)) fail(`an offering with no rules must say so plainly:\n${text}`);
    // the company rate is still live and still discounts this offering — so an
    // empty panel saying "no rule prices this offering" would be a lie
    if (!/without naming one/.test(text)) fail(`the empty panel must still own what it cannot see:\n${text}`);
    ok(`NOTHING TO SHOW: "${text.split('\n')[0]}" — and it still says a company deal or a campaign applies without naming one`);
  } finally {
    /* ---------- clean up whatever we made, in reverse ---------- */
    if (browser) await browser.close();
    for (const [p, id] of made.reverse()) await call('DELETE', `${p}/${id}`, staff);
    // four suites used to leak their fixtures: sweep by the name this run chose,
    // so a failure above still leaves the tenant as it was found
    for (const [listPath, itemPath] of [[`${POL}/policyRule?limit=200`, `${POL}/policyRule`],
      [`${CAT}/productOffering?limit=100`, `${CAT}/productOffering`],
      [`${CAT}/productOfferingPrice?limit=100`, `${CAT}/productOfferingPrice`]]) {
      // a cleanup block that throws leaves more behind than one that does nothing:
      // a paging cap or a refusal answers an object, not a list
      const page = (await call('GET', listPath, staff)).body;
      if (!Array.isArray(page)) { console.error(`WARNING: cannot sweep ${itemPath} — ${JSON.stringify(page).slice(0, 120)}`); continue; }
      const strays = page.filter((x) => x && (x.name || '').includes(tag));
      for (const x of strays) await call('DELETE', `${itemPath}/${x.id}`, staff);
      if (strays.length) console.error(`WARNING: ${strays.length} stray ${itemPath} row(s) needed a second sweep`);
    }
  }

  console.log('\nALL OFFERING-RULES CHECKS PASSED — an offering now says which rules name it, pricing and blocking, live and'
    + ' switched off, in words and with the way to each rule; it says plainly when none do; it owns on the page the rules it'
    + ' cannot see, because only one rule kind carries an Item; the read is gated exactly as the rules page is, the anonymous'
    + ' shop window is untouched, and the panel carries no identifier and no second editor.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
