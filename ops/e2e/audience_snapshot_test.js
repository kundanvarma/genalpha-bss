/* Materialized membership: freeze an audience's members into a snapshot so hot
 * audiences read instantly and membership is STABLE between refreshes.
 *   refresh -> snapshot = {A}; a new matching customer arrives; live recomputes
 *   to {A,B} but the snapshot stays {A} until the next refresh -> then {A,B}.
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

  const A = `snapA-${run}`; const B = `snapB-${run}`;
  const tier = `plat-${run}`;
  const setTier = (ref) => ctx.post(`${BRIDGE}/bridge/v1/acme-bss/event`, { headers: J, data: { kind: 'LOYALTY_TIER', account: { ref }, tier } });
  const facetHas = async (v) => { for (let i = 0; i < 40; i++) { const f = await (await ctx.get(FACETS, { headers: H(staff) })).json(); if (f.some((x) => x.key === 'loyaltyTier' && x.value === v)) return true; await sleep(1500); } return false; };
  const membersOf = async (id, snap) => (await (await ctx.get(`${AUDIENCE}/${id}/members${snap ? '?snapshot=true' : ''}`, { headers: H(staff) })).json()).map((m) => m.partyId).filter((p) => p === A || p === B);

  /* ---------- one matching customer, an audience over the trait ---------- */
  await setTier(A);
  if (!await facetHas(tier)) fail('the loyalty trait never landed');
  const aud = await (await ctx.post(AUDIENCE, { headers: H(staff), data: { name: `Snap ${run}`, population: 'customer', criteria: { all: [{ type: 'trait', key: 'loyaltyTier', value: tier }] } } })).json();
  let live = [];
  for (let i = 0; i < 15; i++) { live = await membersOf(aud.id, false); if (live.includes(A)) break; await sleep(800); }
  if (!(live.length === 1 && live.includes(A))) fail('live audience should be {A}: ' + live);

  /* ---------- materialize: freeze the member set ---------- */
  const refreshed = await (await ctx.post(`${AUDIENCE}/${aud.id}/refresh`, { headers: H(staff), data: {} })).json();
  if (refreshed.memberCount !== 1 || !refreshed.materializedAt) fail('refresh did not materialize {A}: ' + JSON.stringify(refreshed));
  const snap1 = await membersOf(aud.id, true);
  if (!(snap1.length === 1 && snap1.includes(A))) fail('snapshot should be {A}: ' + snap1);
  console.log('OK the audience materialized — snapshot = {A}, memberCount 1');

  /* ---------- a new matching customer arrives ---------- */
  await setTier(B);
  // wait until LIVE recomputes to include B (proves the trait landed)
  let live2 = [];
  for (let i = 0; i < 30; i++) { live2 = await membersOf(aud.id, false); if (live2.includes(B)) break; await sleep(1000); }
  if (!(live2.includes(A) && live2.includes(B))) fail('live should recompute to {A,B}: ' + live2);

  /* ---------- the snapshot is STILL {A} — stable between refreshes ---------- */
  const snap2 = await membersOf(aud.id, true);
  if (!(snap2.length === 1 && snap2.includes(A))) fail('the snapshot should be UNCHANGED {A} until refresh: ' + snap2);
  console.log('OK live recomputed to {A,B} but the snapshot stayed {A} — materialized membership is stable');

  /* ---------- refresh again -> snapshot catches up ---------- */
  const r2 = await (await ctx.post(`${AUDIENCE}/${aud.id}/refresh`, { headers: H(staff), data: {} })).json();
  if (r2.memberCount !== 2) fail('the second refresh should materialize 2 members: ' + JSON.stringify(r2));
  const snap3 = await membersOf(aud.id, true);
  if (!(snap3.includes(A) && snap3.includes(B))) fail('after refresh the snapshot should be {A,B}: ' + snap3);
  console.log('OK a refresh caught the snapshot up to {A,B}');

  console.log('\nALL SNAPSHOT CHECKS PASSED — hot audiences materialize to a frozen member set (instant reads, '
    + 'stable membership), and a refresh re-freezes on cadence. The scale tier above live resolution.');
})();
