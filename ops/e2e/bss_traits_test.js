/* More BSS traits + range operators: loyalty tier, churn band, monthly spend
 * become audience-able traits; a numeric trait supports >= / <=; single-valued
 * traits REPLACE (was gold, now bronze must stop matching gold).
 * Produced through the bss-bridge (which also proves it handling more event
 * types); in production these arrive on the real loyalty/intelligence/billing
 * topics.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const BRIDGE = 'http://localhost:8140';
const run = Date.now();
const FACETS = `${API}/insight/v1/audience/facets`;
const AUDIENCE = `${API}/insight/v1/audience`;

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'bss', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const J = { 'Content-Type': 'application/json' };
  const cust = `traits-cust-${run}`;
  const bridge = (kind, extra) => ctx.post(`${BRIDGE}/bridge/v1/acme-bss/event`, { headers: J, data: { kind, account: { ref: cust }, ...extra } });

  const audienceHas = async (criteria, wantIn) => {
    const a = await (await ctx.post(AUDIENCE, { headers: H(staff), data: { name: `t-${run}-${Math.random().toString(36).slice(2, 7)}`, population: 'customer', criteria } })).json();
    let members = [];
    for (let i = 0; i < 15; i++) {
      members = await (await ctx.get(`${AUDIENCE}/${a.id}/members`, { headers: H(staff) })).json();
      if (members.some((m) => m.partyId === cust) === wantIn) break;
      await sleep(1000);
    }
    return members.some((m) => m.partyId === cust);
  };
  const facetHas = async (key, value) => {
    for (let i = 0; i < 40; i++) {
      const f = await (await ctx.get(FACETS, { headers: H(staff) })).json();
      if (f.some((x) => x.key === key && x.value === String(value))) return true;
      await sleep(1500);
    }
    return false;
  };

  /* ---------- BSS signals become traits ---------- */
  await bridge('LOYALTY_TIER', { tier: 'gold' });
  await bridge('CHURN_SCORED', { band: 'high' });
  await bridge('BILL_ISSUED', { amount: 75 });
  if (!await facetHas('loyaltyTier', 'gold')) fail('loyaltyTier trait never landed');
  if (!await facetHas('churnRisk', 'high')) fail('churnRisk trait never landed');
  if (!await facetHas('monthlySpend', '75')) fail('monthlySpend trait never landed');
  console.log('OK loyalty tier, churn band and monthly spend all became BSS traits (via the bridge)');

  /* ---------- exact-match band audiences ---------- */
  if (!await audienceHas({ all: [{ type: 'trait', key: 'loyaltyTier', value: 'gold' }] }, true)) fail('loyaltyTier=gold did not resolve');
  if (!await audienceHas({ all: [{ type: 'trait', key: 'churnRisk', value: 'high' }] }, true)) fail('churnRisk=high did not resolve');
  console.log('OK band audiences resolve — loyaltyTier=gold, churnRisk=high');

  /* ---------- NUMERIC RANGE operator ---------- */
  if (!await audienceHas({ all: [{ type: 'trait', key: 'monthlySpend', op: 'gte', value: '50' }] }, true)) fail('monthlySpend >= 50 did not include the 75-spend customer');
  if (await audienceHas({ all: [{ type: 'trait', key: 'monthlySpend', op: 'gte', value: '100' }] }, false)) fail('monthlySpend >= 100 wrongly included the 75-spend customer');
  if (!await audienceHas({ all: [{ type: 'trait', key: 'monthlySpend', op: 'lte', value: '80' }] }, true)) fail('monthlySpend <= 80 did not include the 75-spend customer');
  console.log('OK numeric range operators work — monthlySpend >= 50 ✓, >= 100 ✗, <= 80 ✓');

  /* ---------- combined AND: high-value AND at-risk ---------- */
  if (!await audienceHas({ all: [{ type: 'trait', key: 'monthlySpend', op: 'gte', value: '50' }, { type: 'trait', key: 'churnRisk', value: 'high' }] }, true)) {
    fail('the combined high-value + at-risk audience did not resolve');
  }
  console.log('OK a combined audience resolves — spend ≥ 50 AND churnRisk = high (the retention play)');

  /* ---------- single-valued REPLACE (no stale match) ---------- */
  await bridge('LOYALTY_TIER', { tier: 'bronze' });
  if (!await facetHas('loyaltyTier', 'bronze')) fail('the tier change to bronze never landed');
  if (await audienceHas({ all: [{ type: 'trait', key: 'loyaltyTier', value: 'gold' }] }, false)) fail('gold still matched after the customer moved to bronze (stale trait)');
  if (!await audienceHas({ all: [{ type: 'trait', key: 'loyaltyTier', value: 'bronze' }] }, true)) fail('bronze did not match after the tier change');
  console.log('OK a tier change REPLACED the trait — gold stops matching, bronze matches (no stale segments)');

  console.log('\nALL BSS-TRAITS CHECKS PASSED — loyalty/churn/spend are audience-able BSS signals, numeric traits '
    + 'support range operators, and single-valued traits replace so a segment never matches a stale value.');
})();
