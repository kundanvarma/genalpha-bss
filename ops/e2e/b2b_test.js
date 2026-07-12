/* B2B foundation: Acme's admin manages the company from the business console.
 * Setup (staff): create the Acme organization + link Bianca (business:admin)
 * to it. Then, IN THE BROWSER at /biz, Bianca: sees her org, adds an employee,
 * orders a plan for them, watches the line activate — and after the operator's
 * billing run, sees ONE consolidated Acme invoice carrying the employee's
 * charges. Boundaries proven: Bianca cannot order for a non-member. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const run = Date.now();

async function token(request, client, user, pass) {
  const res = await request.post(KC, {
    form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

  // --- setup as operator staff: org + admin membership (id pinned to her sub)
  const staff = await token(ctx.request, 'bss-demo', 'demo', 'demo');
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };
  const bianca = await token(ctx.request, 'bss-biz', 'bianca@acme.example', 'bianca');
  const biancaSub = JSON.parse(Buffer.from(bianca.split('.')[1], 'base64').toString()).sub;

  const org = await (await ctx.request.post(`${API}/tmf-api/party/v4/organization`, {
    headers: H, data: { name: `Acme AS ${run}`, tradingName: 'Acme' } })).json();
  const linked = await ctx.request.post(`${API}/tmf-api/party/v4/individual`, {
    headers: H, data: { id: biancaSub, givenName: 'Bianca', familyName: 'Boss',
      organization: { id: org.id } } });
  if (![200, 201].includes(linked.status())) fail('provisioning bianca failed: ' + linked.status());
  // idempotent create may return the existing row without org — patch to be sure
  await ctx.request.patch(`${API}/tmf-api/party/v4/individual/${biancaSub}`, {
    headers: H, data: { organization: { id: org.id } } });
  console.log('OK operator provisioned', `Acme AS ${run}`, '+ Bianca as its business admin');

  // --- boundary: Bianca cannot order for a stranger
  const stranger = await (await ctx.request.post(`${API}/tmf-api/party/v4/individual`, {
    headers: H, data: { givenName: 'Sven', familyName: `Stranger${run}` } })).json();
  const offers = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`, { headers: H })).json();
  const plan = offers.find((o) => (o.name || '').includes('Unlimited') && !o.isBundle);
  const BH = { Authorization: 'Bearer ' + bianca, 'Content-Type': 'application/json' };
  const crossOrg = await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: BH, data: { productOrderItem: [{ action: 'add', productOffering: { id: plan.id } }],
      relatedParty: [{ id: stranger.id, role: 'customer' }] } });
  if (crossOrg.status() === 201) fail('business admin ordered outside their org!');
  console.log('OK boundary holds: ordering for a non-member is refused ('
    + (await crossOrg.json()).message + ')');

  // --- the business console, in a real browser
  const page = await (await browser.newContext()).newPage();
  await page.goto(`${API}/biz/`);
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', 'bianca@acme.example');
  await page.fill('input[name="password"]', 'bianca');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  const orgName = await page.locator('#org-name').textContent();
  if (!orgName.includes('Acme')) fail('org name missing: ' + orgName);
  console.log('OK Bianca signed in to /biz — her organization:', orgName);

  // add an employee
  await page.fill('#new-given', 'Erik');
  await page.fill('#new-family', `Employee${run}`);
  await page.fill('#new-email', `erik-${run}@acme.example`);
  await page.click('#add-member');
  await page.locator('#member-status.ok').waitFor({ timeout: 15000 });
  await page.locator('.memberrow', { hasText: 'Erik' }).waitFor({ timeout: 15000 });
  console.log('OK Bianca added Erik to Acme (lands in Acme automatically, server-side)');

  // order a plan for Erik
  await page.locator('#order-member option', { hasText: 'Erik' }).waitFor({ state: 'attached', timeout: 10000 });
  await page.selectOption('#order-member', { label: `Erik Employee${run}` });
  await page.selectOption('#order-offering', { label: plan.name });
  await page.click('#place-order');
  await page.locator('#order-status.ok').waitFor({ timeout: 20000 });
  console.log('OK Bianca ordered', plan.name, 'for Erik from the business console');

  // the line activates and shows on Erik's row
  let lineShown = false;
  for (let i = 0; i < 25 && !lineShown; i++) {
    await page.waitForTimeout(2500);
    await page.reload();
    await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
    const row = page.locator('.memberrow', { hasText: 'Erik' });
    const txt = await row.textContent().catch(() => '');
    lineShown = /line/.test(txt) && /\+\d/.test(txt);
  }
  if (!lineShown) fail("Erik's line/number did not appear in the business console");
  console.log("OK Erik's line is live — number visible on his row");

  // --- operator billing run → ONE consolidated Acme invoice
  await ctx.request.post(`${API}/tmf-api/customerBillManagement/v4/billingRun`, { headers: H });
  const orgBills = await (await ctx.request.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill?relatedPartyId=${org.id}`,
    { headers: H })).json();
  if (orgBills.length !== 1) fail(`expected exactly 1 consolidated Acme bill, got ${orgBills.length}`);
  const erikBills = await (await ctx.request.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill?relatedPartyId=${
      (await (await ctx.request.get(`${API}/tmf-api/party/v4/individual?organizationId=${org.id}&limit=50`,
        { headers: H })).json()).find((p) => p.givenName === 'Erik').id}`,
    { headers: H })).json();
  if (erikBills.length) fail('Erik got a personal bill — should be consolidated under Acme');
  console.log('OK ONE consolidated invoice for Acme,', orgBills[0].amountDue.value,
    orgBills[0].amountDue.unit, '— and no personal bill for Erik');

  // and Bianca sees it in /biz
  await page.reload();
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  await page.locator('.billrow', { hasText: 'BILL-' }).first().waitFor({ timeout: 15000 });
  console.log('OK the consolidated invoice renders in the business console');

  await browser.close();
  console.log('\nALL B2B CHECKS PASSED — org, membership, boundary, order-for-member, consolidated invoice.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
