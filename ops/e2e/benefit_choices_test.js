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
  const by = (n) => offers.find((o) => o.name === n) || fail(`missing offering ${n} — run ops/seed/seed_choices.py`);
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
  const staffPeek = await ctx.get(`${CHOICES}/pool?ownerPartyId=${dagId}`, { headers: H(staff) });
  if (staffPeek.status() !== 403) fail('A8: staff without benefit:read read a pool: ' + staffPeek.status());
  const nils = (await (await ctx.post(`${KC}/nova/protocol/openid-connect/token`, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: 'nils@nova.example', password: 'nils' } })).json()).access_token;
  if (!nils) fail('A8: could not sign in as the nova persona');
  const novaPool = await (await ctx.get(`${CHOICES}/pool`, { headers: H(nils) })).json();
  if (novaPool.offered !== false || novaPool.granted !== 0) fail('A8: nova does not offer Choices: ' + JSON.stringify(novaPool));
  console.log("OK A8: a household reads only its own pool, a customer cannot move Choices, staff need benefit:read, and nova (no Choices) says so");

  await browser.close();
  console.log('\nALL BENEFIT-CHOICES CHECKS PASSED — a plan grants Choices, a pick holds then commits, never oversold, never spent twice, walls hold.');
})().catch(async (e) => {
  console.error('FAIL:', e.message.split('\n')[0]);
  try { require('fs').mkdirSync(SHOTS, { recursive: true }); await global.shotPage?.screenshot({ path: `${SHOTS}/benefit_choices_FAIL.png`, fullPage: true }); } catch { /* no page to show */ }
  process.exit(1);
});
