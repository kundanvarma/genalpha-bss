/* Product-trait retraction: a customer who CANCELS a product should stop matching
 * an audience for it. Product holdings used to be add-only in the CDP (a stale
 * plan lingered forever); now a ProductDelete / status->cancelled on the bus
 * retracts the holding trait, so "customers on Plan X" is honest again.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const PLAN = `RetractPlan-${run}`;
const INGEST = `${API}/insight/v1/traits/backfill`;
const AUD = `${API}/insight/v1/audience`;
const PRODUCT = `${API}/tmf-api/productInventory/v4/product`;

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
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };

  // a customer who holds the plan (seed the trait directly)
  const email = `retract-${run}@example.com`;
  const u = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H, data: { email, givenName: 'Re', familyName: 'Tract' } })).json();
  await ctx.post(INGEST, { headers: H, data: { traits: [{ partyId: u.id, key: 'product', value: PLAN, multi: true }] } });

  const audience = await (await ctx.post(AUD, { headers: H, data: {
    name: `__retract_probe_${run}`, population: 'customer',
    criteria: { all: [{ type: 'trait', key: 'product', op: 'eq', value: PLAN }] } } })).json();
  const inAudience = async () => {
    const m = await (await ctx.get(`${AUD}/${audience.id}/members`, { headers: H })).json();
    return (Array.isArray(m) ? m : []).some((x) => x.partyId === u.id);
  };
  if (!(await inAudience())) fail('the customer is not in the plan audience after seeding the trait');
  console.log(`OK a customer holds ${PLAN} and matches the plan audience`);

  // they hold the product in inventory, then CANCEL it
  const prod = await (await ctx.post(PRODUCT, { headers: H, data: {
    name: PLAN, status: 'active', relatedParty: [{ id: u.id, role: 'owner' }] } })).json();
  if (!prod.id) fail('could not create the inventory product: ' + JSON.stringify(prod).slice(0, 200));
  await ctx.patch(`${PRODUCT}/${prod.id}`, { headers: H, data: { status: 'cancelled' } });
  console.log('OK the customer cancelled the product (status -> cancelled on the bus)');

  // the CDP retracts the holding — the audience drops them
  let gone = false;
  for (let i = 0; i < 25; i++) {
    if (!(await inAudience())) { gone = true; break; }
    await sleep(1000);
  }
  if (!gone) fail('the cancelled product still matches the audience — retraction did not fire');
  console.log('OK the cancelled holding was retracted — the customer no longer matches the plan audience');

  await ctx.delete(`${AUD}/${audience.id}`, { headers: H }); // clean up the probe audience
  console.log('\nALL RETRACTION CHECKS PASSED — product holdings are no longer add-only: a cancellation on '
    + 'the bus retracts the CDP trait, so a plan audience reflects who ACTUALLY holds it, not who ever did.');
})();
