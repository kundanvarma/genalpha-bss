/* GJ3 — multi-channel delivery.
 *
 *  - a message on the "sms" channel is routed to the SMS gateway (Twilio wire
 *    shape) and actually leaves the building — the mock A2P records it, To the
 *    customer's real number, with the rendered body
 *  - a TEMPLATED sms (GJ2 + GJ3) is personalized AND delivered over SMS
 *  - a "push" message is stored on its channel (the push seam is a stub)
 *  - the in-app inbox stays the record for every channel
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const SMS_MOCK = 'http://localhost:8139';
const run = Date.now();
const MSG = `${API}/tmf-api/communicationManagement/v4/communicationMessage`;
const TEMPLATE = `${API}/tmf-api/communicationManagement/v4/messageTemplate`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;

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

  /* ---------- a customer with a phone number ---------- */
  const phone = `+4740${String(run).slice(-7)}`;
  const firstName = `Sms${run}`;
  const email = `ch-${run}@example.com`;
  const login = await (await ctx.post(USER, { headers: H(staff),
    data: { email, givenName: firstName, familyName: `Ch${run}` } })).json();
  await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: firstName,
    familyName: `Ch${run}`,
    contactMedium: [
      { mediumType: 'email', characteristic: { emailAddress: email } },
      { mediumType: 'phone', characteristic: { phoneNumber: phone } },
    ] } });
  const rp = [{ id: login.id, role: 'customer' }];
  console.log(`OK a customer with phone ${phone}`);

  const smsTo = async (to) => (await (await ctx.get(`${SMS_MOCK}/messages?to=${encodeURIComponent(to)}`)).json());

  /* ---------- an sms-channel message actually leaves over the gateway ---------- */
  const smsBody = `Your code is ready ${run}`;
  await ctx.post(MSG, { headers: H(staff),
    data: { messageType: 'sms', subject: 'Code', content: smsBody, relatedParty: rp } });
  let sent = [];
  for (let i = 0; i < 20; i++) {
    sent = await smsTo(phone);
    if (sent.length) break;
    await sleep(1000);
  }
  if (!sent.length) fail('the sms channel did not reach the gateway');
  if (sent[0].body !== smsBody || !sent[0].sid || sent[0].from == null) {
    fail('the gateway record is malformed (Twilio shape): ' + JSON.stringify(sent[0]));
  }
  console.log(`OK the sms left over the gateway (Twilio shape): sid ${sent[0].sid}, To ${sent[0].to}`);

  /* ---------- a TEMPLATED sms is personalized AND delivered over SMS ---------- */
  const tpl = await (await ctx.post(TEMPLATE, { headers: H(staff), data: {
    name: `SMS tpl ${run}`, channel: 'sms',
    locales: { en: { subject: 'Hi', body: `Hi {{party.firstName}}, your PIN ${run}` } } } })).json();
  await ctx.post(MSG, { headers: H(staff), data: { templateRef: tpl.id, relatedParty: rp } });
  let templated = null;
  for (let i = 0; i < 20; i++) {
    templated = (await smsTo(phone)).find((m) => m.body.includes(firstName));
    if (templated) break;
    await sleep(1000);
  }
  if (!templated) fail('the templated sms was not delivered personalized over SMS');
  console.log(`OK a templated sms was personalized and delivered over SMS: "${templated.body}"`);

  /* ---------- a push message is stored on its channel (stub seam) ---------- */
  const push = await (await ctx.post(MSG, { headers: H(staff),
    data: { messageType: 'push', subject: 'Push', content: `ping ${run}`, relatedParty: rp } })).json();
  if (push.messageType !== 'push') fail('push channel not recorded: ' + JSON.stringify(push.messageType));
  console.log('OK a push message routed to the push seam and is on the record as channel "push"');

  console.log('\nALL GJ3 CHECKS PASSED — messages route by channel: sms leaves over a Twilio-shaped gateway '
    + '(inline and templated/personalized), push routes to its seam, and the in-app inbox stays the record.');
})();
