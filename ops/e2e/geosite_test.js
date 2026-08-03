/* TMF674 geographic sites (the address book the org model never had). Suite #84.
 *
 *  - a site is a NAMED, reusable place: name + owner + status + a stored
 *    TMF673 address, validated to exist and embedded on every read
 *  - the lifecycle is real: planned → active → retired, then deletable
 *  - the address-book story: the branch address entered ONCE, then reused
 *    on an order whose fulfilment parcel carries it — no retyping
 *  - walls: customers 403; nova sees only nova
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms';
const SITE = '/tmf-api/geographicSiteManagement/v4';
const ADDR = '/tmf-api/geographicAddressManagement/v4';
const ORD = '/tmf-api/productOrderingManagement/v4';
const SHIP = '/tmf-api/shippingOrderManagement/v4';
const run = Date.now();
const fail = (m) => { throw new Error(m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(realm, user, pass) {
  const r = await fetch(`${KC}/${realm}/protocol/openid-connect/token`, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo',
      username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
const sub = (t) => JSON.parse(Buffer.from(t.split('.')[1], 'base64url').toString()).sub;
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}

(async () => {
  const staff = await token('bss', 'demo', 'demo');
  const kai = await token('bss', 'kai@bss.local', 'kai');
  const orgId = `acme-sites-${run}`;

  /* ---------- 1. named places, owned and embedded ---------- */
  const hqAddr = await call('POST', `${ADDR}/geographicAddress`, staff, {
    street1: 'Kungsgatan 1', postCode: '11122', city: 'Stockholm', country: 'SE' });
  if (hqAddr.status >= 300) fail(`hq address: ${hqAddr.status} ${hqAddr.text.slice(0, 150)}`);
  const brAddr = await call('POST', `${ADDR}/geographicAddress`, staff, {
    street1: 'Storgatan 9', postCode: '22233', city: 'Göteborg', country: 'SE' });
  const hq = await call('POST', `${SITE}/geographicSite`, staff, {
    name: `Acme HQ ${run}`, status: 'active',
    relatedParty: [{ id: orgId, role: 'owner', '@referredType': 'Organization' }],
    place: { id: hqAddr.body.id } });
  if (hq.status !== 201) fail(`hq site: ${hq.status} ${hq.text.slice(0, 200)}`);
  if (!hq.body.place || hq.body.place[0].street1 !== 'Kungsgatan 1') {
    fail('the site must EMBED its stored address');
  }
  const branch = await call('POST', `${SITE}/geographicSite`, staff, {
    name: `Acme Göteborg branch ${run}`,
    relatedParty: [{ id: orgId, role: 'owner', '@referredType': 'Organization' }],
    place: { id: brAddr.body.id } });
  if (branch.body.status !== 'planned') fail('a new site defaults to planned');
  const bogus = await call('POST', `${SITE}/geographicSite`, staff,
    { name: 'nowhere', place: { id: 'not-an-address' } });
  if (bogus.status !== 400) fail('a site must refuse a place that is not a stored address');
  const mine = (await call('GET', `${SITE}/geographicSite?relatedPartyId=${orgId}`, staff)).body || [];
  if (mine.length !== 2) fail(`org filter should find 2 sites, got ${mine.length}`);
  console.log(`OK NAMED PLACES: "${hq.body.name}" (active) and the Göteborg branch (planned)`
    + ' — each owning a STORED address, embedded on read, refused when the address does'
    + ' not exist; the org filter lists exactly its own two.');

  /* ---------- 2. the lifecycle ---------- */
  const activated = await call('PATCH', `${SITE}/geographicSite/${branch.body.id}`, staff,
    { status: 'active' });
  if (activated.body.status !== 'active') fail('branch should activate');
  const badStatus = await call('PATCH', `${SITE}/geographicSite/${branch.body.id}`, staff,
    { status: 'demolished' });
  if (badStatus.status !== 400) fail('an invented status must be refused');
  console.log('OK THE LIFECYCLE: the branch went planned → active over PATCH; an invented'
    + ' status was refused — the lifecycle is a vocabulary, not a suggestion.');

  /* ---------- 3. entered once, reused ---------- */
  const offerings = await (await fetch(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`)).json();
  const phone = offerings.find((o) => o.name === 'Apple iPhone 17') || fail('no phone offering');
  const site = (await call('GET', `${SITE}/geographicSite/${hq.body.id}`, staff)).body;
  const place = { role: 'shipping', '@type': 'GeographicAddress',
    street1: site.place[0].street1, postCode: site.place[0].postCode,
    city: site.place[0].city, country: site.place[0].country };
  const order = await call('POST', `${ORD}/productOrder`, kai, {
    description: `site reuse probe ${run}`,
    productOrderItem: [{ id: '1', action: 'add', quantity: 1,
      productOffering: { id: phone.id, name: phone.name, '@referredType': 'ProductOffering' },
      product: { place: [place] } }] });
  if (order.status !== 201) fail(`order with site address: ${order.status} ${order.text.slice(0, 200)}`);
  let parcel = null;
  for (let i = 0; i < 20 && !parcel; i++) {
    await sleep(3000);
    const parcels = (await call('GET', `${SHIP}/shippingOrder`, staff)).body || [];
    parcel = parcels.find((p) => p.productOrderId === order.body.id) || null;
  }
  if (!parcel) fail('no shipping order minted for the site-addressed order');
  const shipped = JSON.stringify(parcel.place || {});
  if (!shipped.includes('Kungsgatan 1')) fail('the parcel lost the site\'s address: ' + shipped.slice(0, 150));
  console.log(`OK ENTERED ONCE: the HQ address — typed exactly once when the site was born —`
    + ` rode kai's order and landed on fulfilment's parcel (${parcel.id.slice(0, 8)}…,`
    + ' Kungsgatan 1 intact). The address book works end to end.');

  /* ---------- 4. walls + retirement ---------- */
  const kaiRead = await call('GET', `${SITE}/geographicSite`, kai);
  if (kaiRead.status !== 403) fail(`customer site read must 403, got ${kaiRead.status}`);
  const nova = await token('nova', 'demo', 'demo');
  const novaSites = (await call('GET', `${SITE}/geographicSite?relatedPartyId=${orgId}`, nova)).body || [];
  if (novaSites.length !== 0) fail('genalpha\'s sites leaked into nova');
  await call('PATCH', `${SITE}/geographicSite/${branch.body.id}`, staff, { status: 'retired' });
  const del = await call('DELETE', `${SITE}/geographicSite/${branch.body.id}`, staff);
  if (del.status !== 204) fail(`retired site should delete, got ${del.status}`);
  console.log('OK THE WALLS: customers 403 (the address book is back-office), nova sees'
    + ' nothing, and a retired branch deletes cleanly.');

  console.log('\nALL GEOGRAPHIC-SITE CHECKS PASSED — places have names now: owned, staged'
    + ' through a real lifecycle, leaning on the addresses next door, and reused instead'
    + ' of retyped. The TMF gap list is closed.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
