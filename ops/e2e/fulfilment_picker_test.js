/* The product manager picks a fulfilment pattern by name; the copilot proposes one. Suite #231.
 *
 * Catalog-to-provisioning step 3, ticket 8a. The chain under a product spec
 * (CFS → RFS → resource spec) is authored in the catalog; this proves a
 * product manager can operate it without seeing a seam or an RFS:
 *
 *  - PICK: on the Product Specification form the Fulfilment picker lists the
 *    tenant's customer-facing services by name with the family in words, and
 *    spells out what each needs; picking "Billing-only product" and saving
 *    routes the change through the governed action assignFulfilmentPattern —
 *    the spec's TMF620 serviceSpecification[0] is that CFS, the page shows the
 *    receipt, and the action's own reply carries a decision id.
 *  - REFUSED: pointing a spec at a resource-facing service is refused by the
 *    action's precondition with a reason in words — the API cannot be talked
 *    into the wrong layer either.
 *  - PROPOSED: the product copilot's proposal for a mobile plan names the
 *    Mobile line pattern and says the product lacks a charging plan.
 *
 * Fixtures are timestamp-named and deleted at the end.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = '/tmf-api/productCatalogManagement/v4';
const SCAT = '/tmf-api/serviceCatalogManagement/v4';
const CONSOLE = 'http://localhost:8080/console/';
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
    headers: { Authorization: `Bearer ${tok}`, ...(body ? { 'Content-Type': 'application/json' } : {}), 'Cache-Control': 'no-cache' },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}

(async () => {
  const staff = await token('demo', 'demo');
  const cfss = (await call('GET', `${SCAT}/serviceSpecification?serviceType=CFS&limit=100`, staff)).body || [];
  // TV entitlement is a seeded CFS with no RFS — the "nothing from the network" case a product manager should read in words
  const tv = cfss.find((c) => c.name === 'TV entitlement');
  const mobile = cfss.find((c) => c.name === 'Mobile line');
  if (!tv || !mobile) fail('the seeded CFS are missing — run seed_service_specifications and seed_resource_facing_services first');
  const spec = (await call('POST', `${CAT}/productSpecification`, staff, { name: `${tag} picker spec`, brand: 'GenAlpha', lifecycleStatus: 'Active' })).body;
  if (!spec || !spec.id) fail('could not create the fixture spec');

  /* ---------- PICK, through the console ---------- */
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  page.on('pageerror', (e) => console.log('PAGE ERROR:', e.message));
  await page.goto(CONSOLE);
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#username', { timeout: 20000 });
  await page.locator('.tab', { hasText: 'Product Specifications' }).first().click();
  await page.waitForSelector('#listing-body tr', { timeout: 15000 });
  let row = page.locator('#listing-body tr', { hasText: `${tag} picker spec` });
  for (let hop = 0; hop < 40 && !(await row.count()); hop++) {
    if (await page.locator('#next').isDisabled()) break;
    await page.click('#next'); await page.waitForTimeout(300);
    row = page.locator('#listing-body tr', { hasText: `${tag} picker spec` });
  }
  if (!(await row.count())) fail('the fixture spec is not in the Product Specifications list');
  await row.locator('button', { hasText: 'Open' }).click();
  const select = page.locator('select[name="serviceSpecification"]');
  await select.waitFor({ timeout: 10000 });
  await page.waitForFunction(() => document.querySelectorAll('select[name="serviceSpecification"] option').length > 2, null, { timeout: 15000 });
  const labels = await select.locator('option').allTextContents();
  if (!labels.some((l) => l.includes('Mobile line') && l.includes('a network line'))) fail(`the picker does not speak in patterns and words: ${labels.join(' | ')}`);
  if (labels.some((l) => /seam|RFS|vendor/i.test(l))) fail('the picker leaks technical vocabulary');
  await select.selectOption({ label: labels.find((l) => l.startsWith('TV entitlement')) });
  await page.waitForFunction(() => /realised inside this BSS|only bill/.test(document.querySelector('[data-testid="fulfilment-consequences"]')?.textContent || ''), null, { timeout: 10000 });
  const consequences = await page.locator('[data-testid="fulfilment-consequences"]').textContent();
  ok(`PICKER: ${labels.length - 1} patterns by name; TV entitlement says "${consequences.trim()}"`);
  await select.selectOption({ label: labels.find((l) => l.startsWith('Mobile line')) });
  await page.waitForFunction(() => /Number assignment/.test(document.querySelector('[data-testid="fulfilment-consequences"]')?.textContent || ''), null, { timeout: 15000 });
  const mobileWords = await page.locator('[data-testid="fulfilment-consequences"]').textContent();
  if (!/always/.test(mobileWords) || !/only when the product carries/.test(mobileWords)) fail(`Mobile line consequences lack the required/optional words: ${mobileWords}`);
  ok(`CONSEQUENCES: "${mobileWords.trim().slice(0, 140)}…"`);
  await select.selectOption({ label: labels.find((l) => l.startsWith('TV entitlement')) });
  await select.scrollIntoViewIfNeeded();
  await page.screenshot({ path: `${require('os').tmpdir()}/fulfilment-picker-${run}.png` });
  await page.click('#save');
  await page.locator('[data-testid="fulfilment-receipt"]').waitFor({ timeout: 15000 });
  const receiptLine = await page.locator('[data-testid="fulfilment-receipt"]').textContent();
  if (!/TV entitlement/.test(receiptLine) || !/receipt/.test(receiptLine)) fail(`receipt line wrong: ${receiptLine}`);
  const after = (await call('GET', `${CAT}/productSpecification/${spec.id}`, staff)).body;
  if (((after.serviceSpecification || [])[0] || {}).id !== tv.id) fail(`the spec does not name the CFS after save: ${JSON.stringify(after.serviceSpecification)}`);
  ok(`GOVERNED: saved through assignFulfilmentPattern — "${receiptLine.trim()}"; TMF620 serviceSpecification[0] = ${after.serviceSpecification[0].name}`);
  await browser.close();

  /* ---------- the action itself, and its refusal ---------- */
  const exec = await call('POST', '/ontology/v1/actions/assignFulfilmentPattern/execute', staff, { inputs: { specId: spec.id, cfsId: mobile.id } });
  if (exec.status !== 200 || !exec.body?.done || !exec.body?.decisionId) fail(`execute: ${exec.status} ${exec.text.slice(0, 300)}`);
  ok(`RECEIPT: the action returns done=true with decision ${String(exec.body.decisionId).slice(0, 8)}…`);
  const rfs = ((await call('GET', `${SCAT}/serviceSpecification?serviceType=RFS&limit=100`, staff)).body || [])[0];
  if (rfs) {
    const refused = await call('POST', '/ontology/v1/actions/assignFulfilmentPattern/execute', staff, { inputs: { specId: spec.id, cfsId: rfs.id } });
    if (refused.status !== 422 || !/customer-facing/.test(refused.text)) fail(`pointing a spec at an RFS should be refused with a reason: ${refused.status} ${refused.text.slice(0, 200)}`);
    ok(`REFUSED: an RFS is not a pattern — "${(refused.body?.refusal || refused.body?.check?.refusal || '').slice(0, 120)}"`);
  }

  /* ---------- PROPOSED, by the copilot ---------- */
  const reply = await call('POST', '/ai/v1/productCopilot', staff, { messages: [{ role: 'user', content: 'create a 50 GB plan with a Samsung discount' }] });
  if (reply.status !== 200 || reply.body?.kind !== 'proposal') fail(`copilot: ${reply.status} ${reply.text.slice(0, 200)}`);
  const specs = reply.body.proposal?.specs || [];
  const fp = specs[0]?.fulfilmentPattern;
  if (!fp || fp.cfsName !== 'Mobile line') fail(`the proposal does not name the Mobile line pattern: ${JSON.stringify(specs[0])}`);
  if (!(fp.missingConsumed || []).some((m) => m.characteristic === 'chargingSpecId')) fail(`the proposal does not warn about the missing charging plan: ${JSON.stringify(fp)}`);
  ok(`PROPOSED: copilot → ${fp.cfsName} (${fp.reason}); warns: ${fp.missingConsumed.map((m) => m.effect).join('; ')}`);

  /* ---------- cleanup ---------- */
  await call('DELETE', `${CAT}/productSpecification/${spec.id}`, staff);
  console.log('\nALL FULFILMENT-PICKER CHECKS PASSED — a product manager picks the pattern by name and reads its consequences in words,'
    + ' the change is a governed action with a receipt, the wrong layer is refused, and the copilot proposes the pattern with what the product lacks.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
