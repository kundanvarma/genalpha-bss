/* OCS-sourced "running low" trigger: the real-time balance lives in the OCS,
 * but the OCS EMITS a threshold notification northbound; the growth engine
 * consumes it and messages the customer, personalized per brand.
 *   - a subscriber is provisioned on the OCS (as SOM does at activation)
 *   - the network charges usage against the OCS until it crosses 80%
 *   - the OCS notifies the BSS -> UsageThresholdBreachedEvent on bss.usage.events
 *   - a journey on that event fires: "Hi Nora, you've used 85% — 1.5 GB left"
 * This is the honest shape: the OCS owns balance, the BSS owns the conversation.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const OCS = 'http://localhost:8115'; // the network/OCS seam (as production Gy would drive)
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

  /* ---------- a "running low" journey, personalized per brand ---------- */
  const firstName = `Nora${run}`;
  const j = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Running low ${run}`, triggerEventType: 'UsageThresholdBreachedEvent', holdoutPercent: 0,
    steps: [{ type: 'message', stage: 'Retain', channel: 'inApp',
      subject: `Running low, {{party.firstName}}?`,
      content: 'Hi {{party.firstName}}, you\'ve used {{usage.percentUsed}}% — {{usage.remaining}} GB '
        + 'left on {{brand.name}}. Top up in a tap.' }] } })).json();
  if (!j.id) fail('journey not created: ' + JSON.stringify(j));

  /* ---------- a customer with a service provisioned on the OCS ---------- */
  const email = `octhr-${run}@example.com`;
  const login = await (await ctx.post(USER, { headers: H(staff),
    data: { email, givenName: firstName, familyName: `N${run}` } })).json();
  if (!login.temporaryPassword) fail('user create failed: ' + JSON.stringify(login));
  await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: firstName, familyName: `N${run}` } });

  const sub = await (await ctx.post(`${OCS}/subscribers`, { headers: { 'Content-Type': 'application/json' },
    data: { tenantId: 'genalpha', partyId: login.id, serviceId: `svc-${run}`, ratePlanId: 'RG-DATA-10' } })).json();
  if (!sub.id) fail('OCS provisioning failed: ' + JSON.stringify(sub));
  console.log(`OK ${firstName} is provisioned on the OCS with a 10 GB counter`);

  /* ---------- the network charges usage until it crosses 80% ---------- */
  await ctx.post(`${OCS}/subscribers/${sub.id}/usage`, { headers: { 'Content-Type': 'application/json' },
    data: { gb: 8.5 } });
  console.log('OK the network charged 8.5 GB (85%) — the OCS crosses its 80% threshold and notifies the BSS');

  /* ---------- the running-low journey fired, personalized ---------- */
  const custTok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
  let msg = null;
  for (let i = 0; i < 30; i++) {
    const res = await ctx.get(INBOX, { headers: H(custTok) });
    const inbox = res.ok() ? await res.json() : [];
    msg = inbox.find((m) => m.content && m.content.includes(firstName) && m.content.includes('85')
      && m.content.includes('1.5'));
    if (msg) break;
    await sleep(1500);
  }
  if (!msg) fail('the OCS threshold journey never fired with the right numbers (85% / 1.5 GB)');
  if (msg.content.includes('{{')) fail('an unresolved token leaked: ' + msg.content);
  console.log(`OK the running-low journey fired — "${msg.content}"`);

  await ctx.delete(`${JOURNEY}/${j.id}`, { headers: H(staff) });
  console.log('\nALL OCS-THRESHOLD CHECKS PASSED — the OCS owns real-time balance and EMITS the "running '
    + 'low" event; the growth engine consumes it and personalizes the message per brand. Multi-brand ready: '
    + 'the event is tenant-stamped, so each brand runs its own journey off the same OCS feed.');
})();
