/* Region trait + B2B org-population audiences.
 *   region: a customer's region becomes an audience-able trait.
 *   org:    organizations are their own population with their own traits
 *           (industry), resolved separately from individuals.
 * Produced via the bss-bridge (also proves it handling org + region events).
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const BRIDGE = 'http://localhost:8140';
const run = Date.now();
const FACETS = `${API}/insight/v1/audience/facets`;
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
  const J = { 'Content-Type': 'application/json' };
  const bridge = (data) => ctx.post(`${BRIDGE}/bridge/v1/acme-bss/event`, { headers: J, data });
  const facetHas = async (key, value) => { for (let i = 0; i < 40; i++) { const f = await (await ctx.get(FACETS, { headers: H(staff) })).json(); if (f.some((x) => x.key === key && x.value === value)) return true; await sleep(1500); } return false; };
  const members = async (id) => (await (await ctx.get(`${AUDIENCE}/${id}/members`, { headers: H(staff) })).json()).map((m) => m.partyId);

  /* ---------- region trait ---------- */
  const cust = `reg-cust-${run}`; const region = `Oslo${run}`;
  await bridge({ kind: 'CUSTOMER_CREATED', account: { ref: cust, firstName: 'Ola', mail: `ola-${run}@x.com` }, region });
  if (!await facetHas('region', region)) fail('the region trait never landed');
  const rAud = await (await ctx.post(AUDIENCE, { headers: H(staff), data: { name: `Region ${run}`, population: 'customer', criteria: { all: [{ type: 'trait', key: 'region', value: region }] } } })).json();
  let rm = []; for (let i = 0; i < 12 && !rm.includes(cust); i++) { rm = await members(rAud.id); await sleep(800); }
  if (!rm.includes(cust)) fail('the region audience did not resolve the customer');
  console.log(`OK region became a trait — an audience {region=${region}} resolves the customer`);

  /* ---------- B2B org-population ---------- */
  const org = `org-${run}`; const industry = `telco${run}`;
  await bridge({ kind: 'ORG_CREATED', account: { ref: org }, industry, tradingName: `Acme ${run}` });
  if (!await facetHas('industry', industry)) fail('the org industry trait never landed');
  const oAud = await (await ctx.post(AUDIENCE, { headers: H(staff), data: { name: `Orgs ${run}`, population: 'organization', criteria: { all: [{ type: 'trait', key: 'industry', value: industry }] } } })).json();
  if (oAud.population !== 'organization') fail('the audience was not saved as an organization population');
  let om = []; for (let i = 0; i < 12 && !om.includes(org); i++) { om = await members(oAud.id); await sleep(800); }
  if (!om.includes(org)) fail('the org-population audience did not resolve the organization');
  if (om.includes(cust)) fail('an individual leaked into an org-population audience');
  console.log(`OK organizations are their own population — an audience {industry=${industry}} resolves the org, not individuals`);

  console.log('\nALL REGION + ORG CHECKS PASSED — region is an audience-able trait, and B2B audiences resolve over '
    + 'ORGANIZATIONS (their own traits, marked so individuals never leak in). Population model: customer / prospect / org.');
})();
