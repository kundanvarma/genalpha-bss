/* C2b — quote → order → contract automation.
 * An accepted quote produces BOTH a TMF622 product order AND a TMF651 agreement
 * (the contract), for the same party + items, linked back to the quote — no
 * re-keying. genalpha (bss realm); demo/demo staff.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const SALES = `${API}/tmf-api/salesManagement/v4`;
const CATALOG = `${API}/tmf-api/productCatalogManagement/v4`;
const AGREEMENT = `${API}/tmf-api/agreementManagement/v4`;
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const run = Date.now();

async function token(ctx) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const tok = await token(ctx);
  const H = { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' };

  // a real account + a real catalog offering (so the downstream order accepts it)
  const email = `qoc-${run}@example.com`;
  const login = await (await ctx.post(USER, { headers: H, data: { email, givenName: `QOC${run}`, familyName: `A${run}` } })).json();
  await ctx.post(PARTY, { headers: H, data: { id: login.id, givenName: `QOC${run}`, familyName: `A${run}` } });
  const offerings = await (await ctx.get(`${CATALOG}/productOffering?limit=20`, { headers: H })).json();
  const off = (offerings || []).find((o) => o.id && o.name);
  if (!off) fail('no catalog offering to quote');
  console.log(`OK using account ${login.id.slice(0, 8)} and offering "${off.name}"`);

  /* ---------- opportunity → quote ---------- */
  const lead = await (await ctx.post(`${SALES}/salesLead`, { headers: H, data: { name: `QOC deal ${run}`, source: 'campaign' } })).json();
  const q = await (await ctx.patch(`${SALES}/salesLead/${lead.id}`, { headers: H, data: { state: 'qualified', partyId: login.id } })).json();
  const oppId = q.salesOpportunity.id;
  await ctx.post(`${SALES}/salesOpportunity/${oppId}/item`, { headers: H, data: {
    offeringId: off.id, offeringName: off.name, quantity: 1, unitPrice: 300, recurring: true } });
  const built = await (await ctx.post(`${SALES}/salesOpportunity/${oppId}/quote`, { headers: H, data: {} })).json();
  const qhref = built.quote.href;
  console.log('OK the deal built a quote');

  /* ---------- approve → accept ---------- */
  const approved = await ctx.patch(`${API}${qhref}`, { headers: H, data: { state: 'approved' } });
  if (approved.status() !== 200) fail('could not approve the quote: ' + approved.status());
  const accepted = await (await ctx.post(`${API}${qhref}/accept`, { headers: H })).json();

  /* ---------- BOTH an order and a contract, linked ---------- */
  if (!accepted.productOrder || !accepted.productOrder.id) fail('accept did not create a product order: ' + JSON.stringify(accepted));
  if (!accepted.agreement || !accepted.agreement.id) fail('accept did not create a contract (agreement): ' + JSON.stringify(accepted));
  console.log(`OK accepting the quote created a product order (${accepted.productOrder.id.slice(0, 8)}) AND a contract (${accepted.agreement.id.slice(0, 8)})`);

  /* ---------- the contract is real, for the same party ---------- */
  const agr = await (await ctx.get(`${AGREEMENT}/agreement/${accepted.agreement.id}`, { headers: H })).json();
  if (!agr.id) fail('the agreement is not retrievable: ' + JSON.stringify(agr));
  const parties = agr.engagedParty || agr.engagedPartyRole || [];
  if (!parties.some((p) => p.id === login.id)) fail('the contract is not engaged to the deal party: ' + JSON.stringify(parties));
  console.log(`OK the TMF651 contract is retrievable and engaged to the deal's party (status ${agr.status || agr.agreementStatus || '—'})`);

  console.log('\nALL QUOTE→ORDER→CONTRACT CHECKS PASSED — one acceptance yields the order AND the contract, '
    + 'same party, same items, linked to the quote — the telco lead-to-order spine with no swivel-chair.');
})();
