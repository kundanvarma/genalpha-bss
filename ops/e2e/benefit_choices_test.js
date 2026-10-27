/* Benefit Choices (#352): a plan grants Choices into the household's pool, and
 * the household spends them on benefits. The tracer (#354), through the
 * gateway with real tokens:
 *
 *   1. a customer buys a plan that grants 2 Choices; the pool says 2 of 2 left
 *   2. the shop's Choices page shows them in words, and a pick there is an
 *      ordinary order that holds, then commits once the partner confirms
 *   A1. two simultaneous picks for the last free Choice: one 201, one 409
 *   A2. the same order item held twice is the same allocation, spent once
 *   A8. another household reads only its own pool, cannot move Choices, and
 *       a tenant that does not offer Choices says so
 * Swap and release (#356), against the partner stand-in's switches:
 *   release on the page gives the Choice back
 *   A7. a swap keeps the old benefit active until the new one is; a swap whose
 *       new benefit is refused keeps the old one and gives the hold back
 *   A4. a refused pick gives its Choice back
 *   A6. the swap limit (a policy rule: 3 a quarter) refuses the 4th and says
 *       when the next is possible
 * Plan lifecycle (#357), on a second household:
 *   A5. a downgrade below what is in use is refused with the benefits named,
 *       and goes through after a release
 *   another plan (a line) adds its Choices
 *   a held benefit (PhoneCo, 12 months) shows its date and refuses release
 *   A9. a catalog change does not rewrite what a household already picked
 *   a cease ends the newest benefit it can no longer pay for — never the held one
 * Care desk and governed actions (#358), on a third household:
 *   a CSR sees the household's Choices in the cockpit and picks one — through
 *   the governed action pickBenefit, with a receipt
 *   the desk assistant (an agent) picks through the ontology API, receipt and all
 *   an agent's goodwill request is FILED, not granted; a human approver
 *   approves it on the workforce desk and the pool grows by one
 * Authoring (#359), in the back office:
 *   a product manager sets what a plan grants and makes a benefit — no JSON —
 *   and a household picks it; in a governed tenant (Taranga) a new benefit
 *   waits for an approver, is launched, and can be unlaunched
 *
 * Later tickets add their legs here (swap/release, downgrade, holds, CSR desk,
 * governed actions, partner cost, marketing).
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms';
const CHOICES = `${API}/benefitAllowance/v1`;
const ORDERS = `${API}/tmf-api/productOrderingManagement/v4/productOrder`;
const run = Date.now();
const SHOTS = `${__dirname}/.shots`;

const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function register(page, email, first, last) {
  await page.goto(`${API}/shop/`);
  await page.click('.who >> text=Sign in');
  await page.waitForSelector('a[href*="registration"], input[name="username"]', { timeout: 20000 });
  await page.click('a[href*="registration"]');
  await page.waitForSelector('input[name="email"]');
  await page.fill('input[name="firstName"]', first);
  await page.fill('input[name="lastName"]', last);
  await page.fill('input[name="email"]', email);
  await page.fill('input[name="password"]', 'calm otters swim at dawn');
  await page.fill('input[name="password-confirm"]', 'calm otters swim at dawn');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.nav', { timeout: 20000 });
}

/** A fetch FROM the signed-in page, so the shop's own token and X-Channel ride along. */
function call(page, method, url, token, body) {
  return page.evaluate(async ({ method, url, token, body }) => {
    const res = await fetch(url, {
      method,
      headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json', 'X-Channel': 'web' },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: res.status, body: await res.json().catch(() => ({})) };
  }, { method, url: url.replace(API, ''), token, body });
}

const orderOf = (o) => ({
  productOrderItem: [{ id: '1', action: 'add', quantity: 1,
    productOffering: { id: o.id, name: o.name, '@referredType': 'ProductOffering' } }],
});

(async () => {
  const ctx = await request.newContext();
  const offers = await (await ctx.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100&lifecycleStatus=Active`)).json();
  const by = (n) => {
    const o = offers.find((x) => x.name === n);
    if (!o) fail(`missing offering ${n} — run ops/seed/seed_choices.py`);
    return o;
  };
  const plan = by('GenAlpha Mobile Unlimited 5G');
  const stream = by('StreamCo Standard');
  const audio = by('Lydbok+ Audiobooks');
  if (plan.choicesGranted !== 2) fail(`${plan.name} should grant 2 Choices, grants ${plan.choicesGranted}`);
  if (stream.choiceCost !== 1 || audio.choiceCost !== 1) fail('each benefit should cost 1 Choice');
  console.log(`OK catalog: ${plan.name} grants 2 Choices; ${stream.name} and ${audio.name} cost 1 each`);

  const browser = await chromium.launch();
  const page = await (await browser.newContext({ viewport: { width: 1280, height: 900 } })).newPage();
  global.shotPage = page; // so a failure leaves a picture of what the customer saw
  await register(page, `chloe-${run}@example.com`, 'Chloe', `Choices${run}`);
  const chloe = await page.evaluate(() => sessionStorage.getItem('bss.shop.token'));

  /* 1 — a plan that grants Choices */
  const none = await call(page, 'GET', `${CHOICES}/pool`, chloe);
  if (none.status !== 200 || !none.body.offered || none.body.granted !== 0) {
    fail('a new household should see Choices offered and 0 granted: ' + JSON.stringify(none));
  }
  const bought = await call(page, 'POST', ORDERS, chloe, orderOf(plan));
  if (bought.status !== 201) fail('plan order refused: ' + JSON.stringify(bought.body));
  let pool = null;
  for (let i = 0; i < 40 && pool?.granted !== 2; i++) {
    await sleep(1500);
    pool = (await call(page, 'GET', `${CHOICES}/pool`, chloe)).body;
  }
  if (pool?.granted !== 2 || pool.left !== 2) fail('the plan never granted its 2 Choices: ' + JSON.stringify(pool));
  console.log(`OK the plan completed and granted: ${pool.left} of ${pool.granted} ${pool.label} left (from ${pool.grants[0].offeringName})`);

  /* 2 — the shop's Choices page: words, not ids; a pick is an order */
  await page.click('[data-testid=profile-menu] summary');
  // the exact menu item — a text match would hit "Chloe Choices…", the customer's own name
  await page.getByRole('menuitem', { name: 'Choices', exact: true }).click();
  await page.waitForSelector('[data-testid=choices-page]', { timeout: 15000 });
  const summary = await page.locator('[data-testid=choices-summary]').innerText();
  if (!/2\s+of\s+2\s+Choices\s+left/.test(summary)) fail('the page should say "2 of 2 Choices left": ' + summary);
  if (/[0-9a-f]{8}-[0-9a-f]{4}-/.test(await page.locator('[data-testid=choices-page]').innerText())) {
    fail('the Choices page shows an id to a customer');
  }
  const card = page.locator('[data-testid=benefit-card]', { hasText: stream.name });
  await card.locator('[data-testid=pick-benefit]').click();
  await page.waitForSelector('[data-testid=choices-notice]', { timeout: 15000 });
  let row = null;
  for (let i = 0; i < 30; i++) {
    await sleep(1500);
    pool = (await call(page, 'GET', `${CHOICES}/pool`, chloe)).body;
    row = pool.allocations.find((a) => a.offeringName === stream.name);
    if (row?.state === 'committed') break;
  }
  if (row?.state !== 'committed') fail(`${stream.name} never became active: ` + JSON.stringify(pool));
  if (pool.left !== 1 || pool.inUse !== 1) fail('after one pick 1 Choice should be left: ' + JSON.stringify(pool));
  await page.reload();
  await page.waitForSelector('[data-testid=choice-row]', { timeout: 15000 });
  await page.waitForSelector('[data-testid=benefit-card]', { timeout: 30000 });
  const picked = await page.locator('[data-testid=benefit-card]', { hasText: stream.name })
    .locator('[data-testid=pick-benefit]').innerText();
  if (picked !== 'Picked') fail(`${stream.name} should read "Picked" on its card, reads "${picked}"`);
  const shown = await page.locator('[data-testid=choice-row]', { hasText: stream.name })
    .locator('[data-testid=choice-state]').innerText();
  if (shown !== 'Active') fail(`the page should show ${stream.name} as Active, shows ${shown}`);
  require('fs').mkdirSync(SHOTS, { recursive: true });
  await page.screenshot({ path: `${SHOTS}/benefit_choices_page.png`, fullPage: true });
  console.log(`OK picked ${stream.name} on the Choices page: held, then active once the partner confirmed — 1 left`);

  /* A1 — two simultaneous picks for the last free Choice */
  const race = await page.evaluate(async ({ url, token, body }) => {
    const one = () => fetch(url, { method: 'POST', body: JSON.stringify(body),
      headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json', 'X-Channel': 'web' } })
      .then(async (r) => ({ status: r.status, body: await r.json().catch(() => ({})) }));
    return Promise.all([one(), one()]);
  }, { url: ORDERS.replace(API, ''), token: chloe, body: orderOf(audio) });
  const statuses = race.map((r) => r.status).sort();
  if (statuses[0] !== 201 || statuses[1] !== 409) fail('A1: expected one 201 and one 409, got ' + JSON.stringify(race));
  const refusal = race.find((r) => r.status === 409).body.message;
  if (!/costs 1 of your Choices and you have 0 left/.test(refusal)) fail('A1: the refusal should say why: ' + refusal);
  pool = (await call(page, 'GET', `${CHOICES}/pool`, chloe)).body;
  if (pool.inUse !== 2 || pool.left !== 0) fail('A1: the pool should be fully used, never over: ' + JSON.stringify(pool));
  console.log(`OK A1: two picks raced for the last Choice — one placed, one refused: "${refusal}"`);

  /* A2 — the same order item held twice: one allocation, spent once (ordering's own machine identity) */
  const machine = (await (await ctx.post(`${KC}/bss/protocol/openid-connect/token`, { form: {
    grant_type: 'client_credentials', client_id: 'bss-ordering', client_secret: 'ordering-secret' } })).json()).access_token;
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const email2 = `dag-${run}@example.com`;
  const staff = (await (await ctx.post(`${KC}/bss/protocol/openid-connect/token`, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json()).access_token;
  const login2 = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email: email2, givenName: 'Dag', familyName: `Other${run}` } })).json();
  const dag = (await (await ctx.post(`${KC}/bss/protocol/openid-connect/token`, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: email2, password: login2.temporaryPassword } })).json()).access_token;
  const dagId = JSON.parse(Buffer.from(dag.split('.')[1], 'base64url')).sub;
  const settle = await ctx.post(`${CHOICES}/order/a2-plan-${run}/settle`, { headers: H(machine), data: {
    ownerPartyId: dagId, grants: [{ orderItemId: '1', offeringId: plan.id, offeringName: plan.name, choices: 2 }] } });
  if (settle.status() !== 200) fail('A2: settle refused ' + settle.status());
  const hold = { ownerPartyId: dagId, orderId: `a2-${run}`, orderItemId: '1', offeringId: audio.id,
    offeringName: audio.name, cost: 1 };
  const h1 = await (await ctx.post(`${CHOICES}/hold`, { headers: H(machine), data: hold })).json();
  const h2 = await (await ctx.post(`${CHOICES}/hold`, { headers: H(machine), data: hold })).json();
  if (!h1.id || h1.id !== h2.id) fail('A2: the same item held twice made two allocations: ' + JSON.stringify([h1, h2]));
  const dagPool = await (await ctx.get(`${CHOICES}/pool`, { headers: H(dag) })).json();
  if (dagPool.inUse !== 1) fail('A2: a repeated hold spent twice: ' + JSON.stringify(dagPool));
  await ctx.post(`${CHOICES}/order/a2-${run}/release`, { headers: H(machine) });
  console.log('OK A2: the same order item held twice is one allocation; the household spent 1, not 2');

  /* A8 — households and tenants are walls */
  const peek = await (await ctx.get(`${CHOICES}/pool?ownerPartyId=${encodeURIComponent(
    JSON.parse(Buffer.from(chloe.split('.')[1], 'base64url')).sub)}`, { headers: H(dag) })).json();
  if (peek.allocations?.some((a) => a.offeringName === stream.name)) fail("A8: Dag read Chloe's pool");
  const spend = await ctx.post(`${CHOICES}/hold`, { headers: H(dag), data: { ...hold, orderId: `a8-${run}` } });
  if (spend.status() !== 403) fail('A8: a customer token moved Choices: ' + spend.status());
  // a token without benefit:read (a machine that never needed it) is refused; staff who hold it may read
  const loyaltyMachine = (await (await ctx.post(`${KC}/bss/protocol/openid-connect/token`, { form: {
    grant_type: 'client_credentials', client_id: 'bss-loyalty', client_secret: 'loyalty-secret' } })).json()).access_token;
  const machinePeek = await ctx.get(`${CHOICES}/pool?ownerPartyId=${dagId}`, { headers: H(loyaltyMachine) });
  if (machinePeek.status() !== 403) fail('A8: a token without benefit:read read a pool: ' + machinePeek.status());
  const staffPeek = await ctx.get(`${CHOICES}/pool?ownerPartyId=${dagId}`, { headers: H(staff) });
  if (staffPeek.status() !== 200) fail('A8: staff with benefit:read could not read a pool: ' + staffPeek.status());
  const nils = (await (await ctx.post(`${KC}/nova/protocol/openid-connect/token`, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: 'nils@nova.example', password: 'nils' } })).json()).access_token;
  if (!nils) fail('A8: could not sign in as the nova persona');
  const novaPool = await (await ctx.get(`${CHOICES}/pool`, { headers: H(nils) })).json();
  if (novaPool.offered !== false || novaPool.granted !== 0) fail('A8: nova does not offer Choices: ' + JSON.stringify(novaPool));
  console.log("OK A8: a household reads only its own pool, a customer cannot move Choices, only benefit:read may read another household, and nova (no Choices) says so");

  /* ---- swap and release (#356) ---- */
  const PARTNER = 'http://localhost:8164';
  const setMode = async (modes) => { await ctx.delete(`${PARTNER}/switches`); if (modes) await ctx.put(`${PARTNER}/switches`, { data: modes }); };
  const poolOf = async () => (await call(page, 'GET', `${CHOICES}/pool`, chloe)).body;
  const productFor = async (name) => {
    const pool = await poolOf();
    const a = pool.allocations.find((x) => x.offeringName === name && x.state === 'committed');
    if (!a) return null;
    const prods = (await call(page, 'GET', `${API}/tmf-api/productInventory/v4/product?status=active`, chloe)).body || [];
    return prods.find((p) => (p.productOrderItem || []).some((l) => l.productOrderId === a.orderId)) || null;
  };
  const settled = async (what, check, ms = 45000) => {
    for (let w = 0; w < ms; w += 1500) { const v = await check(); if (v) return v; await sleep(1500); }
    fail(`${what} never happened`);
    return null;
  };
  await setMode(null);
  pool = await poolOf();
  if (pool.inUse !== 2) fail('before swaps both Choices should be in use: ' + JSON.stringify(pool));

  // release, on the page
  await page.reload();
  await page.waitForSelector('[data-testid=choice-row]', { timeout: 15000 });
  await settled('the release button', async () => (await page.locator('[data-testid=choice-row]', { hasText: audio.name })
    .locator('[data-testid=release-benefit]').count()) > 0, 20000);
  await page.locator('[data-testid=choice-row]', { hasText: audio.name }).locator('[data-testid=release-benefit]').click();
  await page.waitForSelector('[data-testid=choices-notice]', { timeout: 15000 });
  pool = await settled('the release giving the Choice back', async () => { const p = await poolOf(); return p.left === 1 ? p : null; });
  if (pool.allocations.some((a) => a.offeringName === audio.name)) fail(`${audio.name} is still in use after the release`);
  console.log(`OK release: ${audio.name} given back on the page — ${pool.left} of ${pool.granted} left`);

  // A7 — swap StreamCo for Lydbok+ while the partner confirms LATE: the old stays until the new is active
  await setMode({ [audio.name]: 'late' });
  const old1 = await productFor(stream.name);
  if (!old1) fail('no StreamCo product to swap');
  const swap1 = await call(page, 'POST', ORDERS, chloe, { productOrderItem: [
    { id: '1', action: 'delete', product: { id: old1.id } },
    { id: '2', action: 'add', quantity: 1, productOffering: { id: audio.id, name: audio.name } }] });
  if (swap1.status !== 201) fail('A7: swap refused: ' + JSON.stringify(swap1.body));
  await sleep(1500);
  const mid = (await call(page, 'GET', `${API}/tmf-api/serviceInventory/v4/service`, chloe)).body || [];
  if (!mid.some((s) => s.name === stream.name && s.state === 'active')) fail('A7: the old benefit stopped before the new one was active');
  pool = await settled('the late swap landing', async () => { const p = await poolOf();
    return p.allocations.some((a) => a.offeringName === audio.name && a.state === 'committed')
      && !p.allocations.some((a) => a.offeringName === stream.name) ? p : null; });
  if (pool.swapsThisQuarter !== 1 || pool.left !== 1) fail('A7: after one swap: 1 swap, 1 left — ' + JSON.stringify(pool));
  console.log(`OK A7 swap: ${stream.name} stayed active while ${audio.name} was activating, then ended — swaps this quarter: 1`);

  // A7 — a swap whose new benefit the partner REFUSES: the old stays, the hold comes back
  await setMode({ [stream.name]: 'refuse' });
  const old2 = await productFor(audio.name);
  if (!old2) fail('no Lydbok+ product to swap');
  const swap2 = await call(page, 'POST', ORDERS, chloe, { productOrderItem: [
    { id: '1', action: 'delete', product: { id: old2.id } },
    { id: '2', action: 'add', quantity: 1, productOffering: { id: stream.id, name: stream.name } }] });
  await settled('the refused swap failing', async () =>
    (await call(page, 'GET', `${ORDERS}/${swap2.body.id}`, chloe)).body.state === 'failed');
  pool = await poolOf();
  if (!pool.allocations.some((a) => a.offeringName === audio.name && a.state === 'committed') || pool.left !== 1) {
    fail('A7: a refused swap must keep the old benefit and give the hold back: ' + JSON.stringify(pool));
  }
  if (pool.swapsThisQuarter !== 1) fail('A7: a swap that did not happen counted as a swap');
  console.log(`OK A7 refused swap: order failed, ${audio.name} kept, the Choice it held came back`);

  // A4 — a plain pick the partner refuses gives its Choice back
  const refusedPick = await call(page, 'POST', ORDERS, chloe, orderOf(stream));
  await settled('the refused pick failing', async () =>
    (await call(page, 'GET', `${ORDERS}/${refusedPick.body.id}`, chloe)).body.state === 'failed');
  pool = await poolOf();
  if (pool.left !== 1 || pool.allocations.some((a) => a.offeringName === stream.name)) fail('A4: the refused pick kept its Choice: ' + JSON.stringify(pool));
  console.log('OK A4: a refused pick gave its Choice back — still 1 left');

  // A6 — the swap limit: two more swaps reach 3; the fourth is refused, with the date
  await setMode(null);
  for (const [from, to] of [[audio, stream], [stream, audio]]) {
    // the pool and inventory are two services: wait for the product the pool's allocation names
    const p = await settled(`the ${from.name} product`, () => productFor(from.name), 20000);
    const r = await call(page, 'POST', ORDERS, chloe, { productOrderItem: [
      { id: '1', action: 'delete', product: { id: p.id } },
      { id: '2', action: 'add', quantity: 1, productOffering: { id: to.id, name: to.name } }] });
    if (r.status !== 201) fail(`A6: swap ${from.name} → ${to.name} refused early: ` + JSON.stringify(r.body));
    await settled(`the swap to ${to.name}`, async () => (await poolOf()).allocations
      .some((a) => a.offeringName === to.name && a.state === 'committed'));
  }
  pool = await poolOf();
  if (pool.swapsThisQuarter !== 3) fail('A6: three swaps should be counted: ' + pool.swapsThisQuarter);
  const p4 = await settled(`the ${audio.name} product in inventory`, () => productFor(audio.name), 20000);
  const fourth = await call(page, 'POST', ORDERS, chloe, { productOrderItem: [
    { id: '1', action: 'delete', product: { id: p4.id } },
    { id: '2', action: 'add', quantity: 1, productOffering: { id: stream.id, name: stream.name } }] });
  if (fourth.status === 201) fail('A6: the fourth swap this quarter was accepted');
  if (!fourth.body.message?.includes(`possible from ${pool.nextQuarterStarts}`)) fail('A6: the refusal should name the date: ' + fourth.body.message);
  console.log(`OK A6: the 4th swap this quarter refused (${fourth.status}): "${fourth.body.message}"`);

  /* ---- plan lifecycle (#357), on a second household ---- */
  await setMode(null);
  // a fresh staff token: the one minted at the start may have expired by now
  const staffNow = (await (await ctx.post(`${KC}/bss/protocol/openid-connect/token`, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json()).access_token;
  const phone = by('PhoneCo Upgrade Pass');
  const midPlan = by('GenAlpha Mobile 60 GB 5G');
  const linePlan = by('GenAlpha Mobile 50 GB');   // a line with no commitment, so it may be ceased
  const emailH = `hedda-${run}@example.com`;
  const madeH = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staffNow), data: { email: emailH, givenName: 'Hedda', familyName: `Plans${run}` } })).json();
  const hedda = (await (await ctx.post(`${KC}/bss/protocol/openid-connect/token`, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: emailH, password: madeH.temporaryPassword } })).json()).access_token;
  const HH = { ...H(hedda), 'X-Channel': 'web' };
  const hPool = async () => (await (await ctx.get(`${CHOICES}/pool`, { headers: HH })).json());
  const hOrder = async (items) => { const r = await ctx.post(ORDERS, { headers: HH, data: { productOrderItem: items } });
    return { status: r.status(), body: await r.json().catch(() => ({})) }; };
  const add = (o, id = '1') => ({ id, action: 'add', quantity: 1, productOffering: { id: o.id, name: o.name } });
  const hProduct = async (name) => ((await (await ctx.get(`${API}/tmf-api/productInventory/v4/product?status=active`,
    { headers: HH })).json()) || []).find((p) => p.name === name);

  await hOrder([add(plan)]);
  await settled('Hedda\'s plan granting 2', async () => (await hPool()).granted === 2);
  await hOrder([add(stream)]);
  await hOrder([add(audio)]);
  await settled('both of Hedda\'s picks in use', async () => (await hPool()).allocations.filter((a) => a.state === 'committed').length === 2);

  // A5 — downgrade below what is in use: refused, with the benefits named
  const bigPlan = await settled('the plan product', () => hProduct(plan.name), 20000);
  const down = () => hOrder([{ id: '1', action: 'modify', product: { id: bigPlan.id },
    productOffering: { id: midPlan.id, name: midPlan.name } }]);
  const refusedDown = await down();
  if (refusedDown.status !== 409 || !/gives 1 Choice and 2 are in use/.test(refusedDown.body.message || '')) {
    fail('A5: the downgrade should be refused with the numbers: ' + refusedDown.status + ' ' + JSON.stringify(refusedDown.body));
  }
  if (!refusedDown.body.message.includes(stream.name) || !refusedDown.body.message.includes(audio.name)) fail('A5: the refusal should name the benefits');
  const audioProduct = await settled('the audiobook product', () => hProduct(audio.name), 20000);
  await hOrder([{ id: '1', action: 'delete', product: { id: audioProduct.id } }]);
  await settled('the release', async () => (await hPool()).inUse === 1);
  const okDown = await down();
  if (okDown.status !== 201) fail('A5: after a release the downgrade should go through: ' + JSON.stringify(okDown.body));
  const afterDown = await settled('the downgrade re-granting 1', async () => { const p = await hPool(); return p.granted === 1 ? p : null; });
  console.log(`OK A5: downgrade refused ("${refusedDown.body.message}"), then went through after a release — granted ${afterDown.granted}, from ${afterDown.grants[0].offeringName}`);

  // another plan (a second line) adds its Choices
  await hOrder([add(linePlan)]);
  await settled('the second line granting', async () => (await hPool()).granted === 2);
  console.log('OK a second line on Mobile 50 GB added its Choice — granted 2');

  // a held benefit: PhoneCo holds its Choice 12 months (GenAlpha names no country: the EU cap applies)
  await hOrder([add(phone)]);
  const held = await settled('PhoneCo committed with a date', async () =>
    (await hPool()).allocations.find((a) => a.offeringName === phone.name && a.state === 'committed' && a.heldUntil));
  const expect = new Date(); expect.setUTCMonth(expect.getUTCMonth() + 12);
  if (held.heldUntil !== expect.toISOString().slice(0, 10)) fail(`a 12-month hold should end ${expect.toISOString().slice(0, 10)}, says ${held.heldUntil}`);
  const phoneProduct = await settled('the PhoneCo product', () => hProduct(phone.name), 20000);
  const early = await hOrder([{ id: '1', action: 'delete', product: { id: phoneProduct.id } }]);
  if (early.status !== 409 || !early.body.message?.includes(`until ${held.heldUntil}`)) fail('a held benefit was released early: ' + JSON.stringify(early.body));
  console.log(`OK hold: ${phone.name} held until ${held.heldUntil}; an early release is refused: "${early.body.message}"`);
  // and the household SEES it: Hedda's own Choices page, signed in through the shop
  const hPage = await (await browser.newContext({ viewport: { width: 1280, height: 900 } })).newPage();
  await hPage.goto(`${API}/shop/`);
  await hPage.click('.who >> text=Sign in');
  await hPage.fill('input[name="username"]', emailH);
  await hPage.fill('input[name="password"]', madeH.temporaryPassword);
  await hPage.click('input[type="submit"], button[type="submit"]');
  await hPage.waitForSelector('.nav', { timeout: 20000 });
  await hPage.goto(`${API}/shop/choices`);
  const heldRow = hPage.locator('[data-testid=choice-row]', { hasText: phone.name });
  await heldRow.locator('[data-testid=held-until]').waitFor({ timeout: 20000 });
  if ((await heldRow.locator('[data-testid=release-benefit]').count()) > 0) fail('a held benefit offers Release on the page');
  await hPage.screenshot({ path: `${SHOTS}/benefit_choices_held.png`, fullPage: true });
  console.log(`OK the page says "${await heldRow.locator('[data-testid=held-until]').innerText()}" and offers no Release`);

  // A9 — a catalog change does not rewrite what was picked
  const catalogUrl = `${API}/tmf-api/productCatalogManagement/v4/productOffering/${stream.id}`;
  await ctx.patch(catalogUrl, { headers: H(staffNow), data: { choiceCost: 2 } });
  const stillOne = (await hPool()).allocations.find((a) => a.offeringName === stream.name);
  await ctx.patch(catalogUrl, { headers: H(staffNow), data: { choiceCost: 1 } });
  if (stillOne?.cost !== 1) fail('A9: a catalog change rewrote a household\'s pick: ' + JSON.stringify(stillOne));
  console.log('OK A9: StreamCo re-priced to 2 Choices in the catalog — Hedda\'s pick still costs the 1 she picked it at');

  // a cease: ending the second line leaves 1 Choice for 2 in use → the newest that is not held ends with it
  const line2 = ((await (await ctx.get(`${API}/tmf-api/productInventory/v4/product?status=active`, { headers: HH })).json()) || [])
    .find((p) => p.name === linePlan.name);
  const cease = await hOrder([{ id: '1', action: 'delete', product: { id: line2.id } }]);
  if (cease.status !== 201) fail('the cease was refused: ' + JSON.stringify(cease.body));
  const ended = (cease.body.productOrderItem || []).filter((i) => i.action === 'delete').map((i) => i.product?.name);
  if (!ended.includes(stream.name) || ended.includes(phone.name)) fail('the cease should end StreamCo (newest not held), not PhoneCo: ' + JSON.stringify(ended));
  const afterCease = await settled('the cease settling', async () => { const p = await hPool(); return p.granted === 1 && p.inUse === 1 ? p : null; });
  if (afterCease.allocations[0].offeringName !== phone.name) fail('after the cease only the held PhoneCo should remain');
  console.log(`OK cease: ending the line also ended ${stream.name} in the same order; ${phone.name} (held) stays — ${afterCease.inUse} of ${afterCease.granted} in use`);

  /* ---- care desk and governed actions (#358), on a third household ---- */
  const staff358 = (await (await ctx.post(`${KC}/bss/protocol/openid-connect/token`, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json()).access_token;
  const emailI = `ingrid-${run}@example.com`;
  const madeI = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff358), data: { email: emailI, givenName: 'Ingrid', familyName: `Desk${run}` } })).json();
  const ingrid = (await (await ctx.post(`${KC}/bss/protocol/openid-connect/token`, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: emailI, password: madeI.temporaryPassword } })).json()).access_token;
  const ingridId = JSON.parse(Buffer.from(ingrid.split('.')[1], 'base64url')).sub;
  // the party record the shop makes at first sign-in (TMF632), so the desk can open her
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: { ...H(ingrid), 'X-Channel': 'web' }, data: {
    givenName: 'Ingrid', familyName: `Desk${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: emailI } }] } });
  await ctx.post(ORDERS, { headers: { ...H(ingrid), 'X-Channel': 'web' }, data: { productOrderItem: [add(plan)] } });
  const iPool = async () => (await (await ctx.get(`${CHOICES}/pool?ownerPartyId=${ingridId}`, { headers: H(staff358) })).json());
  await settled('Ingrid\'s plan granting 2', async () => (await iPool()).granted === 2);

  // a CSR on the desk: sees the Choices in words, picks one through the governed action
  const csr = await (await browser.newContext({ viewport: { width: 1280, height: 1000 } })).newPage();
  global.shotPage = csr;
  await csr.goto(`${API}/csr/`);
  await csr.waitForSelector('input[name="username"]', { timeout: 20000 });
  await csr.fill('input[name="username"]', 'agent-anna');
  await csr.fill('input[name="password"]', 'agent');
  await csr.click('input[type="submit"], button[type="submit"]');
  await csr.waitForSelector('.searchbar', { timeout: 20000 });
  await csr.goto(`${API}/csr/customer/${ingridId}`);
  await csr.click('[data-testid=area-services]');
  const panel = csr.locator('[data-testid=csr-choices]');
  await panel.waitFor({ timeout: 20000 });
  if (!/2 of 2 left/.test(await panel.innerText())) fail('the desk should say "2 of 2 left": ' + await panel.innerText());
  await panel.locator('[data-testid=csr-pick]', { hasText: stream.name }).click();
  const csrSaid = await panel.locator('[data-testid=csr-choices-said]').innerText({ timeout: 20000 });
  await settled('the desk pick committing', async () => (await iPool()).allocations.some((a) => a.offeringName === stream.name && a.state === 'committed'));
  await csr.reload();
  await csr.click('[data-testid=area-services]');
  await csr.locator('[data-testid=csr-choice-row]', { hasText: stream.name }).waitFor({ timeout: 20000 });
  if (/[0-9a-f]{8}-[0-9a-f]{4}-/.test(await csr.locator('[data-testid=csr-choices]').innerText())) fail('the desk shows an id');
  // the whole shelf is on offer, not the first catalog page: the benefits still pickable show
  await csr.locator('[data-testid=csr-pick]', { hasText: audio.name }).waitFor({ timeout: 20000 });
  await csr.screenshot({ path: `${SHOTS}/benefit_choices_desk.png`, fullPage: true });
  console.log(`OK desk: a CSR picked ${stream.name} through pickBenefit — "${csrSaid}"`);

  // the desk assistant (an agent) picks through the ontology API: a receipt names it
  const anna = (await (await ctx.post(`${KC}/bss/protocol/openid-connect/token`, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: 'agent-anna', password: 'agent' } })).json()).access_token;
  const AG = { ...H(anna), 'X-Channel': 'care', 'X-GenAlpha-Agent': 'care-assist' };
  const agentPick = await ctx.post(`${API}/ontology/v1/actions/pickBenefit/execute`, { headers: AG,
    data: { customerId: ingridId, benefitOfferingId: audio.id } });
  const agentReceipt = await agentPick.json();
  if (agentPick.status() !== 200 || !agentReceipt.decisionId) fail('the agent pick should execute with a receipt: ' + agentPick.status() + ' ' + JSON.stringify(agentReceipt).slice(0, 300));
  await settled('the agent pick committing', async () => (await iPool()).allocations.some((a) => a.offeringName === audio.name && a.state === 'committed'));
  console.log(`OK agent: care-assist picked ${audio.name} through the ontology — receipt ${agentReceipt.decisionId.slice(0, 8)}…`);

  // goodwill: the agent ASKS; it is filed, not granted; a human approves it
  const ask = await ctx.post(`${API}/ontology/v1/actions/grantGoodwillChoice/execute`, { headers: AG,
    data: { customerId: ingridId, reason: 'outage on 26 October' } });
  const asked = await ask.json();
  if (ask.status() !== 202 || !asked.approvalId) fail('an agent\'s goodwill must be filed for approval: ' + ask.status() + ' ' + JSON.stringify(asked).slice(0, 300));
  if ((await iPool()).granted !== 2) fail('a filed goodwill must not grant anything yet');
  const approved = await ctx.post(`${API}/ai/v1/workforce/approvals/${asked.approvalId}/approve`, { headers: H(staff358) });
  if (!approved.ok()) fail('the approver could not approve: ' + approved.status() + ' ' + (await approved.text()).slice(0, 200));
  const afterGoodwill = await settled('the goodwill Choice', async () => { const p = await iPool(); return p.granted === 3 ? p : null; });
  const gw = afterGoodwill.grants.find((g) => /Goodwill/.test(g.offeringName));
  console.log(`OK goodwill: the agent's request was filed (${asked.approvalId.slice(0, 8)}…), a human approved it — "${gw.offeringName}", granted ${afterGoodwill.granted}`);

  /* ---- authoring (#359): the back office, no JSON ---- */
  const bo = await (await browser.newContext({ viewport: { width: 1360, height: 1000 } })).newPage();
  global.shotPage = bo;
  await bo.goto(`${API}/console/`);
  await bo.waitForSelector('input[name="username"]', { timeout: 20000 });
  await bo.fill('input[name="username"]', 'demo');
  await bo.fill('input[name="password"]', 'demo');
  await bo.click('input[type="submit"], button[type="submit"]');
  await bo.waitForSelector('#username', { timeout: 30000 });
  await bo.locator('.tab', { hasText: /^Benefit Choices$/ }).first().click();
  const desk = bo.locator('[data-testid=island-choices]');
  await desk.locator('[data-testid=choice-plans]').waitFor({ timeout: 30000 });
  // a plan's grant, set on the desk
  const planRow = desk.locator('[data-testid=choice-plans] tr[data-plan="GenAlpha Mobile 30GB 5G"]');
  await planRow.locator('input').fill('1');
  await planRow.locator('button', { hasText: 'Save' }).click();
  await desk.locator('[data-testid=choices-said]', { hasText: 'GenAlpha Mobile 30GB 5G now grants 1 Choice per line' }).waitFor({ timeout: 20000 });
  // a benefit, made on the desk
  const newName = `PlayCo Arcade ${run}`;
  const form = desk.locator('[data-testid=benefit-new]');
  await form.locator('[name=name]').fill(newName);
  await form.locator('[name=group]').selectOption('AI');
  await form.locator('[name=partner]').fill('PlayCo');
  await form.locator('[name=terms]').fill('https://playco.example/terms');
  await form.locator('[name=wholesale]').fill('3.50');
  await form.locator('[name=description]').fill('Cloud games from PlayCo, picked with one of your Choices.');
  await form.locator('button[type=submit]').click();
  await desk.locator('[data-testid=choices-said]', { hasText: `${newName} is made and on sale` }).waitFor({ timeout: 30000 });
  if ((await bo.locator('#island-panel textarea').count()) > 0) fail('the authoring desk shows a JSON box');
  await bo.screenshot({ path: `${SHOTS}/benefit_choices_authoring.png`, fullPage: true });
  const authored = (await (await ctx.get(`${API}/tmf-api/productCatalogManagement/v4/productOffering?name=${encodeURIComponent(newName)}`)).json())[0];
  if (!authored || authored.choiceCost !== 1 || authored.choicePartner !== 'PlayCo' || authored.choiceWholesaleCost !== 3.5) {
    fail('the authored benefit should carry its cost, partner and wholesale cost: ' + JSON.stringify(authored));
  }
  // a household picks it — Ingrid has one Choice left
  await ctx.post(ORDERS, { headers: { ...H(ingrid), 'X-Channel': 'web' }, data: { productOrderItem: [add(authored)] } });
  await settled('the authored benefit being picked', async () => (await iPool()).allocations
    .some((a) => a.offeringName === newName && a.state === 'committed'));
  console.log(`OK authoring: the 30GB plan now grants 1 per line, and "${newName}" was made on the desk (no JSON) and picked by a household`);

  // a governed tenant: Taranga's benefits wait for an approver like any offering
  const tTok = async (u, pw) => (await (await ctx.post(`${KC}/taranga/protocol/openid-connect/token`, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: u, password: pw } })).json()).access_token;
  const sigrid = await tTok('sigrid@taranga.example', 'sigrid');
  const henrik = await tTok('henrik@taranga.example', 'henrik');
  const CAT = `${API}/tmf-api/productCatalogManagement/v4`;
  const draft = await (await ctx.post(`${CAT}/productOffering`, { headers: H(sigrid), data: {
    name: `Lydbok+ Familie ${run}`, isBundle: false, choiceCost: 1, choicePartner: 'Lydbok+', choiceWholesaleCost: 4 } })).json();
  if (draft.lifecycleStatus === 'Active' || draft.lifecycleStatus === 'Launched') fail('a Taranga benefit went on sale without approval: ' + draft.lifecycleStatus);
  const gov = (id, door, tok, body) => ctx.post(`${CAT}/productOffering/${id}/governance/${door}`, { headers: H(tok), data: body || {} });
  const selfApprove = await gov(draft.id, 'approve', sigrid, {});
  if (selfApprove.ok()) fail('the product manager approved her own benefit');
  const launched = await gov(draft.id, 'launch', henrik, { force: true, note: 'benefit approved' });
  if (!launched.ok()) fail('the approver could not launch the benefit: ' + launched.status());
  const onSale = await (await ctx.get(`${CAT}/productOffering/${draft.id}`, { headers: H(henrik) })).json();
  if (!['Launched', 'Active'].includes(onSale.lifecycleStatus) || onSale.choiceCost !== 1) fail('the approver launch did not put the benefit on sale: ' + JSON.stringify(onSale).slice(0, 200));
  await gov(draft.id, 'unlaunch', henrik, { note: 'test cleanup' });
  console.log(`OK governance: a Taranga benefit was "${draft.lifecycleStatus}" until an approver launched it (${onSale.lifecycleStatus}); the manager could not approve her own; unlaunched after`);

  await browser.close();
  console.log('\nALL BENEFIT-CHOICES CHECKS PASSED — grants, picks, never oversold, never spent twice, walls hold; release, swaps that wait for the new benefit, refusals that give Choices back, the swap limit; downgrades, lines, holds and a cease that keeps what is held; the care desk, an agent pick with a receipt, and goodwill only by a human; benefits authored with no JSON, under launch governance.');
})().catch(async (e) => {
  console.error('FAIL:', e.message.split('\n')[0]);
  try { require('fs').mkdirSync(SHOTS, { recursive: true }); await global.shotPage?.screenshot({ path: `${SHOTS}/benefit_choices_FAIL.png`, fullPage: true }); } catch { /* no page to show */ }
  process.exit(1);
});
