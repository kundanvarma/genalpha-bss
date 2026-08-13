/* Wholesale / open access — selling retail fibre over a third party's network. Suite #88.
 *
 * Norway's fibre is opening to third-party access. This proves the BSS can be a
 * retail ISP (an access SEEKER) selling broadband over an owner's fibre, split by
 * access layer (L2 VULA / L3 activated), ordered operator-to-operator and settled.
 *
 *  - PARTNERSHIP: a typed wholesale partnership names the access provider/seeker
 *    roles and refuses any other (W1).
 *  - PRODUCTS: the wholesale access products (L2/L3) are modeled and kept out of
 *    the consumer shop (W2).
 *  - SERVICEABILITY: an open-access address returns the owners that can serve it,
 *    ranked, and retail fibre qualifies there (W3).
 *  - UPSTREAM ORDER: a retail fibre sale places an access-seeker order to the right
 *    owner; an address on our own network does not (W4).
 *  - EFFICIENT PICK: a slower plan takes the smallest tier that meets it (W4).
 *  - SETTLEMENT: what we owe each owner (lines x rate) and the margin it leaves (W5).
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = '/tmf-api/productCatalogManagement/v4';
const ORD = '/tmf-api/productOrderingManagement/v4/productOrder';
const SQ = '/tmf-api/serviceQualificationManagement/v4';
const POQ = '/tmf-api/productOfferingQualification/v4';
const PT = '/tmf-api/partnershipTypeManagement/v4';
const AGR = '/tmf-api/agreementManagement/v4';
const WAO = '/tmf-api/serviceOrdering/v4/wholesaleAccessOrder';
const SETTLE = '/tmf-api/serviceOrdering/v4/wholesaleSettlement';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}

(async () => {
  const kai = await token('kai@bss.local', 'kai');
  const staff = await token('demo', 'demo');

  /* ---------- 1. PARTNERSHIP (W1) ---------- */
  const types = (await call('GET', `${PT}/partnershipType?limit=100`, staff)).body || [];
  const wt = types.find((t) => t.name === 'Wholesale access');
  if (!wt) fail('no "Wholesale access" partnership type');
  const roles = (wt.roleType || []).map((r) => r.name);
  if (!roles.includes('accessProvider') || !roles.includes('accessSeeker')) {
    fail('wholesale partnership does not permit accessProvider/accessSeeker: ' + roles);
  }
  const bad = await call('POST', `${AGR}/agreement`, staff, {
    name: 'bad wholesale', agreementType: 'partnership', status: 'active',
    engagedParty: [{ id: 'x', role: 'freeloader' }],
    characteristic: { partnershipTypeId: wt.id } });
  if (bad.status < 400) fail('a bad role was accepted onto the wholesale partnership');
  ok(`PARTNERSHIP: "Wholesale access" permits ${roles.join('/')}, refuses others`);

  /* ---------- 2. PRODUCTS (W2) ---------- */
  const offs = (await call('GET', `${CAT}/productOffering?limit=100`, staff)).body || [];
  const wholesale = offs.filter((o) => ((o.category || [{}])[0] || {}).name === 'Wholesale access');
  if (wholesale.length < 2) fail('the two wholesale access products are not modeled');
  const SHOP_CATS = ['Mobile plans', 'Broadband', 'TV & Add-ons', 'Partner services', 'Devices', 'Security', 'Insurance', 'Top-ups'];
  if (wholesale.some((o) => SHOP_CATS.includes(((o.category || [{}])[0] || {}).name))) {
    fail('a wholesale product leaked into a consumer shop category');
  }
  ok(`PRODUCTS: ${wholesale.length} wholesale access products (L2/L3) modeled, none in the shop`);

  /* ---------- 3. SERVICEABILITY (W3) ---------- */
  const opts = (await call('POST', `${SQ}/queryAccessOptions`, staff,
    { searchCriteria: { place: { postCode: '5020' }, technology: 'fiber' } })).body || {};
  const owners = (opts.accessOption || []).map((o) => o.accessOwner);
  if (!owners.includes('NORDACCESS') || !owners.includes('FJORDFIBER')) {
    fail('open-access serviceability did not return both owners: ' + JSON.stringify(owners));
  }
  const bws = (opts.accessOption || []).map((o) => o.maxDownMbps);
  if (bws[0] < bws[bws.length - 1]) fail('access options are not ranked by bandwidth');
  const fiber = offs.find((o) => o.name === 'GenAlpha Fiber 1000');
  const q = (await call('POST', `${POQ}/checkProductOfferingQualification`, staff, {
    productOfferingQualificationItem: [{ id: '1',
      productOffering: { id: fiber.id, name: fiber.name }, place: { postCode: '5020' } }] })).body;
  if ((q.productOfferingQualificationItem || [{}])[0].qualificationItemResult !== 'qualified') {
    fail('retail fibre is not serviceable at the open-access address');
  }
  ok(`SERVICEABILITY: 5020 served by ${owners.join(' + ')} (ranked); retail fibre qualifies there`);

  /* ---------- 4. UPSTREAM ORDER (W4) ---------- */
  const place = (pc) => ({ '@type': 'GeographicAddress', streetName: 'Testv 1', postCode: pc, country: 'NO' });
  const orderFibre = async (pc, speed) => (await call('POST', ORD, kai, {
    description: `fibre ${speed} at ${pc}`, productOrderItem: [{ id: '1', action: 'add',
      productOffering: { id: fiber.id, name: fiber.name, '@referredType': 'ProductOffering' },
      product: { place: [place(pc)], productCharacteristic: [{ name: 'downloadSpeed', value: speed }] } }] })).body;
  const waoFor = async (oid) => (await call('GET', `${WAO}?productOrderId=${oid}`, staff)).body || [];

  const o1 = await orderFibre('5020', 1000);
  let w1 = [];
  for (let i = 0; i < 12 && !w1.length; i++) { await sleep(2000); w1 = await waoFor(o1.id); }
  if (!w1.length) fail('a retail fibre sale placed NO upstream access order');
  if (w1[0].accessOwner !== 'NORDACCESS' || w1[0].state !== 'active') {
    fail('the upstream order did not go active to NordAccess: ' + JSON.stringify(w1[0]));
  }
  if (!String(w1[0].externalId).startsWith('SO-')) fail('no owner OSS reference on the upstream order');
  ok(`UPSTREAM ORDER: fibre 1000 at 5020 -> access-seeker order to ${w1[0].accessOwner} ${w1[0].accessLayer} (${w1[0].externalId}), active`);

  const own = await orderFibre('1110', 1000);
  await sleep(6000);
  const wOwn = await waoFor(own.id);
  if (wOwn.length) fail('an order on our own network wrongly placed an upstream wholesale order');
  ok('OWN NETWORK: a fibre sale where we own the fibre places no upstream order');

  /* ---------- 5. EFFICIENT PICK (W4) ---------- */
  const o3 = await orderFibre('5020', 300);
  let w3 = [];
  for (let i = 0; i < 12 && !w3.length; i++) { await sleep(2000); w3 = await waoFor(o3.id); }
  if (!w3.length || w3[0].accessOwner !== 'FJORDFIBER') {
    fail('a 300 Mbit/s plan did not take the smallest tier that meets it (FjordFiber L2 500): '
      + JSON.stringify(w3[0]));
  }
  ok(`EFFICIENT PICK: a 300 Mbit/s plan takes ${w3[0].accessOwner} ${w3[0].accessLayer} (500) — the smallest tier that meets it`);

  /* ---------- 6. SETTLEMENT (W5) ---------- */
  const s = (await call('GET', SETTLE, staff)).body || {};
  const nord = (s.owner || []).find((x) => x.accessOwner === 'NORDACCESS');
  const fjord = (s.owner || []).find((x) => x.accessOwner === 'FJORDFIBER');
  if (!nord || !fjord) fail('settlement is missing an owner');
  for (const st of [nord, fjord]) {
    if (Math.abs(st.monthlyOwed - st.ratePerLine * st.activeLines) > 0.01) {
      fail(`owed != lines x rate for ${st.accessOwner}: ${JSON.stringify(st)}`);
    }
    if (st.retailMonthlyPerLine && Math.abs(st.marginPerLine - (st.retailMonthlyPerLine - st.ratePerLine)) > 0.01) {
      fail(`margin != retail - wholesale for ${st.accessOwner}`);
    }
  }
  if (!(fjord.marginPerLine > nord.marginPerLine)) {
    fail('L2 (FjordFiber) should leave more margin than L3 (NordAccess)');
  }
  ok(`SETTLEMENT: owe ${nord.accessOwner} ${nord.monthlyOwed} + ${fjord.accessOwner} ${fjord.monthlyOwed}`
    + ` /mo; margin/line ${nord.marginPerLine} (L3) vs ${fjord.marginPerLine} (L2) — total margin ${s.totalMonthlyMargin}`);

  console.log('\nALL WHOLESALE / OPEN-ACCESS CHECKS PASSED — the BSS sells retail fibre over a third'
    + " party's network: a typed wholesale partnership, L2/L3 access products, owner-by-owner"
    + ' serviceability, an access-seeker order placed upstream to the right owner, and a settlement'
    + ' that shows what we owe each owner and the margin it leaves.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
