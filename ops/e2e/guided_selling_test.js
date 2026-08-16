/* C2c — guided selling. Author a question + answer→offering rules, then the
 * decision endpoint (agent-callable) turns answers into recommended offerings,
 * and applying them to an opportunity composes the deal. Run-unique question
 * key keeps it deterministic against accumulated tenant rules.
 * genalpha (bss realm); demo/demo staff.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const SALES = `${API}/tmf-api/salesManagement/v4`;
const QBASE = `${API}/tmf-api/quoteManagement/v4`;
const run = Date.now();
const KEY = `sites-${run}`;
const SDWAN = `SDWAN-${run}`, IP2 = `IP2-${run}`;

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

  /* ---------- author the questionnaire + rules ---------- */
  await ctx.post(`${QBASE}/quote/guidedQuestion`, { headers: H, data: { questionKey: KEY, prompt: 'How many sites?', sortOrder: 1 } });
  await ctx.post(`${QBASE}/quote/guidedRecommendation`, { headers: H, data: { questionKey: KEY, answerValue: 'multi', offeringName: SDWAN, quantity: 1 } });
  await ctx.post(`${QBASE}/quote/guidedRecommendation`, { headers: H, data: { questionKey: KEY, answerValue: 'multi', offeringName: IP2, quantity: 2 } });
  const questions = await (await ctx.get(`${QBASE}/quote/guidedQuestion`, { headers: H })).json();
  if (!questions.some((q) => q.questionKey === KEY)) fail('question not stored');
  console.log('OK authored a guided question and two answer→offering rules');

  /* ---------- the decision endpoint: answers → offerings (agent-callable) ---------- */
  const reco = await (await ctx.post(`${QBASE}/quote/guidedRecommend`, { headers: H, data: { answers: { [KEY]: 'multi' } } })).json();
  const recs = reco.recommendations || [];
  const sdwan = recs.find((r) => r.offeringName === SDWAN);
  const ip = recs.find((r) => r.offeringName === IP2);
  if (!sdwan || !ip || ip.quantity !== 2) fail('recommendation did not resolve the answers: ' + JSON.stringify(recs));
  if (!ip.because || !ip.because.includes('multi')) fail('recommendation is missing its rationale: ' + JSON.stringify(ip));
  console.log(`OK "multi sites" recommended ${SDWAN}×1 and ${IP2}×2, each with its rationale`);

  // a different answer recommends nothing (no rule matched)
  const none = await (await ctx.post(`${QBASE}/quote/guidedRecommend`, { headers: H, data: { answers: { [KEY]: 'single' } } })).json();
  if ((none.recommendations || []).length !== 0) fail('an unmatched answer should recommend nothing: ' + JSON.stringify(none));
  console.log('OK an unmatched answer recommends nothing — rules only fire on a match');

  /* ---------- apply the guided result to a deal ---------- */
  const lead = await (await ctx.post(`${SALES}/salesLead`, { headers: H, data: { name: `Guided ${run}`, source: 'campaign' } })).json();
  const q = await (await ctx.patch(`${SALES}/salesLead/${lead.id}`, { headers: H, data: { state: 'qualified' } })).json();
  const oppId = q.salesOpportunity.id;
  const applied = await (await ctx.post(`${SALES}/salesOpportunity/${oppId}/applyGuided`, { headers: H, data: { answers: { [KEY]: 'multi' } } })).json();
  if (applied.applied !== 2) fail('applyGuided did not add both offerings: ' + JSON.stringify(applied.applied));
  const items = applied.opportunity.items || [];
  if (!items.some((i) => i.offeringName === SDWAN) || !items.some((i) => i.offeringName === IP2 && i.quantity === 2)) {
    fail('the guided offerings did not land on the deal: ' + JSON.stringify(items));
  }
  console.log('OK applying the guided answers composed the deal — both recommended offerings are line items');

  console.log('\nALL GUIDED-SELLING CHECKS PASSED — a questionnaire narrows the catalog to the right products '
    + '(agent-callable), and applying it composes the opportunity — no need to know the whole product tree.');
})();
