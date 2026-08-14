/* #2 acquisition activation: push a first-party audience OUT to an ad/social
 * platform as a Custom Audience — how a segment reaches STRANGERS.
 *   seed:     a prospect audience -> the platform builds a lookalike from it
 *   suppress: a customer audience -> paid spend EXCLUDES people you already have
 * PII never leaves in clear — emails are SHA-256 hashed; only hashes cross the
 * wire. Customer emails are denormalised (IndividualCreateEvent -> email trait)
 * so the export is a batch, not a per-row party lookup.
 */
const { request } = require('playwright');
const crypto = require('crypto');

const API = 'http://localhost:8080';
const SOCIAL = 'http://localhost:8122';
const run = Date.now();
const IMPORT = `${API}/insight/v1/prospect/import`;
const AUDIENCE = `${API}/insight/v1/audience`;
const FACETS = `${AUDIENCE}/facets`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;
const sha256 = (s) => crypto.createHash('sha256').update(s.trim().toLowerCase()).digest('hex');

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'bss', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  /* ================= seed: a prospect audience -> Custom Audience ================= */
  const src = `acq-${run}`;
  const p1 = `seed-a-${run}@example.com`;
  const p2 = `seed-b-${run}@example.com`;
  await ctx.post(IMPORT, { headers: H(staff), data: { prospects: [
    { email: p1, source: src, lawfulBasis: 'opt-in' },
    { email: p2, source: src, lawfulBasis: 'opt-in' } ] } });
  const seedAud = await (await ctx.post(AUDIENCE, { headers: H(staff), data: {
    name: `Seed ${run}`, population: 'prospect', criteria: { all: [{ type: 'source', value: src }] } } })).json();

  const extSeed = `ca-seed-${run}`;
  const act = await (await ctx.post(`${AUDIENCE}/${seedAud.id}/activate`, { headers: H(staff),
    data: { externalAudienceId: extSeed, mode: 'seed' } })).json();
  if (act.mode !== 'seed' || act.pushed !== 2) fail('seed activation did not push 2 hashed emails: ' + JSON.stringify(act));

  const pushed = await (await ctx.get(`${SOCIAL}/v1/${extSeed}/users`, { headers: { Authorization: 'Bearer x' } })).json();
  if (!pushed.includes(sha256(p1)) || !pushed.includes(sha256(p2))) {
    fail('the platform did not receive the hashed prospect emails: ' + JSON.stringify(pushed).slice(0, 200));
  }
  if (pushed.includes(p1)) fail('a RAW email reached the platform — PII must be hashed');
  console.log('OK a prospect audience seeded a Custom Audience — 2 SHA-256 hashed emails, no PII in clear (lookalike source)');

  /* ================= suppress: a customer audience -> exclusion ================= */
  const cEmail = `cust-${run}@example.com`;
  const login = await (await ctx.post(USER, { headers: H(staff),
    data: { email: cEmail, givenName: `Cust${run}`, familyName: `A${run}` } })).json();
  if (!login.id) fail('customer user create failed: ' + JSON.stringify(login));
  await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: `Cust${run}`, familyName: `A${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: cEmail } }] } });

  // IndividualCreateEvent -> email denormalised into the trait store
  let hasEmailTrait = false;
  for (let i = 0; i < 30; i++) {
    const facets = await (await ctx.get(FACETS, { headers: H(staff) })).json();
    if (facets.some((f) => f.key === 'email' && f.value === cEmail)) { hasEmailTrait = true; break; }
    await sleep(1500);
  }
  if (!hasEmailTrait) fail('the customer email was not denormalised into the trait store');

  const suppAud = await (await ctx.post(AUDIENCE, { headers: H(staff), data: {
    name: `Existing customers ${run}`, population: 'customer',
    criteria: { all: [{ type: 'trait', key: 'email', value: cEmail }] } } })).json();
  const extSupp = `ca-suppress-${run}`;
  const sact = await (await ctx.post(`${AUDIENCE}/${suppAud.id}/activate`, { headers: H(staff),
    data: { externalAudienceId: extSupp, mode: 'suppress' } })).json();
  if (sact.mode !== 'suppress' || sact.pushed < 1) fail('suppress activation did not push the customer: ' + JSON.stringify(sact));

  const suppUsers = await (await ctx.get(`${SOCIAL}/v1/${extSupp}/users`, { headers: { Authorization: 'Bearer x' } })).json();
  if (!suppUsers.includes(sha256(cEmail))) fail('the customer hash is not in the suppression audience: ' + JSON.stringify(suppUsers).slice(0, 200));
  console.log('OK a customer audience pushed a suppression list — email denormalised, hashed, exported (spend excludes existing customers)');

  console.log('\nALL ACTIVATION CHECKS PASSED — first-party audiences reach STRANGERS: seed a lookalike from '
    + 'prospects/customers and suppress people you already have, all hashed and batched. This is how the martech '
    + 'stack closes the acquisition loop without shipping PII or standing up a separate CDP.');
})();
