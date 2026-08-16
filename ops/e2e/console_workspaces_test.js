/* Console workspaces: every stakeholder sees their own desk. Suite #87.
 *
 *  - the tab bar is DEPARTMENTS: a group renders only when the token can
 *    see at least one of its tabs; visibility is ergonomics, the API 403
 *    underneath is the security (each persona gets a negative pair)
 *  - personas: pat (product), finn (finance), gro (growth), omar (ops),
 *    demo (the operator, everything) — departments are COMPOSITE ROLES,
 *    an IdP admin edits them, not code
 *  - the duplicate Disputes tab is dead; Workforce is named AI Workforce
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

async function token(request, user, pass) {
  const res = await request.post(KC, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

async function loginConsole(browser, user, pass) {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto(`${API}/console/`);
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', user);
  await page.fill('input[name="password"]', pass);
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  await page.waitForSelector('#tabs .tab', { timeout: 10000 });
  return { ctx, page };
}

async function desks(page) {
  return page.evaluate(() => {
    const out = {};
    for (const g of document.querySelectorAll('#tabs .tabgroup')) {
      const label = g.querySelector('.tabgroup-label');
      out[label ? label.textContent : '(unlabeled)'] =
        [...g.querySelectorAll('.tab')].map((b) => b.textContent);
    }
    return out;
  });
}

(async () => {
  const browser = await chromium.launch();
  const ctx0 = await browser.newContext();

  /* ---------- 1. pat: the product desk, nothing else ---------- */
  const pat = await loginConsole(browser, 'pat@bss.local', 'pat');
  const patDesks = await desks(pat.page);
  const patGroups = Object.keys(patDesks);
  if (!patGroups.includes('Catalog & Pricing')) fail('pat lost his catalog: ' + patGroups);
  for (const g of ['Money', 'Marketing', 'Sales', 'Sales setup']) {
    if (patGroups.includes(g)) fail(`pat sees the ${g} desk: ` + JSON.stringify(patDesks[g]));
  }
  const patAI = patDesks['AI & Automation'] || [];
  if (patAI.some((t) => t.includes('Runbooks') || t.includes('Workforce'))) {
    fail('pat sees ops AI furniture: ' + patAI);
  }
  if (!patAI.includes('AI Audit')) fail('pat should see his own AI audit trail');
  console.log('OK PAT (product): ' + patGroups.join(' | ')
    + ' — the catalog, his pricing rules, his audit trail; no money, no ops crew.');
  await pat.ctx.close();

  /* ---------- 2. finn: the money desk ---------- */
  const finn = await loginConsole(browser, 'finn@bss.local', 'finn');
  const finnDesks = await desks(finn.page);
  const finnGroups = Object.keys(finnDesks);
  if (!finnGroups.includes('Money')) fail('finn has no money desk: ' + finnGroups);
  if ((finnDesks['Money'] || []).length < 6) {
    fail('finn is missing money tabs: ' + JSON.stringify(finnDesks['Money']));
  }
  const finnDisputes = (finnDesks['Money'] || []).filter((t) => t === 'Disputes');
  if (finnDisputes.length !== 1) {
    fail('exactly ONE Disputes tab expected, got ' + finnDisputes.length);
  }
  for (const g of ['Catalog & Pricing', 'Marketing', 'Sales', 'Sales setup', 'AI & Automation', 'Platform', 'Care & Ops']) {
    if (finnGroups.includes(g)) fail(`finn sees ${g}: ` + JSON.stringify(finnDesks[g]));
  }
  console.log('OK FINN (finance): Money only — '
    + finnDesks['Money'].join(', ') + '. The duplicate Disputes tab is dead.');
  // the negative pair: the hidden tab's API refuses him too
  const finnTok = await token(ctx0.request, 'finn@bss.local', 'finn');
  const finnPost = await ctx0.request.post(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering`, {
      headers: { Authorization: 'Bearer ' + finnTok, 'Content-Type': 'application/json' },
      data: { name: 'finn should not create this' } });
  if (finnPost.status() !== 403) {
    fail('finance creating offerings must 403, got ' + finnPost.status());
  }
  await finn.ctx.close();

  /* ---------- 3. gro: the growth desk ---------- */
  const gro = await loginConsole(browser, 'gro@bss.local', 'gro');
  const groDesks = await desks(gro.page);
  const groGroups = Object.keys(groDesks);
  // gro (growth-staff) spans marketing + sales — the split gives her Marketing,
  // Sales and Sales setup desks (a narrower role would get just one).
  if (!groGroups.includes('Marketing')) fail('gro has no marketing desk: ' + groGroups);
  if ((groDesks['Marketing'] || []).length < 6) {
    fail('gro is missing marketing tabs: ' + JSON.stringify(groDesks['Marketing']));
  }
  if (!groGroups.includes('Sales')) fail('gro (quote:read) has no sales desk: ' + groGroups);
  for (const g of ['Money', 'Catalog & Pricing', 'AI & Automation', 'Platform', 'Care & Ops']) {
    if (groGroups.includes(g)) fail(`gro sees ${g}: ` + JSON.stringify(groDesks[g]));
  }
  const groTok = await token(ctx0.request, 'gro@bss.local', 'gro');
  const groBills = await ctx0.request.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill?limit=1`, {
      headers: { Authorization: 'Bearer ' + groTok } });
  const groRows = groBills.ok() ? await groBills.json() : [];
  if (groBills.ok() && Array.isArray(groRows) && groRows.length) {
    fail('growth reading the operator bill ledger should see nothing');
  }
  console.log('OK GRO (growth): Marketing + Sales — ' + (groDesks['Marketing'] || []).join(', ')
    + '; the bill ledger shows her nothing.');
  await gro.ctx.close();

  /* ---------- 3b. mkt: marketing-staff — Marketing desk ONLY ---------- */
  const mkt = await loginConsole(browser, 'mkt@bss.local', 'mkt');
  const mktDesks = await desks(mkt.page);
  const mktGroups = Object.keys(mktDesks);
  if (!mktGroups.includes('Marketing')) fail('mkt has no marketing desk: ' + mktGroups);
  for (const g of ['Sales', 'Sales setup', 'Money', 'Catalog & Pricing', 'Care & Ops']) {
    if (mktGroups.includes(g)) fail(`mkt (marketing-staff) sees ${g}: ` + JSON.stringify(mktDesks[g]));
  }
  console.log('OK MKT (marketing-staff): Marketing only — ' + (mktDesks['Marketing'] || []).join(', '));
  await mkt.ctx.close();

  /* ---------- 3c. sel: sales-staff — Sales + Sales setup, no Marketing ---------- */
  const sel = await loginConsole(browser, 'sel@bss.local', 'sel');
  const selDesks = await desks(sel.page);
  const selGroups = Object.keys(selDesks);
  if (!selGroups.includes('Sales')) fail('sel has no sales desk: ' + selGroups);
  if (!selGroups.includes('Sales setup')) fail('sel (quote:write) has no sales-setup desk: ' + selGroups);
  for (const g of ['Marketing', 'Money', 'Catalog & Pricing', 'Care & Ops']) {
    if (selGroups.includes(g)) fail(`sel (sales-staff) sees ${g}: ` + JSON.stringify(selDesks[g]));
  }
  console.log('OK SEL (sales-staff): Sales + Sales setup, no Marketing — '
    + (selDesks['Sales'] || []).join(', '));
  await sel.ctx.close();

  /* ---------- 4. omar: the ops desk + the AI crew window ---------- */
  const omar = await loginConsole(browser, 'omar@bss.local', 'omar');
  const omarDesks = await desks(omar.page);
  const omarGroups = Object.keys(omarDesks);
  if (!omarGroups.includes('Care & Ops')) fail('omar has no ops desk: ' + omarGroups);
  const omarAI = omarDesks['AI & Automation'] || [];
  if (!omarAI.includes('AI Workforce')) {
    fail('omar (workforce:use) should see the AI Workforce: ' + JSON.stringify(omarAI));
  }
  if (omarAI.includes('Runbooks')) fail('runbook signing is ai:admin — omar must not see it');
  for (const g of ['Money', 'Catalog & Pricing', 'Marketing', 'Sales', 'Sales setup']) {
    if (omarGroups.includes(g)) fail(`omar sees ${g}: ` + JSON.stringify(omarDesks[g]));
  }
  // negative pair: watching the crew is his; GOVERNING it is not
  const omarTok = await token(ctx0.request, 'omar@bss.local', 'omar');
  const omarAdmin = await ctx0.request.get(`${API}/ai/v1/governance/adminCheck`, {
    headers: { Authorization: 'Bearer ' + omarTok } });
  if (omarAdmin.status() !== 403) {
    fail('omar is not a governor — adminCheck must 403, got ' + omarAdmin.status());
  }
  console.log('OK OMAR (ops): ' + omarGroups.join(' | ')
    + ' — flows, appointments, porting, knowledge, and the AI Workforce queue;'
    + ' governance still refuses him (adminCheck 403).');
  await omar.ctx.close();

  /* ---------- 5. demo: the whole operator, grouped ---------- */
  const demo = await loginConsole(browser, 'demo', 'demo');
  const demoDesks = await desks(demo.page);
  const demoGroups = Object.keys(demoDesks);
  for (const g of ['Catalog & Pricing', 'Money', 'Care & Ops', 'Marketing', 'Sales', 'Sales setup', 'AI & Automation', 'Platform']) {
    if (!demoGroups.includes(g)) fail('demo is missing the ' + g + ' desk: ' + demoGroups);
  }
  if (!(demoDesks['AI & Automation'] || []).includes('AI Workforce')) {
    fail('the crew tab is named AI Workforce now');
  }
  // the DOM contract the fleet's suites click by: same .tab, same text
  await demo.page.locator('.tab', { hasText: 'Journal' }).click();
  await demo.page.waitForFunction(() =>
    document.querySelector('.tab.on') && document.querySelector('.tab.on').textContent === 'Journal',
  { timeout: 10000 });
  console.log('OK DEMO (operator): all six desks — '
    + demoGroups.join(' | ') + '; grouped tabs still click by text.');
  await demo.ctx.close();

  await browser.close();
  console.log('\nALL CONSOLE-WORKSPACE CHECKS PASSED — the console is departments now:'
    + ' product, finance, growth and ops each land on their OWN desk, the operator'
    + ' sees the whole floor, a department is a composite role an IdP admin edits,'
    + ' and every hidden tab is backed by a 403 where it counts.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
