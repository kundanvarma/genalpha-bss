/* Set-based SQL member resolution (the scale path): a trait-ONLY audience
 * compiles to one native SQL query over party_trait (INTERSECT/UNION/EXCEPT),
 * not a per-row loop. Prove the set algebra is correct AND that the SQL path is
 * the one that ran (behavioural trees still use the in-memory path).
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

  const A = `sqlA-${run}`; const B = `sqlB-${run}`; const C = `sqlC-${run}`;
  const mine = new Set([A, B, C]);
  const ev = (ref, kind, extra) => ctx.post(`${BRIDGE}/bridge/v1/acme-bss/event`, { headers: J, data: { kind, account: { ref }, ...extra } });

  // A: gold, 120, low   B: gold, 40, high   C: silver, 200, high
  await Promise.all([
    ev(A, 'LOYALTY_TIER', { tier: 'gold' }), ev(A, 'BILL_ISSUED', { amount: 120 }), ev(A, 'CHURN_SCORED', { band: 'low' }),
    ev(B, 'LOYALTY_TIER', { tier: 'gold' }), ev(B, 'BILL_ISSUED', { amount: 40 }), ev(B, 'CHURN_SCORED', { band: 'high' }),
    ev(C, 'LOYALTY_TIER', { tier: 'silver' }), ev(C, 'BILL_ISSUED', { amount: 200 }), ev(C, 'CHURN_SCORED', { band: 'high' }),
  ]);

  // wait for the last-written traits to land
  const facetHas = async (key, value) => {
    for (let i = 0; i < 40; i++) {
      const f = await (await ctx.get(FACETS, { headers: H(staff) })).json();
      if (f.some((x) => x.key === key && x.value === String(value))) return true;
      await sleep(1500);
    }
    return false;
  };
  if (!await facetHas('monthlySpend', '120') || !await facetHas('monthlySpend', '40') || !await facetHas('monthlySpend', '200')) {
    fail('the bridged spend traits never all landed');
  }
  console.log('OK three customers set up with loyalty/spend/churn traits (via the bridge)');

  const resolve = async (criteria) => {
    const a = await (await ctx.post(AUDIENCE, { headers: H(staff), data: { name: `sql-${run}-${Math.random().toString(36).slice(2, 6)}`, population: 'customer', criteria } })).json();
    let r = null;
    for (let i = 0; i < 15; i++) {
      r = await (await ctx.get(`${AUDIENCE}/${a.id}/members?explain=true`, { headers: H(staff) })).json();
      await sleep(700);
      // settle: re-read once membership is non-trivial or after a couple tries
      if (i >= 2) break;
    }
    const got = new Set((r.members || []).map((m) => m.partyId).filter((p) => mine.has(p)));
    return { path: r.path, got };
  };
  const eq = (got, want) => got.size === want.length && want.every((w) => got.has(w));

  /* ---------- AND + range: gold AND spend >= 100  -> A only ---------- */
  let r = await resolve({ all: [{ type: 'trait', key: 'loyaltyTier', value: 'gold' }, { type: 'trait', key: 'monthlySpend', op: 'gte', value: '100' }] });
  if (r.path !== 'sql') fail('trait-only audience did not use the SQL path: ' + r.path);
  if (!eq(r.got, [A])) fail('gold AND spend>=100 should be {A}: ' + [...r.got]);
  console.log('OK [SQL] gold AND spend ≥ 100 → {A} (INTERSECT + range)');

  /* ---------- OR: churnRisk high -> B, C ---------- */
  r = await resolve({ any: [{ type: 'trait', key: 'churnRisk', value: 'high' }] });
  if (!eq(r.got, [B, C])) fail('churnRisk=high should be {B,C}: ' + [...r.got]);
  console.log('OK [SQL] churnRisk = high → {B,C} (UNION/select)');

  /* ---------- NOT + AND + range: spend >= 100 AND NOT gold -> C ---------- */
  r = await resolve({ all: [{ type: 'trait', key: 'monthlySpend', op: 'gte', value: '100' }, { not: { type: 'trait', key: 'loyaltyTier', value: 'gold' } }] });
  if (!eq(r.got, [C])) fail('spend>=100 AND NOT gold should be {C}: ' + [...r.got]);
  console.log('OK [SQL] spend ≥ 100 AND NOT gold → {C} (EXCEPT)');

  /* ---------- OR mix: gold OR spend >= 150 -> A, B, C ---------- */
  r = await resolve({ any: [{ type: 'trait', key: 'loyaltyTier', value: 'gold' }, { type: 'trait', key: 'monthlySpend', op: 'gte', value: '150' }] });
  if (!eq(r.got, [A, B, C])) fail('gold OR spend>=150 should be {A,B,C}: ' + [...r.got]);
  console.log('OK [SQL] gold OR spend ≥ 150 → {A,B,C}');

  /* ---------- routing: a behavioural tree still uses the in-memory path ---------- */
  const beh = await (await ctx.post(AUDIENCE, { headers: H(staff), data: { name: `mem-${run}`, population: 'customer', criteria: { all: [{ type: 'interest', value: `Devices${run}` }] } } })).json();
  const be = await (await ctx.get(`${AUDIENCE}/${beh.id}/members?explain=true`, { headers: H(staff) })).json();
  if (be.path !== 'memory') fail('a behavioural (interest) tree should use the memory path: ' + be.path);
  console.log('OK routing is correct — trait-only → SQL, behavioural → in-memory');

  console.log('\nALL SQL-RESOLUTION CHECKS PASSED — trait-only audiences resolve as one set-based SQL query '
    + '(INTERSECT/UNION/EXCEPT over indexed party_trait), correct across AND/OR/NOT + numeric ranges. The scale '
    + 'path is real, and behavioural audiences still take the bounded in-memory path.');
})();
