/* GJ5 — journey node vocabulary: waitForEvent + exit.
 *
 *  - EVENT PATH: a journey parks at waitForEvent; when the awaited business
 *    event arrives it advances (and the timeout nudge is never sent)
 *  - TIMEOUT PATH: if the event never comes, the node's timeout fires the
 *    onTimeout nudge and the journey moves on
 *  - EXIT: an explicit exit node ends the enrollment (completed)
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;
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

  const mkCustomer = async (tag) => {
    const email = `graph-${tag}-${run}@example.com`;
    const login = await (await ctx.post(USER, { headers: H(staff),
      data: { email, givenName: `Graph${tag}`, familyName: `A${run}` } })).json();
    await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: `Graph${tag}`,
      familyName: `A${run}` } });
    const tok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
    return { id: login.id, tok };
  };
  const subjects = async (p) => (await (await ctx.get(INBOX, { headers: H(p.tok) })).json()).map((m) => m.subject);
  const waitFor = async (p, subj) => {
    for (let i = 0; i < 25; i++) { if ((await subjects(p)).includes(subj)) return true; await sleep(1200); }
    return false;
  };

  /* ---------- EVENT PATH ---------- */
  const j1 = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Graph event ${run}`, triggerEventType: 'IndividualCreateEvent', holdoutPercent: 0,
    steps: [
      { type: 'message', stage: 'Welcome', subject: `EVT Welcome ${run}`, content: 'hi' },
      { type: 'waitForEvent', stage: 'Activate', event: 'IndividualAttributeValueChangeEvent', days: 7,
        onTimeout: { subject: `EVT Timeout ${run}`, content: 'still there?' } },
      { type: 'message', stage: 'Done', subject: `EVT Done ${run}`, content: 'welcome aboard' },
      { type: 'exit' },
    ] } })).json();
  if (!j1.id) fail('event journey not created: ' + JSON.stringify(j1));
  const c1 = await mkCustomer('EVT');
  if (!(await waitFor(c1, `EVT Welcome ${run}`))) fail('welcome never sent (event journey)');
  // parked at waitForEvent: neither the timeout nudge nor the post-event message yet
  let subs = await subjects(c1);
  if (subs.includes(`EVT Done ${run}`) || subs.includes(`EVT Timeout ${run}`)) {
    fail('journey ran past waitForEvent without the event: ' + JSON.stringify(subs));
  }
  console.log('OK the journey parked at waitForEvent (welcome sent, waiting — no timeout, no post-message)');

  // the awaited event arrives (patch the individual -> IndividualAttributeValueChangeEvent)
  await ctx.patch(`${PARTY}/${c1.id}`, { headers: H(staff), data: { familyName: `Changed${run}` } });
  if (!(await waitFor(c1, `EVT Done ${run}`))) fail('the awaited event did not advance the journey');
  subs = await subjects(c1);
  if (subs.includes(`EVT Timeout ${run}`)) fail('the timeout nudge fired even though the event arrived');
  console.log('OK the awaited event advanced the journey past waitForEvent (and the timeout nudge never fired)');

  // exit node: the enrollment completed
  let stats1 = null;
  for (let i = 0; i < 15; i++) {
    stats1 = await (await ctx.get(`${JOURNEY}/${j1.id}/stats`, { headers: H(staff) })).json();
    if ((stats1.completedUnconverted || 0) >= 1) break;
    await sleep(1200);
  }
  if ((stats1.completedUnconverted || 0) < 1) fail('the exit node did not complete the enrollment: ' + JSON.stringify(stats1));
  console.log('OK the exit node ended the enrollment (completed)');

  /* ---------- TIMEOUT PATH ---------- */
  const j2 = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Graph timeout ${run}`, triggerEventType: 'IndividualCreateEvent', holdoutPercent: 0,
    steps: [
      { type: 'message', stage: 'Welcome', subject: `TMO Welcome ${run}`, content: 'hi' },
      { type: 'waitForEvent', stage: 'Activate', event: `NeverFires${run}`, seconds: 5,
        onTimeout: { subject: `TMO Nudge ${run}`, content: 'still there?' } },
      { type: 'message', stage: 'Done', subject: `TMO Done ${run}`, content: 'moving on' },
      { type: 'exit' },
    ] } })).json();
  const c2 = await mkCustomer('TMO');
  if (!(await waitFor(c2, `TMO Nudge ${run}`))) fail('the timeout nudge never fired');
  if (!(await waitFor(c2, `TMO Done ${run}`))) fail('the journey did not advance past the timed-out waitForEvent');
  console.log('OK an event that never came timed out: the onTimeout nudge fired and the journey moved on');

  console.log('\nALL GJ5 CHECKS PASSED — the journey node vocabulary is complete: waitForEvent parks until an '
    + 'event (advancing on arrival, nudging on timeout) and exit ends the journey.');
})();
