/* Live journey editing — the Klaviyo/Customer.io model, forward-only.
 *
 *  - a journey's steps are EDITABLE after launch (PATCH /journey/{id})
 *  - edits apply forward-only: a customer parked mid-journey (in a wait)
 *    receives the NEW copy at their next send — and never the old version
 *  - the edit is stamped: stepsEditedAt on the journey + an honesty note in
 *    /stats, so funnel/lift readouts admit they mix step versions
 *  - invalid steps are rejected with the same rulebook as create (400),
 *    leaving the running journey untouched
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;
const INBOX = `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=100`;
const SEG = `Edit${run}`;

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

  /* ---------- a customer in the segment ---------- */
  const email = `edit-${run}@example.com`;
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Edith', familyName: `E${run}` } })).json();
  const vid = `edit-vis-${run}`;
  await ctx.post(`${API}/insight/v1/consent`, { headers: { 'Content-Type': 'application/json' },
    data: { visitorId: vid, analytics: true, personalization: true } });
  await ctx.post(`${API}/insight/v1/event`, { headers: { 'Content-Type': 'application/json' },
    data: { visitorId: vid, type: 'view', category: SEG } });
  const cust = await token(ctx, 'bss-biz', email, login.temporaryPassword);
  await ctx.post(`${API}/insight/v1/stitch`, { headers: H(cust), data: { visitorId: vid } });
  console.log('OK a customer in the segment, enrollable');

  /* ---------- launch: hello -> wait 25s -> offer V1 ---------- */
  const journey = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Edit ${run}`, segmentName: SEG, holdoutPercent: 0,
    steps: [
      { type: 'message', stage: 'Hello', subject: `Hello ${run}`, content: 'welcome' },
      { type: 'wait', seconds: 25 },
      { type: 'message', stage: 'Offer', subject: `Offer V1 ${run}`, content: 'old copy' },
    ] } })).json();
  if (!journey.id) fail('journey not created: ' + JSON.stringify(journey));
  if (journey.stepsEditedAt) fail('a freshly created journey must not be marked edited');
  await ctx.post(`${JOURNEY}/${journey.id}/enroll`, { headers: H(staff), data: {} });

  const subs = async () => (await (await ctx.get(INBOX, { headers: H(cust) })).json()).map((m) => m.subject);
  const waitFor = async (want) => { for (let i = 0; i < 30; i++) { if ((await subs()).includes(want)) return true; await sleep(1500); } return false; };
  if (!(await waitFor(`Hello ${run}`))) fail('the customer never got the hello — journey not running');
  console.log('OK enrolled and parked in the 25s wait, offer V1 not yet sent');

  /* ---------- the marketer fixes the copy WHILE she is parked ---------- */
  const bad = await ctx.patch(`${JOURNEY}/${journey.id}`, { headers: H(staff),
    data: { steps: [{ type: 'message' }] } });
  if (bad.status() !== 400) fail('invalid steps must be rejected with 400, got ' + bad.status());
  console.log('OK invalid steps rejected with the create-time rulebook (400)');

  const edited = await (await ctx.patch(`${JOURNEY}/${journey.id}`, { headers: H(staff), data: {
    name: `Edit ${run} v2`,
    steps: [
      { type: 'message', stage: 'Hello', subject: `Hello ${run}`, content: 'welcome' },
      { type: 'wait', seconds: 25 },
      { type: 'message', stage: 'Offer', subject: `Offer V2 ${run}`, content: 'new copy' },
    ] } })).json();
  if (!edited.stepsEditedAt) fail('a steps edit must stamp stepsEditedAt: ' + JSON.stringify(edited));
  if (edited.name !== `Edit ${run} v2`) fail('name edit not applied');
  if (!JSON.stringify(edited.steps).includes(`Offer V2 ${run}`)) fail('edited steps not persisted');
  console.log('OK steps + name edited in place, stepsEditedAt stamped');

  /* ---------- forward-only: she gets V2 at her next send, never V1 ---------- */
  if (!(await waitFor(`Offer V2 ${run}`))) fail('the parked customer did not receive the EDITED offer');
  const all = await subs();
  if (all.includes(`Offer V1 ${run}`)) fail('the customer received the OLD copy after the edit');
  if (all.filter((s) => s === `Hello ${run}`).length !== 1) fail('the hello was re-sent — edits must not replay passed steps');
  console.log('OK the in-flight customer got the new copy at her next send — no old copy, no re-sends');

  /* ---------- the stats readout admits the mix ---------- */
  const stats = await (await ctx.get(`${JOURNEY}/${journey.id}/stats`, { headers: H(staff) })).json();
  if (!stats.stepsEditedAt) fail('stats must carry stepsEditedAt after an edit');
  if (!stats.editNote || !stats.editNote.includes('edited')) fail('stats must carry the honesty note: ' + JSON.stringify(stats));
  console.log('OK stats carry the honesty marker: ' + stats.editNote);

  console.log('\nALL JOURNEY-EDIT CHECKS PASSED — steps are live-editable (validated, stamped), edits apply '
    + 'forward-only to in-flight customers, and the stats readout is honest about mixing versions.');
})();
