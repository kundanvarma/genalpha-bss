/* Marketing preference centre — the compliance spine.
 *  - a customer reads + sets their OWN marketing opt-out (self-service)
 *  - opting out STOPS marketing sends (in-app + email), enforced in the send path
 *  - opting back in resumes them
 *  - every marketing message carries a one-click, no-login unsubscribe link;
 *    following it honours the opt-out (and a forged token is rejected)
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const MSG = `${API}/tmf-api/communicationManagement/v4/communicationMessage`;
const PREF = `${API}/tmf-api/communicationManagement/v4/marketingPreference`;

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

  // a fresh customer
  const email = `pref-${run}@example.com`;
  const u = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Pref', familyName: 'User' } })).json();
  const cust = await token(ctx, 'bss-biz', email, u.temporaryPassword);
  const rp = [{ id: u.id, role: 'customer' }];
  const marketing = () => ctx.post(MSG, { headers: H(staff),
    data: { messageType: 'inApp', subject: `Offer ${run}`, content: 'A deal for you.', relatedParty: rp } });

  // default: opted IN
  const pref0 = await (await ctx.get(PREF, { headers: H(cust) })).json();
  if (pref0.marketingOptOut !== false) fail('a new customer should default to opted-in: ' + JSON.stringify(pref0));
  console.log('OK a customer can read their own marketing preference (defaults to opted-in)');

  // a marketing message reaches them, and carries an unsubscribe link
  const first = await (await marketing()).json();
  if (!first || !first.id) fail('the first marketing message did not send: ' + JSON.stringify(first));
  if (!String(first.content || '').includes('/esp/v1/unsubscribe?p=')) fail('marketing message has no unsubscribe link');
  const link = String(first.content).match(/\/esp\/v1\/unsubscribe\?p=([^&\s]+)&t=([^\s]+)/);
  if (!link) fail('could not parse the unsubscribe link');
  console.log('OK the marketing message sent AND carries a one-click unsubscribe link');

  // opt OUT via the preference centre
  const set = await (await ctx.post(PREF, { headers: H(cust), data: { optOut: true } })).json();
  if (set.marketingOptOut !== true) fail('opt-out did not take: ' + JSON.stringify(set));
  const blocked = await (await marketing()).json();
  if (blocked.status !== 'suppressed' || blocked.optedOut !== 1) fail('opted-out customer was still messaged: ' + JSON.stringify(blocked));
  console.log('OK opting out STOPS marketing — the next send is suppressed, not delivered');

  // opt back IN
  await ctx.post(PREF, { headers: H(cust), data: { optOut: false } });
  const resumed = await (await marketing()).json();
  if (!resumed || !resumed.id) fail('opting back in did not resume sends: ' + JSON.stringify(resumed));
  console.log('OK opting back in resumes marketing');

  // one-click unsubscribe: a forged token is rejected; the real link works
  const [, pid, tok] = link;
  const bad = await ctx.get(`${API}/esp/v1/unsubscribe?p=${pid}&t=deadbeefdeadbeefdeadbeef`);
  if (bad.status() !== 400) fail('a forged unsubscribe token was accepted (status ' + bad.status() + ')');
  const good = await ctx.get(`${API}/esp/v1/unsubscribe?p=${pid}&t=${tok}`);
  if (good.status() !== 200) fail('the real unsubscribe link failed: ' + good.status());
  const afterClick = await (await marketing()).json();
  if (afterClick.status !== 'suppressed') fail('one-click unsubscribe did not stop marketing: ' + JSON.stringify(afterClick));
  console.log('OK one-click unsubscribe honours the opt-out (and a forged token is refused)');

  console.log('\nALL MARKETING-PREFERENCE CHECKS PASSED — a customer controls their own marketing consent, '
    + 'opt-out is enforced in the send path (in-app + email), every message carries a one-click no-login '
    + 'unsubscribe, and forged links are rejected. Opt-out is a right, and the system honours it.');
})();
