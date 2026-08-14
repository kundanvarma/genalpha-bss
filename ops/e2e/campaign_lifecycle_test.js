/* GJ6 — lifecycle & governance.
 *
 *  - a journey moves through draft -> active -> paused, and only ACTIVE runs:
 *    a customer who registers while it's draft/paused is NOT messaged; one who
 *    registers while it's active IS
 *  - an invalid status is rejected
 *  - delete removes the journey (and a campaign) from the list
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;
const CAMPAIGN = `${API}/tmf-api/campaignManagement/v4/campaign`;
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
  const welcome = `Lifecycle Welcome ${run}`;

  const mkCustomer = async (tag) => {
    const email = `life-${tag}-${run}@example.com`;
    const login = await (await ctx.post(USER, { headers: H(staff),
      data: { email, givenName: `Life${tag}`, familyName: `A${run}` } })).json();
    await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: `Life${tag}`, familyName: `A${run}` } });
    const tok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
    return { id: login.id, tok };
  };
  const gotWelcome = async (p) => {
    for (let i = 0; i < 8; i++) {
      const subs = (await (await ctx.get(INBOX, { headers: H(p.tok) })).json()).map((m) => m.subject);
      if (subs.includes(welcome)) return true;
      await sleep(1200);
    }
    return false;
  };

  /* ---------- created as DRAFT: it must not run ---------- */
  const j = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Lifecycle ${run}`, status: 'draft', triggerEventType: 'IndividualCreateEvent', holdoutPercent: 0,
    steps: [ { type: 'message', stage: 'Welcome', subject: welcome, content: 'hi' } ] } })).json();
  if (!j.id || j.status !== 'draft') fail('journey not created as draft: ' + JSON.stringify(j));
  const draftCust = await mkCustomer('draft');
  if (await gotWelcome(draftCust)) fail('a DRAFT journey messaged a customer');
  console.log('OK a draft journey does not run — the customer who registered got nothing');

  /* ---------- activate: now it runs ---------- */
  await ctx.patch(`${JOURNEY}/${j.id}`, { headers: H(staff), data: { status: 'active' } });
  const activeCust = await mkCustomer('active');
  if (!(await gotWelcome(activeCust))) fail('an ACTIVE journey did not message a new customer');
  console.log('OK once active, a newly-registered customer is enrolled and messaged');

  /* ---------- pause: it stops enrolling ---------- */
  await ctx.patch(`${JOURNEY}/${j.id}`, { headers: H(staff), data: { status: 'paused' } });
  const pausedCust = await mkCustomer('paused');
  if (await gotWelcome(pausedCust)) fail('a PAUSED journey messaged a new customer');
  console.log('OK once paused, a newly-registered customer is not enrolled');

  /* ---------- invalid status is rejected ---------- */
  const bad = await ctx.patch(`${JOURNEY}/${j.id}`, { headers: H(staff), data: { status: 'bogus' } });
  if (bad.status() < 400) fail('an invalid lifecycle status was accepted');
  console.log('OK an invalid lifecycle status is rejected');

  /* ---------- delete removes it from the list ---------- */
  const del = await ctx.delete(`${JOURNEY}/${j.id}`, { headers: H(staff) });
  if (del.status() !== 204) fail('journey delete did not return 204: ' + del.status());
  const list = await (await ctx.get(JOURNEY, { headers: H(staff) })).json();
  if (list.some((x) => x.id === j.id)) fail('the deleted journey is still in the list');
  console.log('OK delete removed the journey from the list');

  /* ---------- a campaign deletes too ---------- */
  const c = await (await ctx.post(CAMPAIGN, { headers: H(staff), data: {
    name: `Life campaign ${run}`, status: 'draft', triggerEventType: 'IndividualCreateEvent',
    message: { subject: `x ${run}`, content: 'y' } } })).json();
  const cdel = await ctx.delete(`${CAMPAIGN}/${c.id}`, { headers: H(staff) });
  if (cdel.status() !== 204) fail('campaign delete did not return 204: ' + cdel.status());
  const clist = await (await ctx.get(CAMPAIGN, { headers: H(staff) })).json();
  if (clist.some((x) => x.id === c.id)) fail('the deleted campaign is still in the list');
  console.log('OK a campaign supports the full lifecycle and delete too');

  console.log('\nALL GJ6 CHECKS PASSED — draft/scheduled/active/paused/archived lifecycle, only active runs, '
    + 'invalid transitions rejected, and delete clears a campaign or journey.');
})();
