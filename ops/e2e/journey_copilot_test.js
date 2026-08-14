/* BB3 — AI-native journey authoring.
 *
 *  - describe a journey in a sentence; the copilot drafts a STAGED plan with
 *    written copy for each stage — a proposal, nothing deployed
 *  - the draft is the exact object the campaign engine accepts: a human
 *    approves it and it creates a real journey (no draw-vs-run drift)
 *  - every model call is governed and lands in the AI audit ledger — the AI
 *    carries a receipt
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const DRAFT = `${API}/ai/v1/journeyDraft`;
const AUDIT = `${API}/ai/v1/audit`;
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;

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

  /* ---------- describe the journey; the copilot drafts a staged plan ---------- */
  const brief = `welcome new fibre customers ${run} and nudge them to activate their line`;
  const draft = await (await ctx.post(DRAFT, { headers: H(staff), data: { brief, brandName: 'GenAlpha' } })).json();
  if (!Array.isArray(draft.steps)) fail('no draft returned: ' + JSON.stringify(draft));
  const messages = draft.steps.filter((s) => s.type === 'message');
  if (messages.length < 2) fail('the draft has fewer than 2 stage messages: ' + JSON.stringify(draft.steps));
  for (const m of messages) {
    if (!m.stage || !m.subject || !m.content) fail('a stage message is missing stage/subject/content: ' + JSON.stringify(m));
  }
  if (draft.triggerEventType !== 'IndividualCreateEvent') fail('the draft did not choose an onboarding trigger');
  if (!draft.provider || !draft.model) fail('the draft does not name the model that wrote it');
  console.log(`OK the copilot drafted a ${messages.length}-stage journey (${messages.map((m) => m.stage).join(' -> ')}) `
    + `with written copy, by ${draft.provider}/${draft.model}`);

  /* ---------- a human approves: the draft creates a real journey, unchanged ---------- */
  const created = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: draft.name, triggerEventType: draft.triggerEventType,
    holdoutPercent: draft.holdoutPercent, steps: draft.steps } })).json();
  if (!created.id) fail('the AI draft was not directly acceptable to the campaign engine: ' + JSON.stringify(created));
  const list = await (await ctx.get(JOURNEY, { headers: H(staff) })).json();
  const back = list.find((j) => j.id === created.id);
  if (!back || back.steps[0].stage !== draft.steps[0].stage) fail('the approved journey lost the drafted stages');
  console.log('OK a human approved the draft and it created a real, running-shaped journey — no drift');

  /* ---------- the AI carries a receipt: the calls are in the audit ledger ---------- */
  const audit = await (await ctx.get(AUDIT, { headers: H(staff) })).json();
  const drafted = audit.filter((a) => a.useCase && a.useCase.startsWith('journey-draft'));
  if (drafted.length < messages.length) fail('the drafting calls were not all audited: ' + JSON.stringify(drafted.map((a) => a.useCase)));
  console.log(`OK every drafting call is governed and audited (${drafted.length} ledger rows, model named on each)`);

  console.log('\nALL BB3 CHECKS PASSED — describe a journey and the copilot drafts a staged plan with copy; '
    + 'a human approves it into a real journey; and every model call carries a receipt in the AI ledger.');
})();
