/* #123 sigscale_ocs_test — a REAL Online Charging System behind the seam.
 *
 * Taranga (the vendor demo tenant) charges on SigScale OCS — open source,
 * Apache-2.0, 3GPP Diameter Ro/Gy — selected per tenant in tenants.yml
 * (ocs-provider: sigscale). The default tenant stays on the bundled mock, so
 * both shapes run side by side in one fleet. What is proven, end to end:
 *
 *  - catalog references charging: the plan's spec carries chargingSpecId, the
 *    RATE PLAN itself is a TMF620 offering inside the OCS (seeded by its own
 *    tooling: ops/seed/seed_sigscale_ocs.py)
 *  - activation provisions the OCS over its TM Forum APIs: a TMF637 product on
 *    the offering (the allowance bucket appears by the OCS's own policy) and a
 *    TMF638 service whose identity is the line's MSISDN
 *  - TMF654 projects the balance the OCS keeps; a TMF654 top-up credits it
 *  - the network charges over REAL Gy: SigScale's own Diameter test client opens
 *    a credit-control session (CCR-I/U/T) and the balance falls
 *  - running low is the OCS's line: its balance hub notifies the BSS, the
 *    tenant-stamped UsageThresholdBreachedEvent fires the brand's journey
 *  - a TMF622 modify swaps the rate plan (new product, identity re-pointed,
 *    old product retired); vacation hold disables the charging identity
 *  - another tenant sees none of it
 */
const { execSync } = require('child_process');

const API = 'http://localhost:8080';
const OCS = 'http://localhost:8155';
const OCS_AUTH = 'Basic ' + Buffer.from('bss:bss-secret').toString('base64');
const CATALOG = `${API}/tmf-api/productCatalogManagement/v4`;
const TMF654 = `${API}/tmf-api/prepayBalanceManagement/v4`;
const ORDERS = `${API}/tmf-api/productOrderingManagement/v4/productOrder`;
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;
const INBOX = `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=100`;
const run = Date.now();
const GB = 1e9;

