/* B2B: the ACCOUNT is an Organization (the contract holder); the CONTACTS are
 * Individuals (the humans who read mail). Party != user. Two things must hold:
 *   - org tokens resolve: {{organization.name}} renders the company name
 *   - account -> contact routing: a message addressed to the ORG fans out to
 *     its member individuals, each greeted by THEIR own first name
 * so "notify the account" reaches people, not a legal entity.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const ORG = `${API}/tmf-api/party/v4/organization`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;
const MSG = `${API}/tmf-api/communicationManagement/v4/communicationMessage`;
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

  /* ---------- an org account with two human contacts ---------- */
  const orgName = `Acme ${run}`;
  const org = await (await ctx.post(ORG, { headers: H(staff),
    data: { name: orgName, tradingName: `Acme Corp ${run}` } })).json();
  if (!org.id) fail('org not created: ' + JSON.stringify(org));

  const makeContact = async (given) => {
    const email = `${given.toLowerCase()}-${run}@acme.example.com`;
    const login = await (await ctx.post(USER, { headers: H(staff),
      data: { email, givenName: given, familyName: `Acme${run}` } })).json();
    if (!login.temporaryPassword) fail('contact user create failed: ' + JSON.stringify(login));
    await ctx.post(PARTY, { headers: H(staff), data: {
      id: login.id, givenName: given, familyName: `Acme${run}`, organization: { id: org.id } } });
    return { id: login.id, given, email, pass: login.temporaryPassword };
  };
  const bianca = await makeContact(`Bianca${run}`);
  const derek = await makeContact(`Derek${run}`);
  console.log(`OK ${orgName} has two contacts: ${bianca.given}, ${derek.given}`);

  /* ---------- one message ADDRESSED TO THE ORG ---------- */
  const created = await (await ctx.post(MSG, { headers: H(staff), data: {
    messageType: 'inApp',
    subject: 'Your invoice is ready',
    content: 'Hi {{party.firstName}} at {{organization.name}} — this month\'s invoice is ready.',
    relatedParty: [{ id: org.id, role: 'customer' }] } })).json();
  if (created.status !== 'sent' && !created.id) fail('org-addressed send failed: ' + JSON.stringify(created));
  console.log('OK one message was addressed to the ORG account (not a person)');

  /* ---------- each contact got THEIR OWN personalized copy ---------- */
  const inboxOf = async (c) => {
    const tok = await token(ctx, 'bss-biz', c.email, c.pass);
    for (let i = 0; i < 20; i++) {
      const res = await ctx.get(INBOX, { headers: H(tok) });
      const list = res.ok() ? await res.json() : [];
      const m = list.find((x) => x.content && x.content.includes('invoice is ready'));
      if (m) return m;
      await sleep(1000);
    }
    return null;
  };
  for (const c of [bianca, derek]) {
    const m = await inboxOf(c);
    if (!m) fail(`${c.given} (a contact of the org) never received the account message`);
    if (!m.content.includes(c.given)) fail(`the message to ${c.given} was not personalized to them: ${m.content}`);
    if (!m.content.includes(orgName)) fail(`{{organization.name}} did not resolve for ${c.given}: ${m.content}`);
    if (m.content.includes('{{')) fail('an unresolved token leaked: ' + m.content);
    console.log(`OK ${c.given} received their own copy — "${m.content}"`);
  }

  console.log('\nALL B2B CHECKS PASSED — an Organization account and its Individual contacts are distinct '
    + 'entities: {{organization.name}} resolves the company, and a message to the account fans out to each '
    + 'contact greeted by their own name. The growth modules work for B2B, not just B2C.');
})();
