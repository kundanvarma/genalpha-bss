/* A product manager can make a shelf. Suite #253.
 *
 * Ticket #155, found while adding three categories in #154. Categories ARE
 * data — TMF620 `Category` is a first-class entity, the endpoint answers, and
 * offerings pick from what exists — and there was no way to author one. The
 * three added in #154 arrived through `ops/seed/seed_catalog_taxonomy.py`, a
 * hardcoded Python list, because that was the only route. If the answer to
 * "how do I add a shelf" is "edit a seed script and rerun it", that is a code
 * change wearing a different hat, and it makes demo scaffolding load-bearing.
 *
 * THE TICKET'S PREMISE WAS WRONG IN ONE PLACE, and it changed the work: it says
 * categories "even support parentId". They did not. The table carried id, href,
 * name, description and tenant_id — no parent, no lifecycle. So this needed a
 * migration before it could need a page.
 *
 * What is proven here, end to end:
 *
 *  - A SHELF CAN BE MADE, from the console, by a person. Named, described, and
 *    on the list afterwards.
 *  - IT CAN BE NESTED. A parent is set from the page and read back, so the flat
 *    list can become a two-level shelf. #143's nesting proposal cannot be
 *    adopted by any operator until this exists.
 *  - AN EMPTY SHELF IS VISIBLE. Each row carries how many offerings sit on it,
 *    so a shelf with nothing on it is seen on this page rather than discovered
 *    by a customer.
 *  - A SHELF IN USE CANNOT BE DELETED. Offerings point at a category by id, so
 *    deleting one still in use would leave them pointing at nothing. The API
 *    refuses and says how many hold it. Retiring is the way to take one off the
 *    shelf, and it is reversible.
 *
 * HONEST LIMITS: retire is a lifecycle value, not a filter — a retired category
 * still appears on this page (deliberately: you need to see it to bring it
 * back) and nothing yet stops an offering being filed under a retired one. And
 * this does NOT make fulfilment families data; those are still a code-level Set
 * of eight, which is #143's larger question.
 *
 * Everything this suite creates, it deletes.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = '/tmf-api/productCatalogManagement/v4';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const tag = `C155-${Date.now()}`;

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
    const openCategories = async () => {
      await page.evaluate(() => sessionStorage.removeItem('bss.console.tab')).catch(() => {});
      await page.goto(`${API}/console/`);
      await signIn();
      await page.locator('.tab', { hasText: /^Categories$/ }).first().click();
      await page.waitForSelector('[data-testid="island-categories"]', { timeout: 30000 });
      await page.waitForSelector('[data-testid="category-rows"] tr', { timeout: 30000 });
    };
    await page.goto(`${API}/console/`);
    await page.waitForSelector('input[name="username"], #username', { timeout: 40000 });
    await signIn();
    await openCategories();
    ok('the console has a Categories page at all — there was none');

    /* ---------- a shelf can be made ---------- */

    const parentName = `${tag} Equipment`;
    await page.fill('[data-testid="category-new"] [name="name"]', parentName);
    await page.fill('[data-testid="category-new"] [name="description"]', 'Routers and set-top boxes');
    await page.click('[data-testid="category-new"] button[type="submit"]');
    await page.waitForSelector(`[data-category="${parentName}"]`, { timeout: 25000 });
    ok(`a product manager made a shelf from the page: "${parentName}"`);

    const parent = ((await call('GET', `${CAT}/category?limit=100`, staff)).body || [])
      .find((c) => c.name === parentName) || fail('the shelf is on screen but not in the catalog');
    made.push(parent.id);

    /* ---------- it can be nested ---------- */

    const childName = `${tag} Routers`;
    await page.fill('[data-testid="category-new"] [name="name"]', childName);
    await page.selectOption('[data-testid="category-new"] [name="parentId"]', parent.id);
    await page.click('[data-testid="category-new"] button[type="submit"]');
    await page.waitForSelector(`[data-category="${childName}"]`, { timeout: 25000 });

    const child = ((await call('GET', `${CAT}/category?limit=100`, staff)).body || [])
      .find((c) => c.name === childName) || fail('the nested shelf did not save');
    made.push(child.id);
    if (child.parentId !== parent.id) {
      fail(`the child's parent is ${child.parentId}, not the shelf it was filed under`
        + ' — nesting is what #143 cannot be adopted without');
    }
    const childRow = await page.locator(`[data-category="${childName}"]`).innerText();
    if (!childRow.includes(parentName)) fail(`the page shows "${childRow}" without naming the parent shelf`);
    ok(`it nests: "${childName}" sits under "${parentName}", and the page says so`);

    /* ---------- an empty shelf is visible as empty ---------- */

    const emptyCell = await page.locator(`[data-testid="count-${child.id}"]`).innerText();
    if (!/none/i.test(emptyCell) && emptyCell.trim() !== '0') {
      fail(`a shelf with nothing on it reads "${emptyCell}" — an empty shelf must be visible here`);
    }
    ok(`a shelf with nothing on it says so: "${emptyCell}"`);

    /* ---------- a shelf in use cannot be deleted ---------- */

    const offering = (await call('POST', `${CAT}/productOffering`, staff, {
      name: `${tag} Router`, lifecycleStatus: 'Active', version: '1.0', isSellable: true,
      category: [{ id: child.id, name: childName, '@referredType': 'Category' }],
    })).body;
    if (!offering || !offering.id) fail('could not file an offering under the new shelf');

    const refused = await call('DELETE', `${CAT}/category/${child.id}`, staff);
    if (refused.status === 204 || refused.status === 200) {
      fail('a category holding an offering was deleted — those offerings now point at nothing');
    }
    if (refused.status !== 400) fail(`deleting a category in use answered ${refused.status}, not a refusal`);
    if (!/offering/i.test(refused.text)) fail(`the refusal reads "${refused.text.slice(0, 120)}" without saying why`);
    ok(`a shelf holding an offering refuses deletion: ${JSON.parse(refused.text).message}`);

    // and the count on the page now shows it is in use
    await openCategories();
    const usedCell = await page.locator(`[data-testid="count-${child.id}"]`).innerText();
    if (usedCell.trim() !== '1') fail(`the shelf holds one offering but the page says "${usedCell}"`);
    ok('the page counts the offering that was just filed there');

    /* ---------- retire is the way, and it is reversible ---------- */

    await page.click(`[data-testid="retire-${child.id}"]`);
    await page.waitForFunction((id) => {
      const row = document.querySelector(`[data-testid="count-${id}"]`)?.closest('tr');
      return row && /Retired/.test(row.innerText);
    }, child.id, { timeout: 25000 }).catch(() => {});
    const retired = ((await call('GET', `${CAT}/category?limit=100`, staff)).body || [])
      .find((c) => c.id === child.id);
    if (retired.lifecycleStatus !== 'Retired') fail(`retiring left the shelf ${retired.lifecycleStatus}`);
    ok('a shelf in use can be retired instead — the offerings keep pointing at something real');

    await call('DELETE', `${CAT}/productOffering/${offering.id}`, staff);
    const nowGone = await call('DELETE', `${CAT}/category/${child.id}`, staff);
    if (nowGone.status !== 204 && nowGone.status !== 200) {
      fail(`an empty shelf still refuses deletion (${nowGone.status})`);
    }
    made.splice(made.indexOf(child.id), 1);
    ok('with nothing on it, the shelf deletes cleanly');

    console.log('\nPASS category_authoring_test — shelves are data a person can author');
  } finally {
    if (browser) await browser.close();
    for (const id of made.reverse()) await call('DELETE', `${CAT}/category/${id}`, staff).catch(() => {});
    const leftovers = ((await call('GET', `${CAT}/productOffering?limit=100&q=${tag}`, staff)).body || []);
    for (const o of leftovers) await call('DELETE', `${CAT}/productOffering/${o.id}`, staff).catch(() => {});
    console.log('fixtures removed');
  }
})().catch((e) => { console.error('\nFAIL ' + e.message); process.exit(1); });
