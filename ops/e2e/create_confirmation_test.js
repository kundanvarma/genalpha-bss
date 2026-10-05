/* Making something says so, and gives you a way back to it. Suite #252.
 *
 * Ticket #161. Create an offering, press Create, and the drawer closed onto a
 * list that had not moved. The new row was nowhere on screen — the list is not
 * newest-first, so it lands wherever it sorts, possibly pages away — and there
 * was no confirmation, no link and no highlight. An operator who had just made
 * something was left with no signal that anything had happened at all.
 *
 * `samsung x` was created, Active and saved correctly, into a list of 56
 * offerings over six pages. With the search box then only filtering the loaded
 * page (#160), both of the operator's natural next moves returned nothing, and
 * the work was reported lost.
 *
 * This is the third instance of one shape — THE SYSTEM DID THE RIGHT THING AND
 * FAILED TO SHOW IT — after a diagnosis rendering at the foot of a list (#142)
 * and a rules panel 830px below where anyone looks (#158). So the fix lives in
 * the shared save path, and this suite proves it there: the same confirmation
 * must appear for a resource that is not the catalogue.
 *
 * What is proven, in a real browser:
 *
 *  - IT SAYS SO. Creating an offering leaves a confirmation naming the thing
 *    that was created.
 *  - THERE IS A WAY BACK. The confirmation carries an action that opens the new
 *    row directly — it must work even when the row is NOT on the loaded page,
 *    which is the case the ticket is about.
 *  - IT IS SHARED, NOT PER PAGE. The same confirmation appears when a rule is
 *    created on the Platform desk.
 *  - IT DOES NOT FOLLOW YOU. Moving to another page clears it: what was created
 *    on one desk is not news on the next.
 *  - EDITING IS NOT CREATING. Saving a change to an existing row says nothing,
 *    because nothing new appeared for the operator to go and find.
 *
 * HONEST LIMITS: this is option 1 of the ticket's three — say it happened, with
 * a way back. The list is still not newest-first and the new row is still not
 * highlighted in place; those change the default view for everyone and were
 * deliberately not taken. The confirmation stays until you leave the page: it
 * does not fade, because a notice that fades is the same bug in slow motion.
 *
 * Everything this suite creates, it deletes.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = '/tmf-api/productCatalogManagement/v4';
const POL = '/tmf-api/policyManagement/v4';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const tag = `R161-${Date.now()}`;

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

(async () => {
  const staff = await token('demo', 'demo');
  const made = [];
  let browser = null;
  try {
    browser = await chromium.launch();
    const page = await browser.newPage({ viewport: { width: 1600, height: 1200 } });
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

    /* ---------- an offering: the case from the ticket ---------- */

    await openTab('Product Offerings');
    if (await page.locator('[data-testid="created-note"]').count()) {
      fail('a confirmation is on screen before anything was created');
    }
    await page.click('#new-button');
    await page.waitForSelector('[name="name"]', { timeout: 20000 });
    const offeringName = `${tag} Needle`;
    await page.fill('[name="name"]', offeringName);
    await page.selectOption('[name="lifecycleStatus"]', 'Active').catch(() => {});
    await page.click('#save');

    const note = page.locator('[data-testid="created-note"]');
    await note.waitFor({ timeout: 25000 }).catch(() => {});
    if (!(await note.count())) {
      fail('creating an offering said nothing — the drawer closed onto an unchanged list, which is the defect');
    }
    const text = await note.innerText();
    if (!text.includes(offeringName)) fail(`the confirmation reads "${text}" without naming what was created`);
    ok(`creating an offering says: "${text.replace(/\n/g, ' ')}"`);

    // it must be reachable even though the list did not move to it
    const onPage = await page.locator('#listing-body tr', { hasText: offeringName }).count();
    await page.click('[data-testid="open-created"]');
    await page.waitForSelector('[name="name"]', { timeout: 20000 });
    const openedName = await page.locator('[name="name"]').first().inputValue();
    if (openedName !== offeringName) {
      fail(`"Open it" opened "${openedName}" instead of the thing just created`);
    }
    ok(`"Open it" opens it${onPage ? '' : ' — and the row was NOT on the loaded page, which is the case that failed'}`);

    const createdId = ((await call('GET', `${CAT}/productOffering?limit=100&q=${encodeURIComponent(tag)}`, staff)).body || [])
      .find((o) => o.name === offeringName);
    if (createdId) made.push([`${CAT}/productOffering`, createdId.id]);

    /* ---------- editing is not creating ---------- */

    await page.fill('[name="description"]', 'edited by the suite').catch(() => {});
    await page.click('#save');
    await page.waitForTimeout(2500);
    if (await page.locator('[data-testid="created-note"]').count()) {
      fail('saving an EDIT announced a creation — nothing new appeared to go and find');
    }
    ok('saving a change to an existing row says nothing, because nothing new appeared');

    /* ---------- the same thing on another desk: it is shared, not per page ---------- */

    await openTab(/^Rules$/);
    await page.click('#new-button');
    await page.waitForSelector('[data-field="ruleKind"]', { timeout: 20000 });
    const ruleName = `${tag} rule`;
    await page.fill('[name="name"]', ruleName);
    await page.selectOption('[name="ruleKind"]', 'quantity-cap');
    await page.waitForTimeout(400);
    await page.fill('[name="message"]', ruleName);
    await page.click('#save');
    const ruleNote = page.locator('[data-testid="created-note"]');
    await ruleNote.waitFor({ timeout: 25000 }).catch(() => {});
    if (!(await ruleNote.count())) {
      fail('creating a RULE said nothing — the fix is per page, not in the shared save path');
    }
    if (!(await ruleNote.innerText()).includes(ruleName)) fail('the rule confirmation does not name the rule');
    ok('the same confirmation appears on another desk — it lives in the shared save path');

    const rule = ((await call('GET', `${POL}/policyRule?limit=100`, staff)).body || [])
      .find((r) => r.name === ruleName);
    if (rule) made.push([`${POL}/policyRule`, rule.id]);

    /* ---------- it does not follow you ---------- */

    await openTab('Product Offerings');
    if (await page.locator('[data-testid="created-note"]').count()) {
      fail('the confirmation followed the operator to another page — stale news presented as current');
    }
    ok('moving to another page clears it');

    console.log('\nPASS create_confirmation_test — making something says so, and you can get back to it');
  } finally {
    if (browser) await browser.close();
    for (const [path, id] of made.reverse()) {
      await call('DELETE', `${path}/${id}`, staff).catch(() => {});
    }
    console.log('fixtures removed');
  }
})().catch((e) => { console.error('\nFAIL ' + e.message); process.exit(1); });
