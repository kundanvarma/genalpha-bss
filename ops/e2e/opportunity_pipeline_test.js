/* B2B sales pipeline — the opportunity is a workable deal, not a read-only stub.
 * Proves the whole spine:
 *   qualify a lead (with an account) -> opportunity opens at Qualification (10%)
 *   -> work the stage (Proposal auto-rides to 50%) -> compose the deal with
 *   catalog line items (amount = sum of lines) -> it shows on the pipeline board
 *   with a probability-weighted forecast -> log an activity that lands on the
 *   customer's TMF683 360 -> win it -> won-by-source attributes the revenue.
 * Run in genalpha (bss realm); demo/demo is staff with quote:read+write.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const SALES = `${API}/tmf-api/salesManagement/v4`;
const INTERACTION = `${API}/tmf-api/partyInteraction/v4/partyInteraction`;

async function token(ctx, client, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/bss/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const num = (v) => Number(v);

  // A real account to hang the deal on, so activities reach a 360.
  const email = `opp-${run}@example.com`;
  const login = await (await ctx.post(USER, { headers: H(staff), data: { email, givenName: `Opp${run}`, familyName: `A${run}` } })).json();
  if (!login.id) fail('user create failed: ' + JSON.stringify(login));
  await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: `Opp${run}`, familyName: `A${run}` } });

  /* ---------- 1. a sourced lead, qualified into a workable opportunity ---------- */
  const lead = await (await ctx.post(`${SALES}/salesLead`, { headers: H(staff), data: {
    name: `Enterprise fibre ${run}`, company: 'Northwind', source: 'campaign' } })).json();
  if (!lead.id) fail('lead create failed: ' + JSON.stringify(lead));
  const qualified = await (await ctx.patch(`${SALES}/salesLead/${lead.id}`, { headers: H(staff),
    data: { state: 'qualified', partyId: login.id } })).json();
  const oppId = qualified.salesOpportunity && qualified.salesOpportunity.id;
  if (!oppId) fail('qualify did not mint an opportunity: ' + JSON.stringify(qualified));
  let opp = await (await ctx.get(`${SALES}/salesOpportunity/${oppId}`, { headers: H(staff) })).json();
  if (opp.stage !== 'qualification' || opp.probability !== 10 || opp.state !== 'developed') {
    fail('opportunity did not open at Qualification/10%%: ' + JSON.stringify(opp));
  }
  if (opp.partyId !== login.id) fail('the account was not carried onto the opportunity');
  console.log('OK a sourced lead qualified into an opportunity — opens at Qualification, 10%, on an account');

  /* ---------- 2. work the stage — probability rides with it ---------- */
  opp = await (await ctx.patch(`${SALES}/salesOpportunity/${oppId}`, { headers: H(staff),
    data: { stage: 'proposal', ownerName: 'Dana Rep', expectedCloseDate: '2026-12-01' } })).json();
  if (opp.stage !== 'proposal' || opp.probability !== 50) {
    fail('moving to Proposal did not ride probability to 50%%: ' + JSON.stringify(opp));
  }
  console.log('OK moving the stage to Proposal rode the win-probability to 50% and set owner + close date');

  /* ---------- 3. compose the deal with catalog line items — amount = sum ---------- */
  await ctx.post(`${SALES}/salesOpportunity/${oppId}/item`, { headers: H(staff),
    data: { offeringName: 'Fibre 1Gbps', quantity: 2, unitPrice: 300 } });
  opp = await (await ctx.post(`${SALES}/salesOpportunity/${oppId}/item`, { headers: H(staff),
    data: { offeringName: 'Static IP block', quantity: 1, unitPrice: 100 } })).json();
  if (num(opp.amount) !== 700) fail('deal value is not the sum of its lines (2×300 + 1×100 = 700): ' + JSON.stringify(opp.amount));
  if (!opp.items || opp.items.length !== 2) fail('line items did not attach: ' + JSON.stringify(opp.items));
  console.log('OK the deal composes from catalog line items — amount = 700, the sum of the lines');

  /* ---------- 4. it shows on the pipeline board with a weighted forecast ---------- */
  const pipe = await (await ctx.get(`${SALES}/salesOpportunity/pipeline`, { headers: H(staff) })).json();
  const proposal = (pipe.stages || []).find((s) => s.stage === 'proposal');
  if (!proposal || num(proposal.amount) < 700) fail('the deal is not in the Proposal column: ' + JSON.stringify(pipe));
  // weighted = amount × probability; our 700 @ 50% contributes 350
  if (num(pipe.weightedForecast) < 350) fail('the weighted forecast does not reflect 700 @ 50%%: ' + JSON.stringify(pipe));
  console.log(`OK the pipeline board shows the deal in Proposal; weighted forecast reflects 700 × 50% (≥350)`);

  /* ---------- 5. an activity lands on the customer's TMF683 360 ---------- */
  await ctx.post(`${SALES}/salesOpportunity/${oppId}/activity`, { headers: H(staff),
    data: { type: 'call', note: `Discovery call — sized the fibre deal ${run}` } });
  let onTimeline = false;
  for (let i = 0; i < 30; i++) {
    const rows = await (await ctx.get(`${INTERACTION}?relatedPartyId=${login.id}&limit=100`, { headers: H(staff) })).json();
    if (Array.isArray(rows) && rows.some((r) => String(r.description || '').includes(`Discovery call — sized the fibre deal ${run}`))) { onTimeline = true; break; }
    await sleep(1500);
  }
  if (!onTimeline) fail('the sales activity never reached the account 360 (TMF683)');
  console.log('OK a logged sales activity landed on the account\'s TMF683 360 — sales + service on one record');

  /* ---------- 6. win it — state/stage/probability close together ---------- */
  opp = await (await ctx.patch(`${SALES}/salesOpportunity/${oppId}`, { headers: H(staff),
    data: { state: 'won', closeReason: 'Best coverage + price' } })).json();
  if (opp.state !== 'won' || opp.stage !== 'closedWon' || opp.probability !== 100) {
    fail('winning did not close state/stage/probability: ' + JSON.stringify(opp));
  }
  console.log('OK winning closed the deal — state won, stage closedWon, probability 100');

  /* ---------- 7. won-by-source attributes the revenue to the programme ---------- */
  const wonReport = await (await ctx.get(`${SALES}/salesOpportunity/wonReport`, { headers: H(staff) })).json();
  const campaignRow = (wonReport.bySource || []).find((r) => r.source === 'campaign');
  if (!campaignRow || num(campaignRow.wonAmount) < 700) {
    fail('won-by-source did not attribute the 700 deal to source=campaign: ' + JSON.stringify(wonReport));
  }
  console.log('OK won-by-source attributes the closed revenue to the campaign that sourced the lead');

  console.log('\nALL OPPORTUNITY-PIPELINE CHECKS PASSED — the opportunity is a real B2B deal: staged, valued, '
    + 'composed of catalog lines, forecast on a weighted board, on the customer 360, and its won revenue is '
    + 'attributed to the programme that sourced it.');
})();
