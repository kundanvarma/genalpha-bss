/* G1 — member-get-member, the honest game.
 *
 *  - Rita (referrer) mints her code; Jon (joiner) redeems it
 *  - self-referral and double-redeem are refused with clear errors
 *  - the reward pays on Jon's FIRST COMPLETED ORDER — a referral is a
 *    customer, not a click — and lands as +5 GB on BOTH meters, verified at
 *    the usage API, never on the referral service's word
 *  - the staff report shows the conversion and the program's honest GB cost
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const CAT = `${API}/tmf-api/productCatalogManagement/v4`;
const REF = `${API}/tmf-api/campaignManagement/v4/referral`;
const USAGE = `${API}/tmf-api/usageManagement/v4`;

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

  /* ---------- a plan WITH an allowance (the reward needs a bucket) ---------- */
  const price = await (await ctx.post(`${CAT}/productOfferingPrice`, { headers: H(staff),
    data: { name: `Ref ${run} monthly`, priceType: 'recurring', recurringChargePeriodType: 'month',
      lifecycleStatus: 'Active', price: { unit: 'EUR', value: 20.0 } } })).json();
  const offering = await (await ctx.post(`${CAT}/productOffering`, { headers: H(staff),
    data: { name: `Ref Plan ${run}`, lifecycleStatus: 'Active', isBundle: false, isSellable: true,
      productOfferingPrice: [{ id: price.id, name: price.name }] } })).json();
  await ctx.post(`${USAGE}/usageAllowance`, { headers: H(staff), data: {
    productOffering: { id: offering.id, name: offering.name },
    usageType: `RefData${run}`, allowance: { value: 10, units: 'GB' },
    overagePrice: { value: 5, unit: 'EUR' } } });

  const mkUser = async (tag) => {
    const email = `ref-${tag}-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'Ref', familyName: `${tag}${run}` } })).json();
    return { id: login.id, tok: await token(ctx, 'bss-biz', email, login.temporaryPassword) };
  };
  const buy = async (user) => {
    const order = await (await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`,
      { headers: H(user.tok), data: { productOrderItem: [{ action: 'add',
        productOffering: { id: offering.id, name: offering.name } }] } })).json();
    for (let i = 0; i < 30; i++) {
      const st = (await (await ctx.get(
        `${API}/tmf-api/productOrderingManagement/v4/productOrder/${order.id}`,
        { headers: H(staff) })).json()).state;
      if (st === 'completed') return;
      await sleep(3000);
    }
    fail('order never completed');
  };
  const allowanceOf = async (user) => {
    const r = await (await ctx.get(
      `${API}/tmf-api/usageConsumption/v4/usageConsumptionReport?relatedPartyId=${user.id}`,
      { headers: H(user.tok) })).json();
    let max = 0;
    const walk = (o) => {
      if (Array.isArray(o)) o.forEach(walk);
      else if (o && typeof o === 'object') {
        if (o.allowedValue != null) max = Math.max(max, Number(o.allowedValue));
        Object.values(o).forEach(walk);
      }
    };
    walk(r);
    return max;
  };

  const openMeter = async (user) => {
    await ctx.post(`${USAGE}/usage`, { headers: H(staff), data: {
      usageType: `RefData${run}`, usageCharacteristic: { value: 0.2, units: 'GB' },
      productOffering: { id: offering.id }, relatedParty: [{ id: user.id, role: 'customer' }] } });
  };
  const rita = await mkUser('rita');
  const jon = await mkUser('jon');
  // H2: Rita plays for her local club — the dugnad share books as MONEY
  const club = await (await ctx.post(`${API}/tmf-api/party/v4/organization`,
    { headers: H(staff), data: { name: `IL Refklubb ${run}`, isLegalEntity: true } })).json();
  // Rita is an EXISTING customer: plan bought, meter open (usage recorded)
  await buy(rita);
  await openMeter(rita);

  /* ---------- the code and the guardrails ---------- */
  const mine = await (await ctx.get(`${REF}/myCode`, { headers: H(rita.tok) })).json();
  await ctx.post(`${REF}/myClub`, { headers: H(rita.tok), data: { clubOrgId: club.id } });
  if (!/^[A-Z2-9]{8}$/.test(mine.code)) fail('code shape wrong: ' + JSON.stringify(mine));
  const again = await (await ctx.get(`${REF}/myCode`, { headers: H(rita.tok) })).json();
  if (again.code !== mine.code) fail('the code must be stable, not re-minted');
  console.log(`OK Rita's code: ${mine.code} (stable across asks, reward ${mine.rewardGb} GB)`);

  const selfish = await ctx.post(`${REF}/redeem`, { headers: H(rita.tok), data: { code: mine.code } });
  if (selfish.status() !== 400) fail('self-referral must refuse with 400');
  const redeemed = await (await ctx.post(`${REF}/redeem`, { headers: H(jon.tok),
    data: { code: mine.code.toLowerCase() } })).json();
  if (redeemed.status !== 'pending') fail('redeem should be pending: ' + JSON.stringify(redeemed));
  const twice = await ctx.post(`${REF}/redeem`, { headers: H(jon.tok), data: { code: mine.code } });
  if (twice.status() !== 400) fail('double redeem must refuse with 400');
  console.log('OK guardrails: self-referral 400, case-insensitive redeem pending, double redeem 400');

  /* ---------- the reward pays on the first completed order ---------- */
  const ritaBefore = await allowanceOf(rita);
  await buy(jon);
  // Jon is BRAND NEW: his reward arrives before his meter exists and must be
  // PARKED — his first usage record opens the bucket and the reward lands
  await sleep(6000);
  await openMeter(jon);
  let ritaAfter = 0; let jonAfter = 0;
  for (let i = 0; i < 25 && !(ritaAfter >= ritaBefore + 5 && jonAfter >= 15); i++) {
    await sleep(3000);
    ritaAfter = await allowanceOf(rita);
    jonAfter = await allowanceOf(jon);
  }
  if (jonAfter < 15) fail(`Jon's PARKED reward never landed (allowance ${jonAfter}, wanted >= 15)`);
  if (ritaAfter < ritaBefore + 5) fail(`Rita's meter missing the +5 GB (${ritaBefore} -> ${ritaAfter})`);
  console.log(`OK THE REWARD AT THE METER: Jon ${jonAfter} GB (10 plan + 5 PARKED reward landed on first usage), `
    + `Rita ${ritaBefore} -> ${ritaAfter} (immediate) — both verified at the usage API`);

  /* ---------- the staff report tells the honest story ---------- */
  const report = await (await ctx.get(`${REF}/report`, { headers: H(staff) })).json();
  const row = (report.rows || []).find((r) => r.joinerPartyId === jon.id);
  if (!row || row.status !== 'rewarded') fail('the conversion is not rewarded in the report');
  if (!(Number(report.rewardCostGb) >= 10)) fail('the report must carry the honest GB cost');
  console.log(`OK report: ${report.rewarded} rewarded, cost ${report.rewardCostGb} GB — the program's price tag on its face`);

  /* ---------- H2: the dugnad share is a LIABILITY, not a scoreboard ---------- */
  let clubEntry = null;
  for (let i = 0; i < 20 && !clubEntry; i++) {
    const entries = await (await ctx.get(`${API}/revenue/v1/journalEntry?limit=200`,
      { headers: H(staff) })).json();
    clubEntry = (Array.isArray(entries) ? entries : []).find((e) =>
      String(e.sourceRef || '').startsWith('club-share:')
      && String(e.description || '').includes(club.id));
    if (!clubEntry) await sleep(3000);
  }
  if (!clubEntry) fail('the club share never booked to the subledger');
  const detail = await (await ctx.get(`${API}/revenue/v1/journalEntry/${clubEntry.id}`,
    { headers: H(staff) })).json();
  const expense = (detail.lines || []).find((l) => l.accountCode === '6150');
  const payable = (detail.lines || []).find((l) => l.accountCode === '2150');
  if (!expense || Number(expense.debit) !== 10) fail('club expense wrong: ' + JSON.stringify(detail.lines));
  if (!payable || Number(payable.credit) !== 10) fail('club payable wrong');
  console.log('OK KLUBBDUGNAD MONEY: 10.00 booked DR 6150 sponsorship / CR 2150 payable-to-club — '
    + 'the season tally is a balance the operator OWES');

  console.log('\nALL G1 CHECKS PASSED — a referral is a customer, not a click: code minted, guarded, '
    + 'paid in data on both meters at first completed order, and honestly accounted.');
})();
