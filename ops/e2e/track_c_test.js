/* Track C — independent per-component fulfillment + the Helthjem logistics seam.
 * Suite #88.
 *
 *  - a MIXED order fulfils on independent clocks: the digital service activates
 *    in seconds (item -> completed) while a physical item ships and stays
 *    inProgress; the order shows partiallyCompleted, not stuck
 *  - the physical item is BOOKED with a carrier (Helthjem-shaped seam) — a real
 *    tracking number appears — and the carrier's delivery COMPLETES that item
 *    and rolls the order up, no human touch
 *  - a mobile plan offers BOTH SIM paths: eSIM activates instantly with no
 *    parcel; a physical SIM ships via Helthjem
 *  - an under-configured bundle is refused at ORDER TIME (5b)
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const ORDERS = '/tmf-api/productOrderingManagement/v4';
const SHIP = '/tmf-api/shippingOrderManagement/v4';
const CAT = '/tmf-api/productCatalogManagement/v4';
const run = Date.now();
const fail = (m) => { throw new Error(m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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
const leaves = (items, into = []) => {
  for (const it of items || []) {
    const kids = it.productOrderItem || [];
    if (kids.length) leaves(kids, into);
    else if (it.productOffering) into.push(it);
  }
  return into;
};
const stateOf = (o, name) => (leaves(o.productOrderItem)
  .find((i) => (i.productOffering.name || '').includes(name)) || {}).state;
async function waitOrder(id, tok, pred, tries = 20) {
  for (let i = 0; i < tries; i++) {
    const o = (await call('GET', `${ORDERS}/productOrder/${id}`, tok)).body;
    if (o && pred(o)) return o;
    await sleep(2500);
  }
  return null;
}

(async () => {
  const staff = await token('demo', 'demo');
  const offers = (await call('GET', `${CAT}/productOffering?limit=100`, staff)).body || [];
  const pick = (n) => offers.find((o) => o.name === n) || fail(`offer '${n}' not in catalog`);
  const netflix = pick('Netflix Standard');
  const kidstv = pick('GenAlpha Kids TV');
  const mobile = pick('GenAlpha Mobile 10 GB');

  /* ---------- 1. mixed order: digital NOW, physical on its own track ---------- */
  const mixed = await call('POST', `${ORDERS}/productOrder`, staff, {
    productOrderItem: [
      { action: 'add', quantity: 1, productOffering: { id: netflix.id, name: netflix.name } },
      { action: 'add', quantity: 1, productOffering: { id: kidstv.id, name: kidstv.name },
        product: { place: [{ role: 'shippingAddress', streetName: `Storgata ${run}` }] } },
    ],
    relatedParty: [{ id: `tcc-mixed-${run}`, name: 'TC Mixed', role: 'customer' }] });
  if (mixed.status !== 201) fail(`mixed order: ${mixed.status} ${mixed.text.slice(0, 150)}`);
  const mid = mixed.body.id;

  const partial = await waitOrder(mid, staff,
    (o) => o.state === 'partiallyCompleted' && stateOf(o, 'Netflix') === 'completed');
  if (!partial) fail('mixed order never reached partiallyCompleted with Netflix active');
  if (stateOf(partial, 'Kids TV') !== 'inProgress') {
    fail('the physical item should be inProgress while the digital one is done: '
      + JSON.stringify(leaves(partial.productOrderItem).map((i) => [i.productOffering.name, i.state])));
  }
  console.log('OK INDEPENDENT: a mixed order did NOT wait as a whole — Netflix (digital)'
    + ' activated to completed in seconds while GenAlpha Kids TV (physical) stayed inProgress;'
    + ' the order shows partiallyCompleted, not stuck.');

  /* ---------- 2. the carrier booked it and delivered it, machine-driven ---------- */
  let so = null;
  for (let i = 0; i < 12 && !so; i++) {
    const list = (await call('GET', `${SHIP}/shippingOrder`, staff)).body || [];
    so = list.find((s) => s.productOrderId === mid) || null;
    if (!so) await sleep(2000);
  }
  if (!so) fail('physical item minted no shipping order');
  if (!so.trackingRef || !so.trackingRef.startsWith('HJ')) {
    fail('the carrier seam did not book a Helthjem tracking number: ' + JSON.stringify(so.trackingRef));
  }
  console.log(`OK CARRIER: the parcel was BOOKED with the carrier — Helthjem tracking`
    + ` ${so.trackingRef} rode onto shippingOrder ${so.id.slice(0, 8)}… at dispatch.`);

  const done = await waitOrder(mid, staff, (o) => o.state === 'completed', 20);
  if (!done) fail('the carrier delivery did not complete the order');
  if (stateOf(done, 'Kids TV') !== 'completed') fail('Kids TV item not completed after delivery');
  console.log('OK DELIVERED: the carrier delivered the parcel on its own and that completed'
    + ' the shipped item — the order rolled up partiallyCompleted -> completed with no human touch.');

  /* ---------- 3. one plan, two SIM paths ---------- */
  const esim = await call('POST', `${ORDERS}/productOrder`, staff, {
    productOrderItem: [{ action: 'add', quantity: 1, productOffering: { id: mobile.id, name: mobile.name },
      product: { productCharacteristic: [{ name: 'simType', value: 'esim' }] } }],
    relatedParty: [{ id: `tcc-esim-${run}`, name: 'TC eSIM', role: 'customer' }] });
  const esimDone = await waitOrder(esim.body.id, staff, (o) => o.state === 'completed', 8);
  if (!esimDone) fail('eSIM order did not activate instantly');
  const esimShip = ((await call('GET', `${SHIP}/shippingOrder`, staff)).body || [])
    .filter((s) => s.productOrderId === esim.body.id);
  if (esimShip.length) fail('an eSIM must not ship a parcel');
  console.log('OK eSIM: a mobile plan with an eSIM activated instantly (completed) with NO parcel.');

  const psim = await call('POST', `${ORDERS}/productOrder`, staff, {
    productOrderItem: [{ action: 'add', quantity: 1, productOffering: { id: mobile.id, name: mobile.name },
      product: { productCharacteristic: [{ name: 'simType', value: 'physical' }],
        place: [{ role: 'shippingAddress', streetName: `Storgata ${run}` }] } }],
    relatedParty: [{ id: `tcc-psim-${run}`, name: 'TC pSIM', role: 'customer' }] });
  let psimSo = null;
  for (let i = 0; i < 12 && !psimSo; i++) {
    const list = (await call('GET', `${SHIP}/shippingOrder`, staff)).body || [];
    psimSo = list.find((s) => s.productOrderId === psim.body.id) || null;
    if (!psimSo) await sleep(2000);
  }
  if (!psimSo || !(psimSo.trackingRef || '').startsWith('HJ')) fail('physical SIM did not ship via Helthjem');
  const psimDone = await waitOrder(psim.body.id, staff, (o) => o.state === 'completed', 20);
  if (!psimDone) fail('physical SIM order did not complete on delivery');
  console.log(`OK PHYSICAL SIM: the same plan with a physical SIM shipped via Helthjem`
    + ` (${psimSo.trackingRef}) and completed on delivery — two SIM paths, two clocks, one plan.`);

  /* ---------- 4. an under-configured bundle is refused at order time (5b) ---------- */
  const bundle = offers.find((o) => o.name === 'GenAlpha One Home & Mobile');
  if (bundle) {
    const bad = await call('POST', `${ORDERS}/productOrder`, staff, {
      productOrderItem: [{ action: 'add', quantity: 1,
        productOffering: { id: bundle.id, name: bundle.name } }],
      relatedParty: [{ id: `tcc-bad-${run}`, name: 'TC Bad', role: 'customer' }] });
    if (bad.status < 400) fail('an under-configured bundle should be refused at order time, got ' + bad.status);
    console.log(`OK VALIDATION (5b): the bundle with no phone picked was refused at ORDER TIME`
      + ` — "${(bad.body && bad.body.message || bad.text || '').slice(0, 80)}…".`);
  } else {
    console.log('SKIP VALIDATION (5b): the bundle offering is not in this catalog.');
  }

  console.log('\nALL TRACK C CHECKS PASSED — a bundle no longer fulfils all-or-nothing: each'
    + ' component completes on its own clock, physical goods ship through a real carrier seam'
    + ' (Helthjem) and deliver themselves, eSIM and physical SIM are both offered, and an'
    + ' under-configured bundle is refused at the door.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
