/* #3a social lead -> nurture prospect: a lead-form entry the operator captured
 * flows into the martech prospect store automatically — consented, reachable,
 * ready for a welcome journey. No hand-off between Sales and Marketing.
 *   seed a lead in the social lead-form -> quote imports it (sales_lead)
 *   -> insight captures it as a CONSENTED prospect (source=social)
 *   -> a prospect audience resolves it for nurture.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const SOCIAL = 'http://localhost:8122';
const FORM = 'form-genalpha-fiber';
const run = Date.now();
const IMPORT_SOCIAL = `${API}/tmf-api/salesManagement/v4/salesLead/importSocial`;
const PROSPECTS = `${API}/insight/v1/prospect`;
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
  const staff = await token(ctx, 'bss', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  const email = `slead-${run}@example.com`;

  /* ---------- a prospect fills the brand's social lead-form ---------- */
  await ctx.post(`${SOCIAL}/v1/${FORM}/leads`, { headers: { 'Content-Type': 'application/json' },
    data: { email, full_name: `Sara ${run}`, need: `Fibre interest ${run}` } });
  console.log('OK a prospect submitted the social lead-form');

  /* ---------- Sales imports it; it becomes a sales lead + fires the event ---------- */
  const imp = await (await ctx.post(IMPORT_SOCIAL, { headers: H(staff), data: {} })).json();
  if (imp.imported === undefined && !Array.isArray(imp)) fail('importSocial failed: ' + JSON.stringify(imp).slice(0, 200));
  console.log('OK Sales imported the social lead-form into the pipeline');

  /* ---------- the martech side captured it as a CONSENTED nurture prospect ---------- */
  let prospect = null;
  for (let i = 0; i < 30; i++) {
    const list = await (await ctx.get(`${PROSPECTS}?source=social`, { headers: H(staff) })).json();
    prospect = Array.isArray(list) ? list.find((p) => p.email === email) : null;
    if (prospect) break;
    await sleep(1500);
  }
  if (!prospect) fail('the social lead never became a prospect');
  if (prospect.consent !== 'consented') fail('a social lead-form lead should be consented (opt-in): ' + JSON.stringify(prospect));
  console.log(`OK the lead became a consented nurture prospect (source=${prospect.source}, basis=${prospect.lawfulBasis})`);

  /* ---------- a prospect audience resolves it for a welcome journey ---------- */
  const aud = await (await ctx.post(AUDIENCE, { headers: H(staff), data: {
    name: `Social leads ${run}`, population: 'prospect',
    criteria: { all: [{ type: 'source', value: 'social' }] } } })).json();
  let members = [];
  for (let i = 0; i < 15; i++) {
    members = await (await ctx.get(`${AUDIENCE}/${aud.id}/members`, { headers: H(staff) })).json();
    if (members.some((m) => m.email === email)) break;
    await sleep(1000);
  }
  if (!members.some((m) => m.email === email)) fail('the social lead is not in the nurture audience');
  console.log('OK a prospect audience resolves the social lead — ready for a welcome/nurture journey');

  console.log('\nALL SOCIAL-LEAD CHECKS PASSED — a social lead-form entry flows Sales -> Marketing with no '
    + 'hand-off: captured as a consented prospect and immediately targetable. Inbound social becomes nurture.');
})();
