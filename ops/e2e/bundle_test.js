/* TMF620 soft-bundle cardinality with teeth. We build an isolated bundle whose
 * choice group is "pick exactly 2 of 3", then order it three ways:
 *   - 1 selected  → refused (too few);
 *   - 2 selected  → accepted;
 *   - 3 selected  → refused (too many).
 * The cardinality lives in catalog DATA and is enforced at order time — a
 * configurable bundle that actually constrains the configuration. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const run = Date.now();

async function token(request) {
  const res = await request.post(KC, {
    form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' },
  });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

  const t = await token(ctx.request);
  const H = { Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' };
  const CAT = `${API}/tmf-api/productCatalogManagement/v4`;
  const ORDERS = `${API}/tmf-api/productOrderingManagement/v4/productOrder`;
  const created = [];

  async function offering(name, isBundle = false, bundled = null) {
    const body = { name, lifecycleStatus: 'Active', version: '1.0', isBundle };
    if (bundled) body.bundledProductOffering = bundled;
    const res = await ctx.request.post(`${CAT}/productOffering`, { headers: H, data: body });
    if (res.status() !== 201) fail(`create offering ${name} → ${res.status()}`);
    const o = await res.json();
    created.push(o.id);
    return o;
  }

  // Three streaming apps + a bundle that requires picking exactly 2 of them.
  const a = await offering(`E2E Netflix ${run}`);
  const b = await offering(`E2E Disney+ ${run}`);
  const c = await offering(`E2E HBO ${run}`);
  const ref = (o) => ({ id: o.id, name: o.name, '@referredType': 'ProductOffering' });
  const bundle = await offering(`E2E Streaming Bundle ${run}`, true, [{
    '@type': 'BundledProductOfferingChoice',
    name: 'Pick 2 streaming apps',
    numberRelOfferLowerLimit: 2,
    numberRelOfferUpperLimit: 2,
    options: [ref(a), ref(b), ref(c)],
  }]);
  console.log('OK isolated bundle created with a "pick exactly 2 of 3" choice group');

  const order = (picks) => ({
    productOrderItem: [{
      id: '1', action: 'add', quantity: 1,
      productOffering: { id: bundle.id, name: bundle.name, '@referredType': 'ProductOffering' },
      productOrderItem: picks.map((o, i) => ({
        id: `1.${i + 1}`, action: 'add', quantity: 1,
        productOffering: { id: o.id, name: o.name, '@referredType': 'ProductOffering' },
      })),
    }],
    relatedParty: [{ id: `bundle-${run}`, role: 'customer' }],
  });
  const place = (picks) => ctx.request.post(ORDERS, { headers: H, data: order(picks) });

  // 1 selection → refused (need 2).
  const tooFew = await place([a]);
  if (tooFew.status() === 201) fail('order with 1 of 2 required should be refused');
  const tooFewMsg = (await tooFew.json()).message || '';
  if (!/2/.test(tooFewMsg)) fail('refusal should mention the required count, got: ' + tooFewMsg);
  console.log('OK 1 selected → refused:', tooFewMsg);

  // 2 selections → accepted.
  const ok = await place([a, b]);
  if (ok.status() !== 201) fail('order with exactly 2 should be accepted, got ' + ok.status()
    + ' ' + JSON.stringify(await ok.json()));
  console.log('OK 2 selected → accepted (valid configuration)');

  // 3 selections → refused (max 2).
  const tooMany = await place([a, b, c]);
  if (tooMany.status() === 201) fail('order with 3 (over max 2) should be refused');
  console.log('OK 3 selected → refused (over the upper limit):', (await tooMany.json()).message);

  // cleanup
  for (const id of created.reverse()) {
    await ctx.request.delete(`${CAT}/productOffering/${id}`, { headers: H }).catch(() => {});
  }
  await browser.close();
  console.log('\nBUNDLE E2E PASSED — soft-bundle cardinality is data, enforced at order time.');
})();
