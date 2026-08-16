/* #4 — CDP engagement as a lead-scoring signal.
 * Reach a prospect by email, they open it → the martech loop writes an
 * emailEngagement trait in the CDP. A lead-scoring rule of field `engagement`
 * then reads that CDP signal (quote → insight) and lifts the engaged lead's
 * score. A non-engaged email gets nothing. Run in nova (live ESP path).
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const SALES = `${API}/tmf-api/salesManagement/v4`;
const MSG = `${API}/tmf-api/communicationManagement/v4/communicationMessage`;
const LEADSIG = `${API}/insight/v1/leadSignal`;
const run = Date.now();
const ENGAGED = `hot-${run}@nova.example`;
const COLD = `cold-${run}@nova.example`;
const P = 5000 + (run % 90000);                 // run-unique points

async function token(ctx) {
  const res = await ctx.post('http://localhost:8085/realms/nova/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const tok = await token(ctx);
  const H = { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' };
  const num = (v) => Number(v);

  /* ---------- reach the prospect, they OPEN it → CDP engagement trait ---------- */
  const msg = await (await ctx.post(MSG, { headers: H, data: {
    messageType: 'email', toEmail: ENGAGED, subject: `hello ${run}`, content: 'open me' } })).json();
  if (!msg.id) fail('prospect reach failed: ' + JSON.stringify(msg));
  await ctx.post(`${API}/esp/v1/event`, { headers: { 'X-Esp-Token': 'nova-esp-key', 'Content-Type': 'application/json' },
    data: [{ event: 'open', email: ENGAGED, custom_args: { tenant: 'nova', messageId: msg.id } }] });
  console.log('OK reached a prospect and recorded an OPEN (the martech engagement loop)');

  /* ---------- the CDP now reports the email as engaged ---------- */
  let engaged = false;
  for (let i = 0; i < 30; i++) {
    const sig = await (await ctx.get(`${LEADSIG}?email=${encodeURIComponent(ENGAGED)}`, { headers: H })).json();
    if (sig.engaged === true && sig.engagement === 'opened') { engaged = true; break; }
    await sleep(1500);
  }
  if (!engaged) fail('the CDP never reported the email as engaged');
  const coldSig = await (await ctx.get(`${LEADSIG}?email=${encodeURIComponent(COLD)}`, { headers: H })).json();
  if (coldSig.engaged !== false) fail('a never-reached email should not be engaged: ' + JSON.stringify(coldSig));
  console.log('OK the CDP reports the opened email as engaged, and the cold email as not');

  /* ---------- an engagement scoring rule lifts the engaged lead ---------- */
  await ctx.post(`${SALES}/salesLead/scoringRule`, { headers: H, data: { field: 'engagement', value: 'opened', points: P } });
  const hot = await (await ctx.post(`${SALES}/salesLead`, { headers: H, data: { name: `CDP hot ${run}`, source: `src-${run}`, contactEmail: ENGAGED } })).json();
  const cold = await (await ctx.post(`${SALES}/salesLead`, { headers: H, data: { name: `CDP cold ${run}`, source: `src-${run}`, contactEmail: COLD } })).json();
  if (num(hot.score) < P) fail(`the engaged lead should score at least ${P}, got ${hot.score}`);
  if (num(cold.score) !== 0) fail(`the cold lead should score 0, got ${cold.score}`);
  console.log(`OK the engaged lead scored ${hot.score} (CDP engagement lifted it ≥${P}); the cold lead scored 0`);

  console.log('\nALL CDP-ENGAGEMENT CHECKS PASSED — the sales funnel reads the CDP: a prospect who engaged with '
    + 'our marketing scores higher as a lead than one who never did. Martech behaviour now feeds lead scoring.');
})();
