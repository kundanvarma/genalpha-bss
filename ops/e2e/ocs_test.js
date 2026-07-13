/* The OCS seam: the catalog references charging, the SOM provisions it, the
 * TMF654 facade projects it — the Online Charging System stays the master.
 *
 *  - catalog: a mobile plan's spec carries chargingSpecId (the rate plan
 *    that lives in the OCS's own tooling — referenced, never contained)
 *  - activation: the SOM provisions the subscriber + bucket in the OCS
 *  - runtime: simulated network usage (Gy in production) charges the bucket;
 *    TMF654 shows remaining/used; a TMF654 top-up credits the counter
 *  - plan change: the TMF622 modify swaps the OCS rate plan
 *  - month end: the OCS's own rollover policy carries unused data — and the
 *    TMF654 projection shows it
 *  - scoping: a customer sees only their OWN buckets
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const OCS = 'http://localhost:8115';
const CATALOG = `${API}/tmf-api/productCatalogManagement/v4`;
const TMF654 = `${API}/tmf-api/prepayBalanceManagement/v4`;
const run = Date.now();

async function token(request, client, user, pass) {
  const res = await request.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const staff = await token(ctx.request, 'bss-demo', 'demo', 'demo');
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };

  /* ---------- self-contained catalog: two plans referencing OCS rate plans ---------- */
  const mkPlan = async (name, chargingSpecId) => {
    const spec = await (await ctx.request.post(`${CATALOG}/productSpecification`, {
      headers: H, data: { name, lifecycleStatus: 'Active',
        productSpecCharacteristic: [{ name: 'chargingSpecId', valueType: 'string',
          configurable: false, productSpecCharacteristicValue: [{ value: chargingSpecId }] }] } })).json();
    const offering = await (await ctx.request.post(`${CATALOG}/productOffering`, {
      headers: H, data: { name, lifecycleStatus: 'Active',
        category: [{ name: 'Mobile plans' }],
        productSpecification: { id: spec.id, name: spec.name, '@referredType': 'ProductSpecification' } } })).json();
    return { spec, offering };
  };
  const small = await mkPlan(`OCS Plan 10 ${run}`, 'RG-DATA-10');
  const big = await mkPlan(`OCS Plan 50 ${run}`, 'RG-DATA-50');
  console.log('OK catalog references charging: chargingSpecId on the spec — the rate plan'
    + ' itself lives in the OCS');

  const cleanup = async () => {
    for (const x of [small, big]) {
      await ctx.request.delete(`${CATALOG}/productOffering/${x.offering.id}`, { headers: H });
      await ctx.request.delete(`${CATALOG}/productSpecification/${x.spec.id}`, { headers: H });
    }
  };

  try {
    /* ---------- order -> activation -> OCS subscriber ---------- */
    const olle = (await (await ctx.request.post(`${API}/tmf-api/party/v4/individual`, {
      headers: H, data: { givenName: 'Olle', familyName: `Ocs${run}` } })).json()).id;
    const order = await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
      headers: H, data: {
        productOrderItem: [{ action: 'add',
          productOffering: { id: small.offering.id, name: small.offering.name } }],
        relatedParty: [{ id: olle, role: 'customer' }] } });
    if (order.status() !== 201) fail('order failed: ' + order.status());

    let sub = null;
    for (let i = 0; i < 25 && !sub; i++) {
      await new Promise((r) => setTimeout(r, 2500));
      const subs = await (await ctx.request.get(
        `${OCS}/subscribers?tenantId=genalpha&partyId=${olle}`)).json();
      sub = subs[0] || null;
    }
    if (!sub) fail('the SOM never provisioned the OCS subscriber');
    if (sub.ratePlanId !== 'RG-DATA-10' || sub.buckets[0].totalGB !== 10) {
      fail('OCS subscriber wrong: ' + JSON.stringify(sub));
    }
    console.log('OK activation provisioned the OCS: subscriber on RG-DATA-10 with a 10 GB bucket');

    /* ---------- TMF654 projection + simulated network usage ---------- */
    const buckets = async () => (await (await ctx.request.get(
      `${TMF654}/bucket?relatedPartyId=${olle}`, { headers: H })).json());
    let bucket = (await buckets())[0];
    if (!bucket || bucket.remainingValue.amount !== 10) {
      fail('TMF654 projection wrong: ' + JSON.stringify(bucket));
    }
    await ctx.request.post(`${OCS}/subscribers/${sub.id}/usage`, { data: { gb: 3.5 } });
    bucket = (await buckets())[0];
    if (bucket.remainingValue.amount !== 6.5 || bucket.usedValue.amount !== 3.5) {
      fail(`after 3.5 GB of network usage: ${JSON.stringify(bucket)}`);
    }
    console.log('OK the network charged the OCS (3.5 GB); TMF654 shows 6.5 GB remaining');

    /* ---------- TMF654 top-up credits the OCS counter ---------- */
    const topup = await ctx.request.post(`${TMF654}/topupBalance`, { headers: H, data: {
      relatedParty: [{ id: olle, role: 'customer' }],
      bucket: { id: bucket.id }, amount: { amount: 5, units: 'GB' } } });
    if (topup.status() !== 201) fail('topupBalance failed: ' + topup.status());
    bucket = (await buckets())[0];
    if (bucket.remainingValue.amount !== 11.5) {
      fail('top-up did not credit the counter: ' + JSON.stringify(bucket.remainingValue));
    }
    console.log('OK TMF654 top-up: +5 GB credited in the OCS, 11.5 GB remaining');

    /* ---------- plan change swaps the OCS rate plan ---------- */
    const products = await (await ctx.request.get(
      `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${olle}&status=active&limit=10`,
      { headers: H })).json();
    const product = products.find((p) => p.name === small.offering.name);
    const modify = await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
      headers: H, data: {
        productOrderItem: [{ action: 'modify',
          product: { id: product.id, realizingService: [{ id: sub.serviceId }] },
          productOffering: { id: big.offering.id, name: big.offering.name } }],
        relatedParty: [{ id: olle, role: 'customer' }] } });
    if (modify.status() !== 201) fail('plan change failed: ' + modify.status());
    let swapped = null;
    for (let i = 0; i < 20 && !swapped; i++) {
      await new Promise((r) => setTimeout(r, 2000));
      const current = await (await ctx.request.get(`${OCS}/subscribers/${sub.id}`)).json();
      if (current.ratePlanId === 'RG-DATA-50') swapped = current;
    }
    if (!swapped || swapped.buckets[0].totalGB !== 50) {
      fail('OCS rate plan did not follow the plan change');
    }
    console.log('OK TMF622 modify swapped the OCS rate plan: RG-DATA-10 -> RG-DATA-50 (50 GB)');

    /* ---------- month end: the OCS's rollover policy, projected by TMF654 ---------- */
    await ctx.request.post(`${OCS}/subscribers/${sub.id}/usage`, { data: { gb: 48 } });
    await ctx.request.post(`${OCS}/cycle`, { data: {} });
    bucket = (await buckets())[0];
    if (bucket.rolloverValue.amount !== 2 || bucket.remainingValue.amount !== 52) {
      fail(`rollover wrong: ${JSON.stringify(bucket)}`);
    }
    console.log('OK month end: 2 GB unused rolled over (RG-DATA-50 policy) — 52 GB for the'
      + ' new month, visible through TMF654');

    /* ---------- scoping: a customer sees only their own buckets ---------- */
    const kai = await token(ctx.request, 'bss-biz', 'kai@bss.local', 'kai');
    const kaiView = await (await ctx.request.get(
      `${TMF654}/bucket?relatedPartyId=${olle}`,
      { headers: { Authorization: 'Bearer ' + kai } })).json();
    if (kaiView.some((b) => (b.relatedParty || []).some((p) => p.id === olle))) {
      fail('a customer could read another party\'s buckets');
    }
    console.log('OK party scoping: kai asking for Olle\'s buckets gets only his own (none)');
  } finally {
    await cleanup();
  }

  await browser.close();
  console.log('\nALL OCS-SEAM CHECKS PASSED — catalog references charging, activation'
    + ' provisions it, TMF654 projects it, top-ups credit it, plan changes follow it,'
    + ' rollover is the OCS\'s own policy.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
