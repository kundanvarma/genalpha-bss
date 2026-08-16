/* O2 tail — team roll-up + weekly pipeline snapshot.
 * Two reps on one team; their quotas and won deals roll up to the team.
 * A snapshot captures the open weighted forecast for forecast-over-time.
 * genalpha (bss realm); demo/demo staff.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const SALES = `${API}/tmf-api/salesManagement/v4`;
const run = Date.now();
const TEAM = `TeamA-${run}`;
const AE1 = `AE1-${run}`, AE2 = `AE2-${run}`;
const now = new Date();
const PERIOD = `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`;

async function token(ctx) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const tok = await token(ctx);
  const H = { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' };
  const num = (v) => Number(v);

  const wonDeal = async (owner, amount) => {
    const lead = await (await ctx.post(`${SALES}/salesLead`, { headers: H, data: { name: `TS ${run} ${Math.random()}`, source: 'campaign' } })).json();
    const q = await (await ctx.patch(`${SALES}/salesLead/${lead.id}`, { headers: H, data: { state: 'qualified' } })).json();
    const id = q.salesOpportunity.id;
    await ctx.patch(`${SALES}/salesOpportunity/${id}`, { headers: H, data: { ownerName: owner, amount, stage: 'proposal' } });
    await ctx.patch(`${SALES}/salesOpportunity/${id}`, { headers: H, data: { state: 'won' } });
  };

  /* ---------- two reps on one team, with quotas ---------- */
  await ctx.post(`${SALES}/salesOpportunity/quota`, { headers: H, data: { ownerName: AE1, quotaPeriod: PERIOD, amount: 5000, team: TEAM } });
  await ctx.post(`${SALES}/salesOpportunity/quota`, { headers: H, data: { ownerName: AE2, quotaPeriod: PERIOD, amount: 5000, team: TEAM } });
  await wonDeal(AE1, 3000);
  await wonDeal(AE2, 2000);
  console.log(`OK two reps on ${TEAM}, quotas 5000 each, won 3000 + 2000`);

  /* ---------- attainment rolls up to the team ---------- */
  const att = await (await ctx.get(`${SALES}/salesOpportunity/quotaAttainment?period=${PERIOD}`, { headers: H })).json();
  const team = (att.byTeam || []).find((t) => t.team === TEAM);
  if (!team) fail('no team roll-up row: ' + JSON.stringify(att.byTeam));
  if (num(team.quota) !== 10000 || num(team.won) !== 5000 || team.attainmentPct !== 50) {
    fail('team roll-up wrong (quota 10000, won 5000, 50%): ' + JSON.stringify(team));
  }
  console.log(`OK the team rolls up: quota ${team.quota}, won ${team.won}, ${team.attainmentPct}% attainment`);

  /* ---------- a pipeline snapshot for forecast-over-time ---------- */
  const snap = await (await ctx.post(`${SALES}/salesOpportunity/snapshot`, { headers: H, data: {} })).json();
  if (snap.weightedForecast == null || snap.openCount == null || !snap.capturedAt) {
    fail('snapshot missing fields: ' + JSON.stringify(snap));
  }
  const history = await (await ctx.get(`${SALES}/salesOpportunity/snapshots`, { headers: H })).json();
  if (!Array.isArray(history) || !history.some((s) => s.id === snap.id)) fail('the snapshot is not in the history');
  console.log(`OK a pipeline snapshot captured the open weighted forecast (${snap.weightedForecast}) into the history`);

  console.log('\nALL TEAM + SNAPSHOT CHECKS PASSED — quotas roll up to teams, and the pipeline is snapshotted over '
    + 'time (the weekly job runs this per tenant) so forecast-over-time and slippage are visible.');
})();
