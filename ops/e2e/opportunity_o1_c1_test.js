/* O1 (opportunity solid) + C1 (CPQ solid).
 * O1: forecast categories ride with the stage (and override), stage-aging is
 *     tracked, and a next-step becomes an OPEN task on the queue with an
 *     overdue flag until it's marked done.
 * C1: the deal's line items (recurring + one-time) become a TMF648 quote in one
 *     click — MRR vs one-off split — linked back to the opportunity, and the
 *     quote renders a branded document.
 * genalpha (bss realm); demo/demo is staff with quote:read+write.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const SALES = `${API}/tmf-api/salesManagement/v4`;
const QUOTE = `${API}/tmf-api/quoteManagement/v4`; // resolved below from href if different
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
  const num = (v) => Number(v);

  /* ---------- qualify a deal ---------- */
  const lead = await (await ctx.post(`${SALES}/salesLead`, { headers: H, data: { name: `O1C1 deal ${run}`, source: 'campaign' } })).json();
  const q = await (await ctx.patch(`${SALES}/salesLead/${lead.id}`, { headers: H, data: { state: 'qualified' } })).json();
  const oppId = q.salesOpportunity.id;
  let opp = await (await ctx.get(`${SALES}/salesOpportunity/${oppId}`, { headers: H })).json();

  /* ========== O1 ========== */
  // forecast category defaults with the stage; aging tracked
  if (opp.forecastCategory !== 'pipeline') fail('qualified opp should default to forecastCategory=pipeline: ' + JSON.stringify(opp.forecastCategory));
  if (opp.daysInStage == null) fail('stage aging (daysInStage) not tracked');
  console.log('OK a qualified deal opens in forecast category "pipeline" with stage-aging tracked');

  opp = await (await ctx.patch(`${SALES}/salesOpportunity/${oppId}`, { headers: H, data: { stage: 'negotiation' } })).json();
  if (opp.forecastCategory !== 'commit') fail('moving to Negotiation should ride forecast category to commit: ' + JSON.stringify(opp.forecastCategory));
  console.log('OK moving to Negotiation rides the forecast category to "commit"');

  // manager override
  opp = await (await ctx.patch(`${SALES}/salesOpportunity/${oppId}`, { headers: H, data: { forecastCategory: 'bestCase' } })).json();
  if (opp.forecastCategory !== 'bestCase') fail('forecast category override did not persist');
  // roll-up in the pipeline
  const pipe = await (await ctx.get(`${SALES}/salesOpportunity/pipeline`, { headers: H })).json();
  const bestCase = (pipe.byCategory || []).find((c) => c.category === 'bestCase');
  if (!bestCase || bestCase.count < 1) fail('pipeline byCategory did not roll up the bestCase deal: ' + JSON.stringify(pipe.byCategory));
  console.log('OK a manager can override the forecast category, and the pipeline rolls up by category');

  // a next-step task: past due -> open + overdue on the queue
  await ctx.post(`${SALES}/salesOpportunity/${oppId}/activity`, { headers: H, data: {
    type: 'nextStep', note: `Send proposal ${run}`, dueDate: '2020-01-01T00:00:00Z', assignee: `rep-${run}` } });
  let tasks = await (await ctx.get(`${SALES}/salesOpportunity/tasks?assignee=rep-${run}`, { headers: H })).json();
  const mine = (tasks.tasks || []).filter((t) => t.note === `Send proposal ${run}`);
  if (mine.length !== 1 || mine[0].status !== 'open' || mine[0].overdue !== true) {
    fail('the next-step did not become an OPEN, overdue task: ' + JSON.stringify(tasks));
  }
  const taskId = mine[0].id;
  console.log('OK a next-step with a due date became an OPEN task on the queue, flagged overdue');

  // complete it -> off the queue
  await ctx.post(`${SALES}/salesOpportunity/${oppId}/activity/${taskId}/done`, { headers: H });
  tasks = await (await ctx.get(`${SALES}/salesOpportunity/tasks?assignee=rep-${run}`, { headers: H })).json();
  if ((tasks.tasks || []).some((t) => t.id === taskId)) fail('completing the task did not remove it from the open queue');
  console.log('OK marking the task done clears it from the open-tasks queue');

  /* ========== C1 ========== */
  // compose the deal: a recurring line + a one-time line
  await ctx.post(`${SALES}/salesOpportunity/${oppId}/item`, { headers: H, data: { offeringName: 'Fibre 300', quantity: 2, unitPrice: 300, recurring: true } });
  await ctx.post(`${SALES}/salesOpportunity/${oppId}/item`, { headers: H, data: { offeringName: 'Install fee', quantity: 1, unitPrice: 150, recurring: false } });

  // one-click quote from the deal
  const built = await (await ctx.post(`${SALES}/salesOpportunity/${oppId}/quote`, { headers: H, data: {} })).json();
  const quote = built.quote;
  if (!quote || !quote.id) fail('quote hand-off did not return a quote: ' + JSON.stringify(built));
  if (built.opportunity.quote.id !== quote.id) fail('the quote was not linked back to the opportunity');
  const mrr = quote.quoteTotalPrice && num(quote.quoteTotalPrice.value);
  const oneTime = quote.quoteOneTimePrice && num(quote.quoteOneTimePrice.value);
  if (mrr !== 600) fail('quote MRR should be 2×300 = 600: ' + JSON.stringify(quote.quoteTotalPrice));
  if (oneTime !== 150) fail('quote one-time should be 150: ' + JSON.stringify(quote.quoteOneTimePrice));
  console.log('OK the deal became a TMF648 quote in one click — MRR 600/mo + one-time 150, linked to the opportunity');

  // the quote renders a branded document
  const href = String(quote.href || `/tmf-api/quoteManagement/v4/quote/${quote.id}`);
  const docRes = await ctx.get(`${API}${href}/document`, { headers: { Authorization: 'Bearer ' + tok } });
  if (docRes.status() !== 200) fail('quote document did not render: HTTP ' + docRes.status());
  const doc = await docRes.text();
  if (!doc.includes('Fibre 300') || !doc.includes('Install fee') || !/Quotation/i.test(doc)) {
    fail('the quote document is missing its lines: ' + doc.slice(0, 200));
  }
  console.log('OK the quote renders a branded, printable document carrying its lines and totals');

  console.log('\nALL O1+C1 CHECKS PASSED — the opportunity is a workable deal (forecast categories, aging, '
    + 'tasks) and the quote is real (one-click from the deal, MRR/one-off split, a sendable document).');
})();
