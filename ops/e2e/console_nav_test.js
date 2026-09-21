/* Console navigation hierarchy: groups in the page row, one verb per row,
 * health chips that filter. Back-office UX plan steps 3 and 4.
 *
 *  - Catalog & Pricing's page row shows FIVE group headings (CATALOG PRICING
 *    AVAILABILITY TOOLS LAUNCH) and is ONE line at 1440px wide — no wrap
 *  - Marketing and AI & Automation are grouped too; a flat department has
 *    no headings
 *  - a row has one visible verb, Open, which opens the editor; the rest
 *    lives behind "⋯": the lifecycle step, Edit, and Delete — Delete only
 *    while the offering is a draft (a live one is retired, never deleted)
 *  - the "drafts" chip filters the list to the drafts; a second click clears
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const CAT = '/tmf-api/productCatalogManagement/v4';
const run = Date.now();
// the draft this run creates — deleted on the way out, pass or fail
let cleanup = null;
const fail = async (m) => { console.error('FAIL: ' + m); if (cleanup) await cleanup().catch(() => {}); process.exit(1); };

async function findRow(page, text) {
  // rewind to page 1, then walk forward until the row shows
  for (let hop = 0; hop < 40 && !(await page.locator('#prev').isDisabled()); hop++) {
    await page.click('#prev');
    await page.waitForTimeout(250);
  }
  let row = page.locator('#listing-body tr', { hasText: text });
  for (let hop = 0; hop < 40 && !(await row.count()); hop++) {
    if (await page.locator('#next').isDisabled()) break;
    await page.click('#next');
    await page.waitForTimeout(350);
    row = page.locator('#listing-body tr', { hasText: text });
  }
  if (!(await row.count())) await fail(`row "${text}" not found on any page`);
  return row.first();
}

async function openDept(page, tabTitle) {
  await page.locator('#tabs .tab', { hasText: tabTitle }).first().click();
  await page.waitForSelector('#pagerow:not([hidden])', { timeout: 10000 });
  await page.waitForTimeout(300);
}

async function groupLabels(page) {
  return page.evaluate(() => [...document.querySelectorAll('#pagerow .pagegroup')].map((e) => e.textContent));
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  page.on('dialog', (d) => d.accept());

  /* ---------- login as the operator ---------- */
  await page.goto(`${API}/console/`);
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', 'demo');
  await page.fill('input[name="password"]', 'demo');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  await page.waitForSelector('#tabs .tab', { timeout: 10000 });

  /* ---------- 1. Catalog & Pricing: five groups, one line at 1440 ---------- */
  await openDept(page, 'Product Offerings');
  const labels = await groupLabels(page);
  const want = ['Catalog', 'Pricing', 'Availability', 'Tools', 'Launch'];
  if (JSON.stringify(labels) !== JSON.stringify(want)) await fail(`Catalog & Pricing groups: ${JSON.stringify(labels)}, expected ${JSON.stringify(want)}`);
  const geo = await page.evaluate(() => {
    const row = document.getElementById('pagerow');
    const tabs = [...row.querySelectorAll('.pagetab')];
    const tops = new Set(tabs.map((b) => Math.round(b.getBoundingClientRect().top)));
    return { height: row.clientHeight, lines: tops.size, width: row.clientWidth, scroll: row.scrollWidth,
      tab: tabs[0] ? tabs[0].getBoundingClientRect().height : 0, texts: tabs.map((b) => b.textContent) };
  });
  console.log(`  page row: ${geo.height}px tall, ${geo.lines} line(s), ${geo.width}px wide (content ${geo.scroll}px)`);
  if (geo.lines !== 1) await fail(`the Catalog & Pricing page row wraps onto ${geo.lines} lines at 1440px: ${geo.texts.join(' | ')}`);
  if (geo.height > geo.tab + 16) await fail(`the page row is ${geo.height}px tall for a ${geo.tab}px button — it wrapped`);
  if (geo.scroll > geo.width) await fail(`the page row overflows: ${geo.scroll}px of content in ${geo.width}px`);
  if (!geo.texts.includes('Offerings') || !geo.texts.includes('Prospect sim')) await fail('page buttons missing: ' + geo.texts.join(' | '));
  console.log('OK Catalog & Pricing: CATALOG · PRICING · AVAILABILITY · TOOLS · LAUNCH on one line');

  // the short label never renames the page: the rail and the crumb keep the full title
  const railHasFull = await page.locator('#tabs .tab', { hasText: 'Product Offerings' }).count();
  if (!railHasFull) await fail('the rail lost the full page title');

  /* ---------- 2. Marketing + AI grouped; a flat department has no headings ---------- */
  await openDept(page, 'Campaigns');
  const mk = await groupLabels(page);
  for (const g of ['Campaigns', 'Audiences', 'Content', 'Insights', 'Care', 'Brand & guardrails']) {
    if (!mk.includes(g)) await fail(`Marketing lacks the ${g} group: ${mk}`);
  }
  await openDept(page, 'Decisions');
  const ai = await groupLabels(page);
  if (JSON.stringify(ai) !== JSON.stringify(['Work', 'Decisions', 'Audit'])) await fail(`AI & Automation groups: ${ai}`);
  await openDept(page, 'Customer Bills');
  const money = await groupLabels(page);
  if (money.length) await fail(`Money is flat but shows groups: ${money}`);
  console.log('OK Marketing and AI & Automation grouped; Money stays flat');

  /* ---------- 3. a draft offering and a live one ---------- */
  const draftName = `Nav draft ${run}`;
  const draft = await page.evaluate(async ({ CAT, name }) => {
    const r = await authFetch(`${CAT}/productOffering`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, description: 'console_nav_test', lifecycleStatus: 'In design', version: '1.0' }) });
    return r.ok ? r.json() : { error: r.status };
  }, { CAT, name: draftName });
  if (!draft.id) await fail('could not create a draft offering: ' + JSON.stringify(draft));
  cleanup = () => page.evaluate(async ({ CAT, id }) => authFetch(`${CAT}/productOffering/${id}`, { method: 'DELETE' }), { CAT, id: draft.id });
  const live = await page.evaluate(async (CAT) => {
    const r = await authFetch(`${CAT}/productOffering?limit=100`);
    const all = await r.json();
    const o = all.find((x) => ['Active', 'Launched'].includes(x.lifecycleStatus) && x.name);
    return o ? { id: o.id, name: o.name } : null;
  }, CAT);
  if (!live) await fail('no Active offering in the catalog to compare against');

  await openDept(page, 'Product Offerings');
  await page.waitForSelector('#listing-body tr');

  /* ---------- 4. the row: Open + ⋯, nothing else ---------- */
  const draftRow = await findRow(page, draftName);
  const verbs = await draftRow.locator('.rowactions > button').allTextContents();
  if (JSON.stringify(verbs) !== JSON.stringify(['Open', '⋯'])) await fail(`row verbs: ${JSON.stringify(verbs)}, expected Open + ⋯`);
  await draftRow.locator('button', { hasText: 'Open' }).click();
  await page.waitForSelector('#editor.open', { timeout: 10000 });
  const editorTitle = await page.locator('#editor-title').textContent();
  if (!editorTitle.includes(draftName)) await fail(`Open did not load the row into the editor: "${editorTitle}"`);
  const loadedName = await page.locator('input[name="name"]').inputValue();
  if (loadedName !== draftName) await fail(`the editor holds "${loadedName}", not the opened row`);
  await page.keyboard.press('Escape');
  await page.waitForSelector('#editor:not(.open)', { timeout: 5000 });
  console.log('OK Open opens the editor with the row loaded');

  /* ---------- 5. ⋯ on a draft: the ladder step, Edit, Delete; on a live one: no Delete ---------- */
  await draftRow.locator('button.more').click();
  const draftMenu = draftRow.locator('.rowmenu:not([hidden])');
  await draftMenu.waitFor({ timeout: 5000 });
  const draftItems = await draftMenu.locator('button').allTextContents();
  if (!draftItems.includes('Delete')) await fail(`a draft's overflow lacks Delete: ${draftItems}`);
  if (!draftItems.includes('Edit')) await fail(`the overflow lacks Edit: ${draftItems}`);
  if (!draftItems.some((t) => t.startsWith('→ '))) await fail(`the overflow lacks the lifecycle step: ${draftItems}`);
  await page.keyboard.press('Escape');
  if (await draftMenu.count()) await fail('Escape did not close the overflow');
  // Edit from the overflow is the same door as Open
  await draftRow.locator('button.more').click();
  await draftRow.locator('.rowmenu button', { hasText: 'Edit' }).click();
  await page.waitForSelector('#editor.open', { timeout: 10000 });
  await page.keyboard.press('Escape');
  await page.waitForSelector('#editor:not(.open)', { timeout: 5000 });

  const liveRow = await findRow(page, live.name);
  await liveRow.locator('button.more').first().click();
  const liveMenu = liveRow.locator('.rowmenu:not([hidden])');
  await liveMenu.waitFor({ timeout: 5000 });
  const liveItems = await liveMenu.locator('button').allTextContents();
  if (liveItems.includes('Delete')) await fail(`a live offering's overflow offers Delete: ${liveItems}`);
  if (!liveItems.includes('→ Retired')) await fail(`a live offering's overflow lacks "→ Retired": ${liveItems}`);
  // outside click closes it
  await page.mouse.click(5, 5);
  if (await liveMenu.count()) await fail('an outside click did not close the overflow');
  console.log(`OK ⋯ : draft = ${draftItems.join(' / ')}; live "${live.name}" = ${liveItems.join(' / ')}`);

  /* ---------- 6. the drafts chip filters, and clears ---------- */
  await openDept(page, 'Product Offerings');
  await page.waitForSelector('#listing-body tr');
  const chip = page.locator('[data-testid="kpi"]', { hasText: 'drafts' });
  await chip.waitFor({ timeout: 10000 });
  const draftsCount = Number((await chip.locator('strong').textContent()).trim());
  if (draftsCount < 1) await fail('the drafts chip counts nothing although a draft was just created');
  const before = await page.evaluate(() => [...document.querySelectorAll('#listing-body tr')].filter((t) => !t.hidden).length);
  await chip.click();
  if (!(await chip.evaluate((e) => e.classList.contains('on')))) await fail('the chip did not switch on');
  const after = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#listing-body tr[data-id]')];
    return { shown: rows.filter((t) => !t.hidden).length, hidden: rows.filter((t) => t.hidden).length,
      shownStatuses: rows.filter((t) => !t.hidden).map((t) => t.children[1].textContent) };
  });
  if (after.shown === before && after.hidden === 0 && before > 1) await fail('clicking the drafts chip hid nothing');
  for (const s of after.shownStatuses) {
    if (!['In study', 'In design', 'In test'].includes(s)) await fail(`a non-draft row stayed visible under the drafts filter: ${s}`);
  }
  await chip.click();
  if (await chip.evaluate((e) => e.classList.contains('on'))) await fail('a second click did not clear the chip');
  const cleared = await page.evaluate(() => [...document.querySelectorAll('#listing-body tr[data-id]')].filter((t) => t.hidden).length);
  if (cleared) await fail(`${cleared} rows stayed hidden after clearing the filter`);
  console.log(`OK drafts chip: ${before} rows → ${after.shown} draft(s) shown, ${after.hidden} hidden → all back`);

  /* ---------- 7. Delete from the overflow removes the draft ---------- */
  const again = await findRow(page, draftName);
  await again.locator('button.more').click();
  await again.locator('.rowmenu button', { hasText: 'Delete' }).click();
  let gone = false;
  for (let i = 0; i < 10 && !gone; i++) {
    await page.waitForTimeout(500);
    gone = await page.evaluate(async ({ CAT, id }) => (await authFetch(`${CAT}/productOffering/${id}`)).status === 404, { CAT, id: draft.id });
  }
  if (!gone) await fail('Delete from the overflow did not remove the draft');
  cleanup = null;
  console.log('OK Delete behind ⋯ removed the draft');

  await browser.close();
  console.log('PASS console_nav_test');
})().catch(async (e) => { console.error(e); if (cleanup) await cleanup().catch(() => {}); process.exit(1); });
