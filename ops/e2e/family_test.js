/* Family roles: the household grows management, the Verizon way.
 *
 *  - the OWNER (the payer) may promote a consenting member to FAMILY ADMIN
 *    ("my wife can also be the admin") — and only the owner manages roles
 *  - an admin sees the WHOLE family (the payer's other members, with roles)
 *    and orders for any of them — the payer stamp is the HOUSEHOLD PAYER,
 *    never the admin: admin is authority, not a wallet
 *  - privacy is proportional: through the family view an adult member shows
 *    only what the family FUNDS — their own-paid products stay their own;
 *    a plain member sees no family at all
 *  - the hub UI: role chips, member cards with funded services, promote /
 *    demote for the owner alone
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

  /* ---------- the family: Petra pays, Wanda is the wife, Sam the son ---------- */
  const mkPerson = async (given, family, email) => {
    const login = await (await ctx.request.post(
      `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`, {
        headers: H, data: { email, givenName: given, familyName: family } })).json();
    await ctx.request.post(`${API}/tmf-api/party/v4/individual`, {
      headers: H, data: { id: login.id, givenName: given, familyName: family,
        contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
    return { id: login.id, email, password: login.temporaryPassword };
  };
  const petra = await mkPerson('Petra', `Owner${run}`, `petra-${run}@family.example`);
  const wanda = await mkPerson('Wanda', `Wife${run}`, `wanda-${run}@family.example`);
  const sam = await mkPerson('Sam', `Son${run}`, `sam-${run}@family.example`);
  const heads = async (p) => ({ Authorization: 'Bearer '
    + await token(ctx.request, 'bss-biz', p.email, p.password), 'Content-Type': 'application/json' });
  const PH = await heads(petra); const WH = await heads(wanda); const SH = await heads(sam);
  const PARTY = `${API}/tmf-api/party/v4/individual`;

  // both join by consent: request -> Petra accepts
  for (const [person, hdrs] of [[wanda, WH], [sam, SH]]) {
    await ctx.request.post(`${PARTY}/${person.id}/householdPayer`,
      { headers: hdrs, data: { payerEmail: petra.email } });
    const acc = await ctx.request.post(`${PARTY}/${person.id}/householdPayer/accept`,
      { headers: PH, data: {} });
    if (acc.status() !== 200) fail('accept failed for ' + person.email);
  }
  console.log('OK one payer, two consenting members: Wanda (the wife) and Sam (the son)');

  /* ---------- roles are the OWNER's alone ---------- */
  // the wife, not yet admin, cannot promote herself or manage Sam
  const selfPromote = await ctx.request.post(`${PARTY}/${wanda.id}/householdRole`,
    { headers: WH, data: { role: 'admin' } });
  const wandaOnSam = await ctx.request.post(`${PARTY}/${sam.id}/householdRole`,
    { headers: WH, data: { role: 'admin' } });
  if (selfPromote.status() === 200 || wandaOnSam.status() === 200) {
    fail('a non-owner changed family roles');
  }
  console.log('OK a member cannot grant roles — not to herself, not to anyone');

  /* ---------- Petra promotes Wanda IN THE HUB ---------- */
  const petraPage = await (await browser.newContext()).newPage();
  await petraPage.goto(`${API}/shop/`);
  await petraPage.locator('.who >> text=Sign in').click();
  await petraPage.waitForSelector('input[name="username"]', { timeout: 20000 });
  await petraPage.fill('input[name="username"]', petra.email);
  await petraPage.fill('input[name="password"]', petra.password);
  await petraPage.click('input[type="submit"], button[type="submit"]');
  await petraPage.waitForSelector('.nav', { timeout: 20000 });
  await petraPage.locator('.nav >> text=Family').click();
  await petraPage.locator(`[data-testid=fam-member-${wanda.id}]`).waitFor({ timeout: 20000 });
  const chipBefore = await petraPage
    .locator(`[data-testid=fam-member-${wanda.id}] [data-testid=role-chip]`).textContent();
  if (!chipBefore.includes('member')) fail('Wanda should start as a plain member: ' + chipBefore);
  await petraPage.click(`[data-testid=fam-promote-${wanda.id}]`);
  await petraPage.locator('[data-testid=hh-note]', { hasText: 'family admin now' }).waitFor({ timeout: 15000 });
  // the chip flips once the household reload lands
  await petraPage.locator(`[data-testid=fam-member-${wanda.id}] [data-testid=role-chip]`,
    { hasText: 'family admin' }).waitFor({ timeout: 15000 });
  console.log('OK Petra promoted Wanda to family admin from the hub — role chip flips');

  /* ---------- the admin sees the WHOLE family; a plain member sees none ---------- */
  const wandaHh = await (await ctx.request.get(`${PARTY}/${wanda.id}/household`, { headers: WH })).json();
  if (wandaHh.myRole !== 'admin') fail('Wanda\'s own role not reported: ' + JSON.stringify(wandaHh).slice(0, 120));
  if (!(wandaHh.family || []).some((d) => d.id === sam.id)) {
    fail('the admin does not see the family: ' + JSON.stringify(wandaHh.family));
  }
  const samHh = await (await ctx.request.get(`${PARTY}/${sam.id}/household`, { headers: SH })).json();
  if (samHh.family) fail('a plain member sees the family roster — admin-only data leaked');
  console.log('OK the admin sees every member; a plain member sees only their own link');

  /* ---------- the admin orders for the son — it bills to the PAYER ---------- */
  const wandaPage = await (await browser.newContext()).newPage();
  await wandaPage.goto(`${API}/shop/`);
  await wandaPage.locator('.who >> text=Sign in').click();
  await wandaPage.waitForSelector('input[name="username"]', { timeout: 20000 });
  await wandaPage.fill('input[name="username"]', wanda.email);
  await wandaPage.fill('input[name="password"]', wanda.password);
  await wandaPage.click('input[type="submit"], button[type="submit"]');
  await wandaPage.waitForSelector('.nav', { timeout: 20000 });
  await wandaPage.locator('.nav >> text=Family').click();
  await wandaPage.locator(`[data-testid=fam-member-${sam.id}]`).waitFor({ timeout: 20000 });
  // admin authority stops at roles: no promote/demote, no stop-paying
  if (await wandaPage.locator(`[data-testid=fam-promote-${sam.id}]`).count()) {
    fail('an admin sees role management — that is the owner\'s alone');
  }
  if (await wandaPage.locator('[data-testid=hh-stop]').count()) {
    fail('an admin can end links — that is the owner\'s alone');
  }
  await wandaPage.locator(`[data-testid=fam-member-${sam.id}] [data-testid=hh-order-select]`)
    .selectOption({ label: 'GenAlpha Mobile 10 GB' });
  await wandaPage.locator(`[data-testid=fam-member-${sam.id}] [data-testid=hh-order]`).click();
  await wandaPage.locator('[data-testid=hh-note]', { hasText: 'bills to the family payer' })
    .waitFor({ timeout: 20000 });
  let product = null;
  for (let i = 0; i < 25 && !product; i++) {
    await new Promise((r) => setTimeout(r, 2500));
    const products = await (await ctx.request.get(
      `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${sam.id}&status=active&limit=10`,
      { headers: H })).json();
    product = (products || []).find((p) => p.name === 'GenAlpha Mobile 10 GB') || null;
  }
  if (!product) fail('the plan Wanda ordered for Sam never activated');
  const payerParty = (product.relatedParty || []).find((p) => p.role === 'payer');
  if (!payerParty || payerParty.id !== petra.id) {
    fail('the payer stamp must be PETRA (the household payer), got: '
      + JSON.stringify(product.relatedParty));
  }
  console.log('OK Wanda ordered for Sam from her hub — payer stamp is PETRA\'s:'
    + ' admin is authority, not a wallet');

  /* ---------- privacy proportional: the family view shows FUNDED only ---------- */
  const offers = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`, { headers: H })).json();
  const netflix = offers.find((o) => o.name === 'Netflix Standard');
  await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: SH, data: {
      productOrderItem: [{ action: 'add', productOffering: { id: netflix.id, name: netflix.name } }],
      relatedParty: [{ id: sam.id, role: 'customer' }] } });
  let netflixUp = false;
  for (let i = 0; i < 20 && !netflixUp; i++) {
    await new Promise((r) => setTimeout(r, 2500));
    const products = await (await ctx.request.get(
      `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${sam.id}&status=active&limit=10`,
      { headers: H })).json();
    netflixUp = (products || []).some((p) => p.name === 'Netflix Standard');
  }
  if (!netflixUp) fail('Sam\'s own Netflix never activated');
  const wandaView = await (await ctx.request.get(
    `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${sam.id}&limit=20`,
    { headers: WH })).json();
  if (!wandaView.some((p) => p.name === 'GenAlpha Mobile 10 GB')) {
    fail('the admin cannot see the funded plan');
  }
  if (wandaView.some((p) => p.name === 'Netflix Standard')) {
    fail('Sam\'s OWN Netflix leaked into the family view — paying is not surveillance');
  }
  // a plain member gets no family window at all
  const samPeeks = await ctx.request.get(
    `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${wanda.id}&limit=20`,
    { headers: SH });
  if (samPeeks.status() === 200) fail('a plain member opened a family view');
  console.log('OK the family view shows what the family FUNDS — Sam\'s own Netflix stays'
    + ' his; a plain member has no window at all');

  /* ---------- demote: the owner takes it back ---------- */
  const demote = await ctx.request.post(`${PARTY}/${wanda.id}/householdRole`,
    { headers: PH, data: { role: 'member' } });
  if (demote.status() !== 200) fail('the owner could not demote');
  const wandaAfter = await (await ctx.request.get(`${PARTY}/${wanda.id}/household`, { headers: WH })).json();
  if (wandaAfter.family) fail('a demoted admin still sees the family');
  const wandaViewAfter = await ctx.request.get(
    `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${sam.id}&limit=20`,
    { headers: WH });
  if (wandaViewAfter.status() === 200) fail('a demoted admin still opens the family view');
  console.log('OK demoted: Wanda is a plain member again — no roster, no family view');

  await browser.close();
  console.log('\nALL FAMILY-ROLE CHECKS PASSED — owner-only role grants, admins manage the'
    + ' whole family with the payer\'s wallet, funded-only visibility for adults, and'
    + ' demotion closes every window.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
