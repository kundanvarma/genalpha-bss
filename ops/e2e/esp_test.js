/* ESP delivery seam — bring your own email provider, per tenant.
 *
 *  - nova runs DELIVERY_PROVIDER=esp: a customer message ALSO leaves the
 *    building through "their" SendGrid (the mock), addressed to the email
 *    the BSS already knows, signed with the tenant's own API key
 *  - the in-app inbox stays the source of truth (the message is there too)
 *  - genalpha runs internal: its messages NEVER appear at the ESP — same
 *    binary, config apart
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const ESP = 'http://localhost:8121';
const run = Date.now();
const NORAH = { id: 'e9226b68-7cb2-4a1a-af00-39ac33c4ef7a', email: 'norah@nova.example' };

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const MSG = `${API}/tmf-api/communicationManagement/v4/communicationMessage`;

  /* ---------- nova: the message leaves through nova's OWN provider ---------- */
  const novaStaff = await token(ctx, 'nova', 'demo', 'demo');
  const subject = `Velkommen ${run}`;
  const sent = await (await ctx.post(MSG, { headers: H(novaStaff), data: {
    subject, content: 'Din faktura er klar.',
    relatedParty: [{ id: NORAH.id, role: 'customer' }] } })).json();
  if (!sent.id) fail('nova send failed: ' + JSON.stringify(sent).slice(0, 200));

  let mail = null;
  for (let i = 0; i < 15 && !mail; i++) {
    await sleep(1000);
    const mails = await (await ctx.get(`${ESP}/mails?to=${NORAH.email}`)).json();
    mail = mails.find((m) => m.subject === subject);
  }
  if (!mail) fail('the message never reached nova\'s ESP');
  if (mail.from !== 'no-reply@nova.example') fail('wrong sender identity: ' + mail.from);
  if (mail.apiKey !== 'nova-esp-key') fail('the ESP was not called with nova\'s own key');
  if (!mail.content.includes('faktura')) fail('the body did not survive the wire: ' + mail.content);
  console.log('OK the ESP SEAM: norah\'s message left through nova\'s own provider — right'
    + ' address (looked up from party management), right sender, nova\'s own API key');

  /* the in-app inbox remains the source of truth */
  const norah = await token(ctx, 'nova', NORAH.email, 'norah');
  const inbox = await (await ctx.get(`${MSG}?limit=100`, { headers: H(norah) })).json();
  if (!inbox.some((m) => m.subject === subject)) {
    fail('the ESP got the mail but the in-app inbox lost it — the inbox is the source of truth');
  }
  console.log('OK the in-app inbox has the same message — the ESP send is an AND, not an OR');

  /* ---------- genalpha: internal-only, nothing leaves the building ---------- */
  const staff = await token(ctx, 'bss', 'demo', 'demo');
  const email = `esp-wall-${run}@example.com`;
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Espen', familyName: `Wall${run}` } })).json();
  const gaSubject = `Internal only ${run}`;
  await ctx.post(MSG, { headers: H(staff), data: {
    subject: gaSubject, content: 'This stays in-app.',
    relatedParty: [{ id: login.id, role: 'customer' }] } });
  await sleep(5000);
  const all = await (await ctx.get(`${ESP}/mails`)).json();
  if (all.some((m) => m.subject === gaSubject || m.to.includes(email))) {
    fail('a genalpha message leaked out through the ESP — genalpha is internal-only');
  }
  console.log('OK the tenant wall: genalpha\'s message never left the building — same binary,'
    + ' per-tenant config');

  console.log('\nALL ESP CHECKS PASSED — the delivery seam is per-tenant: nova\'s messages ride'
    + ' nova\'s own email provider, genalpha stays in-app, and the inbox is always the record.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
