/* deviceModel trait — the network's live device truth in the CDP.
 * The EIR / device-detection reports the handset a SIM is in; the CDP turns it
 * into a single-valued deviceModel trait. So "customers on an iPhone 15" is an
 * audience — regardless of where they bought the phone (incl. BYOD) — and a
 * device swap re-homes them automatically (single-valued = replace-on-change).
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const DETECT = `${API}/tmf-api/usageManagement/v4/deviceDetection`;
const AUD = `${API}/insight/v1/audience`;

async function token(ctx) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx);
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };

  const uid = (await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H, data: { email: `dev-${run}@example.com`, givenName: 'Dev', familyName: 'Ice' } })).json()).id;
  const OLD = `iPhone 15 ${run}`; const NEW = `iPhone 16 ${run}`;

  // the network detects their handset (no purchase needed — this is BYOD-safe)
  const det = await (await ctx.post(DETECT, { headers: H, data: { partyId: uid, deviceModel: OLD, tac: '35123456' } })).json();
  if (det.status !== 'recorded') fail('device detection was not recorded: ' + JSON.stringify(det));

  const audFor = async (model) => (await (await ctx.post(AUD, { headers: H, data: {
    name: `__dev_${model}`, population: 'customer', criteria: { all: [{ type: 'trait', key: 'deviceModel', op: 'eq', value: model }] } } })).json()).id;
  const oldAud = await audFor(OLD); const newAud = await audFor(NEW);
  const inAud = async (id) => (await (await ctx.get(`${AUD}/${id}/members`, { headers: H })).json()).some((m) => m.partyId === uid);

  let ok = false;
  for (let i = 0; i < 20; i++) { if (await inAud(oldAud)) { ok = true; break; } await sleep(1000); }
  if (!ok) fail('the detected device did not become an audience-able deviceModel trait');
  console.log(`OK the network's device (${OLD}) became a CDP trait — the customer is in the "on ${OLD}" audience`);

  // they swap handsets — the network re-detects; the CDP replaces the trait
  await ctx.post(DETECT, { headers: H, data: { partyId: uid, deviceModel: NEW, tac: '35998877' } });
  let moved = false;
  for (let i = 0; i < 20; i++) { if (!(await inAud(oldAud)) && (await inAud(newAud))) { moved = true; break; } await sleep(1000); }
  if (!moved) fail('a device swap did not re-home the customer (old audience should drop, new should match)');
  console.log(`OK a device swap re-homed them — out of "${OLD}", into "${NEW}" — automatically`);

  await ctx.delete(`${AUD}/${oldAud}`, { headers: H });
  await ctx.delete(`${AUD}/${newAud}`, { headers: H });
  console.log('\nALL DEVICE-MODEL CHECKS PASSED — the OSS/network device truth (EIR/device-detection) is a '
    + 'first-class CDP trait: target "customers on an iPhone 15" for an upgrade offer regardless of where the '
    + 'phone was bought (BYOD included), and a swap re-homes them live. The BSS ingests the network signal, '
    + "it doesn't warehouse it.");
})();
