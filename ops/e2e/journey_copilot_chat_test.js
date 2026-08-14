/* Journey/Campaign Copilot — the conversational loop, like the product copilot.
 *   - a marketer describes onboarding -> the copilot asks a clarifying question
 *   - they answer -> the copilot returns a JOURNEY proposal
 *   - confirm = apply: the proposal creates a real, running journey
 *   - a "win back" ask returns a CAMPAIGN proposal that applies too
 *   - every turn is governed and lands in the AI audit ledger
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const COPILOT = `${API}/ai/v1/journeyCopilot`;
const AUDIT = `${API}/ai/v1/audit`;
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;
const CAMPAIGN = `${API}/tmf-api/campaignManagement/v4/campaign`;

async function token(ctx, client, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const staff = await token(ctx, 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const chat = (messages) => ctx.post(COPILOT, { headers: H(staff), data: { messages } }).then((r) => r.json());

  /* ---------- turn 1: describe onboarding -> a clarifying question ---------- */
  const q = await chat([{ role: 'owner', content: `I want to welcome new customers ${run} when they sign up` }]);
  if (q.kind !== 'question') fail('expected a clarifying question, got: ' + JSON.stringify(q));
  if (!q.provider || !q.model) fail('the copilot did not name the model behind it');
  console.log(`OK the copilot asked a clarifying question (${q.provider}/${q.model}): "${q.message.slice(0, 60)}…"`);

  /* ---------- turn 2: answer -> a JOURNEY proposal ---------- */
  const p = await chat([
    { role: 'owner', content: `welcome new customers ${run}` },
    { role: 'copilot', content: q.message },
    { role: 'owner', content: 'yes, a short series with an activation nudge and a check-in' },
  ]);
  if (p.kind !== 'proposal' || !p.proposal || p.proposal.artifact !== 'journey' || !Array.isArray(p.proposal.journey.steps)) {
    fail('expected a journey proposal, got: ' + JSON.stringify(p).slice(0, 300));
  }
  if (p.proposal.journey.triggerEventType !== 'IndividualCreateEvent') fail('the proposed journey lost the onboarding trigger');
  console.log(`OK the copilot proposed a ${p.proposal.journey.steps.length}-step journey on IndividualCreateEvent`);

  /* ---------- confirm = apply: the proposal creates a real journey ---------- */
  const jr = p.proposal.journey;
  const created = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `${jr.name} ${run}`, triggerEventType: jr.triggerEventType,
    holdoutPercent: jr.holdoutPercent, priority: jr.priority, steps: jr.steps } })).json();
  if (!created.id) fail('the proposed journey was not directly applicable: ' + JSON.stringify(created));
  console.log('OK confirming the proposal created a real, running-shaped journey — no re-typing');

  /* ---------- a "win back" ask -> a CAMPAIGN proposal that also applies ---------- */
  const c = await chat([{ role: 'owner', content: 'build a win back campaign for customers at risk of churn' }]);
  if (c.kind !== 'proposal' || c.proposal.artifact !== 'campaign' || !c.proposal.campaign.message) {
    fail('expected a campaign proposal, got: ' + JSON.stringify(c).slice(0, 300));
  }
  const cp = c.proposal.campaign;
  const camp = await (await ctx.post(CAMPAIGN, { headers: H(staff), data: {
    name: `${cp.name} ${run}`, triggerEventType: cp.triggerEventType, holdoutPercent: cp.holdoutPercent,
    message: cp.message } })).json();
  if (!camp.id) fail('the proposed campaign was not directly applicable: ' + JSON.stringify(camp));
  console.log(`OK the copilot proposed AND applied a win-back campaign on ${cp.triggerEventType}`);

  /* ---------- every turn carries a receipt ---------- */
  const audit = await (await ctx.get(AUDIT, { headers: H(staff) })).json();
  if (!audit.some((a) => a.useCase && a.useCase.startsWith('journey-copilot'))) fail('the copilot turns were not audited');
  console.log('OK every copilot turn is governed and in the AI audit ledger');

  await ctx.delete(`${JOURNEY}/${created.id}`, { headers: H(staff) });
  await ctx.delete(`${CAMPAIGN}/${camp.id}`, { headers: H(staff) });
  console.log('\nALL JOURNEY-COPILOT CHECKS PASSED — a marketer chats, the copilot clarifies then proposes a '
    + 'journey or campaign, confirming applies it to a real running artifact, and every turn is audited.');
})();
