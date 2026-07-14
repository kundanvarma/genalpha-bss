/* Knowledge base (component #32): articles and FAQs as DATA, one library,
 * every channel — WHO you are decides WHAT you see.
 *
 *  - customers search the FAQ on the shop's Support page (customer shelf only)
 *  - CSRs get the cheat-sheets on top, plus ✨Ask: a grounded AI answer with
 *    sources, retrieved with the ASKER's own token
 *  - product owners get the how-to-build-products library in the console
 *  - authors publish from the console; drafts stay invisible to readers
 *  - tenants are walled: nova's library is nova's
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();

async function token(request, realm, client, user, pass) {
  const res = await request.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const KB = `${API}/tmf-api/knowledgeManagement/v4/article`;
  const staff = await token(ctx.request, 'bss', 'bss-demo', 'demo', 'demo');
  const paula = await token(ctx.request, 'bss', 'bss-biz', 'paula@family.example', 'paula');
  const anna = await token(ctx.request, 'bss', 'bss-csr', 'agent-anna', 'agent');
  const pat = await token(ctx.request, 'bss', 'bss-demo', 'pat@bss.local', 'pat');
  const search = async (tok, q) => (await (await ctx.request.get(
    `${KB}?q=${encodeURIComponent(q)}`, { headers: H(tok) })).json());

  /* ---------- audiences: the same search, three different shelves ---------- */
  const paulaHits = await search(paula, 'gift');
  if (!paulaHits.some((a) => a.id === 'faq-gift-data')) fail('customer FAQ missing for Paula');
  if (paulaHits.some((a) => !['customer', 'all'].includes(a.audience))) {
    fail('a non-customer article leaked to a customer: ' + JSON.stringify(paulaHits.map((a) => a.id)));
  }
  const annaHits = await search(anna, 'gift');
  if (!annaHits.some((a) => a.id === 'csr-gift-rollover-rules')) fail('CSR cheat-sheet missing for Anna');
  if (annaHits.some((a) => a.audience === 'productOwner')) fail('the product-owner shelf leaked to a CSR');
  const patHits = await search(pat, 'create product spec offering');
  if (!patHits.some((a) => a.id === 'po-create-product')) {
    fail('the product-owner how-to missing for pat: ' + JSON.stringify(patHits.map((a) => a.id)));
  }
  console.log('OK one search, three shelves: Paula sees FAQs, Anna adds cheat-sheets,'
    + ' pat gets the how-to-build-products library');

  /* ---------- authoring: publish live, drafts stay backstage ---------- */
  const created = await (await ctx.request.post(KB, { headers: H(staff), data: {
    title: `eSIM activation ${run}`, audience: 'customer', category: 'SIM & device',
    tags: 'esim,activation', status: 'published',
    body: 'Order an eSIM from the shop and scan the QR code we send to your inbox.' } })).json();
  const draft = await (await ctx.request.post(KB, { headers: H(staff), data: {
    title: `Draft pricing note ${run}`, audience: 'customer', status: 'draft',
    body: 'Not ready for customers yet.' } })).json();
  const paulaSees = await search(paula, 'esim activation');
  if (!paulaSees.some((a) => a.id === created.id)) fail('published article not live for customers');
  if ((await search(paula, 'draft pricing note')).some((a) => a.id === draft.id)) {
    fail('a DRAFT leaked to a customer');
  }
  if (!(await search(staff, 'draft pricing note')).some((a) => a.id === draft.id)) {
    fail('the author cannot see their own draft');
  }
  console.log('OK publish is live at once; drafts show only to authors');

  /* ---------- tenant wall ---------- */
  const novaStaff = await token(ctx.request, 'nova', 'bss-demo', 'demo', 'demo');
  const novaHits = await (await ctx.request.get(
    'http://shop.nova.localhost:8080/tmf-api/knowledgeManagement/v4/article?q=gift',
    { headers: H(novaStaff) })).json();
  if (novaHits.length !== 0) fail('genalpha articles leaked into nova: ' + novaHits.length);
  console.log('OK the tenant wall: nova\'s library starts empty — genalpha\'s answers are its own');

  /* ---------- storefront: the FAQ shelf on Support ---------- */
  const shop = await (await browser.newContext()).newPage();
  await shop.goto(`${API}/shop/`);
  await shop.locator('.who >> text=Sign in').click();
  await shop.waitForSelector('input[name="username"]', { timeout: 20000 });
  await shop.fill('input[name="username"]', 'paula@family.example');
  await shop.fill('input[name="password"]', 'paula');
  await shop.click('input[type="submit"], button[type="submit"]');
  await shop.waitForSelector('.nav', { timeout: 20000 });
  await shop.click('.nav >> text=Support');
  await shop.locator('[data-testid=faq-card]').waitFor({ timeout: 20000 });
  await shop.fill('[data-testid=faq-search]', 'gift');
  await shop.click('[data-testid=faq-go]');
  await shop.locator('[data-testid=faq-faq-gift-data]').waitFor({ timeout: 15000 });
  await shop.click('[data-testid=faq-faq-gift-data]');
  const faqBody = await shop.locator('[data-testid=faq-body]').textContent();
  if (!faqBody.includes('Gift data')) fail('FAQ body did not render: ' + faqBody.slice(0, 120));
  if (await shop.locator('[data-testid=faq-csr-gift-rollover-rules]').count()) {
    fail('the CSR shelf leaked into the shop');
  }
  console.log('OK the shop\'s Support page answers first: FAQ search, expandable answers,'
    + ' customer shelf only');

  /* ---------- CSR console: search + the grounded ✨Ask ---------- */
  const csr = await (await browser.newContext()).newPage();
  await csr.goto(`${API}/csr/`);
  await csr.waitForSelector('input[name="username"]', { timeout: 20000 });
  await csr.fill('input[name="username"]', 'agent-anna');
  await csr.fill('input[name="password"]', 'agent');
  await csr.click('input[type="submit"], button[type="submit"]');
  await csr.waitForSelector('.nav', { timeout: 20000 });
  await csr.click('.nav >> text=Knowledge');
  await csr.locator('[data-testid=kb-search]').waitFor({ timeout: 20000 });
  await csr.fill('[data-testid=kb-search]', 'held order approval');
  await csr.click('[data-testid=kb-go]');
  await csr.locator('[data-testid=kb-article-csr-held-orders]').waitFor({ timeout: 15000 });
  await csr.click('[data-testid=kb-article-csr-held-orders]');
  await csr.locator('[data-testid=kb-body]').waitFor({ timeout: 5000 });
  await csr.fill('[data-testid=kb-search]', 'can a child gift data?');
  await csr.click('[data-testid=kb-ask]');
  await csr.locator('[data-testid=kb-answer]').waitFor({ timeout: 30000 });
  const answer = await csr.locator('[data-testid=kb-answer]').textContent();
  const sources = await csr.locator('[data-testid=kb-sources]').textContent();
  if (!answer.includes('According to')) fail('the ask answer is not grounded: ' + answer.slice(0, 120));
  if (!sources.includes('gifting and rollover')) fail('sources not named: ' + sources);
  console.log('OK the agent asked in plain words and got a grounded answer WITH sources —'
    + ' retrieved as herself, answered by the AI seam');

  /* ---------- console: the authoring tab, gated by knowledge:write ---------- */
  const con = await (await browser.newContext()).newPage();
  await con.goto(`${API}/console/`);
  await con.waitForSelector('input[name="username"]', { timeout: 20000 });
  await con.fill('input[name="username"]', 'pat@bss.local');
  await con.fill('input[name="password"]', 'pat');
  await con.click('input[type="submit"], button[type="submit"]');
  await con.waitForSelector('.tabs', { timeout: 20000 });
  const knowledgeTab = con.locator('.tabs >> text=Knowledge');
  if (!(await knowledgeTab.count())) fail('the Knowledge tab is missing for a product owner');
  await knowledgeTab.click();
  await con.locator('td', { hasText: 'How to create a product' }).first().waitFor({ timeout: 15000 });
  console.log('OK the console has the Knowledge tab — pat opens the how-to library where'
    + ' the products are built');

  // cleanup the throwaway articles
  await ctx.request.delete(`${KB}/${created.id}`, { headers: H(staff) });
  await ctx.request.delete(`${KB}/${draft.id}`, { headers: H(staff) });

  await browser.close();
  console.log('\nALL KNOWLEDGE CHECKS PASSED — one library, audience shelves, live'
    + ' publishing with backstage drafts, tenant walls, FAQ in the shop, grounded'
    + ' ask-with-sources at the CSR desk, how-tos in the console.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
