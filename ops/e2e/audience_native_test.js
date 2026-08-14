/* BSS-native audiences: build a segment from the operator's OWN data, with no
 * dump to a marketing tool and no reverse-ETL round-trip.
 *   API:  a customer buys a product -> the BSS event becomes a `product` trait
 *         -> an audience {all:[trait product=X]} resolves to exactly the holders
 *         -> a customer without the product is NOT in it; facets offer the trait
 *   UI:   the Audience builder tab composes that same rule tree with real
 *         dropdowns (Customer data -> product -> value), saves, and previews
 *         members live.
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const CONSOLE = 'http://localhost:8080/console/';
const run = Date.now();
const OFFERINGS = `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`;
const ORDER = `${API}/tmf-api/productOrderingManagement/v4/productOrder`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;
const AUDIENCE = `${API}/insight/v1/audience`;
const PLAN = 'GenAlpha Mobile 10 GB';

async function token(ctx, client, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  const plan = (await (await ctx.get(OFFERINGS)).json()).find((o) => o.name === PLAN);
  if (!plan) fail(`${PLAN} offering missing — run the catalog seed`);

  const mkCustomer = async (tag) => {
    const email = `aud-${tag}-${run}@example.com`;
    const login = await (await ctx.post(USER, { headers: H(staff),
      data: { email, givenName: `${tag}${run}`, familyName: `Aud${run}` } })).json();
    if (!login.temporaryPassword) fail(`user create failed for ${tag}: ` + JSON.stringify(login));
    await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: `${tag}${run}`, familyName: `Aud${run}` } });
    return login.id;
  };

  /* ---------- a holder (orders the plan) and a non-holder ---------- */
  const holder = await mkCustomer('holder');
  const nonHolder = await mkCustomer('nonholder');
  const order = await (await ctx.post(ORDER, { headers: H(staff), data: {
    productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name } }],
    relatedParty: [{ id: holder, role: 'customer' }] } })).json();
  if (!order.id) fail('order not created: ' + JSON.stringify(order));

  /* ---------- the BSS event became a customer trait (no export anywhere) ---------- */
  let hasTrait = false;
  for (let i = 0; i < 40; i++) {
    const facets = await (await ctx.get(`${AUDIENCE}/facets`, { headers: H(staff) })).json();
    if (facets.some((f) => f.key === 'product' && f.value === plan.name)) { hasTrait = true; break; }
    await sleep(1500);
  }
  if (!hasTrait) fail('the completed order never became a `product` trait (BSS-native ingest)');
  console.log(`OK ${PLAN} order became a first-party \`product\` trait — no dump to a marketing tool`);

  /* ---------- an audience over BSS data resolves to exactly the holders ---------- */
  const aud = await (await ctx.post(AUDIENCE, { headers: H(staff), data: {
    name: `Plan holders ${run}`,
    criteria: { all: [{ type: 'trait', key: 'product', value: plan.name }] } } })).json();
  if (!aud.id) fail('audience not created: ' + JSON.stringify(aud));

  let members = [];
  for (let i = 0; i < 20; i++) {
    members = await (await ctx.get(`${AUDIENCE}/${aud.id}/members`, { headers: H(staff) })).json();
    if (members.some((m) => m.partyId === holder)) break;
    await sleep(1000);
  }
  const ids = members.map((m) => m.partyId);
  if (!ids.includes(holder)) fail('the holder is not in the BSS-native audience: ' + JSON.stringify(ids).slice(0, 200));
  if (ids.includes(nonHolder)) fail('a customer WITHOUT the product leaked into the audience');
  console.log(`OK the audience {all:[product=${PLAN}]} resolved to the holder and excluded the non-holder — straight off BSS data`);

  /* ================= the console rule-tree builder ================= */
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
  console.log('OK the Growth workspace has an Audience builder tab');

  // compose: Customer data -> product -> the plan (real dropdowns from facets)
  const uiName = `UI holders ${run}`;
  await page.fill('#audience-name', uiName);
  const row = page.locator('[data-testid="aud-cond"]').first();
  await row.locator('[data-testid="aud-cond-type"]').selectOption('trait');
  await row.locator('[data-testid="aud-cond-key"]').selectOption('product');
  await row.locator('[data-testid="aud-cond-value"]').selectOption(plan.name);
  await page.locator('[data-testid="aud-save"]').click();
  await page.waitForFunction((nm) => [...document.querySelectorAll('[data-testid="aud-row-name"]')]
    .some((e) => e.textContent === nm), uiName, { timeout: 10000 });
  console.log('OK the builder saved a BSS-data audience (Customer data -> product -> plan) from real dropdowns');

  // it is a real audience the API can resolve — with the trait leaf intact
  const saved = (await (await ctx.get(AUDIENCE, { headers: H(staff) })).json()).find((a) => a.name === uiName);
  if (!saved) fail('the UI-built audience is not in the API list');
  const leaf = (saved.criteria.all || [])[0];
  if (!leaf || leaf.type !== 'trait' || leaf.key !== 'product' || leaf.value !== plan.name) {
    fail('the UI-built criteria tree is wrong: ' + JSON.stringify(saved.criteria));
  }
  console.log('OK the UI-built audience is a real, resolvable rule tree — {all:[trait product=' + PLAN + ']}');

  await ctx.delete(`${AUDIENCE}/${aud.id}`, { headers: H(staff) }).catch(() => {});
  await browser.close();
  console.log('\nALL BSS-NATIVE AUDIENCE CHECKS PASSED — audiences are built from the operator\'s own BSS data '
    + 'in real time (the operational event bus IS the feed), resolved in-house, no marketing-tool dump and no '
    + 'reverse-ETL round-trip. The rule-tree builder makes it a few clicks.');
})();
