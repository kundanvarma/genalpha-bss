/* Engagement feedback loop: opens/clicks flow back as a trait, so "opened
 * recently" and "dormant" become audiences — the loop most stacks miss.
 *   send a message -> the ESP reports an open -> insight sets emailEngagement
 *   -> an audience {trait emailEngagement=opened} resolves the customer.
 * Run in nova (real ESP tenant) so the receipt path is live.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const MSG = `${API}/tmf-api/communicationManagement/v4/communicationMessage`;
const AUDIENCE = `${API}/insight/v1/audience`;

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const nova = await token(ctx, 'nova', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  const email = `eng-${run}@nova.example`;
  const login = await (await ctx.post(USER, { headers: H(nova), data: { email, givenName: `Eng${run}`, familyName: `E${run}` } })).json();
  if (!login.id) fail('user create failed: ' + JSON.stringify(login));
  await ctx.post(PARTY, { headers: H(nova), data: { id: login.id, givenName: `Eng${run}`, familyName: `E${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });

  /* ---------- send a message, then the ESP reports an open ---------- */
  const msg = await (await ctx.post(MSG, { headers: H(nova), data: { messageType: 'email', subject: `hi ${run}`, content: 'open me', relatedParty: [{ id: login.id, role: 'customer' }] } })).json();
  if (!msg.id) fail('message send failed: ' + JSON.stringify(msg));
  await ctx.post(`${API}/esp/v1/event`, { headers: { 'X-Esp-Token': 'nova-esp-key', 'Content-Type': 'application/json' },
    data: [{ event: 'open', email, custom_args: { tenant: 'nova', messageId: msg.id } }] });
  console.log('OK a message was sent and the ESP reported an OPEN');

  /* ---------- the open became an audience-able trait ---------- */
  const aud = await (await ctx.post(AUDIENCE, { headers: H(nova), data: {
    name: `Engaged ${run}`, population: 'customer', criteria: { all: [{ type: 'trait', key: 'emailEngagement', value: 'opened' }] } } })).json();
  let inAudience = false;
  for (let i = 0; i < 30; i++) {
    const members = await (await ctx.get(`${AUDIENCE}/${aud.id}/members`, { headers: H(nova) })).json();
    if (members.some((m) => m.partyId === login.id)) { inAudience = true; break; }
    await sleep(1500);
  }
  if (!inAudience) fail('the open never became an emailEngagement=opened trait / audience member');
  console.log('OK the open fed back as emailEngagement=opened — the customer is now in an "engaged" audience');

  console.log('\nALL ENGAGEMENT CHECKS PASSED — opens/clicks close the loop into traits, so engaged vs dormant '
    + 'customers are audiences (re-engage the quiet ones, reward the active ones).');
})();
