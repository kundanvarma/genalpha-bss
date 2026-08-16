/* TMF683 interaction timeline — marketing is on the customer's record too.
 * Two things a CSR needs before they pick up the phone:
 *   1. WHICH campaign said it — a send stamped with `source` shows on the
 *      timeline as "Marketing (<campaign>): <subject>", not a bare subject.
 *   2. Did it land — an ESP open/click closes the loop with "Email opened"
 *      and "Email clicked" touchpoints, so the CSR sees sent -> opened -> clicked.
 * Run in nova (real ESP tenant) so the receipt path is live.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const MSG = `${API}/tmf-api/communicationManagement/v4/communicationMessage`;
const INTERACTION = `${API}/tmf-api/partyInteraction/v4/partyInteraction`;

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

  const email = `tl-${run}@nova.example`;
  const login = await (await ctx.post(USER, { headers: H(nova), data: { email, givenName: `TL${run}`, familyName: `T${run}` } })).json();
  if (!login.id) fail('user create failed: ' + JSON.stringify(login));
  await ctx.post(PARTY, { headers: H(nova), data: { id: login.id, givenName: `TL${run}`, familyName: `T${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });

  // Poll the customer's timeline for a touchpoint whose description matches.
  const timelineHas = async (pred, tries = 30) => {
    for (let i = 0; i < tries; i++) {
      const rows = await (await ctx.get(`${INTERACTION}?relatedPartyId=${login.id}&limit=100`, { headers: H(nova) })).json();
      if (Array.isArray(rows) && rows.some((r) => pred(String(r.description || '')))) return true;
      await sleep(1500);
    }
    return false;
  };

  /* ---------- 1. a campaign-stamped send lands as "Marketing (<name>)" ---------- */
  const campaignName = `Winback ${run}`;
  const subject = `we miss you ${run}`;
  const msg = await (await ctx.post(MSG, { headers: H(nova), data: {
    messageType: 'email', subject, content: 'come back',
    source: campaignName,
    relatedParty: [{ id: login.id, role: 'customer' }] } })).json();
  if (!msg.id) fail('campaign message send failed: ' + JSON.stringify(msg));
  if (String(msg.source) !== campaignName) fail('the message did not echo its source: ' + JSON.stringify(msg));

  const marked = await timelineHas((d) => d.includes(`Marketing (${campaignName})`) && d.includes(subject));
  if (!marked) fail(`the timeline never showed "Marketing (${campaignName}): ${subject}"`);
  console.log(`OK a campaign send appears on the TMF683 timeline as "Marketing (${campaignName}): ${subject}"`);

  /* ---------- 2. an open and a click close the loop on the same timeline ---------- */
  const esp = (event) => ctx.post(`${API}/esp/v1/event`, {
    headers: { 'X-Esp-Token': 'nova-esp-key', 'Content-Type': 'application/json' },
    data: [{ event, email, custom_args: { tenant: 'nova', messageId: msg.id } }] });
  await esp('open');
  const opened = await timelineHas((d) => d === 'Email opened');
  if (!opened) fail('the open never landed on the timeline as "Email opened"');
  console.log('OK the ESP open closed the loop — "Email opened" is on the timeline');

  await esp('click');
  const clicked = await timelineHas((d) => d === 'Email clicked');
  if (!clicked) fail('the click never landed on the timeline as "Email clicked"');
  console.log('OK the ESP click closed the loop — "Email clicked" is on the timeline');

  console.log('\nALL INTERACTION-TIMELINE CHECKS PASSED — a CSR reads one record: which campaign said what, '
    + 'and whether the customer opened and clicked. Marketing is not a silo; it is on the 360.');
})();
