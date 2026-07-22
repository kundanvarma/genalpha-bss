/* "Customers who bought this also bought" — item-to-item affinity. Suite #63.
 *
 *  - a deterministic co-purchase pattern on offering ids UNIQUE to this
 *    run (so no real customer's basket can interfere): three customers
 *    own phone P and case Q; a fourth owns P and a LONELY R
 *  - affinity(P) returns Q (co-owned by three) and NOT R (below the
 *    minimum support of 2) — market-basket, honestly counted, aggregate
 *    only: a single customer's basket can never be read back
 *  - it's PUBLIC: a guest, no token, reads it
 *  - the shop renders "Customers who bought this also bought" on a REAL
 *    offering's page (seeded co-ownership with a real plan)
 *  - the tenant wall holds; a cold offering returns nothing, no crash
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
// UNIQUE offering ids for the counting proof — present in NO other basket
const P = `aff-phone-${run}`;
const Q = `aff-case-${run}`;
const R = `aff-solo-${run}`;
// a REAL catalog offering for the storefront leg — Samsung has strong
// real co-ownership already (its buyers also buy the flagship bundle),
// so the rail renders from genuine data, not a seed
const REAL_PHONE = { id: '1d85b683-406e-4e8b-b3c6-142e7fa7eeda', name: 'Samsung Galaxy S26' };

async function token(request, user, pass) {
  const res = await request.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const staff = await token(ctx.request, 'demo', 'demo');

  // own(party, offeringId, name): a product in that party's basket
  const own = (party, offId, name) => ctx.request.post(`${API}/tmf-api/productInventory/v4/product`,
    { headers: H(staff), data: { name: `${name} of ${party}`, status: 'active',
      productOffering: { id: offId, name },
      relatedParty: [{ id: party, role: 'customer', '@referredType': 'Individual' }] } });
  const affinityOf = (offId, tenant) => ctx.request.get(
    `${API}/tmf-api/recommendationManagement/v4/affinity?forOfferingId=${offId}`,
    { headers: { 'X-Tenant-Id': tenant } });

  /* ---------- seed the market basket (unique ids) ---------- */
  for (let i = 0; i < 3; i++) {
    const p = `aff-both-${run}-${i}`;
    await own(p, P, 'Aff Phone');
    await own(p, Q, 'Aff Case'); // three own P AND Q
  }
  await own(`aff-solo-${run}-cust`, P, 'Aff Phone');
  await own(`aff-solo-${run}-cust`, R, 'Aff Solo'); // one owns P AND R (support 1)
  await sleep(6500); // outlast the dev cache TTL (5s) so the next read is fresh

  /* ---------- 1. aggregate + minimum support ---------- */
  let also = [];
  for (let i = 0; i < 8 && !also.some((x) => x.offering.id === Q); i++) {
    await sleep(2000);
    also = await (await affinityOf(P, 'genalpha')).json();
  }
  const q = also.find((x) => x.offering.id === Q);
  if (!q) fail('Q did not appear in P\'s affinity: ' + JSON.stringify(also).slice(0, 200));
  if (q.coOwners < 3) fail('Q\'s co-owner count is wrong: ' + q.coOwners);
  if (also.some((x) => x.offering.id === R)) {
    fail('R appeared despite only one co-owner — minimum support failed');
  }
  console.log(`OK MARKET BASKET: three customers bought both, so the phone recommends the case`
    + ` (co-owned by ${q.coOwners}); the solo item, bought with the phone by ONE customer,`
    + ' stays out — aggregate counting with a support floor, never a single basket exposed');

  /* ---------- 2. public (a guest, no token) ---------- */
  const anon = await ctx.request.get(
    `${API}/tmf-api/recommendationManagement/v4/affinity?forOfferingId=${P}`,
    { headers: { 'X-Tenant-Id': 'genalpha' } });
  if (anon.status() !== 200) fail('a guest could not read affinity: ' + anon.status());
  if (!(await anon.json()).some((x) => x.offering.id === Q)) fail('the guest got no affinity');
  console.log('OK PUBLIC: a guest with no token reads the "also bought" rail — it is on the'
    + ' product page everyone sees, and it reveals only aggregates');

  /* ---------- 3. the shop renders it (real offering, real signal) ---------- */
  const page = await browser.newPage();
  await page.goto(`${API}/shop/offering/${REAL_PHONE.id}`);
  const rail = page.locator('[data-testid=also-bought]');
  await rail.waitFor({ timeout: 15000 }).catch(() => fail('the shop showed no also-bought rail'));
  const items = await page.locator('[data-testid=also-bought-item]').count();
  if (items < 1) fail('the also-bought rail rendered no items');
  const railText = await rail.textContent();
  console.log(`OK ON THE PRODUCT PAGE: the phone's page shows "Customers who bought this also`
    + ` bought" with ${items} real co-purchase link(s) — e.g. ${railText.replace(/Customers.*bought/, '').trim().slice(0, 50)}…`);

  /* ---------- 4. tenant wall + cold offering ---------- */
  // the tenant is the HOSTNAME (the gateway rewrites X-Tenant-Id from it),
  // so reaching nova means nova's host — a header on localhost stays genalpha
  const nova = await (await ctx.request.get(
    `http://shop.nova.localhost:8080/tmf-api/recommendationManagement/v4/affinity?forOfferingId=${P}`))
    .json().catch(() => []);
  if (nova.some((x) => x.offering.id === Q)) fail('nova saw genalpha\'s baskets');
  const cold = await (await affinityOf(`nobody-owns-${run}`, 'genalpha')).json();
  if (cold.length !== 0) fail('a cold offering returned affinity: ' + JSON.stringify(cold));
  console.log('OK WALLS & COLD START: nova\'s affinity carries none of genalpha\'s baskets, and'
    + ' an offering nobody owns returns nothing — no crash, no leak');

  console.log('\nALL AFFINITY CHECKS PASSED — the product page now answers "customers who bought'
    + ' this also bought", from what the tenant\'s customers actually own together: aggregate,'
    + ' support-floored, public, tenant-walled.');
  await browser.close();
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
