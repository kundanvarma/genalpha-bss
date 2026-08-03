/* The order-time serviceability gate + the footprint the cart renders. Suite #80.
 *
 *  - TMF645 P2: the storefront always checked serviceability at checkout,
 *    but the ORDER API never did — an API-direct or agent order for
 *    un-serviceable fiber sailed through and failed at fulfilment. Now
 *    every placed item is qualified at create, same TMF679 check, same
 *    words, fail-open
 *  - ungated offerings with a place still order anywhere — the gate only
 *    refuses what the qualification actually refuses
 *  - the cart's new technology line renders from queryServiceQualification:
 *    the suite asserts the exact data the UI shows
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = '/tmf-api/productCatalogManagement/v4';
const ORD = '/tmf-api/productOrderingManagement/v4';
const SQM = '/tmf-api/serviceQualificationManagement/v4';
const fail = (m) => { throw new Error(m); };

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo',
      username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
const place = (postCode) => ({ role: 'shipping', '@type': 'GeographicAddress',
  street1: 'Testgatan 1', postCode, city: 'Teststad', country: 'SE' });
const orderFor = (offering, postCode) => ({
  description: `qualification-gate probe ${offering.name}`,
  productOrderItem: [{ id: '1', action: 'add', quantity: 1,
    productOffering: { id: offering.id, name: offering.name, '@referredType': 'ProductOffering' },
    product: { place: [place(postCode)] } }],
});

(async () => {
  const kai = await token('kai@bss.local', 'kai');
  const offerings = await (await fetch(`${API}${CAT}/productOffering?limit=100`)).json();
  const fiber = offerings.find((o) => o.name === 'GenAlpha Fiber 1000') || fail('no fiber offering');
  const ungated = offerings.find((o) => o.name === 'GenAlpha Mobile 10 GB') || fail('no mobile offering');

  /* ---------- 1. the gate the API never had ---------- */
  const refused = await call('POST', `${ORD}/productOrder`, kai, orderFor(fiber, '99999'));
  if (refused.status !== 400) {
    fail(`un-serviceable fiber must be refused at the API, got ${refused.status} ${refused.text.slice(0, 200)}`);
  }
  if (!String(refused.body.message).includes('not available at postcode 99999')) {
    fail('the refusal should speak the qualification\'s words: ' + refused.text.slice(0, 200));
  }
  console.log('OK THE GATE: an API-direct fiber order for postcode 99999 — the kind that'
    + ' sailed through yesterday and died at fulfilment — is refused at CREATE with the'
    + ` qualification's own reason: "${refused.body.message.slice(0, 70)}…".`);

  /* ---------- 2. serviceable fiber orders; ungated orders anywhere ---------- */
  const accepted = await call('POST', `${ORD}/productOrder`, kai, orderFor(fiber, '11122'));
  if (accepted.status !== 201) {
    fail(`serviceable fiber must order: ${accepted.status} ${accepted.text.slice(0, 200)}`);
  }
  const anywhere = await call('POST', `${ORD}/productOrder`, kai, orderFor(ungated, '99999'));
  if (anywhere.status !== 201) {
    fail(`an ungated offering must order anywhere: ${anywhere.status} ${anywhere.text.slice(0, 200)}`);
  }
  console.log(`OK THE NUANCE: the same fiber at 11122 ordered fine (${accepted.body.id.slice(0, 8)}…),`
    + ` and an ungated mobile plan ordered at 99999 (${anywhere.body.id.slice(0, 8)}…) — the gate`
    + ' refuses exactly what the qualification refuses, nothing more.');

  /* ---------- 3. the data behind the cart's technology line ---------- */
  const sthlm = await call('POST', `${SQM}/queryServiceQualification`, null,
    { searchCriteria: { place: { postCode: '11122', city: 'Stockholm' } } });
  const best = sthlm.body.serviceQualificationItem
    .map((i) => Object.fromEntries(i.service.serviceCharacteristic.map((c) => [c.name, c.value])))
    .sort((a, b) => b.maxDownstreamMbps - a.maxDownstreamMbps)[0];
  if (best.technology !== 'fiber' || best.maxDownstreamMbps !== 1000) {
    fail('the cart line data is wrong: ' + JSON.stringify(best));
  }
  const rural = await call('POST', `${SQM}/queryServiceQualification`, null,
    { searchCriteria: { place: { postCode: '99999' } } });
  const ruralBest = rural.body.serviceQualificationItem
    .map((i) => Object.fromEntries(i.service.serviceCharacteristic.map((c) => [c.name, c.value])))
    .sort((a, b) => b.maxDownstreamMbps - a.maxDownstreamMbps)[0];
  if (ruralBest.technology !== '5g-fwa') fail('rural best should be 5g-fwa');
  console.log('OK THE CART LINE: the address block now names what the network delivers —'
    + ' "Fiber up to 1000 Mbit/s" in Stockholm, "5G broadband up to 100 Mbit/s" in Kiruna'
    + ' — rendered from the same TMF645 query the suite just asserted.');

  console.log('\nALL QUALIFICATION-GATE CHECKS PASSED — the serviceability answer now has'
    + ' teeth at the one door every channel walks through, and the shop window says not'
    + ' just WHETHER but WHAT: technology and speed, from the footprint the operator'
    + ' edits as data.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