const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(realm, client, user, pass) {
  const res = await fetch(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: client, username: user, password: pass }) });
  return (await res.json()).access_token;
}
async function call(method, url, tok, body) {
  const res = await fetch(url, { method, headers: { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null; try { json = JSON.parse(text); } catch { /* not json */ }
  return { status: res.status, body: json, text };
}
async function ocs(method, path, body, extraHeaders = {}) {
  const res = await fetch(OCS + path, { method,
    headers: { Authorization: OCS_AUTH, Accept: 'application/json', 'Content-Type': 'application/json', ...extraHeaders },
    body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null; try { json = JSON.parse(text); } catch { /* not json */ }
  return { status: res.status, body: json, headers: res.headers };
}
const chr = (product, name) => ((product && product.characteristic) || []).find((c) => c.name === name)?.value;
const octets = (product) => {
  const b = ((product && product.balance) || []).find((x) => x.name === 'octets');
  return b ? Number(String(b.totalBalance.amount).replace(/b$/, '')) : 0;
};
async function productsOfParty(partyId) {
  // SigScale's characteristic filter matches on presence, not value — match here
  const r = await ocs('GET', '/productInventoryManagement/v2/product', undefined, { Range: 'items=1-2000' });
  return ((r.status === 200 || r.status === 206) && Array.isArray(r.body) ? r.body : []).filter((p) => chr(p, 'bssPartyId') === partyId);
}
async function until(what, fn, tries = 40, ms = 2000) {
  for (let i = 0; i < tries; i++) {
    const v = await fn();
    if (v) return v;
    await sleep(ms);
  }
  fail('timed out waiting for ' + what);
}
function gySession(msisdn, updates = 2) {
  const env = { ...process.env, PATH: '/opt/homebrew/bin:' + (process.env.PATH || ''),
    DOCKER_HOST: process.env.DOCKER_HOST || `unix://${process.env.HOME}/.colima/default/docker.sock` };
  const cmd = `docker exec bss-sigscale-ocs sh -c 'cd /home/otp && ERL_LIBS=/home/otp/lib timeout 120 escript `
    + `lib/ocs-*/priv/bin/data_session.escript --msisdn ${msisdn} --imsi 001001123456789 --raddr 127.0.0.1 `
    + `--updates ${updates} --interval 200 2>&1'`;
  return execSync(cmd, { env, encoding: 'utf8', timeout: 150000 });
}

(async () => {
  const staff = await token('taranga', 'bss-demo', 'demo', 'demo');
  if (!staff) fail('no taranga staff token');
  const health = await fetch(`${OCS}/health`);
  if (health.status !== 200) fail('SigScale OCS is not up on :8155 — docker compose up -d sigscale-ocs');

  /* 1. the catalog references charging; the rate plan lives IN the OCS */
  const offerings = (await call('GET', `${CATALOG}/productOffering?limit=100`, staff)).body;
  const plan = offerings.find((o) => o.name === 'Taranga Mobile 20 GB');
  const unlimited = offerings.find((o) => o.name === 'Taranga Mobile Unlimited 5G');
  if (!plan || !unlimited) fail('Taranga mobile plans not seeded (ops/seed/seed_taranga.py)');
  const specOf = async (o) => (await call('GET', `${CATALOG}/productSpecification/${o.productSpecification.id}`, staff)).body;
  const ratePlanOf = (spec) => (spec.productSpecCharacteristic || []).find((c) => c.name === 'chargingSpecId')
    ?.productSpecCharacteristicValue?.[0]?.value;
  const planRate = ratePlanOf(await specOf(plan));
  const unlimitedRate = ratePlanOf(await specOf(unlimited));
  if (!planRate || !unlimitedRate) fail('chargingSpecId missing on the plan specs');
  const offerInOcs = await ocs('GET', `/productCatalogManagement/v2/productOffering/${planRate}`);
  if (offerInOcs.status !== 200) fail(`rate plan ${planRate} is not an offering in the OCS (run ops/seed/seed_sigscale_ocs.py)`);
  const allowanceGb = Number(String(offerInOcs.body.productOfferingPrice
    .find((p) => p.productOfferPriceAlteration)?.productOfferPriceAlteration.unitOfMeasure).replace(/b$/, '')) / GB;
  console.log(`OK catalog references charging: "${plan.name}" -> ${planRate}, a TMF620 offering INSIDE SigScale OCS granting ${allowanceGb} GB/month`);

  /* 2. a customer with a login (the running-low message lands in their inbox) */
  const firstName = `Siv${run}`;
  const email = `siv-${run}@example.com`;
  const login = (await call('POST', USER, staff, { email, givenName: firstName, familyName: `Ocs${run}` })).body;
  if (!login || !login.temporaryPassword) fail('user create failed: ' + JSON.stringify(login));
  const party = login.id;
  await call('POST', PARTY, staff, { id: party, givenName: firstName, familyName: `Ocs${run}` });

  /* 3. order -> activation -> the OCS is provisioned over TM Forum APIs */
  const order = await call('POST', ORDERS, staff, {
    productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name } }],
    relatedParty: [{ id: party, role: 'customer' }] });
  if (order.status !== 201) fail('order failed: ' + order.status + ' ' + order.text);
  let product = await until('the SOM to provision the SigScale product', async () => (await productsOfParty(party))[0]);
  if (product.productOffering.id !== planRate) fail('wrong rate plan in the OCS: ' + product.productOffering.id);
  if (octets(product) !== allowanceGb * GB) fail('allowance bucket wrong: ' + octets(product));
  product = await until('the charging identity to be linked', async () => {
    const p = (await ocs('GET', `/productInventoryManagement/v2/product/${product.id}`)).body;
    return p.realizingService && p.realizingService.length ? p : null;
  });
  const msisdn = product.realizingService[0].id;
  if (!/^4741\d+$/.test(msisdn)) fail('charging identity is not the line\'s MSISDN: ' + msisdn);
  const serviceId = chr(product, 'bssServiceId');
  if (chr(product, 'bssTenantId') !== 'taranga' || chr(product, 'bssPartyId') !== party || !serviceId) {
    fail('product characteristics wrong: ' + JSON.stringify(product.characteristic));
  }
  console.log(`OK activation provisioned SigScale: TMF637 product ${product.id} on ${planRate} (${allowanceGb} GB), TMF638 service ${msisdn} = the line's MSISDN`);

  /* 4. TMF654 projects the balance the OCS keeps */
  const buckets = async (tok = staff) => (await call('GET', `${TMF654}/bucket?relatedPartyId=${party}`, tok)).body || [];
  let bucket = (await buckets())[0];
  if (!bucket || bucket.id !== product.id || bucket.remainingValue.amount !== allowanceGb) {
    fail('TMF654 projection wrong: ' + JSON.stringify(bucket));
  }
  console.log(`OK TMF654 projects the OCS balance: ${bucket.remainingValue.amount} GB remaining, 0 used`);

  /* 5. the network charges over REAL Diameter Gy */
  const gy = gySession(msisdn, 2);
  const grants = (gy.match(/'Result-Code' = 2001/g) || []).length;
  if (grants < 2) fail('the Gy session was not granted by the OCS:\n' + gy.slice(-1500));
  const afterGy = (await ocs('GET', `/productInventoryManagement/v2/product/${product.id}`)).body;
  const usedBytes = allowanceGb * GB - octets(afterGy);
  if (usedBytes <= 0) fail('the Gy session did not debit the balance');
  bucket = await until('TMF654 to show the Gy usage', async () => {
    const b = (await buckets())[0];
    return b && b.usedValue.amount > 0 ? b : null;
  }, 5);
  console.log(`OK real 3GPP Gy: CCR-I/U/T granted ${grants}x (Result-Code 2001), ${(usedBytes / 1e6).toFixed(2)} MB debited; TMF654 shows ${bucket.usedValue.amount} GB used, ${bucket.remainingValue.amount} GB left`);

  /* 6. a TMF654 top-up credits the OCS */
  const topup = await call('POST', `${TMF654}/topupBalance`, staff, {
    relatedParty: [{ id: party, role: 'customer' }], bucket: { id: bucket.id }, amount: { amount: 5, units: 'GB' } });
  if (topup.status !== 201) fail('topupBalance failed: ' + topup.status + ' ' + topup.text);
  const afterTopup = (await ocs('GET', `/productInventoryManagement/v2/product/${product.id}`)).body;
  if (octets(afterTopup) - octets(afterGy) !== 5 * GB) fail('top-up did not land in the OCS: ' + octets(afterTopup));
  bucket = (await buckets())[0];
  if (Math.abs(bucket.remainingValue.amount - (allowanceGb + 5 - usedBytes / GB)) > 0.01) {
    fail('TMF654 after top-up wrong: ' + JSON.stringify(bucket.remainingValue));
  }
  console.log(`OK TMF654 top-up: +5 GB credited as a SigScale bucket, ${bucket.remainingValue.amount} GB remaining`);

  /* 7. running low is the OCS's line: its hub notifies, the brand's journey speaks */
  const j = (await call('POST', JOURNEY, staff, {
    name: `Running low (SigScale) ${run}`, triggerEventType: 'UsageThresholdBreachedEvent', holdoutPercent: 0,
    category: 'transactional', // a service notice: never parked by marketing quiet hours
    steps: [{ type: 'message', stage: 'Retain', channel: 'inApp',
      subject: `Running low, {{party.firstName}}?`,
      content: 'Hi {{party.firstName}}, you\'ve used {{usage.percentUsed}}% — {{usage.remaining}} GB left on {{brand.name}}. Top up in a tap.' }] })).body;
  if (!j || !j.id) fail('journey not created: ' + JSON.stringify(j));
  // the OCS's own tooling drains the balance to 1.5 GB: drop the buckets, grant 1.5 GB
  const acc = (await ocs('GET', `/balanceManagement/v1/product/${product.id}/accumulatedBalance`)).body;
  for (const b of acc.buckets || []) await ocs('DELETE', `/balanceManagement/v1/bucket/${b.id}`);
  const grant = await ocs('POST', `/balanceManagement/v1/product/${product.id}/balanceTopup`,
    { amount: { units: 'octets', amount: 1.5 * GB }, product: { id: product.id } });
  if (grant.status !== 201) fail('OCS balance grant failed: ' + grant.status);
  const gy2 = gySession(msisdn, 1);
  if (!gy2.includes("'Result-Code' = 2001")) fail('second Gy session not granted');
  const custTok = await token('taranga', 'bss-biz', email, login.temporaryPassword);
  if (!custTok) fail('customer login failed');
  const msg = await until('the running-low journey to message the customer', async () => {
    const r = await call('GET', INBOX, custTok);
    return (r.body || []).find((m) => m.content && m.content.includes(firstName) && m.content.includes('GB left'));
  }, 30, 2000);
  if (msg.content.includes('{{')) fail('an unresolved token leaked: ' + msg.content);
  const pct = Number((msg.content.match(/used (\d+)%/) || [])[1]);
  if (!(pct >= 90 && pct <= 100)) fail('percent used not derived from the OCS balance: ' + msg.content);
  console.log(`OK the OCS's balance hub notified the BSS below its 2 GB line; the Taranga journey fired — "${msg.content}"`);

  /* 8. a plan change swaps the rate plan inside the OCS */
  const products = (await call('GET', `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${party}&status=active&limit=10`, staff)).body || [];
  const bssProduct = products.find((p) => p.name === plan.name);
  if (!bssProduct) fail('no active BSS product for the party');
  const modify = await call('POST', ORDERS, staff, {
    productOrderItem: [{ action: 'modify', product: { id: bssProduct.id, realizingService: [{ id: serviceId }] },
      productOffering: { id: unlimited.id, name: unlimited.name } }],
    relatedParty: [{ id: party, role: 'customer' }] });
  if (modify.status !== 201) fail('plan change failed: ' + modify.status + ' ' + modify.text);
  const swapped = await until('the OCS product to move to the new rate plan', async () => {
    const list = await productsOfParty(party);
    const p = list.find((x) => x.productOffering.id === unlimitedRate);
    return p && p.realizingService && p.realizingService.some((s) => s.id === msisdn) ? p : null;
  });
  const oldGone = (await ocs('GET', `/productInventoryManagement/v2/product/${product.id}`)).status === 404;
  if (!oldGone) fail('the old OCS product was not retired');
  console.log(`OK TMF622 modify: SigScale product moved ${planRate} -> ${unlimitedRate} (new product ${swapped.id}, identity ${msisdn} re-pointed, old product retired)`);

  /* 9. vacation hold disables the charging identity; resume re-enables it */
  const hold = await call('POST', `${API}/tmf-api/serviceInventory/v4/service/${serviceId}/suspend`, staff, { reason: 'vacation hold' });
  if (hold.status !== 200) fail('suspend failed: ' + hold.status + ' ' + hold.text);
  await until('the OCS identity to be disabled', async () => {
    const s = (await ocs('GET', `/serviceInventoryManagement/v2/service/${msisdn}`)).body;
    return s && s.isServiceEnabled === false ? s : null;
  }, 10);
  const resume = await call('POST', `${API}/tmf-api/serviceInventory/v4/service/${serviceId}/resume`, staff, {});
  if (resume.status !== 200) fail('resume failed: ' + resume.status + ' ' + resume.text);
  await until('the OCS identity to be enabled again', async () => {
    const s = (await ocs('GET', `/serviceInventoryManagement/v2/service/${msisdn}`)).body;
    return s && s.isServiceEnabled === true ? s : null;
  }, 10);
  console.log('OK vacation hold: SigScale service disabled (Gy would refuse), resume re-enabled it');

  /* 10. another tenant sees none of it */
  const other = await token('bss', 'bss-demo', 'demo', 'demo');
  const foreign = await buckets(other);
  if (foreign.some((b) => b.id === swapped.id || b.id === product.id)) fail('genalpha could read taranga\'s OCS balances');
  console.log('OK tenant isolation: genalpha (on the mock OCS) sees none of taranga\'s SigScale balances');

  await call('DELETE', `${JOURNEY}/${j.id}`, staff);
  console.log('\nPASS sigscale_ocs_test — a real, open-source OCS behind the seam: rate plans live in the OCS, '
    + 'activation provisions it over TM Forum APIs, the network charges over 3GPP Gy, TMF654 projects and credits, '
    + 'the OCS\'s own hub drives the running-low journey, plan change / hold / isolation all hold.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
