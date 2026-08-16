/* O2b — lead scoring + routing.
 * Author scoring rules (source + keyword) and a routing band, then capture a
 * lead: it is scored, graded, and auto-assigned to an owner; qualifying it
 * carries that owner onto the opportunity. A no-match lead grades cold.
 * Run-unique rule values + points keep the score deterministic against the
 * tenant's accumulated rules. genalpha (bss realm); demo/demo staff.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const SALES = `${API}/tmf-api/salesManagement/v4`;
const run = Date.now();
const P = 1000 + (run % 100000); // run-unique big points, so our band is unmistakable

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

  /* ---------- author scoring + routing rules ---------- */
  await ctx.post(`${SALES}/salesLead/scoringRule`, { headers: H, data: { field: 'source', value: `camp-${run}`, points: P } });
  await ctx.post(`${SALES}/salesLead/scoringRule`, { headers: H, data: { field: 'keyword', value: `ent-${run}`, points: 40 } });
  await ctx.post(`${SALES}/salesLead/routingRule`, { headers: H, data: { minScore: P + 40, assignee: `AE-${run}` } });
  const rules = await (await ctx.get(`${SALES}/salesLead/scoringRule`, { headers: H })).json();
  if (!rules.some((r) => r.value === `camp-${run}`)) fail('scoring rule not stored');
  console.log('OK authored scoring rules (source + keyword) and a routing band');

  /* ---------- a matching lead is scored, graded, routed ---------- */
  const hot = await (await ctx.post(`${SALES}/salesLead`, { headers: H, data: {
    name: `ent-${run}`, source: `camp-${run}` } })).json();
  if (hot.score !== P + 40) fail(`expected score ${P + 40}, got ${hot.score}`);
  if (hot.grade !== 'hot') fail('a high score should grade hot: ' + JSON.stringify(hot.grade));
  if (!hot.owner || hot.owner.name !== `AE-${run}`) fail('the lead did not route to the AE: ' + JSON.stringify(hot.owner));
  console.log(`OK a matching lead scored ${hot.score}, graded HOT, and routed to AE-${run}`);

  /* ---------- qualifying carries the owner onto the opportunity ---------- */
  const q = await (await ctx.patch(`${SALES}/salesLead/${hot.id}`, { headers: H, data: { state: 'qualified' } })).json();
  const opp = await (await ctx.get(`${SALES}/salesOpportunity/${q.salesOpportunity.id}`, { headers: H })).json();
  if (!opp.owner || opp.owner.name !== `AE-${run}`) fail('the opportunity did not inherit the routed owner: ' + JSON.stringify(opp.owner));
  console.log('OK qualifying the lead carried its routed owner onto the opportunity');

  /* ---------- a no-match lead grades cold ---------- */
  const cold = await (await ctx.post(`${SALES}/salesLead`, { headers: H, data: {
    name: `nc-${run}`, source: `nomatch-${run}` } })).json();
  if (cold.score !== 0 || cold.grade !== 'cold') fail('a no-match lead should score 0 / cold: ' + JSON.stringify({ s: cold.score, g: cold.grade }));
  console.log('OK a lead that matches no rule scores 0 and grades cold');

  console.log('\nALL LEAD-SCORING CHECKS PASSED — leads are scored from rules, graded, and auto-routed to an '
    + 'owner the opportunity inherits; the top of the funnel is now intelligent, not hand-sorted.');
})();
