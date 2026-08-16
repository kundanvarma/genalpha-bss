/* O2a — funnel analytics. Runs a controlled set of deals through the stages,
 * closes some won / some lost, and checks the funnel endpoint reports the
 * conversion, win rate and cycle time — plus a plain-language summary a copilot
 * can narrate. genalpha (bss realm); demo/demo staff.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const SALES = `${API}/tmf-api/salesManagement/v4`;
const run = Date.now();

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

  const qualifty = async (tag) => {
    const lead = await (await ctx.post(`${SALES}/salesLead`, { headers: H, data: { name: `Funnel ${tag} ${run}`, source: 'campaign' } })).json();
    const q = await (await ctx.patch(`${SALES}/salesLead/${lead.id}`, { headers: H, data: { state: 'qualified' } })).json();
    return q.salesOpportunity.id;
  };
  const stage = (id, s) => ctx.patch(`${SALES}/salesOpportunity/${id}`, { headers: H, data: { stage: s } });
  const close = (id, state) => ctx.patch(`${SALES}/salesOpportunity/${id}`, { headers: H, data: { state } });
  const funnel = async () => (await ctx.get(`${SALES}/salesOpportunity/funnel`, { headers: H })).json();
  const reachedFrom = (f, from) => (f.stageConversion.find((c) => c.from === from) || {}).reachedFrom || 0;

  const before = await funnel();

  /* A: qualification → proposal → negotiation → WON */
  const a = await qualifty('A'); await stage(a, 'proposal'); await stage(a, 'negotiation'); await close(a, 'won');
  /* B: qualification → needsAnalysis → LOST */
  const b = await qualifty('B'); await stage(b, 'needsAnalysis'); await close(b, 'lost');
  /* C: qualification → proposal → WON */
  const c = await qualifty('C'); await stage(c, 'proposal'); await close(c, 'won');

  const after = await funnel();

  /* ---------- win rate / counts moved by our closes ---------- */
  if (after.wonCount - before.wonCount !== 2) fail(`wonCount should rise by 2: ${before.wonCount}→${after.wonCount}`);
  if (after.lostCount - before.lostCount !== 1) fail(`lostCount should rise by 1: ${before.lostCount}→${after.lostCount}`);
  if (typeof after.winRatePct !== 'number') fail('winRatePct missing/not a number');
  console.log(`OK win rate is computed from closed deals (${after.winRatePct}% over ${after.wonCount + after.lostCount})`);

  /* ---------- stage-to-stage conversion tracked from history ---------- */
  if (reachedFrom(after, 'qualification') - reachedFrom(before, 'qualification') !== 3) {
    fail('qualification reach should rise by 3 (A,B,C all qualified)');
  }
  const qToNa = after.stageConversion.find((cc) => cc.from === 'qualification' && cc.to === 'needsAnalysis');
  if (!qToNa || typeof qToNa.conversionPct !== 'number') fail('missing qualification→needsAnalysis conversion');
  console.log(`OK stage-to-stage conversion is tracked from history (qualification→needsAnalysis ${qToNa.conversionPct}%)`);

  /* ---------- cycle time + time-in-stage present ---------- */
  if (typeof after.avgCycleDays !== 'number') fail('avgCycleDays missing');
  if (!Array.isArray(after.timeInStage) || !after.timeInStage.length) fail('timeInStage missing');
  console.log(`OK average sales cycle (${after.avgCycleDays} days) and time-in-stage are reported`);

  /* ---------- the copilot-ready narrative ---------- */
  if (typeof after.summary !== 'string' || !/win rate/i.test(after.summary)) {
    fail('missing the plain-language summary: ' + JSON.stringify(after.summary));
  }
  console.log(`OK a plain-language summary is returned for a copilot to narrate:\n   "${after.summary}"`);

  console.log('\nALL FUNNEL-ANALYTICS CHECKS PASSED — conversion, win rate and cycle time come out of the BSS, '
    + 'structured for a dashboard AND narrated for a copilot.');
})();
