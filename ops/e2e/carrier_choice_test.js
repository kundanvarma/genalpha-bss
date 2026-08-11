/* Carrier choice — the operator's per-tenant carrier menu (C-P2). Suite #90.
 *
 *  - OPERATOR MENU: a tenant configures its carriers (Helthjem, Bring/Posten…)
 *    as data — add, mark a default, list, delete. The API key is a secret-ref,
 *    never the value.
 *  - PICKUP POINTS: Bring/Posten exposes pickup points near a postcode (the
 *    distinctive Nordic delivery method) — the seam surfaces them.
 *  - ROUTING: with Bring configured as the default, a physical order books via
 *    BRING (a 'BRG…' consignment, carrier 'Posten/Bring' on the shipping order),
 *    not the built-in Helthjem — the operator's choice drives the booking.
 *  - CLEANUP: the menu is removed at the end, so the tenant falls back to the
 *    global carrier (Helthjem) and the fulfilment suite stays green.
 *
 * The customer-facing delivery-method picker at checkout is C-P3; this proves the
 * operator half.
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const F = '/tmf-api/shippingOrderManagement/v4';
const ORDERS = '/tmf-api/productOrderingManagement/v4';
const run = Date.now();
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
  const staff = await token('demo', 'demo');
  const kai = await token('kai@bss.local', 'kai');
  try {
    /* ---------- 1. operator menu: add Bring as the default carrier ---------- */
    const put = await call('PUT', `${F}/carrier`, staff, {
      carrier: 'bring', displayName: 'Posten/Bring', baseUrl: 'http://mock-bring:8080',
      secretRef: 'BRING_API_KEY', methods: ['home', 'pickupPoint'], isDefault: true });
    if (put.status !== 200) fail(`configure bring: ${put.status} ${put.text}`);
    const list = (await call('GET', `${F}/carrier`, staff)).body || [];
    const bring = list.find((c) => c.carrier === 'bring');
    if (!bring || !bring.isDefault) fail('bring not listed as the default carrier: ' + JSON.stringify(list));
    if (bring.secretRef !== 'BRING_API_KEY' || bring.apiKey) fail('secret leaked — should be a ref only');
    ok(`OPERATOR MENU: Posten/Bring configured as the default carrier (secret-ref '${bring.secretRef}', not the value)`);

    /* ---------- 2. pickup points ---------- */
    const pp = (await call('GET', `${F}/carrier/pickupPoints?carrier=bring&postcode=0150`, staff)).body || [];
    if (!pp.length || !pp[0].name || !pp[0].address) fail('no pickup points returned: ' + JSON.stringify(pp));
    ok(`PICKUP POINTS: ${pp.length} near 0150 — e.g. "${pp[0].name}" (${pp[0].address})`);

    /* ---------- 3. routing: a physical order books via BRING ---------- */
    const offers = (await call('GET', '/tmf-api/productCatalogManagement/v4/productOffering?limit=50', kai)).body || [];
    const plan = offers.find((o) => (o.name || '').includes('Unlimited'));
    if (!plan) fail('no plan in catalog');
    const order = await call('POST', `${ORDERS}/productOrder`, kai, {
      productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name },
        product: { place: [{ role: 'delivery', streetName: `Parcel ${run}`, postcode: '111' }] } }] });
    if (order.status !== 201) fail(`order: ${order.status} ${order.text}`);
    let so = null;
    for (let i = 0; i < 24 && !so; i++) {
      await sleep(2500);
      const shipments = (await call('GET', `${F}/shippingOrder`, staff)).body || [];
      so = shipments.find((s) => s.productOrderId === order.body.id
        && s.trackingRef && s.carrier) || null;
    }
    if (!so) fail('physical order minted no booked shipping order');
    if (so.carrier !== 'Posten/Bring' || !(so.trackingRef || '').startsWith('BRG')) {
      fail(`booked with the wrong carrier: ${so.carrier} / ${so.trackingRef} (expected Posten/Bring + BRG…)`);
    }
    ok(`ROUTING: the physical order booked via BRING — carrier "${so.carrier}", consignment ${so.trackingRef}`
      + ' — the operator\'s configured carrier drove the booking, not the built-in Helthjem');

    console.log('\nALL CARRIER-CHOICE CHECKS PASSED — the operator configures a per-tenant carrier'
      + ' menu (with pickup points), and a booking routes to the chosen carrier. The customer-facing'
      + ' delivery-method picker is next (C-P3).');
  } finally {
    await call('DELETE', `${F}/carrier/bring`, staff).catch(() => {}); // restore the Helthjem fallback
  }
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
