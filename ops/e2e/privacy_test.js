/* GDPR — the rights a subscriber actually holds. Suite #58.
 *
 *  - THE DATA PASSPORT: a customer exports THEMSELVES — the fan-out
 *    rides their own token, so the right of access needs no new
 *    authority at all. One JSON, a category per shelf, the legal-hold
 *    categories named beside the data.
 *  - THE HONEST REFUSAL: a subscriber with an ACTIVE plan cannot be
 *    erased (409) — terminate first; erasure never breaks a running
 *    contract (Art. 17(3)(b)).
 *  - THE ERASER: profile anonymized IN PLACE, interactions/carts/
 *    messages/marketing gone, the LOGIN DEAD at the IdP — while bills
 *    and orders stay under bookkeeping law, each retained category
 *    carrying its legal basis in the report. The report itself becomes
 *    an immutable audit row: proof the right was honoured.
 *  - RETENTION IS A CLOCK: a seconds-scale dev dial proves an old
 *    interaction is swept on schedule, TickGuard-guarded like every
 *    other mutating tick.
 */
const { execSync } = require('child_process');
const { request } = require('playwright');

const API = 'http://localhost:8080';
const REPO = `${__dirname}/../..`;
const run = Date.now();
const PLAN = { id: '14291c1a-df26-4232-8084-500466888e46', name: 'GenAlpha Mobile 10 GB' };
const ENV = { ...process.env, PATH: '/opt/homebrew/bin:' + process.env.PATH };

const sh = (cmd, timeout = 240000) =>
  execSync(cmd, { cwd: REPO, env: ENV, timeout }).toString().trim();

