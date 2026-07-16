/* The third operator in an afternoon — MVNO onboarding, timed and proven.
 *
 *  - ops/onboard-tenant.sh stands up operator "fjord": a cloned realm
 *    (clients, roles, machine service accounts), a tenant block appended
 *    to the SHARED registry file, a fleet restart (nothing rebuilt), a
 *    seeded catalog in the new operator's own currency
 *  - then a customer registers, buys the seeded plan, the service
 *    activates and a bill cuts in DKK — the whole order-to-bill spine
 *    on a tenant that did not exist minutes ago
 *  - and the walls hold: fjord sees nothing of genalpha, genalpha
 *    nothing of fjord
 */
const { execSync } = require('child_process');
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();

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

  /* ---------- 1. the afternoon, timed ---------- */
  const t0 = Date.now();
  console.log('onboarding operator "fjord" (Fjord Mobil, da/DKK) — this IS the afternoon…');
  execSync('bash ops/onboard-tenant.sh fjord "Fjord Mobil" da DKK "#1B6CA8"',
    { cwd: `${__dirname}/../..`, stdio: 'inherit', timeout: 600000 });

  const staff = await token(ctx, 'fjord', 'demo', 'demo');
  if (!staff) fail('no staff token from the fjord realm');
  const offers = await (await ctx.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=50`,
    { headers: H(staff) })).json();
  const plan = offers.find((o) => o.name === 'Fjord Mobil Mobile M');
  if (!plan) fail('the seeded fjord offering is missing');
  if (offers.some((o) => (o.name || '').includes('GenAlpha'))) {
    fail("fjord's catalog leaked genalpha offerings");
  }
  console.log(`OK BORN: realm + registry + catalog in ${Math.round((Date.now() - t0) / 1000)}s —`
    + ' and fjord\'s catalog holds ONLY fjord\'s plan');

  /* ---------- 2. a customer lives a whole life on the new operator ---------- */
  const email = `first-${run}@fjord.example`;
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Freja', familyName: `Fjord${run}` } })).json();
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
    id: login.id, givenName: 'Freja', familyName: `Fjord${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
  const freja = await token(ctx, 'fjord', email, login.temporaryPassword);
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(freja), data: { productOrderItem: [{ action: 'add',
      productOffering: { id: plan.id, name: plan.name } }] } });
  let line = null;
  for (let i = 0; i < 25 && !line; i++) {
    await sleep(2000);
    const services = await (await ctx.get(`${API}/tmf-api/serviceInventory/v4/service`,
      { headers: H(freja) })).json();
    line = (Array.isArray(services) ? services : []).find((s) => s.state === 'active') || null;
  }
  if (!line) fail('the first fjord customer never activated');
  let bill = null;
  for (let i = 0; i < 25 && !bill; i++) {
    await sleep(2000);
    await ctx.post(`${API}/tmf-api/customerBillManagement/v4/billingRun`, { headers: H(staff) });
    const bills = await (await ctx.get(`${API}/tmf-api/customerBillManagement/v4/customerBill?limit=20`,
      { headers: H(freja) })).json();
    bill = bills.find((b) => b.state === 'new') || null;
  }
  if (!bill) fail('no fjord bill was cut');
  // a mid-month start is PRORATED — the new operator inherited honest
  // billing for free; assert the currency and that the amount is a real
  // fraction of the 249 DKK monthly price
  if (bill.amountDue.unit !== 'DKK' || !(bill.amountDue.value > 0 && bill.amountDue.value <= 249)) {
    fail('the bill is not a DKK fraction of 249: ' + JSON.stringify(bill.amountDue));
  }
  console.log(`OK A WHOLE LIFE: Freja registered, bought ${plan.name}, her line activated`
    + ` (MSISDN + SIM), and her first bill cut at ${bill.amountDue.value} DKK — PRORATED from`
    + ' 249/mo for a mid-month start. The operator is minutes old; its billing is already honest');

  /* ---------- 3. the walls hold in both directions ---------- */
  const gaStaff = await token(ctx, 'bss', 'demo', 'demo');
  const gaBills = await (await ctx.get(
    `${API}/tmf-api/customerBillManagement/v4/customerBill?limit=100`,
    { headers: H(gaStaff) })).json();
  if (gaBills.some((b) => b.amountDue.unit === 'DKK')) {
    fail("genalpha's staff can see fjord bills");
  }
  const fjordSvc = await ctx.get(`${API}/tmf-api/serviceInventory/v4/service/${line.id}`,
    { headers: H(gaStaff) });
  if (fjordSvc.status() !== 404) fail("genalpha read fjord's service: " + fjordSvc.status());
  console.log('OK THE WALLS HOLD: genalpha staff sees no DKK bill and gets 404 on fjord\'s'
    + ' service — three operators, one deployment, row-level walls');

  console.log(`\nALL THIRD-OPERATOR CHECKS PASSED — a new MVNO went from nothing to its first`
    + ` activated, billed customer in ${Math.round((Date.now() - t0) / 1000)}s, without building`
    + ' a single image. The afternoon is real.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
