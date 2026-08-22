/* G2 — streaks + the Right-Plan Guarantee.
 *
 *  STREAK: a REAL bill, settled by the BANK's remittance file, extends the
 *  customer's bill-paid streak as a CDP trait — verified by audience
 *  membership, the way every other trait is trusted.
 *  RIGHT-PLAN: a customer on a 20 GB plan using 1 GB gets the CHEAPER 5 GB
 *  suggestion, with saving, headroom and the read-only promise on the face.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const CAT = `${API}/tmf-api/productCatalogManagement/v4`;
const BILLS = `${API}/tmf-api/customerBillManagement/v4`;
const USAGE = `${API}/tmf-api/usageManagement/v4`;
const AUD = `${API}/insight/v1/audience`;

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

  /* ---------- two probe plans: Big (20 GB / 300) and Small (5 GB / 150) ---------- */
  const mkPlan = async (name, priceVal, gb) => {
    const price = await (await ctx.post(`${CAT}/productOfferingPrice`, { headers: H(staff),
      data: { name: `${name} monthly`, priceType: 'recurring', recurringChargePeriodType: 'month',
        lifecycleStatus: 'Active', price: { unit: 'EUR', value: priceVal } } })).json();
    const off = await (await ctx.post(`${CAT}/productOffering`, { headers: H(staff),
      data: { name, lifecycleStatus: 'Active', isBundle: false, isSellable: true,
        category: [catRef], productOfferingPrice: [{ id: price.id, name: price.name }] } })).json();
    await ctx.post(`${USAGE}/usageAllowance`, { headers: H(staff), data: {
      productOffering: { id: off.id, name },
      usageType: `G2Data${run}`, allowance: { value: gb, units: 'GB' },
      overagePrice: { value: 5, unit: 'EUR' } } });
    return off;
  };
  const cat = await (await ctx.post(`${CAT}/category`, { headers: H(staff),
    data: { name: `G2Cat${run}`, lifecycleStatus: 'Active' } })).json();
  const catRef = { id: cat.id, name: cat.name, '@referredType': 'Category' };
  const big = await mkPlan(`G2 Big ${run}`, 300, 20);
  await mkPlan(`G2 Small ${run}`, 150, 5);

  const email = `g2-${run}@example.com`;
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Gto', familyName: `O${run}` } })).json();
  const cust = { id: login.id, tok: await token(ctx, 'bss-biz', email, login.temporaryPassword) };
  const order = await (await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`,
    { headers: H(cust.tok), data: { productOrderItem: [{ action: 'add',
      productOffering: { id: big.id, name: big.name } }] } })).json();
  for (let i = 0; i < 30; i++) {
    const st = (await (await ctx.get(`${API}/tmf-api/productOrderingManagement/v4/productOrder/${order.id}`,
      { headers: H(staff) })).json()).state;
    if (st === 'completed') break;
    await sleep(3000);
  }
  await ctx.post(`${USAGE}/usage`, { headers: H(staff), data: {
    usageType: `G2Data${run}`, usageCharacteristic: { value: 1, units: 'GB' },
    productOffering: { id: big.id }, relatedParty: [{ id: cust.id, role: 'customer' }] } });
  console.log('OK a customer on G2 Big (20 GB / 300) has used 1 GB — comfortably oversized');

  /* ---------- STREAK: bill cut, bank settles, trait extends ---------- */
  let bill = null;
  for (let i = 0; i < 30 && !bill; i++) {
    await ctx.post(`${BILLS}/billingRun`, { headers: H(staff), data: {} });
    await sleep(2000);
    const list = await (await ctx.get(`${BILLS}/customerBill?limit=50`, { headers: H(cust.tok) })).json();
    bill = (list || []).find((b) => b.state === 'new' && b.amountDue && Number(b.amountDue.value) > 0);
  }
  if (!bill) fail('no bill was cut for the probe customer');
  const kid = bill.billNo.replace(/\D/g, '');
  const amount = Number(bill.amountDue.value).toFixed(2);
  const camt = `<?xml version="1.0"?><Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.054.001.08">`
    + `<BkToCstmrDbtCdtNtfctn><GrpHdr><MsgId>G2-${run}</MsgId></GrpHdr><Ntfctn><Ntry>`
    + `<Amt Ccy="${bill.amountDue.unit}">${amount}</Amt><CdtDbtInd>CRDT</CdtDbtInd><NtryDtls><TxDtls><RmtInf><Strd>`
    + `<CdtrRefInf><Ref>${kid}</Ref></CdtrRefInf></Strd></RmtInf><Refs><AcctSvcrRef>G2A-${run}</AcctSvcrRef></Refs></TxDtls></NtryDtls>`
    + `</Ntry></Ntfctn></BkToCstmrDbtCdtNtfctn></Document>`;
  const paid = await ctx.post(`${API}/bank/v1/remittance`, {
    headers: { 'Content-Type': 'application/xml', 'X-Bank-Token': 'genalpha-bank-token' }, data: camt });
  if (paid.status() !== 200) fail('remittance refused: ' + paid.status());

  const audience = await (await ctx.post(AUD, { headers: H(staff), data: {
    name: `__streak_probe_${run}`, population: 'customer',
    criteria: { all: [{ type: 'trait', key: 'streak_bill_paid', op: 'eq', value: '1' }] } } })).json();
  let onStreak = false;
  for (let i = 0; i < 25 && !onStreak; i++) {
    await sleep(3000);
    const m = await (await ctx.get(`${AUD}/${audience.id}/members`, { headers: H(staff) })).json();
    onStreak = (Array.isArray(m) ? m : []).some((x) => x.partyId === cust.id);
  }
  if (!onStreak) fail('the settled bill never extended the streak trait');
  console.log('OK STREAK: the bank settled the bill and streak_bill_paid=1 is a CDP trait — '
    + 'audience-targetable like everything else the CDP knows');

  /* ---------- RIGHT-PLAN: the honest downgrade suggestion ---------- */
  const sweep = await (await ctx.post(`${API}/ai/v1/fairPlay/sweep?partyId=${cust.id}`,
    { headers: H(staff), data: {} })).json();
  const sug = (sweep.suggestions || []).find((s) => s.partyId === cust.id
    && s.currentOffering === `G2 Big ${run}`);
  if (!sug) fail('no right-plan suggestion for the oversized customer: ' + JSON.stringify(sweep).slice(0, 300));
  if (sug.suggested.offeringName !== `G2 Small ${run}`) fail('must stay in-category: ' + JSON.stringify(sug));
  if (Number(sug.monthlySaving) !== 150) fail('saving must be 150: ' + sug.monthlySaving);
  if (!(Number(sug.usedGb) >= 1)) fail('usedGb must reflect the real meter: ' + sug.usedGb);
  if (!(sweep.assumptions || []).some((a) => /read-only/i.test(a))) fail('read-only promise missing');
  console.log(`OK RIGHT-PLAN: using ${sug.usedGb} of ${sug.allowanceGb} GB -> suggest `
    + `${sug.suggested.offeringName} (save ${sug.monthlySaving}/mo, headroom kept) — the customer decides`);

  console.log('\nALL G2 CHECKS PASSED — the streak is a real trait earned by a real settled bill, '
    + 'and the Right-Plan Guarantee suggests the cheaper plan the meters prove is enough.');
})();
