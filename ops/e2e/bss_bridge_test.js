/* The add-on thesis, proven: a FOREIGN BSS (its own event shape) feeds the
 * martech module through one thin adapter — no change to any martech service.
 *   a foreign "acme-bss" posts CUSTOMER_CREATED + ORDER_COMPLETED (its shape)
 *   -> the bridge normalizes to the martech envelope on bss.bridge.events
 *   -> the trait store ingests product + email traits
 *   -> a BSS-native audience resolves the foreign customer, and it activates.
 * If this works, the martech stack runs on ANY BSS that can emit events.
 */
const { request } = require('playwright');
const crypto = require('crypto');

const API = 'http://localhost:8080';
const BRIDGE = 'http://localhost:8140';
const SOCIAL = 'http://localhost:8122';
const run = Date.now();
const FACETS = `${API}/insight/v1/audience/facets`;
const AUDIENCE = `${API}/insight/v1/audience`;
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
  const J = { 'Content-Type': 'application/json' };

  const custRef = `acme-cust-${run}`;
  const email = `ada-${run}@acme.example`;
  const plan = `Foreign Plan ${run}`;

  /* ---------- a FOREIGN BSS posts its own-shaped events to the bridge ---------- */
  const c = await (await ctx.post(`${BRIDGE}/bridge/v1/acme-bss/event`, { headers: J, data: {
    kind: 'CUSTOMER_CREATED', account: { ref: custRef, firstName: 'Ada', mail: email } } })).json();
  if (c.status !== 'forwarded' || c.eventType !== 'IndividualCreateEvent') fail('bridge did not normalize CUSTOMER_CREATED: ' + JSON.stringify(c));

  const o = await (await ctx.post(`${BRIDGE}/bridge/v1/acme-bss/event`, { headers: J, data: {
    kind: 'ORDER_COMPLETED', account: { ref: custRef }, lines: [{ sku: plan }] } })).json();
  if (o.status !== 'forwarded' || o.eventType !== 'ProductOrderStateChangeEvent') fail('bridge did not normalize ORDER_COMPLETED: ' + JSON.stringify(o));
  console.log('OK a foreign BSS posted CUSTOMER_CREATED + ORDER_COMPLETED (its own shape); the bridge normalized both');

  // an unmapped foreign event is ignored, not exploded
  const ign = await (await ctx.post(`${BRIDGE}/bridge/v1/acme-bss/event`, { headers: J, data: { kind: 'WHATEVER' } })).json();
  if (ign.status !== 'ignored') fail('an unmapped event should be ignored: ' + JSON.stringify(ign));

  /* ---------- the martech module ingested the foreign data as traits ---------- */
  let gotProduct = false;
  let gotEmail = false;
  for (let i = 0; i < 40; i++) {
    const facets = await (await ctx.get(FACETS, { headers: H(staff) })).json();
    gotProduct = facets.some((f) => f.key === 'product' && f.value === plan);
    gotEmail = facets.some((f) => f.key === 'email' && f.value === email);
    if (gotProduct && gotEmail) break;
    await sleep(1500);
  }
  if (!gotProduct) fail('the foreign order never became a product trait (bridge -> trait ingest)');
  if (!gotEmail) fail('the foreign customer email never became an email trait');
  console.log('OK the martech trait store ingested the foreign product + email — no martech service was changed');

  /* ---------- a BSS-native audience resolves the FOREIGN customer, and activates ---------- */
  const aud = await (await ctx.post(AUDIENCE, { headers: H(staff), data: {
    name: `Foreign holders ${run}`, population: 'customer',
    criteria: { all: [{ type: 'trait', key: 'product', value: plan }] } } })).json();
  let members = [];
  for (let i = 0; i < 20; i++) {
    members = await (await ctx.get(`${AUDIENCE}/${aud.id}/members`, { headers: H(staff) })).json();
    if (members.some((m) => m.partyId === custRef)) break;
    await sleep(1000);
  }
  if (!members.some((m) => m.partyId === custRef)) fail('the foreign customer is not in the audience: ' + JSON.stringify(members).slice(0, 200));
  console.log('OK a BSS-native audience resolved the foreign customer straight off the bridged BSS data');

  const ext = `ca-foreign-${run}`;
  const act = await (await ctx.post(`${AUDIENCE}/${aud.id}/activate`, { headers: H(staff),
    data: { externalAudienceId: ext, mode: 'seed' } })).json();
  if (!act.jobId) fail('activation was not queued: ' + JSON.stringify(act));
  for (let i = 0; i < 40; i++) { // async job: wait for the export to finish
    const j = await (await ctx.get(`${AUDIENCE}/activation/${act.jobId}`, { headers: H(staff) })).json();
    if (j.status === 'done' || j.status === 'error') break;
    await sleep(500);
  }
  const pushed = await (await ctx.get(`${SOCIAL}/v1/${ext}/users`, { headers: { Authorization: 'Bearer x' } })).json();
  if (!pushed.includes(sha256(email))) fail('the foreign customer did not reach the ad platform (hashed): ' + JSON.stringify(act));
  console.log('OK the foreign customer activated to a Custom Audience (email denormalized via the bridge, hashed)');

  console.log('\nALL BSS-BRIDGE CHECKS PASSED — the martech module ran end-to-end on a FOREIGN BSS through ONE '
    + 'config-driven adapter: foreign events -> normalized ingress -> traits -> audience -> activation. The '
    + 'add-on thesis is demonstrated, not asserted: onboarding a new BSS is a mapping config, not a code fork.');
})();
