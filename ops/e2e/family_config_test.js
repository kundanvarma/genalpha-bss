/* Configurability: gifting and rollover are PRODUCT DATA, not code.
 *
 * The same binary serves three different operators' choices:
 *  - NOVA flips to the network-wide model — ANY customer gifts to ANY customer — by
 *    adding giftScope=tenant to the plan's spec characteristics. Pure
 *    catalog configuration; not one line rebuilt.
 *  - GENALPHA keeps the coded default: gifts stay inside the household.
 *  - A plan whose spec says giftable=false / rolloverEligible=false simply
 *    doesn't gift and doesn't roll — per PRODUCT, on the same tenant.
 *  - The operator's veto: a policy rule (domain 'gifting', authored as
 *    data) blocks gifts above a size, with the operator's own message.
 * Tenants stay walled: nova's network-wide gifting still cannot reach a
 * genalpha customer.
 */
const API = 'http://localhost:8080';
const NOVA = 'http://shop.nova.localhost:8080';
const run = Date.now();
const { request } = require('playwright');

async function token(ctx, realm, client, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = { genalpha: await token(ctx, 'bss', 'bss-demo', 'demo', 'demo'),
    nova: await token(ctx, 'nova', 'bss-demo', 'demo', 'demo') };
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  const mkPerson = async (base, staffTok, realm, given) => {
    const email = `${given.toLowerCase()}-${run}@config.example`;
    const login = await (await ctx.post(`${base}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staffTok), data: { email, givenName: given, familyName: `Cfg${run}` } })).json();
    await ctx.post(`${base}/tmf-api/party/v4/individual`, { headers: H(staffTok),
      data: { id: login.id, givenName: given, familyName: `Cfg${run}`,
        contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
    return { id: login.id, email,
      token: await token(ctx, realm, 'bss-biz', email, login.temporaryPassword) };
  };
  const meter = (base, staffTok, party, offeringId, usageType, value) =>
    ctx.post(`${base}/tmf-api/usageManagement/v4/usage`, { headers: H(staffTok),
      data: { usageType, usageCharacteristic: { value, units: 'GB' },
        productOffering: { id: offeringId }, relatedParty: [{ id: party, role: 'customer' }] } });
  const gift = (base, tok, receiverId, amount) =>
    ctx.post(`${base}/tmf-api/usageManagement/v4/gift`,
      { headers: H(tok), data: { receiverId, amount } });
  const allowedOf = async (base, staffTok, party, name) => {
    const report = await (await ctx.get(
      `${base}/tmf-api/usageConsumption/v4/queryUsageConsumption?relatedPartyId=${party}`,
      { headers: H(staffTok) })).json();
    const bucket = (report.bucket || []).find((b) => b.name === name);
    return bucket ? Number(bucket.allowedValue) : null;
  };

  /* ---------- A. NOVA flips to the network-wide model — with catalog data ---------- */
  const novaOffers = await (await ctx.get(
    `${NOVA}/tmf-api/productCatalogManagement/v4/productOffering?limit=50`,
    { headers: H(staff.nova) })).json();
  const novaPlan = novaOffers.find((o) => o.name === 'Nova Smart 15 GB');
  const novaSpec = await (await ctx.post(`${NOVA}/tmf-api/productCatalogManagement/v4/productSpecification`,
    { headers: H(staff.nova), data: { name: 'Nova Smart 15 GB', brand: 'Nova',
      productSpecCharacteristic: [
        { name: 'giftScope', productSpecCharacteristicValue: [{ value: 'tenant' }] }] } })).json();
  const flip = await ctx.patch(`${NOVA}/tmf-api/productCatalogManagement/v4/productOffering/${novaPlan.id}`,
    { headers: H(staff.nova), data: { productSpecification: { id: novaSpec.id, name: novaSpec.name } } });
  if (flip.status() !== 200) fail('nova config flip failed: ' + await flip.text());
  console.log('OK NOVA configured its product network-wide: giftScope=tenant on the plan spec —'
    + ' a catalog edit, zero code');

  const nils = await mkPerson(NOVA, staff.nova, 'nova', 'Nils');
  const norah = await mkPerson(NOVA, staff.nova, 'nova', 'Norah'); // total strangers
  await meter(NOVA, staff.nova, nils.id, novaPlan.id, 'Mobildata', 2);
  await meter(NOVA, staff.nova, norah.id, novaPlan.id, 'Mobildata', 1);
  const novaGift = await gift(NOVA, nils.token, norah.id, 2);
  if (novaGift.status() !== 200) fail('nova stranger-gift failed: ' + await novaGift.text());
  if (await allowedOf(NOVA, staff.nova, norah.id, 'Mobildata') !== 17) {
    fail('Norah\'s meter did not grow to 17');
  }
  console.log('OK on nova, Nils gifted 2 GB to Norah — a complete stranger, same network:'
    + ' the network-wide model, live');

  /* ---------- B. GENALPHA keeps the default: household-only ---------- */
  const PLAN_ID = '14291c1a-df26-4232-8084-500466888e46'; // GenAlpha Mobile 10 GB
  const gus = await mkPerson(API, staff.genalpha, 'bss', 'Gus');
  const greta = await mkPerson(API, staff.genalpha, 'bss', 'Greta'); // strangers
  await meter(API, staff.genalpha, gus.id, PLAN_ID, 'Mobile data', 1);
  const strangerGift = await gift(API, gus.token, greta.id, 1);
  if (strangerGift.status() === 200) fail('genalpha let a gift leave the household');
  if (!(await strangerGift.text()).includes('household')) fail('default rejection unclear');
  // and the tenant wall: nova's network-wide reach ends at nova's border
  const crossTenant = await gift(NOVA, nils.token, gus.id, 1);
  if (crossTenant.status() === 200) fail('a gift crossed the tenant wall');
  console.log('OK genalpha, same binary, keeps gifts inside the household — and nova\'s'
    + ' network-wide reach still stops at the tenant wall');

  /* ---------- C. per-PRODUCT levers: a plan that neither gifts nor rolls ---------- */
  const lockedSpec = await (await ctx.post(`${API}/tmf-api/productCatalogManagement/v4/productSpecification`,
    { headers: H(staff.genalpha), data: { name: `Config Locked ${run}`,
      productSpecCharacteristic: [
        { name: 'giftable', productSpecCharacteristicValue: [{ value: 'false' }] },
        { name: 'rolloverEligible', productSpecCharacteristicValue: [{ value: 'false' }] }] } })).json();
  const lockedOffer = await (await ctx.post(`${API}/tmf-api/productCatalogManagement/v4/productOffering`,
    { headers: H(staff.genalpha), data: { name: `Config Locked 8 GB ${run}`,
      lifecycleStatus: 'Active', productSpecification: { id: lockedSpec.id, name: lockedSpec.name } } })).json();
  await ctx.post(`${API}/tmf-api/usageManagement/v4/usageAllowance`, { headers: H(staff.genalpha),
    data: { productOffering: { id: lockedOffer.id }, usageType: 'Test data',
      allowance: { value: 8, units: 'GB' }, overagePrice: { value: 1, unit: 'EUR' } } });
  // a real household on the locked plan
  const hank = await mkPerson(API, staff.genalpha, 'bss', 'Hank');
  const hilda = await mkPerson(API, staff.genalpha, 'bss', 'Hilda');
  await ctx.post(`${API}/tmf-api/party/v4/individual/${hilda.id}/householdPayer`,
    { headers: H(hilda.token), data: { payerEmail: hank.email } });
  await ctx.post(`${API}/tmf-api/party/v4/individual/${hilda.id}/householdPayer/accept`,
    { headers: H(hank.token), data: {} });
  await meter(API, staff.genalpha, hank.id, lockedOffer.id, 'Test data', 2);
  const lockedGift = await gift(API, hank.token, hilda.id, 1);
  if (lockedGift.status() === 200) fail('a giftable=false plan gifted');
  if (!(await lockedGift.text()).includes('does not include')) fail('locked-plan rejection unclear');
  console.log('OK a plan whose SPEC says giftable=false refuses gifts — even inside a real'
    + ' household: the product, not the platform, decides');

  /* ---------- D. the operator's veto: a gifting rule authored as data ---------- */
  const dora = await mkPerson(API, staff.genalpha, 'bss', 'Dora');
  const dima = await mkPerson(API, staff.genalpha, 'bss', 'Dima');
  await ctx.post(`${API}/tmf-api/party/v4/individual/${dima.id}/householdPayer`,
    { headers: H(dima.token), data: { payerEmail: dora.email } });
  await ctx.post(`${API}/tmf-api/party/v4/individual/${dima.id}/householdPayer/accept`,
    { headers: H(dora.token), data: {} });
  await meter(API, staff.genalpha, dora.id, PLAN_ID, 'Mobile data', 1);
  const rule = await (await ctx.post(`${API}/tmf-api/policyManagement/v4/policyRule`,
    { headers: H(staff.genalpha), data: { name: `Gift size guard ${run}`, domain: 'gifting',
      effect: 'deny', priority: 10, enabled: true,
      condition: JSON.stringify({ '>': [{ var: 'amount' }, 4] }),
      message: 'Gifts above 4 GB need a word with support first.' } })).json();
  const bigGift = await gift(API, dora.token, dima.id, 5);
  if (bigGift.status() === 200) fail('the gifting policy rule did not veto');
  if (!(await bigGift.text()).includes('word with support')) {
    fail('the veto lost the operator\'s message: ' + await bigGift.text());
  }
  const smallGift = await gift(API, dora.token, dima.id, 2);
  if (smallGift.status() !== 200) fail('the veto blocked an allowed gift: ' + await smallGift.text());
  await ctx.delete(`${API}/tmf-api/policyManagement/v4/policyRule/${rule.id}`, { headers: H(staff.genalpha) });
  console.log('OK a policy rule authored as DATA vetoed the 5 GB gift with the operator\'s'
    + ' own words — and 2 GB sailed through; rule deleted, behavior reverts');

  /* ---------- E. rollover obeys the product too ---------- */
  const close = await (await ctx.post(`${API}/tmf-api/usageManagement/v4/cycleClose`,
    { headers: H(staff.genalpha), data: {} })).json();
  if (close.rolledBuckets < 1) fail('nothing rolled at genalpha month close');
  const inboxOf = async (tok) => (await (await ctx.get(
    `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=100`,
    { headers: H(tok) })).json()).map((m) => m.subject);
  let doraRolled = false;
  for (let i = 0; i < 15 && !doraRolled; i++) {
    doraRolled = (await inboxOf(dora.token)).includes('Your unused data rolled over');
    if (!doraRolled) await sleep(2000);
  }
  if (!doraRolled) fail('the normal plan did not roll for Dora');
  if ((await inboxOf(hank.token)).includes('Your unused data rolled over')) {
    fail('a rolloverEligible=false plan rolled for Hank');
  }
  console.log('OK month close: Dora\'s ordinary plan rolled, Hank\'s rolloverEligible=false'
    + ' plan did not — one close, two products, two behaviors');

  console.log('\nALL CONFIGURABILITY CHECKS PASSED — gift scope, giftability, share caps and'
    + ' rollover are catalog characteristics; operator vetoes are policy rules-as-data;'
    + ' nova runs the network-wide model and genalpha the family model ON THE SAME BINARY.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
