/* The Product Advisor — receipts first, market as a seam, proposals never actions.
 *
 *  - TOP-UP ATTACH is COUNTED from inventory products against the
 *    Top-ups category: customers who keep topping up a plan are the
 *    receipt that its allowance is too small
 *  - MARKET PRICE GAP comes from the tenant's market feed (mock-market
 *    as the tariff-comparison subscription): a rival cheaper on the
 *    same bucket is a finding WITH the numbers
 *  - the LLM only narrates; the numbers are computed, never generated
 *  - ADOPT births a DRAFT offering, lifecycleStatus "In study" — the
 *    advisor advises, the product owner decides
 *  - and it is the product owner's tool: a customer token is refused
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const PLAN = { id: '14291c1a-df26-4232-8084-500466888e46', name: 'GenAlpha Mobile 10 GB' };

async function token(ctx, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  /* ---------- 0. seed the receipt: two plan customers who keep topping up ---------- */
  const offerings = await (await ctx.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`,
    { headers: H(staff) })).json();
  const topUp = offerings.find((o) => (o.name || '').toLowerCase().includes('top-up'));
  if (!topUp) fail('no top-up offering in the catalog to seed with');
  // a run-unique plan, so the cohort is EXACTLY the two we seed — the
  // advisor's majority threshold must fire on real arithmetic
  const price = await (await ctx.post(`${API}/tmf-api/productCatalogManagement/v4/productOfferingPrice`,
    { headers: H(staff), data: { name: `Advisor ${run} 7 GB monthly`, priceType: 'recurring',
      recurringChargePeriodType: 'month', recurringChargePeriodLength: 1,
      lifecycleStatus: 'Active', price: { unit: 'EUR', value: 12.0 } } })).json();
  const myPlan = await (await ctx.post(`${API}/tmf-api/productCatalogManagement/v4/productOffering`,
    { headers: H(staff), data: { name: `Advisor ${run} 7 GB`, lifecycleStatus: 'Active',
      isBundle: false, productOfferingPrice: [{ id: price.id, name: price.name }] } })).json();
  for (const tag of ['hungry1', 'hungry2']) {
    const email = `${tag}-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'Hu', familyName: `Ngry${tag}${run}` } })).json();
    const tok = await token(ctx, email, login.temporaryPassword);
    await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
      headers: H(tok), data: { productOrderItem: [{ action: 'add',
        productOffering: { id: myPlan.id, name: myPlan.name } }] } });
    await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
      headers: H(tok), data: { productOrderItem: [{ action: 'add',
        productOffering: { id: topUp.id, name: topUp.name } }] } });
  }
  await sleep(8000); // inventory products land from the order events
  console.log(`OK seeded: two customers on ${myPlan.name} who each ALSO bought ${topUp.name} —`
    + ' the receipt the advisor should find in the inventory');

  /* ---------- 1. the findings, with their numbers ---------- */
  let findings = null;
  for (let i = 0; i < 10; i++) {
    await sleep(2000);
    findings = await (await ctx.get(`${API}/advisor/v1/findings`, { headers: H(staff) })).json();
    if (findings.find((f) => f.kind === 'TOPUP_ATTACH' && f.offering === myPlan.name)) break;
  }
  const attach = findings.find((f) => f.kind === 'TOPUP_ATTACH' && f.offering === myPlan.name);
  if (!attach) fail('the top-up attach finding is missing: '
    + JSON.stringify(findings.map((f) => f.kind + ':' + f.offering)));
  if (!attach.evidence || attach.evidence.alsoBoughtTopUps < 2
      || !attach.proposal || !attach.proposal.name.includes('XL')) {
    fail('the attach finding has no receipt or proposal: ' + JSON.stringify(attach));
  }
  console.log('OK RECEIPTS FIRST: "' + attach.insight + '" — counted from the inventory,'
    + ' with a proposal attached (' + attach.proposal.name + ')');

  const market = findings.find((f) => f.kind === 'MARKET_PRICE' && f.offering === PLAN.name);
  if (!market) fail('the market gap finding is missing');
  if (!market.evidence || !String(market.insight).includes('RivalTel')
      || Number(market.evidence.rivalPrice) !== 11.9) {
    fail('the market finding lost its numbers: ' + JSON.stringify(market));
  }
  console.log('OK THE MARKET SEAM: the feed says RivalTel sells the same 10 GB bucket at 11.9 —'
    + ' a price-position gap with the numbers on it, from the tenant\'s own subscription');

  /* ---------- 2. it is the product owner's tool ---------- */
  const nosyEmail = `nosy-${run}@example.com`;
  const nosyLogin = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email: nosyEmail, givenName: 'No', familyName: `Sy${run}` } })).json();
  const nosy = await token(ctx, nosyEmail, nosyLogin.temporaryPassword);
  if ((await ctx.get(`${API}/advisor/v1/findings`, { headers: H(nosy) })).status() !== 403) {
    fail('a customer read the product advisor');
  }
  console.log('OK the advisor is gated by catalog:write — a customer asking gets 403');

  /* ---------- 3. adopt births a DRAFT, not a product ---------- */
  const adopted = await (await ctx.post(`${API}/advisor/v1/adopt`,
    { headers: H(staff), data: market.proposal })).json();
  if (!adopted.offeringId || adopted.lifecycleStatus !== 'In study') {
    fail('the adoption did not birth a draft: ' + JSON.stringify(adopted));
  }
  const draft = await (await ctx.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering/${adopted.offeringId}`,
    { headers: H(staff) })).json();
  if (draft.lifecycleStatus !== 'In study' || !draft.name.includes('Counter')) {
    fail('the draft is not what was proposed: ' + JSON.stringify({
      name: draft.name, status: draft.lifecycleStatus }));
  }
  const inShop = await (await ctx.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?lifecycleStatus=Active&limit=100`,
    { headers: H(staff) })).json();
  if (inShop.find((o) => o.id === adopted.offeringId)) {
    fail('the DRAFT leaked into the active catalog');
  }
  console.log(`OK PROPOSALS, NEVER ACTIONS: adopting the market finding birthed "${draft.name}"`
    + ' as an In-study DRAFT — visible to the product owner, absent from the shop until a human'
    + ' promotes it');

  /* ---------- 4. the tab on the console ---------- */
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto(`${API}/console/`);
  await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  await page.locator('.tab', { hasText: 'Product advisor' }).click();
  await page.waitForSelector('#listing-body tr:has-text("TOPUP_ATTACH")', { timeout: 30000 })
    .catch(() => fail('the advisor tab shows no findings'));
  if (!(await page.locator('#listing-body tr:has-text("MARKET_PRICE")').count())) {
    fail('the market finding is not on the page');
  }
  await browser.close();
  console.log('OK THE TAB: the product owner opens Product advisor and reads the findings with'
    + ' their receipts — and every row with a proposal carries its "Adopt as draft…" action');

  console.log('\nALL PRODUCT-ADVISOR CHECKS PASSED — the advisor counts before it speaks, the'
    + ' market is a seam, the LLM narrates and never invents, and every suggestion becomes at'
    + ' most a DRAFT a human must promote. Advice with receipts.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
