/* Family phase 2: allowances + ask-to-buy, data gifting, rollover.
 *
 *  - the owner sets a monthly EUR top-up allowance per member; a CHILD's
 *    top-up inside it completes instantly ON THE FAMILY BILL (payer stamp),
 *    above it the order HOLDS and the hub grows an approvals inbox
 *    (Google Family Link's ask-to-buy, T-Mobile's Family Allowances)
 *  - approve releases the held order into the normal flow; deny cancels it;
 *    the requester hears the outcome in their inbox either way
 *  - an ADULT member is never blocked: over (or without) an allowance they
 *    simply pay for it themselves
 *  - GIFTING (the network-wide move): whole-GB chunks of remaining data move between
 *    family members; a child cannot gift; at most half the plan leaves per
 *    cycle; strangers get nothing
 *  - ROLLOVER (AT&T's model, T-Mobile's cap): month close rolls unused GB
 *    into the next cycle, once — running it twice rolls nothing new
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const PLAN = 'GenAlpha Mobile 10 GB';
const TOPUP = 'Data Top-Up 5 GB'; // 5.99 EUR one-time

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
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  const mkLogin = async (given, family, email) => (await (await ctx.request.post(
    `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`, {
      headers: H, data: { email, givenName: given, familyName: family } })).json());
  const mkPerson = async (given, family, email) => {
    const login = await mkLogin(given, family, email);
    await ctx.request.post(`${API}/tmf-api/party/v4/individual`, {
      headers: H, data: { id: login.id, givenName: given, familyName: family,
        contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
    return { id: login.id, email, password: login.temporaryPassword };
  };
  const heads = async (p) => ({ Authorization: 'Bearer '
    + await token(ctx.request, 'bss-biz', p.email, p.password), 'Content-Type': 'application/json' });

  /* ---------- the family: Gina pays; Vera is an adult member; Kiddo is a child ---------- */
  const gina = await mkPerson('Gina', `Payer${run}`, `gina-${run}@family.example`);
  const vera = await mkPerson('Vera', `Adult${run}`, `vera-${run}@family.example`);
  const GH = await heads(gina); const VH = await heads(vera);
  const PARTY = `${API}/tmf-api/party/v4/individual`;
  // adult joins by consent
  await ctx.request.post(`${PARTY}/${vera.id}/householdPayer`,
    { headers: VH, data: { payerEmail: gina.email } });
  await ctx.request.post(`${PARTY}/${vera.id}/householdPayer/accept`, { headers: GH, data: {} });
  // child is payer-created — link born active, role child
  const kidLogin = await mkLogin('Kiddo', `Kid${run}`, `kiddo-${run}@family.example`);
  await ctx.request.post(`${PARTY}/${gina.id}/dependents`, { headers: GH,
    data: { id: kidLogin.id, givenName: 'Kiddo', familyName: `Kid${run}`,
      contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: `kiddo-${run}@family.example` } }] } });
  const kiddo = { id: kidLogin.id, email: `kiddo-${run}@family.example`, password: kidLogin.temporaryPassword };
  const KH = await heads(kiddo);
  console.log('OK the family: Gina (payer), Vera (adult member), Kiddo (child account)');

  /* ---------- plans + metered usage for everyone ---------- */
  const offers = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`, { headers: H })).json();
  const plan = offers.find((o) => o.name === PLAN);
  const topup = offers.find((o) => o.name === TOPUP);
  const orderFor = async (hdrs, customerId) => ctx.request.post(
    `${API}/tmf-api/productOrderingManagement/v4/productOrder`, { headers: hdrs,
      data: { productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name } }],
        relatedParty: [{ id: customerId, role: 'customer' }] } });
  await orderFor(GH, gina.id);
  await orderFor(GH, kiddo.id);   // family-funded line for the kid
  await orderFor(VH, vera.id);
  const activePlan = async (party) => ((await (await ctx.request.get(
    `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${party}&status=active&limit=10`,
    { headers: H })).json()) || []).some((p) => p.name === PLAN);
  for (let i = 0; i < 30 && !(await activePlan(gina.id) && await activePlan(kiddo.id) && await activePlan(vera.id)); i++) {
    await sleep(2500);
  }
  if (!(await activePlan(kiddo.id))) fail('plans never activated');
  const meter = async (party, value) => ctx.request.post(`${API}/tmf-api/usageManagement/v4/usage`, {
    headers: H, data: { usageType: 'Mobile data', usageCharacteristic: { value, units: 'GB' },
      productOffering: { id: plan.id }, relatedParty: [{ id: party, role: 'customer' }] } });
  await meter(gina.id, 1); await meter(vera.id, 2); await meter(kiddo.id, 1);
  const allowedOf = async (party) => {
    const report = await (await ctx.request.get(
      `${API}/tmf-api/usageConsumption/v4/queryUsageConsumption?relatedPartyId=${party}`,
      { headers: H })).json();
    const bucket = (report.bucket || []).find((b) => b.name === 'Mobile data');
    return bucket ? Number(bucket.allowedValue) : null;
  };
  if (await allowedOf(kiddo.id) !== 10) fail('kiddo bucket wrong: ' + await allowedOf(kiddo.id));
  console.log('OK three lines live with metered usage — Mobile data buckets at 10 GB each');

  /* ---------- the owner sets the child's allowance IN THE HUB ---------- */
  const ginaPage = await (await browser.newContext()).newPage();
  await ginaPage.goto(`${API}/shop/`);
  await ginaPage.locator('.who >> text=Sign in').click();
  await ginaPage.waitForSelector('input[name="username"]', { timeout: 20000 });
  await ginaPage.fill('input[name="username"]', gina.email);
  await ginaPage.fill('input[name="password"]', gina.password);
  await ginaPage.click('input[type="submit"], button[type="submit"]');
  await ginaPage.waitForSelector('.nav', { timeout: 20000 });
  await ginaPage.locator('.nav >> text=Family').click();
  const kidCard = ginaPage.locator(`[data-testid=fam-member-${kiddo.id}]`);
  await kidCard.waitFor({ timeout: 20000 });
  await kidCard.locator('[data-testid=fam-allowance-input]').fill('6');
  await kidCard.locator('[data-testid=fam-allowance-set]').click();
  await ginaPage.locator('[data-testid=hh-note]', { hasText: 'allowance set' }).waitFor({ timeout: 15000 });
  console.log('OK Gina set Kiddo\'s allowance to 6 EUR/month from the hub');

  /* ---------- in-cap: instant, family-funded; over-cap: HELD ---------- */
  const buyTopup = async (hdrs) => (await (await ctx.request.post(
    `${API}/tmf-api/productOrderingManagement/v4/productOrder`, { headers: hdrs,
      data: { productOrderItem: [{ action: 'add',
        productOffering: { id: topup.id, name: topup.name } }] } })).json());
  const first = await buyTopup(KH);
  if (first.state === 'held') fail('an in-allowance top-up was held');
  const firstPayer = (first.relatedParty || []).find((p) => p.role === 'payer');
  if (!firstPayer || firstPayer.id !== gina.id) {
    fail('in-allowance top-up not family-funded: ' + JSON.stringify(first.relatedParty));
  }
  for (let i = 0; i < 25 && (await allowedOf(kiddo.id)) !== 15; i++) await sleep(2500);
  if (await allowedOf(kiddo.id) !== 15) fail('the boost never landed: ' + await allowedOf(kiddo.id));
  console.log('OK Kiddo\'s first top-up (5.99 of 6.00) was instant, payer-stamped GINA — meter grew to 15 GB');

  const second = await buyTopup(KH);
  if (second.state !== 'held') fail('an over-allowance child top-up was not held: ' + second.state);
  console.log('OK the second top-up (11.98 > 6.00) HELD — the kid never dead-ends, the family decides');

  /* ---------- the approvals inbox: approve in the hub ---------- */
  await ginaPage.reload();
  await ginaPage.locator('[data-testid=approvals-inbox]').waitFor({ timeout: 20000 });
  const inbox = await ginaPage.locator('[data-testid=approvals-inbox]').textContent();
  if (!inbox.includes(TOPUP) || !inbox.includes('Kiddo')) {
    fail('approvals inbox missing the ask: ' + inbox.slice(0, 200));
  }
  await ginaPage.click('[data-testid=appr-approve]');
  await ginaPage.locator('[data-testid=hh-note]', { hasText: 'approved' }).waitFor({ timeout: 15000 });
  for (let i = 0; i < 25 && (await allowedOf(kiddo.id)) !== 20; i++) await sleep(2500);
  if (await allowedOf(kiddo.id) !== 20) fail('the approved boost never landed');
  console.log('OK Gina approved from the inbox — the held order completed, meter at 20 GB');

  /* ---------- deny path + the adult never blocks ---------- */
  const third = await buyTopup(KH);
  if (third.state !== 'held') fail('third top-up should hold');
  const denied = await (await ctx.request.post(
    `${API}/tmf-api/productOrderingManagement/v4/productOrder/${third.id}/approval`,
    { headers: GH, data: { approve: false } })).json();
  if (denied.state !== 'cancelled') fail('deny did not cancel: ' + denied.state);
  // Vera can't decide (member, not admin) — and a stranger's approval 404s
  const veraDecides = await ctx.request.post(
    `${API}/tmf-api/productOrderingManagement/v4/productOrder/${second.id}/approval`,
    { headers: VH, data: { approve: true } });
  if (veraDecides.status() === 200) fail('a plain member decided an approval');
  const adultBuy = await buyTopup(VH);
  if (adultBuy.state === 'held') fail('an adult was blocked by ask-to-buy');
  if ((adultBuy.relatedParty || []).some((p) => p.role === 'payer')) {
    fail('an adult without allowance was family-funded');
  }
  console.log('OK deny cancels; a plain member cannot decide; an adult self-pays, never blocked');

  /* ---------- notifications tell both sides ---------- */
  const inboxOf = async (hdrs) => (await (await ctx.request.get(
    `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=100`,
    { headers: hdrs })).json()).map((m) => m.subject);
  let ginaInbox = [], kidInbox = [];
  for (let i = 0; i < 15; i++) {
    ginaInbox = await inboxOf(GH); kidInbox = await inboxOf(KH);
    if (ginaInbox.includes('Approval needed') && kidInbox.includes('Approved')
        && kidInbox.includes('Not this time') && ginaInbox.includes('Family top-up bought')) break;
    await sleep(2000);
  }
  if (!ginaInbox.includes('Approval needed') || !ginaInbox.includes('Family top-up bought')) {
    fail('payer notifications missing: ' + ginaInbox.join(' | '));
  }
  if (!kidInbox.includes('Sent for approval') || !kidInbox.includes('Approved')
      || !kidInbox.includes('Not this time')) {
    fail('requester notifications missing: ' + kidInbox.join(' | '));
  }
  console.log('OK the inboxes: payer got the ask + the in-cap purchase note; the kid heard'
    + ' "sent for approval", "approved" and "not this time"');

  /* ---------- gifting: Vera hands Gina 3 GB from her My page ---------- */
  const veraPage = await (await browser.newContext()).newPage();
  await veraPage.goto(`${API}/shop/`);
  await veraPage.locator('.who >> text=Sign in').click();
  await veraPage.waitForSelector('input[name="username"]', { timeout: 20000 });
  await veraPage.fill('input[name="username"]', vera.email);
  await veraPage.fill('input[name="password"]', vera.password);
  await veraPage.click('input[type="submit"], button[type="submit"]');
  await veraPage.waitForSelector('.nav', { timeout: 20000 });
  // Vera's own self-paid top-up boost lands async — settle at 15 GB before
  // taking gift baselines, or the deltas race the event stream
  for (let i = 0; i < 25 && (await allowedOf(vera.id)) !== 15; i++) await sleep(2500);
  if (await allowedOf(vera.id) !== 15) fail('Vera\'s self-paid boost never landed');
  const ginaAllowedBefore = await allowedOf(gina.id);
  const veraAllowedBefore = await allowedOf(vera.id);
  await veraPage.click('.nav >> text=My page');
  await veraPage.locator('[data-testid=gift-select]').waitFor({ timeout: 20000 });
  await veraPage.selectOption('[data-testid=gift-select]', { label: 'Gina Payer' + run });
  await veraPage.fill('[data-testid=gift-amount]', '3');
  await veraPage.click('[data-testid=gift-send]');
  await veraPage.locator('[data-testid=gift-note]', { hasText: 'gifted' }).waitFor({ timeout: 15000 });
  if (await allowedOf(gina.id) !== ginaAllowedBefore + 3) fail('the gift never reached Gina');
  if (await allowedOf(vera.id) !== veraAllowedBefore - 3) fail('the gift never left Vera');
  console.log('OK Vera gifted Gina 3 GB — one meter grew, the other shrank, same instant');

  /* ---------- gifting guardrails ---------- */
  const gift = async (hdrs, receiverId, amount) => ctx.request.post(
    `${API}/tmf-api/usageManagement/v4/gift`,
    { headers: hdrs, data: { receiverId, amount } });
  const kidGift = await gift(KH, gina.id, 1);
  if (kidGift.status() === 200) fail('a child gifted family-funded data');
  if (!(await kidGift.text()).includes('family-funded')) fail('child gift rejection unclear');
  const overCap = await gift(VH, gina.id, 3); // 3 + 3 > half of 10
  if (overCap.status() === 200) fail('the half-the-plan gift cap did not hold');
  if (!(await overCap.text()).includes('capped at')) fail('cap rejection unclear: ' + await overCap.text());
  const strangerGift = await gift(VH, '00000000-0000-0000-0000-000000000000', 1);
  if (strangerGift.status() === 200) fail('a gift left the household');
  // both sides got the gift story
  for (let i = 0; i < 15 && !(await inboxOf(VH)).includes('Gift sent'); i++) await sleep(2000);
  if (!(await inboxOf(VH)).includes('Gift sent')
      || !(await inboxOf(GH)).includes('You received a data gift')) {
    fail('gift notifications missing');
  }
  console.log('OK guardrails: a child cannot gift, half-the-plan cap holds, strangers get'
    + ' nothing — and both inboxes tell the gift story');

  /* ---------- rollover: month close, once and only once ---------- */
  const close1 = await (await ctx.request.post(
    `${API}/tmf-api/usageManagement/v4/cycleClose`, { headers: H, data: {} })).json();
  if (!close1.rolledBuckets || close1.rolledBuckets < 1) {
    fail('cycle close rolled nothing: ' + JSON.stringify(close1));
  }
  const close2 = await (await ctx.request.post(
    `${API}/tmf-api/usageManagement/v4/cycleClose`, { headers: H, data: {} })).json();
  if (close2.rolledBuckets !== 0) fail('cycle close is not idempotent: ' + JSON.stringify(close2));
  for (let i = 0; i < 15 && !(await inboxOf(VH)).includes('Your unused data rolled over'); i++) await sleep(2000);
  if (!(await inboxOf(VH)).includes('Your unused data rolled over')) {
    fail('rollover notification missing');
  }
  console.log('OK month close rolled unused GB into next cycle — capped, once only, and'
    + ' the customer heard about it');

  await browser.close();
  console.log('\nALL FAMILY PHASE-2 CHECKS PASSED — allowance-funded instant top-ups on the'
    + ' family bill, ask-to-buy holds with an approvals inbox, adults never blocked,'
    + ' family data gifts with guardrails, and one-cycle rollover at month close.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
