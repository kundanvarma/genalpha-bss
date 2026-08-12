/* The GENERIC carrier connector — any carrier by config, zero vendor code. Suite #101.
 *
 * Console P4's connector half: a carrier whose API takes a JSON booking and
 * returns a tracking reference rides on CONFIG alone — the same doctrine the
 * CMS proved with Strapi on the generic HTTP provider. Here the generic
 * connector is pointed at the BRING-shaped mock (a wire it has zero code for):
 * bookPath + JSON pointers map the wire, and a real physical order books
 * through it end-to-end. Money never rides this seam — a failed booking
 * degrades to the manual warehouse flow, which is why carriers are SAFE to
 * generic-connect where payments are not.
 *
 *  - CONFIG IS THE ADAPTER: carrier 'http' bound with bookPath/pointers aimed
 *    at mock-bring's wire; no Bring code in the connector.
 *  - BOOKS FOR REAL: a physical order routes to it (default carrier) and the
 *    shipping order carries the mock's BRG… consignment + the display name.
 *  - PICKUP POINTS TOO: the pickupPath template + pointsPointer read Bring's
 *    pickup shape generically.
 *  - CLEANUP: the binding is removed and the standing demo menu re-seeded.
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

const MENU = [
  { carrier: 'helthjem', displayName: 'Helthjem', baseUrl: 'http://mock-logistics:8080', secretRef: 'HELTHJEM_API_KEY', methods: ['home'], isDefault: false },
  { carrier: 'bring', displayName: 'Posten/Bring', baseUrl: 'http://mock-bring:8080', secretRef: 'BRING_API_KEY', methods: ['home', 'pickupPoint'], isDefault: true },
  { carrier: 'postnord', displayName: 'PostNord', baseUrl: 'http://mock-postnord:8080', secretRef: 'POSTNORD_API_KEY', methods: ['home', 'pickupPoint'], isDefault: false },
];

(async () => {
  const staff = await token('demo', 'demo');
  const kai = await token('kai@bss.local', 'kai');
  try {
    /* ---------- 1. config IS the adapter ---------- */
    const put = await call('PUT', `${F}/carrier`, staff, {
      carrier: 'http', displayName: 'AnyCarrier (generic)', baseUrl: 'http://mock-bring:8080',
      secretRef: 'BRING_API_KEY', methods: ['home', 'pickupPoint'], isDefault: true,
      config: {
        bookPath: '/booking/api/booking',
        trackingPointer: '/trackingNumber',
        shipmentPointer: '/carrierShipmentId',
        urlPointer: '/trackingUrl',
        pickupPath: '/pickuppoint/NO/postalCode/{postcode}.json',
        pointsPointer: '/pickupPoint',
      } });
    if (put.status !== 200) fail(`bind http carrier: ${put.status} ${put.text}`);
    ok('CONFIG IS THE ADAPTER: carrier "http" bound at the Bring-shaped mock — bookPath + JSON'
      + ' pointers map the wire, zero vendor code in the connector');

    /* ---------- 2. pickup points through the generic path ---------- */
    const pp = (await call('GET', `${F}/carrier/pickupPoints?carrier=http&postcode=0150`, staff)).body || [];
    if (!pp.length || !pp[0].name) fail('generic pickup points empty: ' + JSON.stringify(pp).slice(0, 120));
    ok(`PICKUP POINTS: ${pp.length} near 0150 through the generic template — e.g. "${pp[0].name}"`);

    /* ---------- 3. a real order books through it ---------- */
    const offers = (await call('GET', '/tmf-api/productCatalogManagement/v4/productOffering?limit=100', kai)).body || [];
    const plan = offers.find((o) => (o.name || '').includes('Unlimited'));
    if (!plan) fail('no plan in catalog');
    const order = await call('POST', `${ORDERS}/productOrder`, kai, {
      productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name },
        product: { place: [{ role: 'delivery', streetName: `Generic ${run}`, postcode: '0150' }] } }] });
    if (order.status !== 201) fail(`order: ${order.status} ${order.text}`);
    let so = null;
    for (let i = 0; i < 24 && !so; i++) {
      await sleep(2500);
      const shipments = (await call('GET', `${F}/shippingOrder`, staff)).body || [];
      so = shipments.find((s) => s.productOrderId === order.body.id && s.trackingRef && s.carrier) || null;
    }
    if (!so) fail('the order minted no booked shipping order via the generic connector');
    if (so.carrier !== 'AnyCarrier (generic)' || !(so.trackingRef || '').startsWith('BRG')) {
      fail(`generic booking wrong: carrier=${so.carrier} tracking=${so.trackingRef}`);
    }
    ok(`BOOKS FOR REAL: the physical order booked through the GENERIC connector — carrier`
      + ` "${so.carrier}", consignment ${so.trackingRef} minted by the Bring-shaped wire it has no code for`);

    console.log('\nALL GENERIC-CARRIER CHECKS PASSED — the long tail of carriers is a CONFIG row:'
      + ' bookPath + JSON pointers map any book-and-track wire, bookings ride it end-to-end, and a'
      + ' failure degrades to the manual flow. Named adapters where the wire is rich; config where it'
      + ' is plain; and money never on either.');
  } finally {
    await call('DELETE', `${F}/carrier/http`, staff).catch(() => {});
    for (const c of MENU) await call('PUT', `${F}/carrier`, staff, c).catch(() => {});
  }
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
