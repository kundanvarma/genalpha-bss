/* BB4 — real-time next-best-action arbitration (the capstone).
 *
 *  - two active journeys both greet a new customer at the same moment; a
 *    priority is the human-set policy
 *  - arbitration picks the SINGLE best action: the higher-priority journey
 *    messages, the other is HELD (not sent this moment)
 *  - every decision is logged with its reason — the NBA, explainable
 *  - governance still binds: consent/quiet-hours/frequency are unchanged
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;
const DECISIONS = `${API}/tmf-api/campaignManagement/v4/journey/arbitrationDecisions`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;
const INBOX = `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=100`;

async function token(ctx, client, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  // isolate from prior runs: prioritized journeys persist and would compete,
  // so clear this test's own leftovers first (delete now exists — GJ6)
  const existing = await (await ctx.get(JOURNEY, { headers: H(staff) })).json();
  for (const j of existing) {
    if (j.name && j.name.startsWith('NBA ')) await ctx.delete(`${JOURNEY}/${j.id}`, { headers: H(staff) });
  }

  const mkJourney = (name, subject, priority) => ctx.post(JOURNEY, { headers: H(staff), data: {
    name, triggerEventType: 'IndividualCreateEvent', holdoutPercent: 0, priority,
    steps: [ { type: 'message', stage: 'Greet', subject, content: 'hi' } ] } }).then((r) => r.json());

  const high = await mkJourney(`NBA High ${run}`, `NBA HIGH ${run}`, 100);
  const low = await mkJourney(`NBA Low ${run}`, `NBA LOW ${run}`, 1);
  if (!high.id || !low.id || high.priority !== 100) fail('journeys not created with priority: ' + JSON.stringify([high, low]));
  console.log('OK two journeys both greet on registration — priority 100 (high) vs 1 (low)');

  /* ---------- a new customer registers: both want to message at once ---------- */
  const email = `nba-${run}@example.com`;
  const login = await (await ctx.post(USER, { headers: H(staff),
    data: { email, givenName: `Nba`, familyName: `A${run}` } })).json();
  await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: 'Nba', familyName: `A${run}` } });
  const custTok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
  const subs = async () => (await (await ctx.get(INBOX, { headers: H(custTok) })).json()).map((m) => m.subject);

  /* ---------- arbitration: the high-priority action wins, the other is held ---------- */
  let gotHigh = false;
  for (let i = 0; i < 25; i++) { if ((await subs()).includes(`NBA HIGH ${run}`)) { gotHigh = true; break; } await sleep(1200); }
  if (!gotHigh) fail('the high-priority next-best-action was not delivered');
  await sleep(3000); // give any (wrong) low-priority send a chance to show
  if ((await subs()).includes(`NBA LOW ${run}`)) fail('the low-priority journey messaged anyway — arbitration failed');
  console.log('OK the single best action won: the high-priority journey messaged, the low was held');

  /* ---------- the decision is logged with its reason ---------- */
  let decision = null;
  for (let i = 0; i < 15; i++) {
    const decisions = await (await ctx.get(`${DECISIONS}?partyId=${login.id}`, { headers: H(staff) })).json();
    decision = decisions.find((d) => d.heldJourneyId === low.id && d.winnerJourneyId === high.id);
    if (decision) break;
    await sleep(1200);
  }
  if (!decision) fail('no arbitration decision was logged for the held journey');
  if (!decision.reason || !decision.reason.toLowerCase().includes('next-best-action')) {
    fail('the decision carries no explaining reason: ' + JSON.stringify(decision));
  }
  console.log(`OK the decision is logged and explainable: "${decision.reason}"`);

  // leave nothing prioritized behind (keeps the shared demo tenant clean)
  await ctx.delete(`${JOURNEY}/${high.id}`, { headers: H(staff) });
  await ctx.delete(`${JOURNEY}/${low.id}`, { headers: H(staff) });

  console.log('\nALL BB4 CHECKS PASSED — real-time NBA arbitration picks the single best action per customer '
    + 'by the human-set priority, holds the rest, and logs every decision with its reason.');
})();
