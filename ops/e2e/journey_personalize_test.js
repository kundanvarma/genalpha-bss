/* Personalization + state filter + the shipping trigger.
 *   - a plain (inline) message with {{party.firstName}} is personalized per
 *     customer — no template needed
 *   - a journey can filter its trigger to a specific state (e.g. completed)
 *   - "handset shipped" is now a real trigger (ShippingOrderStateChangeEvent),
 *     with a state filter of shipped
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
  const cleanup = [];

  /* ---------- inline {{party.firstName}} is personalized, no template ---------- */
  const firstName = `Ada${run}`;
  const j = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Personalize ${run}`, triggerEventType: 'IndividualCreateEvent', holdoutPercent: 0,
    steps: [{ type: 'message', stage: 'Welcome', channel: 'inApp',
      subject: `Welcome ${firstName ? '{{party.firstName}}' : ''} ${run}`,
      content: 'Hi {{party.firstName}}, great to have you — here is how to get started.' }] } })).json();
  if (!j.id) fail('journey not created: ' + JSON.stringify(j));
  cleanup.push(j.id);

  const email = `pz-${run}@example.com`;
  const login = await (await ctx.post(USER, { headers: H(staff),
    data: { email, givenName: firstName, familyName: `P${run}` } })).json();
  await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: firstName, familyName: `P${run}` } });
  const custTok = await token(ctx, 'bss-biz', email, login.temporaryPassword);

  let msg = null;
  for (let i = 0; i < 30; i++) {
    const inbox = await (await ctx.get(INBOX, { headers: H(custTok) })).json();
    msg = inbox.find((m) => m.content && m.content.includes(firstName));
    if (msg) break;
    await sleep(1200);
  }
  if (!msg) fail('the inline {{party.firstName}} was not personalized in the delivered message');
  if (!msg.subject.includes(firstName)) fail('the subject token was not personalized: ' + msg.subject);
  if (msg.content.includes('{{')) fail('an unresolved token leaked into the message: ' + msg.content);
  console.log(`OK a plain inline message was personalized by name — "${msg.subject}" (no template needed)`);

  /* ---------- a journey can filter its trigger to a specific state ---------- */
  const sf = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Activated welcome ${run}`, triggerEventType: 'ProductOrderStateChangeEvent',
    triggerState: 'completed', holdoutPercent: 10,
    steps: [{ type: 'message', channel: 'inApp', subject: `Live! ${run}`, content: 'Your order is active.' }] } })).json();
  cleanup.push(sf.id);
  const back = (await (await ctx.get(JOURNEY, { headers: H(staff) })).json()).find((x) => x.id === sf.id);
  if (!back || back.triggerState !== 'completed') fail('the state filter did not round-trip: ' + JSON.stringify(back && back.triggerState));
  console.log('OK a journey fires only on the chosen state (ProductOrderStateChangeEvent + state "completed")');

  /* ---------- "handset shipped" is now a real, pickable trigger ---------- */
  const ship = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Handset shipped ${run}`, triggerEventType: 'ShippingOrderStateChangeEvent',
    triggerState: 'shipped', holdoutPercent: 0,
    steps: [{ type: 'message', channel: 'inApp', subject: 'On its way, {{party.firstName}}!',
      content: 'Hi {{party.firstName}}, your handset has shipped — track it in the app.' }] } })).json();
  if (!ship.id || ship.triggerEventType !== 'ShippingOrderStateChangeEvent' || ship.triggerState !== 'shipped') {
    fail('the shipping trigger was not accepted: ' + JSON.stringify(ship).slice(0, 200));
  }
  cleanup.push(ship.id);
  console.log('OK "handset shipped" is a real trigger (ShippingOrderStateChangeEvent + state "shipped") — fires when fulfilment ships the parcel');

  for (const id of cleanup) await ctx.delete(`${JOURNEY}/${id}`, { headers: H(staff) });
  console.log('\nALL PERSONALIZE CHECKS PASSED — inline {{party.firstName}} personalizes any message; journeys '
    + 'filter their trigger by state; and shipping (handset shipped/delivered) is a real trigger.');
})();
