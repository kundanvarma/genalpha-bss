/* Wholesale PROVIDER side — be the fibre owner, cross-tenant. Suite #89.
 *
 * X1-X6 proved the platform as an access SEEKER. This proves it as an access
 * PROVIDER too: one platform plays both sides of open access. A genalpha (retailer)
 * end-user fibre sale at Trondheim is realized by an access-seeker order placed
 * CROSS-TENANT to nova's own MEF Sonata provider face; nova provisions it, activates
 * async and calls back; the retail line comes up; and nova sees what the retailer
 * owes it.
 *
 *  - FULL LOOP: a retail fibre sale routes to the on-platform owner (NOVAFIBRE) and
 *    the line goes active after the owner's async callback.
 *  - PROVIDER ORDER: a matching access order lives in the OWNER's tenant (nova),
 *    correlated by the retailer's order reference.
 *  - SETTLEMENT: nova sees what the retailer owes it (active lines x rate).
 *  - PORTAL: the wholesale partner portal serves and answers serviceability.
 */
const API = 'http://localhost:8080';
const KC = (realm) => `http://localhost:8085/realms/${realm}/protocol/openid-connect/token`;
const CAT = '/tmf-api/productCatalogManagement/v4/productOffering?limit=100';
const ORD = '/tmf-api/productOrderingManagement/v4/productOrder';
const SO = '/tmf-api/serviceOrdering/v4';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(realm, user, pass) {
  const r = await fetch(KC(realm), { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${realm}/${user}): ${r.status}`);
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
  const kai = await token('bss', 'kai@bss.local', 'kai');       // genalpha retailer's end customer
  const staff = await token('bss', 'demo', 'demo');             // genalpha staff
  const nova = await token('nova', 'demo', 'demo');             // the OWNER (nova) staff — issuer = nova

  const offs = (await call('GET', CAT, staff)).body || [];
  const fiber = offs.find((o) => o.name === 'GenAlpha Fiber 1000');
  if (!fiber) fail('no retail fibre offering');

  /* ---------- 1. FULL LOOP: retail fibre sale routed cross-tenant to nova ---------- */
  const place = { '@type': 'GeographicAddress', streetName: 'Kongens gate 1', postCode: '7010', country: 'NO' };
  const res = await call('POST', ORD, kai, { description: 'provider-side fibre', productOrderItem: [{
    id: '1', action: 'add',
    productOffering: { id: fiber.id, name: fiber.name, '@referredType': 'ProductOffering' },
    product: { place: [place], productCharacteristic: [{ name: 'downloadSpeed', value: 1000 }] } }] });
  if (res.status !== 201) fail(`fibre order: ${res.status} ${res.text}`);
  const oid = res.body.id;

  let seeker = [];
  for (let i = 0; i < 25; i++) {
    await sleep(1500);
    seeker = (await call('GET', `${SO}/wholesaleAccessOrder?productOrderId=${oid}`, staff)).body || [];
    if (seeker.length && seeker[0].state === 'active') break;
  }
  if (!seeker.length) fail('the retail fibre sale placed no access order');
  if (seeker[0].accessOwner !== 'NOVAFIBRE') fail('the order did not route to the on-platform owner NOVAFIBRE');
  if (seeker[0].state !== 'active') fail('the line never went active (owner callback missing across tenants)');
  const buyerRef = seeker[0].id;
  ok(`FULL LOOP: retail fibre at 7010 -> access order to NOVAFIBRE, active via nova's async callback (ref ${seeker[0].externalId})`);

  // the retail line rolled up
  const order = (await call('GET', `${ORD}/${oid}`, staff)).body;
  if (!['completed', 'partiallyCompleted'].includes(order.state)) {
    fail(`retail order did not roll up: ${order.state}`);
  }
  ok(`RETAIL LINE: the customer's order rolled up (${order.state}) once the owner activated the access`);

  /* ---------- 2. PROVIDER ORDER lives in the OWNER's tenant (nova) ---------- */
  const providerOrders = (await call('GET', `${SO}/providerAccessOrder`, nova)).body || [];
  const match = providerOrders.find((p) => p.buyerRef === buyerRef);
  if (!match) fail('no matching provider access order in the owner (nova) tenant');
  if (match.state !== 'active') fail('the provider access order is not active');
  if (match.retailerPartyId !== 'genalpha') fail('the provider order does not name the retailer');
  ok(`PROVIDER ORDER: nova holds a matching active access order (${match.accessLayer}) sold to ${match.retailerPartyId}`);

  /* ---------- 3. SETTLEMENT: what the retailer owes the owner ---------- */
  const settle = (await call('GET', `${SO}/wholesaleProviderSettlement`, nova)).body || {};
  const genalpha = (settle.retailer || []).find((r) => r.retailer === 'genalpha');
  if (!genalpha) fail('the provider settlement does not bill the retailer genalpha');
  const line = (genalpha.line || [])[0];
  if (!line || Math.abs(genalpha.totalMonthlyCharge - line.ratePerLine * line.activeLines) > 0.01) {
    fail('provider settlement total != lines x rate: ' + JSON.stringify(genalpha));
  }
  ok(`SETTLEMENT: nova charges retailer genalpha ${genalpha.totalMonthlyCharge} EUR/mo`
    + ` (${line.activeLines} ${line.accessLayer} line(s) x ${line.ratePerLine}); total revenue ${settle.totalMonthlyRevenue}`);

  /* ---------- 4. PARTNER PORTAL serves + answers serviceability ---------- */
  const portal = (await call('GET', '/partner/', null)).text || '';
  if (!/Wholesale partner portal/.test(portal)) fail('the partner portal does not serve');
  const opts = (await call('POST', '/tmf-api/serviceQualificationManagement/v4/queryAccessOptions', null,
    { searchCriteria: { place: { postCode: '5020' }, technology: 'fiber' } })).body || {};
  if (!(opts.accessOption || []).length) fail('the portal serviceability check returns no owners');
  ok(`PARTNER PORTAL: serves, and its serviceability check names the owners at an address`);

  console.log('\nALL WHOLESALE-PROVIDER CHECKS PASSED — one platform plays BOTH sides of open access:'
    + " a retailer's fibre sale is realized cross-tenant by the owner's own MEF Sonata face, the owner"
    + ' provisions and activates it, the retail line comes up, and the owner bills the retailer wholesale.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
