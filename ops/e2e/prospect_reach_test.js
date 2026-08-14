/* Prospects (#1): reach not-yet-customers — an Excel paste, a bought list, a
 * lead-form — with CONSENT as the hard gate.
 *   nova (real ESP): import a consented lead + a bought (unconsented) contact;
 *     a prospect audience yields ONLY the consented one; targeting the bought
 *     list's source yields NOBODY; the consented one actually gets an email;
 *     the bought one never does.
 *   genalpha (console): the Audience builder imports a list, flips to the
 *     Prospects population, and builds a lead-source audience — a few clicks.
 * This is the honest answer to "can this be our only marketing system for
 * prospects": yes — and it enforces the law instead of ignoring it.
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const CONSOLE = 'http://localhost:8080/console/';
const ESP = 'http://localhost:8121';
const run = Date.now();
const IMPORT = `${API}/insight/v1/prospect/import`;
const AUDIENCE = `${API}/insight/v1/audience`;
const MSG = `${API}/tmf-api/communicationManagement/v4/communicationMessage`;

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  /* ================= nova: consent gate + real ESP reach ================= */
  const nova = await token(ctx, 'nova', 'demo', 'demo');
  const goodEmail = `lead-${run}@nova.example`;   // our own captured, consented lead
  const boughtEmail = `bought-${run}@nova.example`; // a purchased list — no consent
  const ownSource = `own-form-${run}`;
  const boughtSource = `purchased-${run}`;

  const imp = await (await ctx.post(IMPORT, { headers: H(nova), data: { prospects: [
    { email: goodEmail, name: 'Ada Lead', source: ownSource, lawfulBasis: 'opt-in' },
    { email: boughtEmail, name: 'Cold Contact', source: boughtSource } ] } })).json();
  if (imp.reachable !== 1 || imp.heldUnconsented !== 1) {
    fail('import did not gate on consent: ' + JSON.stringify(imp));
  }
  console.log(`OK imported 2 prospects — ${imp.reachable} reachable (consented), ${imp.heldUnconsented} held (bought list, no basis)`);

  // an audience over the consented lead's source yields the lead
  const audGood = await (await ctx.post(AUDIENCE, { headers: H(nova), data: {
    name: `Own leads ${run}`, population: 'prospect',
    criteria: { all: [{ type: 'source', value: ownSource }] } } })).json();
  const goodMembers = await (await ctx.get(`${AUDIENCE}/${audGood.id}/members`, { headers: H(nova) })).json();
  if (!goodMembers.some((m) => m.email === goodEmail)) fail('the consented lead is not reachable: ' + JSON.stringify(goodMembers));

  // an audience over the BOUGHT list's source yields NOBODY — the gate holds
  const audBought = await (await ctx.post(AUDIENCE, { headers: H(nova), data: {
    name: `Bought list ${run}`, population: 'prospect',
    criteria: { all: [{ type: 'source', value: boughtSource }] } } })).json();
  const boughtMembers = await (await ctx.get(`${AUDIENCE}/${audBought.id}/members`, { headers: H(nova) })).json();
  if (boughtMembers.length !== 0) fail('a bought-list contact was reachable without consent: ' + JSON.stringify(boughtMembers));
  console.log('OK a bought list is captured but NOT reachable — targeting its source resolves to zero contacts');

  // reach the consented lead for real; never send to the bought one
  await ctx.post(MSG, { headers: H(nova), data: {
    toEmail: goodEmail, messageType: 'email', subject: 'A plan for you',
    content: 'Hi — here is an offer we think you will like.' } });
  let delivered = false;
  for (let i = 0; i < 20; i++) {
    const mails = await (await ctx.get(`${ESP}/mails?to=${goodEmail}`)).json();
    if (Array.isArray(mails) && mails.length) { delivered = true; break; }
    await sleep(1000);
  }
  if (!delivered) fail('the consented lead never received the outbound email');
  const boughtMails = await (await ctx.get(`${ESP}/mails?to=${boughtEmail}`)).json();
  if (Array.isArray(boughtMails) && boughtMails.length) fail('an email leaked to the bought (unconsented) contact');
  console.log('OK the consented lead got the email; the bought contact got nothing — owned-channel reach, consent-gated');

  /* ================= genalpha: the console builder ================= */
  const gStaff = await token(ctx, 'bss', 'demo', 'demo');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1200, height: 1200 } });
  await page.goto(CONSOLE);
  await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo'); await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  await page.locator('.tab', { hasText: 'Audience builder' }).click();
  await page.waitForSelector('[data-testid="audience-builder"]', { timeout: 10000 });

  // import a consented lead from the panel
  const cSource = `console-lead-${run}`;
  const cEmail = `clead-${run}@example.com`;
  await page.fill('[data-testid="prospect-emails"]', `${cEmail}, Console Lead`);
  await page.fill('[data-testid="prospect-source"]', cSource);
  await page.fill('[data-testid="prospect-basis"]', 'opt-in');
  await page.locator('[data-testid="prospect-import"]').click();
  await page.waitForFunction(() => /reachable/.test(document.querySelector('[data-testid="prospect-import-result"]')?.textContent || ''), { timeout: 10000 });
  console.log('OK the console imported a consented lead (result shows reachable count)');

  // flip to Prospects and build a lead-source audience
  const cAud = `Console prospects ${run}`;
  await page.selectOption('[data-testid="audience-population"]', 'prospect');
  await page.fill('#audience-name', cAud);
  const row = page.locator('[data-testid="aud-cond"]').first();
  await row.locator('[data-testid="aud-cond-type"]').selectOption('source');
  await row.locator('[data-testid="aud-cond-value"]').fill(cSource);
  await page.locator('[data-testid="aud-save"]').click();
  await page.waitForFunction((nm) => [...document.querySelectorAll('[data-testid="aud-row-name"]')].some((e) => e.textContent === nm), cAud, { timeout: 10000 });

  const saved = (await (await ctx.get(AUDIENCE, { headers: H(gStaff) })).json()).find((a) => a.name === cAud);
  if (!saved || saved.population !== 'prospect') fail('the UI did not save a prospect audience: ' + JSON.stringify(saved));
  const mem = await (await ctx.get(`${AUDIENCE}/${saved.id}/members`, { headers: H(gStaff) })).json();
  if (!mem.some((m) => m.email === cEmail)) fail('the console-built prospect audience did not resolve the imported lead');
  console.log('OK the builder flipped to Prospects and built a lead-source audience that resolves the imported lead');

  await browser.close();
  console.log('\nALL PROSPECT CHECKS PASSED — the stack can be the marketing team\'s only system for prospects: '
    + 'import a list (Excel/bought/social), it stamps consent, a bought list is captured but never messaged, and '
    + 'consented leads are reached on owned channels. Consent is the gate, enforced — not wished for.');
})();
