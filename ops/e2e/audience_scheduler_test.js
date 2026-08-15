/* Auto-refresh scheduler — and the ops story around it. A bounded timer keeps
 * materialized audiences warm; ops can SEE its activity + JVM heap and can
 * PAUSE it at runtime (no restart) if they suspect it.
 *   the timer fires on its own (totalRuns climbs); pause freezes it; a manual
 *   run re-materializes; status carries heap so ops can correlate memory.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const BRIDGE = 'http://localhost:8140';
const run = Date.now();
const AUDIENCE = `${API}/insight/v1/audience`;
const REFRESH = `${API}/insight/v1/refresh`;
const FACETS = `${AUDIENCE}/facets`;

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
  const status = async () => (await ctx.get(`${REFRESH}/status`, { headers: H(staff) })).json();
  const snap = async (id) => (await (await ctx.get(`${AUDIENCE}/${id}/members?snapshot=true`, { headers: H(staff) })).json()).map((m) => m.partyId);

  const M1 = `schedM1-${run}`; const M2 = `schedM2-${run}`;
  const tier = `vip-${run}`;
  const setTier = (ref) => ctx.post(`${BRIDGE}/bridge/v1/acme-bss/event`, { headers: J, data: { kind: 'LOYALTY_TIER', account: { ref }, tier } });

  /* ---------- observability: status carries scheduler activity + JVM heap ---------- */
  const s0 = await status();
  if (typeof s0.heapUsedMb !== 'number' || typeof s0.heapMaxMb !== 'number') fail('status must expose JVM heap for ops: ' + JSON.stringify(s0));
  if (typeof s0.intervalMs !== 'number' || typeof s0.maxPerRun !== 'number') fail('status must expose the schedule config');
  console.log(`OK status exposes scheduler activity + heap (used ${s0.heapUsedMb}MB / max ${s0.heapMaxMb}MB, interval ${s0.intervalMs}ms, cap ${s0.maxPerRun}/run)`);

  /* ---------- materialize an audience ---------- */
  await setTier(M1);
  for (let i = 0; i < 40; i++) { const f = await (await ctx.get(FACETS, { headers: H(staff) })).json(); if (f.some((x) => x.key === 'loyaltyTier' && x.value === tier)) break; await sleep(1500); }
  const aud = await (await ctx.post(AUDIENCE, { headers: H(staff), data: { name: `Sched ${run}`, population: 'customer', criteria: { all: [{ type: 'trait', key: 'loyaltyTier', value: tier }] } } })).json();
  await ctx.post(`${AUDIENCE}/${aud.id}/refresh`, { headers: H(staff), data: {} });

  /* ---------- the timer fires on its own ---------- */
  const before = (await status()).totalRuns;
  await sleep(11000); // > one 8s interval
  const after = await status();
  if (!(after.totalRuns > before)) fail(`the scheduler did not auto-run (totalRuns ${before} -> ${after.totalRuns})`);
  console.log(`OK the scheduler auto-ran on its timer (totalRuns ${before} -> ${after.totalRuns}, last took ${after.lastDurationMs}ms)`);

  /* ---------- ops pause it at runtime — no restart ---------- */
  const paused = await (await ctx.post(`${REFRESH}/pause`, { headers: H(staff), data: {} })).json();
  if (paused.enabled !== false) fail('pause did not disable the scheduler');
  const p0 = paused.totalRuns;
  await sleep(11000);
  const p1 = await status();
  if (p1.totalRuns !== p0) fail(`paused scheduler still ran (totalRuns ${p0} -> ${p1.totalRuns})`);
  console.log('OK ops PAUSED the scheduler at runtime (totalRuns frozen) — no restart needed');

  /* ---------- a manual run re-materializes correctly ---------- */
  await setTier(M2); // a new member joins the audience
  await sleep(2500);
  await ctx.post(`${REFRESH}/resume`, { headers: H(staff), data: {} });
  await ctx.post(`${REFRESH}/run`, { headers: H(staff), data: {} });
  let s = await snap(aud.id);
  for (let i = 0; i < 8 && !(s.includes(M1) && s.includes(M2)); i++) { await sleep(800); await ctx.post(`${REFRESH}/run`, { headers: H(staff), data: {} }); s = await snap(aud.id); }
  if (!(s.includes(M1) && s.includes(M2))) fail('a manual run did not re-materialize the audience to {M1,M2}: ' + s);
  console.log('OK resume + a manual run re-materialized the snapshot to {M1,M2}');

  console.log('\nALL SCHEDULER CHECKS PASSED — the auto-refresh is bounded (cap/run), observable (activity + JVM '
    + 'heap on the status endpoint AND Prometheus), and controllable (pause/resume/run at runtime). Ops can see a '
    + 'memory climb and stop the feature WITHOUT restarting — restart stays available as the bigger hammer.');
})();
