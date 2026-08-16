/* C2 tail — volume pricing rules + quote e-signature.
 * A volume tier discounts a line once quantity clears the threshold; below it,
 * list price stands. The quote document can be e-signed, and shows the sign-off.
 * genalpha (bss realm); demo/demo staff.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const SALES = `${API}/tmf-api/salesManagement/v4`;
const QBASE = `${API}/tmf-api/quoteManagement/v4`;
const run = Date.now();
const OFF = `VOL-${run}`;

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
  const num = (v) => Number(v);

  const mkOppWith = async (qty) => {
    const lead = await (await ctx.post(`${SALES}/salesLead`, { headers: H, data: { name: `Pricing ${run} ${Math.random()}`, source: 'campaign' } })).json();
    const q = await (await ctx.patch(`${SALES}/salesLead/${lead.id}`, { headers: H, data: { state: 'qualified' } })).json();
    const oppId = q.salesOpportunity.id;
    await ctx.post(`${SALES}/salesOpportunity/${oppId}/item`, { headers: H, data: { offeringName: OFF, quantity: qty, unitPrice: 100, recurring: true } });
    return oppId;
  };
  const lineOf = (quote) => (quote.quoteItem || []).find((i) => i.offering && i.offering.name === OFF);

  /* ---------- author a volume tier: 10+ → 25% off ---------- */
  await ctx.post(`${QBASE}/quote/pricingRule`, { headers: H, data: { offeringName: OFF, minQuantity: 10, discountPercent: 25 } });
  console.log('OK authored a volume tier: buy 10+ of the offering → 25% off');

  /* ---------- at 10 units the tier applies ---------- */
  const bigOpp = await mkOppWith(10);
  const bigQuote = (await (await ctx.post(`${SALES}/salesOpportunity/${bigOpp}/quote`, { headers: H, data: {} })).json()).quote;
  const bigLine = lineOf(bigQuote);
  if (!bigLine || num(bigLine.unitPrice.value) !== 75 || num(bigLine.volumeDiscountPercent) !== 25) {
    fail('volume tier did not discount the line to 75: ' + JSON.stringify(bigLine));
  }
  if (num(bigQuote.quoteTotalPrice.value) !== 750) fail('quote MRR should be 10×75 = 750: ' + JSON.stringify(bigQuote.quoteTotalPrice));
  console.log('OK at 10 units the line dropped to 75 (25% off 100); quote MRR = 750');

  /* ---------- below the threshold, list price stands ---------- */
  const smallOpp = await mkOppWith(5);
  const smallQuote = (await (await ctx.post(`${SALES}/salesOpportunity/${smallOpp}/quote`, { headers: H, data: {} })).json()).quote;
  const smallLine = lineOf(smallQuote);
  if (!smallLine || num(smallLine.unitPrice.value) !== 100 || smallLine.volumeDiscountPercent != null) {
    fail('below the tier the price should be list (100), no discount: ' + JSON.stringify(smallLine));
  }
  console.log('OK at 5 units (below the tier) list price stands at 100 — no discount');

  /* ---------- e-sign the quote document ---------- */
  const qhref = bigQuote.href;
  let doc = await (await ctx.get(`${API}${qhref}/document`, { headers: { Authorization: 'Bearer ' + tok } })).text();
  if (!/sign to accept/i.test(doc)) fail('unsigned document should show a signature line');
  const signed = await (await ctx.post(`${API}${qhref}/sign`, { headers: H, data: { signedBy: `Acme CFO ${run}` } })).json();
  if (signed.signatureStatus !== 'signed' || signed.signedBy !== `Acme CFO ${run}`) {
    fail('e-sign did not record the signature: ' + JSON.stringify(signed));
  }
  doc = await (await ctx.get(`${API}${qhref}/document`, { headers: { Authorization: 'Bearer ' + tok } })).text();
  if (!doc.includes(`Acme CFO ${run}`) || !/✓ Signed/.test(doc)) fail('signed document does not show the sign-off: ' + doc.slice(-200));
  console.log('OK the quote was e-signed, and the document shows the signed-off block');

  console.log('\nALL PRICING + E-SIGN CHECKS PASSED — volume tiers price the deal automatically, and the quote '
    + 'document carries a real e-signature — the last two CPQ seams.');
})();