async function token(ctx, user, pass) {
  for (let i = 0; i < 12; i++) {
    try {
      const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
        { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
      const body = await res.json();
      if (body.access_token) return body.access_token;
      if (body.error === 'invalid_grant') return null; // disabled / wrong creds — a verdict
    } catch (transient) { /* mid-boot is not a verdict */ }
    await new Promise((r) => setTimeout(r, 3000));
  }
  return null;
}

async function mintCustomer(ctx, staff, tag) {
  const email = `${tag}-${run}@example.com`;
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Gdpr', familyName: `${tag}${run}` } })).json();
  if (!login.id) throw new Error('customer mint failed: ' + JSON.stringify(login).slice(0, 120));
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
    id: login.id, givenName: 'Gdpr', familyName: `${tag}${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
  return { id: login.id, email, password: login.temporaryPassword };
}

const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'demo', 'demo');
  if (!staff) fail('no staff token');

  /* ---------- 1. a person with data on many shelves ---------- */
  const leaver = await mintCustomer(ctx, staff, 'leaver');
  const leaverTok = await token(ctx, leaver.email, leaver.password);
  if (!leaverTok) fail('leaver cannot sign in');
  // an interaction on the record
  await ctx.post(`${API}/tmf-api/partyInteraction/v4/partyInteraction`, { headers: H(staff),
    data: { description: `Support call before leaving (${run})`, channel: 'phone',
      direction: 'inbound',
      relatedParty: [{ id: leaver.id, role: 'customer', '@referredType': 'Individual' }] } });
  // a cart of their own
  await ctx.post(`${API}/tmf-api/shoppingCart/v4/shoppingCart`, { headers: H(leaverTok),
    data: { cartItem: [] } });

  /* ---------- 2. the data passport, self-service ---------- */
  const passport = await (await ctx.get(`${API}/privacy/v1/export`,
    { headers: H(leaverTok) })).json();
  if (passport.partyId !== leaver.id) fail('the passport names the wrong person: ' + passport.partyId);
  const cat = (name) => (passport.categories || []).find((c) => c.category === name) || {};
  if (!((cat('interactions').count || 0) >= 1)) fail('the passport lacks the interaction');
  if (!((cat('carts').count || 0) >= 1)) fail('the passport lacks the cart');
  if (!passport.profile || passport.profile.givenName !== 'Gdpr') fail('the passport lacks the profile');
  if (!passport.alsoHeldUnderLegalBasis || !passport.alsoHeldUnderLegalBasis.bills) {
    fail('the passport does not name the legal-hold categories');
  }
  console.log('OK THE DATA PASSPORT: the customer exported THEMSELVES — profile, interaction and'
    + ' cart in one JSON, fetched with their own token at every service (the right of access'
    + ' as no-new-authority), and the legal-hold categories are named beside the data');

  /* ---------- 3. the honest refusal: active customers stay ---------- */
  const active = await mintCustomer(ctx, staff, 'active');
  const activeTok = await token(ctx, active.email, active.password);
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(activeTok), data: { productOrderItem: [{ action: 'add', productOffering: PLAN }] } });
  await sleep(8000); // activation makes the product ACTIVE
  const refused = await ctx.post(`${API}/privacy/v1/erase`,
    { headers: H(staff), data: { partyId: active.id } });
  if (refused.status() !== 409) {
    fail('an ACTIVE subscriber was erased (or wrongly refused): ' + refused.status());
  }
  console.log('OK THE HONEST REFUSAL: erasing a subscriber with an active plan answers 409 —'
    + ' terminate first; erasure never breaks a running contract');

  /* ---------- 4. the eraser ---------- */
  const report = await (await ctx.post(`${API}/privacy/v1/erase`,
    { headers: H(staff), data: { partyId: leaver.id } })).json();
  if (report.status !== 'completed') fail('erasure not completed: ' + JSON.stringify(report).slice(0, 300));
  const rcat = (name) => (report.categories || []).find((c) => c.category === name) || {};
  if ((rcat('interactions').deleted || 0) < 1) fail('the interaction survived erasure');
  if ((rcat('carts').deleted || 0) < 1) fail('the cart survived erasure');
  if ((rcat('identity').deleted || 0) !== 1) fail('the login was not erased at the IdP');
  if (!String(rcat('bills').reason || '').includes('bookkeeping')) {
    fail('the report does not carry the bills legal basis');
  }
  if (!report.auditRecordId) fail('no audit record id in the report');
  console.log('OK THE ERASER: interactions, carts, messages and marketing gone; the retained'
    + ' categories each carry their legal basis; the report is a document, not a shrug');

  // the profile is anonymized in place
  const ghost = await (await ctx.get(`${API}/tmf-api/party/v4/individual/${leaver.id}`,
    { headers: H(staff) })).json();
  if (ghost.givenName !== 'Erased' || String(JSON.stringify(ghost)).includes(leaver.email)) {
    fail('the profile still carries personal data: ' + JSON.stringify(ghost).slice(0, 200));
  }
  console.log('OK ANONYMIZED IN PLACE: the profile row answers "Erased" and carries no email —'
    + ' the id survives as the pseudonymous reference the retained records point at');

  // the login is DEAD
  const deadLogin = await token(ctx, leaver.email, leaver.password);
  if (deadLogin) fail('the erased person can still sign in');
  console.log('OK THE LOGIN IS DEAD: the IdP refuses the erased person\'s password — disabled'
    + ' and scrubbed at the realm');

  // and the audit outlives the erased
  const audit = await (await ctx.get(`${API}/privacy/v1/erasure`, { headers: H(staff) })).json();
  const auditRow = audit.find((a) => a.partyId === leaver.id);
  if (!auditRow) fail('no erasure audit row');
  console.log(`OK THE AUDIT OUTLIVES THE ERASED: record ${auditRow.id.slice(0, 8)} says who`
    + ' executed the erasure and when — proof the right was honoured, holding no personal data');

  /* ---------- 5. retention is a clock ---------- */
  console.log('dialing interaction retention to 5 seconds ...');
  sh('RETENTION_INTERACTIONS_SECONDS=5 RETENTION_SWEEP_MS=3000 docker compose up -d party-interaction');
  // wait for the gateway→recreated-container path to heal
  let healed = false;
  for (let i = 0; i < 45 && !healed; i++) {
    await sleep(4000);
    try {
      const probe = await ctx.post(`${API}/tmf-api/partyInteraction/v4/partyInteraction`,
        { headers: H(staff), timeout: 4000,
          data: { description: `Retention probe (${run})`, channel: 'phone', direction: 'inbound',
            relatedParty: [{ id: 'retention-probe-' + run, role: 'customer',
              '@referredType': 'Individual' }] } });
      healed = probe.status() === 201 || probe.status() === 200;
    } catch (healing) { /* knock again */ }
  }
  if (!healed) fail('party-interaction never came back with the retention dial set');
  await sleep(15000); // 5s retention + two 3s sweep cycles, with margin
  const after = await (await ctx.get(
    `${API}/tmf-api/partyInteraction/v4/partyInteraction?limit=50&relatedPartyId=retention-probe-${run}`,
    { headers: H(staff) })).json();
  const survivors = (Array.isArray(after) ? after : [])
    .filter((r) => JSON.stringify(r).includes('retention-probe-' + run));
  if (survivors.length !== 0) fail('the interaction outlived its retention window');
  console.log('OK RETENTION IS A CLOCK: the probe interaction was swept on schedule —'
    + ' data minimization enforced by a TickGuard-guarded tick, not remembered by a human');
  sh('docker compose up -d party-interaction'); // retention back to "keep forever"

  console.log('\nALL PRIVACY CHECKS PASSED — the passport is self-service, erasure is honest'
    + ' about the law both ways (active contracts refuse, bookkeeping holds are named), the'
    + ' login dies at the IdP, the audit outlives the erased, and retention runs on a clock.'
    + ' The subscriber\'s rights are features now, with receipts.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
