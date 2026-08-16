/* Segment price lists off the CDP.
 * A customer's segment is a CDP trait (here, region). A segment pricing rule
 * discounts that segment and OVERRIDES the volume tier (most-specific-wins);
 * a buyer not in the segment falls through to the volume tier. The segment is
 * resolved live from insight at quote build. genalpha (bss realm); demo/demo.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const SALES = `${API}/tmf-api/salesManagement/v4`;
const QBASE = `${API}/tmf-api/quoteManagement/v4`;
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const SEG = `${API}/insight/v1/partySegments`;
const run = Date.now();
const OFF = `SEGOFF-${run}`;
const SEGMENT = `entrpr-${run}`;   // a run-unique CDP segment value (region trait)

async function token(ctx) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const tok = await token(ctx);
  const H = { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' };
  const num = (v) => Number(v);

  const mkParty = async (tag, region) => {
    const email = `seg-${tag}-${run}@example.com`;
    const login = await (await ctx.post(USER, { headers: H, data: { email, givenName: `Seg${tag}`, familyName: `A${run}` } })).json();
    await ctx.post(PARTY, { headers: H, data: { id: login.id, givenName: `Seg${tag}`, familyName: `A${run}`, region } });
    return login.id;
  };
  const quoteFor = async (partyId) => {
    const lead = await (await ctx.post(`${SALES}/salesLead`, { headers: H, data: { name: `Seg ${run} ${Math.random()}`, source: 'campaign' } })).json();
    const q = await (await ctx.patch(`${SALES}/salesLead/${lead.id}`, { headers: H, data: { state: 'qualified', partyId } })).json();
    const oppId = q.salesOpportunity.id;
    await ctx.post(`${SALES}/salesOpportunity/${oppId}/item`, { headers: H, data: { offeringName: OFF, quantity: 1, unitPrice: 100, recurring: true } });
    return (await (await ctx.post(`${SALES}/salesOpportunity/${oppId}/quote`, { headers: H, data: {} })).json()).quote;
  };
  const lineOf = (quote) => (quote.quoteItem || []).find((i) => i.offering && i.offering.name === OFF);

  /* ---------- an enterprise-segment account + a plain account ---------- */
  const enterprise = await mkParty('ent', SEGMENT);
  const plain = await mkParty('plain', `other-${run}`);
  // wait for the CDP to hold the enterprise account's segment (region trait)
  let inCdp = false;
  for (let i = 0; i < 30; i++) {
    const seg = await (await ctx.get(`${SEG}?partyId=${enterprise}`, { headers: H })).json();
    if ((seg.segments || []).includes(SEGMENT)) { inCdp = true; break; }
    await sleep(1500);
  }
  if (!inCdp) fail('the CDP never recorded the account segment');
  console.log(`OK the enterprise account carries the CDP segment "${SEGMENT}"`);

  /* ---------- a segment price + a volume tier on the same offering ---------- */
  await ctx.post(`${QBASE}/quote/pricingRule`, { headers: H, data: { offeringName: OFF, minQuantity: 1, discountPercent: 30, segment: SEGMENT } });
  await ctx.post(`${QBASE}/quote/pricingRule`, { headers: H, data: { offeringName: OFF, minQuantity: 1, discountPercent: 10 } });
  console.log('OK authored a 30% segment price and a 10% volume tier on the same offering');

  /* ---------- the enterprise account gets the segment price (beats volume) ---------- */
  const entLine = lineOf(await quoteFor(enterprise));
  if (!entLine || num(entLine.unitPrice.value) !== 70 || num(entLine.segmentDiscountPercent) !== 30) {
    fail('the enterprise account did not get the 30% segment price (70): ' + JSON.stringify(entLine));
  }
  if (entLine.pricedBy !== `segment:${SEGMENT}`) fail('the line was not priced by the segment: ' + JSON.stringify(entLine.pricedBy));
  console.log('OK the enterprise account is priced by its CDP segment — 70 (30% off), overriding the volume tier');

  /* ---------- a plain account falls through to the volume tier ---------- */
  const plainLine = lineOf(await quoteFor(plain));
  if (!plainLine || num(plainLine.unitPrice.value) !== 90 || num(plainLine.volumeDiscountPercent) !== 10) {
    fail('the plain account did not fall through to the 10% volume tier (90): ' + JSON.stringify(plainLine));
  }
  if (plainLine.pricedBy !== 'volume') fail('the plain line was not priced by volume: ' + JSON.stringify(plainLine.pricedBy));
  console.log('OK a plain account (not in the segment) falls through to the volume tier — 90 (10% off)');

  console.log('\nALL SEGMENT-PRICING CHECKS PASSED — pricing reads the CDP: an account in a segment pays its '
    + 'segment price (most-specific-wins over volume), and one that isn\'t falls through — one segment '
    + 'definition serving both marketing and CPQ.');
})();
