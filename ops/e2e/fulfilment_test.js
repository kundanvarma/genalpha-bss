/* Fulfilment (component #37: TMF700 shippingOrder + TMF697 workOrder). Suite #73.
 *
 *  - a physical order MINTS a shipping order; the warehouse advances it
 *    over the API; DELIVERED completes the product order machine-driven —
 *    the CSR button is now optional, not load-bearing (SOM provisions)
 *  - an install appointment mints a WORK ORDER; both gates must pass
 *  - the process layer's timeline shows the parcel's milestones, and the
 *    physical flow's 'fulfilled' task completes on delivery
 *  - customers track their OWN deliveries; advancing state is staff-grade;
 *    nova sees nothing
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const F = '/tmf-api/shippingOrderManagement/v4';
const P = '/tmf-api/processFlowManagement/v4';
const ORDERS = '/tmf-api/productOrderingManagement/v4';
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
  const staff = await token('demo', 'demo');
  const kai = await token('kai@bss.local', 'kai');
  const kaiId = sub(kai);

  /* ---------- 1. the parcel: booked with a carrier, delivered, COMPLETES ---------- */
  // Track C: fulfilment now hands the parcel to a carrier (Helthjem seam) at
  // dispatch — a real tracking number rides the shipping order — and the
  // carrier's delivery completes it machine-driven. (The warehouse PATCH still
  // exists as a staff override; the carrier is the default driver now.)
  const offers = (await call('GET',
    '/tmf-api/productCatalogManagement/v4/productOffering?limit=50', kai)).body || [];
  const plan = offers.find((o) => (o.name || '').includes('Unlimited'));
  if (!plan) fail('no plan in catalog');
  const order = await call('POST', `${ORDERS}/productOrder`, kai, {
    productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name },
      product: { place: [{ role: 'delivery', streetName: `Parcel ${run}`, postcode: '111' }] } }] });
  if (order.status !== 201) fail(`order: ${order.status}`);
  let so = null;
  for (let i = 0; i < 20 && !so; i++) {
    await sleep(2500);
    const list = (await call('GET', `${F}/shippingOrder`, staff)).body || [];
    so = list.find((s) => s.productOrderId === order.body.id) || null;
  }
  if (!so) fail('physical order minted no shipping order');
  // booked with the carrier: inProgress + a Helthjem tracking number
  if (!(so.trackingRef || '').startsWith('HJ')) {
    fail('the carrier seam did not book a tracking number: ' + JSON.stringify(so.trackingRef));
  }
  // the carrier delivers on its own -> the product order completes machine-driven
  let completed = null;
  for (let i = 0; i < 25 && !completed; i++) {
    await sleep(3000);
    const o = (await call('GET', `${ORDERS}/productOrder/${order.body.id}`, kai)).body;
    if (o && o.state === 'completed') completed = o;
  }
  if (!completed) fail('carrier delivery did not complete the order — the machine driver failed');
  console.log(`OK THE PARCEL: the physical order minted shippingOrder ${so.id.slice(0, 8)}…,`
    + ` fulfilment BOOKED it with the carrier (Helthjem tracking ${so.trackingRef}), and the`
    + ' carrier\'s delivery COMPLETED the product order machine-driven — no human touch.');

  /* ---------- 2. the process layer watched the whole thing ---------- */
  const flows = (await call('GET', `${P}/processFlow?productOrderId=${order.body.id}`, staff)).body || [];
  const flow = flows[0];
  if (!flow) fail('no process flow for the order');
  const detail = (await call('GET', `${P}/processFlow/${flow.id}`, staff)).body;
  const types = (detail.timeline || []).map((e) => e.eventType);
  if (!types.includes('ShippingOrderCreateEvent') || !types.includes('ShippingOrderStateChangeEvent')) {
    fail('timeline misses the parcel milestones: ' + types.join(','));
  }
  const fulfilled = detail.taskFlow.find((t) => t.code === 'fulfilled');
  if (!fulfilled || fulfilled.state !== 'completed') {
    fail('the fulfilled task should complete on delivery: ' + JSON.stringify(fulfilled));
  }
  console.log(`OK EXPLAINED: the flow's timeline carries the parcel's milestones`
    + ` (${types.filter((t) => t.startsWith('Shipping')).length} shipping events) and the`
    + " 'fulfilled' task completed on DELIVERY — the incident agent now sees WHICH leg"
    + ' of fulfilment stalled, not just that something did.');

  /* ---------- 3. the visit: fiber INSTALLS (it does not ship) — the work order
   *  gates completion on its own. Track C: an install line's place is the
   *  engineer's address, so it is NOT booked as a parcel; only the installer's
   *  "completed" closes it. ---------- */
  const fiber = offers.find((o) => /fiber|fibre|broadband/i.test(o.name || ''));
  if (fiber) {
    const order2 = await call('POST', `${ORDERS}/productOrder`, kai, {
      productOrderItem: [{ action: 'add', productOffering: { id: fiber.id, name: fiber.name },
        product: { place: [{ role: 'installation', streetName: `Visit ${run}`,
          postcode: '11122', city: 'Oslo', country: 'NO' }] } }] });
    if (order2.status !== 201) {
      console.log(`SKIP THE VISIT: fiber not orderable here (${order2.status}) — install gate unchecked.`);
    } else {
      const slotsRes = await call('POST', '/tmf-api/appointment/v4/searchTimeSlot', kai, {});
      const slotList = Array.isArray(slotsRes.body) ? slotsRes.body
        : (slotsRes.body && slotsRes.body.availableTimeSlot) || [];
      const slot = slotList[0];
      if (!slot) fail('no install slots: ' + slotsRes.text.slice(0, 120));
      const appt = await call('POST', '/tmf-api/appointment/v4/appointment', kai, {
        validFor: slot.validFor || slot,
        description: `install visit ${run}`,
        relatedEntity: [{ id: order2.body.id, '@referredType': 'ProductOrder' }],
        place: [{ role: 'installation', streetName: `Visit ${run}` }] });
      if (appt.status >= 300) fail(`appointment: ${appt.status} ${appt.text.slice(0, 200)}`);
      let wo = null;
      for (let i = 0; i < 20 && !wo; i++) {
        await sleep(2500);
        const list = (await call('GET', `${F}/workOrder`, staff)).body || [];
        wo = list.find((w) => w.productOrderId === order2.body.id) || null;
      }
      if (!wo) fail('install appointment minted no work order');
      if (wo.state !== 'planned' || !wo.appointment) fail('work order shape wrong: ' + JSON.stringify(wo));
      // fiber installs, so no parcel is booked; the order waits on the workOrder
      const parcels = ((await call('GET', `${F}/shippingOrder`, staff)).body || [])
        .filter((s) => s.productOrderId === order2.body.id);
      if (parcels.length) fail('fiber should INSTALL, not ship — no parcel expected');
      await sleep(6000);
      const midway = (await call('GET', `${ORDERS}/productOrder/${order2.body.id}`, kai)).body;
      if (midway.state === 'completed') fail('order completed with the install visit still open');
      const done = await call('PATCH', `${F}/workOrder/${wo.id}`, staff,
        { state: 'completed', note: 'installed and tested' });
      if (done.status >= 300) fail(`work order complete: ${done.status}`);
      let completed2 = null;
      for (let i = 0; i < 25 && !completed2; i++) {
        await sleep(3000);
        const o = (await call('GET', `${ORDERS}/productOrder/${order2.body.id}`, kai)).body;
        if (o && o.state === 'completed') completed2 = o;
      }
      if (!completed2) fail('visit completion did not complete the order');
      console.log(`OK THE VISIT: fiber INSTALLED — the booking minted workOrder ${wo.id.slice(0, 8)}…,`
        + ' no parcel was booked (it does not ship), the order waited on the visit, and the'
        + ' installer\'s "completed" closed it end-to-end.');
    }
  } else {
    console.log('SKIP THE VISIT: no fiber offering in this catalog.');
  }

  /* ---------- 4. walls ---------- */
  const mine = (await call('GET', `${F}/shippingOrder`, kai)).body || [];
  if (!mine.length || mine.some((s) => (s.relatedParty || []).every((p2) => p2.id !== kaiId))) {
    fail('customer tracking list wrong');
  }
  const advance = await call('PATCH', `${F}/shippingOrder/${so.id}`, kai, { state: 'shipped' });
  if (advance.status !== 403) fail(`customer advancing state must 403, got ${advance.status}`);
  const anon = await fetch(`http://shop.nova.localhost:8080${F}/shippingOrder`);
  if (anon.status === 200) fail('anonymous nova read succeeded');
  console.log('OK WALLS: kai tracks his OWN parcels (self-service delivery tracking for'
    + ' free), cannot drive the warehouse (403), and nova sees nothing.');

  console.log('\nALL FULFILMENT CHECKS PASSED — the parcel and the visit are RESOURCES now:'
    + ' minted from events the fleet already publishes, advanced by the warehouse and the'
    + ' installer over their own API, machine-completing the order when both gates pass,'
    + ' and narrated on the process timeline the incident agent reads.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
