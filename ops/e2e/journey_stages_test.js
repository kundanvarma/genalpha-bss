/* GJ1 — named stages + onboarding trigger.
 *
 *  - a Journey with NAMED stages (Welcome / Activate / Day-7), triggered on
 *    IndividualCreateEvent — i.e. the moment a customer registers
 *  - a brand-new customer is created; registration alone drops them into the
 *    journey (no order, no segment) and the Welcome message is delivered
 *  - the journey stats expose a stageFunnel — how many people sit in each
 *    NAMED stage — so a journey owner reads "who is where", not "step0/step1"
 *  - the stage labels round-trip on the journey's steps
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

  /* ---------- a journey with NAMED stages, triggered on registration ---------- */
  const welcomeSubject = `Welcome aboard ${run}`;
  const journey = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Onboarding ${run}`,
    triggerEventType: 'IndividualCreateEvent',
    holdoutPercent: 0,
    steps: [
      { type: 'message', stage: 'Welcome', subject: welcomeSubject, content: 'You\'re in — welcome!' },
      { type: 'wait', stage: 'Activate', days: 3 },
      { type: 'message', stage: 'Activate', subject: `Activate ${run}`, content: 'Add your first service.' },
      { type: 'wait', stage: 'Day 7', days: 4 },
      { type: 'message', stage: 'Day 7', subject: `How's it going ${run}`, content: 'Anything we can help with?' },
    ],
  } })).json();
  if (!journey.id) fail('journey not created: ' + JSON.stringify(journey));
  console.log(`OK created a 3-stage onboarding journey (Welcome / Activate / Day-7) on IndividualCreateEvent`);

  /* ---------- the stage labels round-trip from storage ---------- */
  const list = await (await ctx.get(JOURNEY, { headers: H(staff) })).json();
  const fetched = list.find((j) => j.id === journey.id);
  if (!fetched || fetched.steps[0].stage !== 'Welcome' || fetched.steps[1].stage !== 'Activate') {
    fail('stage labels did not round-trip: ' + JSON.stringify(fetched && fetched.steps));
  }
  console.log('OK named stages round-trip on the journey steps');

  /* ---------- a brand-new customer registers — that alone enrolls them ---------- */
  const email = `newbie-${run}@example.com`;
  const login = await (await ctx.post(USER, { headers: H(staff),
    data: { email, givenName: 'Newbie', familyName: `Reg${run}` } })).json();
  await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: 'Newbie',
    familyName: `Reg${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
  console.log('OK a brand-new customer registered — no order, no segment, just a signup');

  /* ---------- registration drops them into the journey; Welcome is delivered ---------- */
  let stats = null;
  for (let i = 0; i < 30; i++) {
    stats = await (await ctx.get(`${JOURNEY}/${journey.id}/stats`, { headers: H(staff) })).json();
    if ((stats.entered || 0) >= 1 && stats.stageFunnel) break;
    await sleep(1500);
  }
  if (!stats || (stats.entered || 0) < 1) fail('registration did not enroll the customer: ' + JSON.stringify(stats));
  if (!stats.stageFunnel) fail('stats exposes no stageFunnel: ' + JSON.stringify(stats));
  console.log(`OK registration enrolled the customer — stageFunnel: ${JSON.stringify(stats.stageFunnel)}`);

  /* ---------- after the Welcome send, the customer sits in a NAMED stage ---------- */
  // step0 (Welcome message) fires, then they park at step1 (the Activate wait)
  let parkedInActivate = false;
  for (let i = 0; i < 20; i++) {
    stats = await (await ctx.get(`${JOURNEY}/${journey.id}/stats`, { headers: H(staff) })).json();
    if ((stats.stageFunnel.Activate || 0) >= 1) { parkedInActivate = true; break; }
    await sleep(1500);
  }
  if (!parkedInActivate) fail('customer never advanced into the named "Activate" stage: ' + JSON.stringify(stats.stageFunnel));
  console.log('OK the customer advanced past Welcome and now sits in the named "Activate" stage');

  /* ---------- the Welcome message actually landed in their inbox ---------- */
  const custTok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
  let delivered = false;
  for (let i = 0; i < 20; i++) {
    const subjects = (await (await ctx.get(INBOX, { headers: H(custTok) })).json()).map((m) => m.subject);
    if (subjects.includes(welcomeSubject)) { delivered = true; break; }
    await sleep(1500);
  }
  if (!delivered) fail('the Welcome message never reached the new customer');
  console.log('OK the Welcome message reached the new customer at stage 0');

  console.log('\nALL GJ1 CHECKS PASSED — onboarding is a first-class trigger (registration enters the journey), '
    + 'and stages are NAMED (Welcome / Activate / Day-7) with a stageFunnel a journey owner can read.');
})();
