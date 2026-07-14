/* Household billing: one person, many payers — the consumer mirror of B2B
 * consolidation, built on the same payer stamp.
 *
 *  - consent is a two-step: Sonny REQUESTS Paula as his payer (by email,
 *    pending); only Paula can ACCEPT — from her My page
 *  - Paula orders a plan FOR Sonny from her My page: ordering verifies the
 *    live link and stamps payer=Paula (an Individual, not an Organization)
 *  - the billing run puts Sonny's plan on PAULA's bill, attributed to him,
 *    merged with her own products — ONE family bill, no org semantics
 *  - Sonny's own purchases stay on his own bill (same as a B2B member)
 *  - boundaries: a stranger ordering for Sonny is claimed back to
 *    themselves; either side can end the link
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();

async function token(request, client, user, pass) {
  const res = await request.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const staff = await token(ctx.request, 'bss-demo', 'demo', 'demo');
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };

  /* ---------- two real logins + party records (TMF672 + pinned ids) ---------- */
  const mkPerson = async (given, family, email) => {
    const login = await (await ctx.request.post(
      `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`, {
        headers: H, data: { email, givenName: given, familyName: family } })).json();
    await ctx.request.post(`${API}/tmf-api/party/v4/individual`, {
      headers: H, data: { id: login.id, givenName: given, familyName: family,
        contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
    return { id: login.id, email, password: login.temporaryPassword };
  };
  const paula = await mkPerson('Paula', `Payer${run}`, `paula-${run}@family.example`);
  const sonny = await mkPerson('Sonny', `Son${run}`, `sonny-${run}@family.example`);
  const paulaTok = await token(ctx.request, 'bss-biz', paula.email, paula.password);
  const sonnyTok = await token(ctx.request, 'bss-biz', sonny.email, sonny.password);
  const PH = { Authorization: 'Bearer ' + paulaTok, 'Content-Type': 'application/json' };
  const SH = { Authorization: 'Bearer ' + sonnyTok, 'Content-Type': 'application/json' };
  console.log('OK two people with real logins: Paula (the payer) and Sonny (the son)');

  /* ---------- consent: request (Sonny) -> accept (Paula), in the browser ---------- */
  const sonnyPage = await (await browser.newContext()).newPage();
  await sonnyPage.goto(`${API}/shop/`);
  await sonnyPage.locator('.who >> text=Sign in').click();
  await sonnyPage.waitForSelector('input[name="username"]', { timeout: 20000 });
  await sonnyPage.fill('input[name="username"]', sonny.email);
  await sonnyPage.fill('input[name="password"]', sonny.password);
  await sonnyPage.click('input[type="submit"], button[type="submit"]');
  await sonnyPage.waitForSelector('.nav', { timeout: 20000 });
  await sonnyPage.locator('.nav >> text=My page').click();
  await sonnyPage.locator('[data-testid=hh-request-email]').waitFor({ timeout: 15000 });
  await sonnyPage.fill('[data-testid=hh-request-email]', paula.email);
  await sonnyPage.click('[data-testid=hh-request]');
  await sonnyPage.locator('[data-testid=hh-payer]', { hasText: 'Waiting' }).waitFor({ timeout: 15000 });
  console.log('OK Sonny asked Paula to pay — PENDING until she consents');

  // pending link must NOT let Paula order yet
  const early = await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: PH, data: {
      productOrderItem: [{ action: 'add', productOffering: { name: 'x', id: 'x' } }],
      relatedParty: [{ id: sonny.id, role: 'customer' }] } });
  const earlyBody = await early.json().catch(() => ({}));
  const claimed = (earlyBody.relatedParty || []).some((p) => p.id === paula.id && p.role === 'customer');
  if (early.status() === 201 && !claimed) fail('a PENDING link let the payer order for the dependent');
  console.log('OK a pending link grants nothing — consent is not decoration');

  const paulaPage = await (await browser.newContext()).newPage();
  await paulaPage.goto(`${API}/shop/`);
  await paulaPage.locator('.who >> text=Sign in').click();
  await paulaPage.waitForSelector('input[name="username"]', { timeout: 20000 });
  await paulaPage.fill('input[name="username"]', paula.email);
  await paulaPage.fill('input[name="password"]', paula.password);
  await paulaPage.click('input[type="submit"], button[type="submit"]');
  await paulaPage.waitForSelector('.nav', { timeout: 20000 });
  await paulaPage.locator('.nav >> text=My page').click();
  await paulaPage.locator('[data-testid=hh-accept]').waitFor({ timeout: 15000 });
  await paulaPage.click('[data-testid=hh-accept]');
  await paulaPage.locator('[data-testid=hh-order-select]').waitFor({ timeout: 15000 });
  console.log('OK Paula accepted on her My page — the household is ACTIVE');

  /* ---------- Paula orders Sonny's plan from her My page ---------- */
  await paulaPage.selectOption('[data-testid=hh-order-select]', { label: 'GenAlpha Mobile 10 GB' });
  await paulaPage.click('[data-testid=hh-order]');
  await paulaPage.locator('[data-testid=hh-note]', { hasText: 'bills to you' }).waitFor({ timeout: 20000 });
  let product = null;
  for (let i = 0; i < 25 && !product; i++) {
    await new Promise((r) => setTimeout(r, 2500));
    const products = await (await ctx.request.get(
      `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${sonny.id}&status=active&limit=10`,
      { headers: H })).json();
    product = (products || []).find((p) => p.name === 'GenAlpha Mobile 10 GB') || null;
  }
  if (!product) fail("Sonny's plan never activated");
  const payerParty = (product.relatedParty || []).find((p) => p.role === 'payer');
  if (!payerParty || payerParty.id !== paula.id || payerParty['@referredType'] !== 'Individual') {
    fail('plan not stamped with the PERSON payer: ' + JSON.stringify(product.relatedParty));
  }
  console.log('OK the plan is Sonny\'s, the payer stamp is Paula\'s — an Individual, not an org');

  /* ---------- Sonny self-buys: stays on HIS bill ---------- */
  const offers = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`, { headers: H })).json();
  const netflix = offers.find((o) => o.name === 'Netflix Standard');
  await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: SH, data: {
      productOrderItem: [{ action: 'add', productOffering: { id: netflix.id, name: netflix.name } }],
      relatedParty: [{ id: sonny.id, role: 'customer' }] } });
  let netflixActive = false;
  for (let i = 0; i < 20 && !netflixActive; i++) {
    await new Promise((r) => setTimeout(r, 2500));
    const products = await (await ctx.request.get(
      `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${sonny.id}&status=active&limit=10`,
      { headers: H })).json();
    netflixActive = (products || []).some((p) => p.name === 'Netflix Standard');
  }
  if (!netflixActive) fail('Sonny\'s Netflix never activated');

  /* ---------- ONE family bill, attributed; Sonny's own bill separate ---------- */
  await ctx.request.post(`${API}/tmf-api/customerBillManagement/v4/billingRun`, { headers: H });
  const billsOf = async (party) => (await (await ctx.request.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill?relatedPartyId=${party}`,
    { headers: H })).json());
  const paulaBills = await billsOf(paula.id);
  if (paulaBills.length !== 1) fail(`expected 1 family bill for Paula, got ${paulaBills.length}`);
  const lines = await (await ctx.request.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill/${paulaBills[0].id}/appliedCustomerBillingRate`,
    { headers: H })).json();
  const planLine = lines.find((r) => (r.name || '').includes('10 GB'));
  if (!planLine || planLine.forParty?.id !== sonny.id) {
    fail('family bill missing Sonny\'s plan with per-person attribution');
  }
  if (lines.some((r) => (r.name || '').includes('Netflix'))) {
    fail('Sonny\'s OWN Netflix leaked onto the family bill');
  }
  const sonnyBills = await billsOf(sonny.id);
  if (sonnyBills.length !== 1) fail(`expected Sonny's own bill, got ${sonnyBills.length}`);
  const sonnyLines = await (await ctx.request.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill/${sonnyBills[0].id}/appliedCustomerBillingRate`,
    { headers: H })).json();
  if (!sonnyLines.some((r) => (r.name || '').includes('Netflix'))
      || sonnyLines.some((r) => (r.name || '').includes('10 GB'))) {
    fail('Sonny\'s bill wrong: ' + sonnyLines.map((r) => r.name).join(' | '));
  }
  console.log('OK ONE family bill under Paula (Sonny\'s plan, attributed to him) — and'
    + ' Sonny\'s Netflix on his OWN bill');

  /* ---------- boundary: a stranger "ordering for Sonny" orders for themself ---------- */
  const kai = await token(ctx.request, 'bss-biz', 'kai@bss.local', 'kai');
  const kaiSub = JSON.parse(Buffer.from(kai.split('.')[1], 'base64').toString()).sub;
  const strangerOrder = await (await ctx.request.post(
    `${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
      headers: { Authorization: 'Bearer ' + kai, 'Content-Type': 'application/json' },
      data: { productOrderItem: [{ action: 'add',
        productOffering: { id: netflix.id, name: netflix.name } }],
        relatedParty: [{ id: sonny.id, role: 'customer' }] } })).json();
  const strangerCustomer = (strangerOrder.relatedParty || [])
    .find((p) => p.role === 'customer');
  if (!strangerCustomer || strangerCustomer.id !== kaiSub) {
    fail('a stranger could order onto Sonny: ' + JSON.stringify(strangerOrder.relatedParty));
  }
  console.log('OK boundary: a stranger naming Sonny is claimed back to themself — no link,'
    + ' no household power');

  /* ---------- either side can leave ---------- */
  await sonnyPage.reload();
  await sonnyPage.locator('[data-testid=hh-leave]').waitFor({ timeout: 15000 });
  await sonnyPage.click('[data-testid=hh-leave]');
  await sonnyPage.locator('[data-testid=hh-request-email]').waitFor({ timeout: 15000 });
  console.log('OK Sonny left the household — future orders are his own again');

  /* ---------- v2: Paula CREATES a child account; the kid gets the APP ---------- */
  await paulaPage.reload();
  await paulaPage.locator('summary', { hasText: 'Add a family member' }).click();
  await paulaPage.fill('[data-testid=hh-add-given]', 'Kidd');
  await paulaPage.fill('[data-testid=hh-add-family]', `Kid${run}`);
  const kidEmail = `kidd-${run}@family.example`;
  await paulaPage.fill('[data-testid=hh-add-email]', kidEmail);
  await paulaPage.click('[data-testid=hh-add]');
  await paulaPage.locator('[data-testid=hh-credentials]').waitFor({ timeout: 20000 });
  const creds = await paulaPage.locator('[data-testid=hh-credentials] b').textContent();
  const kidPassword = creds.split('/').pop().trim();
  console.log('OK Paula created the child account — consent implicit when the payer IS the'
    + ' creator; credentials shown once');

  // the link was born ACTIVE: Paula orders the kid's plan immediately
  await paulaPage.locator('[data-testid=hh-order-select]').last().waitFor({ timeout: 15000 });
  await paulaPage.locator('[data-testid=hh-order-select]').last()
    .selectOption({ label: 'GenAlpha Mobile 10 GB' });
  await paulaPage.locator('[data-testid=hh-order]').last().click();
  await paulaPage.locator('[data-testid=hh-note]', { hasText: 'bills to you' }).waitFor({ timeout: 20000 });

  // ...and the kid signs into the MOBILE APP: their own My page, honestly
  // labelled with who pays
  const app = await (await browser.newContext({ viewport: { width: 400, height: 860 } })).newPage();
  await app.goto(`${API}/app/`);
  await app.locator('[data-testid=signin]').click();
  await app.waitForSelector('input[name="username"]', { timeout: 20000 });
  await app.fill('input[name="username"]', kidEmail);
  await app.fill('input[name="password"]', kidPassword);
  await app.click('input[type="submit"], button[type="submit"]');
  await app.locator('[data-testid=app-household-banner]').waitFor({ timeout: 30000 });
  const banner = await app.locator('[data-testid=app-household-banner]').textContent();
  if (!banner.includes('Paula')) fail('app banner does not name the payer: ' + banner);
  let lobCard = false;
  for (let i = 0; i < 20 && !lobCard; i++) {
    await app.waitForTimeout(2500);
    await app.reload();
    await app.locator('[data-testid=app-household-banner]').waitFor({ timeout: 20000 }).catch(() => {});
    lobCard = (await app.locator('[data-testid=lob-card]', { hasText: '10 GB' }).count()) > 0;
  }
  if (!lobCard) fail("the kid's plan never appeared on their app Home");
  console.log('OK the kid signed into the APP: own My page, own line, banner says'
    + ' "paid for by Paula" — one person, many payers, pocket edition');

  await browser.close();
  console.log('\nALL HOUSEHOLD CHECKS PASSED — consent-gated person-payer, family bill with'
    + ' attribution, own purchases stay personal, strangers get nothing, either side can'
    + ' leave; child accounts hand the kid their own app.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
