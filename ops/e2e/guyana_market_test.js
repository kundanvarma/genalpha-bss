/* Guyana market adaptations, proven on the enet tenant (suite #113). Every check
 * is a TENANT fact the platform answers by configuration or a named seam —
 * nothing here is a Guyana fork:
 *  - the tenant manifest carries country, whole-dollar pricing, price note, SIM registration
 *  - addresses validate on the GPOC 7-digit code; other countries are "not served"
 *  - the delivery menu offers collection at the operator's stores (region-filtered)
 *  - MMG mobile money is a real redirect PSP (session → approve → confirm); PayPal is gone
 *  - porting a +592 number follows the PUC rule (1 business day, Porting XS)
 *  - a WhatsApp message leaves through the tenant's own Business line
 *  - the catalog is ENet's published lineup at published prices
 */
const API = 'http://localhost:8080';
const HOST = 'http://shop.enet.localhost:8080';
const KC = 'http://localhost:8085/realms/enet/protocol/openid-connect/token';
const run = Date.now();
const fail = (m) => { throw new Error(m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body, base = API) {
  const r = await fetch(base + path, { method, headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
(async () => {
  const staff = await token('demo', 'demo');
  const devi = await token('devi@enet.example', 'devi');

  /* 1. the manifest is the tenant's */
  const cfg = await (await fetch(`${HOST}/app/tenant-config.json`)).json();
  for (const [k, v] of Object.entries({ country: 'GY', currency: 'GYD', timezone: 'America/Guyana', priceDecimals: 0, currencyDisplay: 'narrowSymbol', simRegistration: 'required' })) {
    if (cfg[k] !== v) fail(`manifest ${k}: ${cfg[k]} != ${v}`);
  }
  if (!/VAT/.test(cfg.priceNote || '')) fail('no statutory price note');
  console.log(`  manifest: ${cfg.brandName} · ${cfg.country}/${cfg.currency} · ${cfg.timezone} · decimals ${cfg.priceDecimals} · SIM registration ${cfg.simRegistration}`);

  /* 2. addresses: GPOC 7-digit code; anything else is honest */
  const V = '/tmf-api/geographicAddressManagement/v4/geographicAddressValidation';
  const addr = (postCode, country) => ({ submittedGeographicAddress: { street1: 'Lot 12 Camp Street', postCode, city: 'Georgetown', country } });
  const ok = await call('POST', V, null, addr('4131519', 'GY'), HOST);
  if (ok.body?.validationResult !== 'success') fail(`GY address rejected: ${ok.text.slice(0, 120)}`);
  if ((await call('POST', V, null, addr('59201', 'GY'), HOST)).body?.validationResult !== 'failed') fail('5-digit GY code accepted');
  if (!/not served/.test((await call('POST', V, null, addr('0150', 'NO'), HOST)).body?.validationReason || '')) fail('Norway served by enet');
  console.log('  address: 7-digit GPOC code accepted, short code refused, NO not served');

  /* 3. delivery: collection at the operator's stores, region-filtered */
  const menu = await call('GET', '/tmf-api/shippingOrderManagement/v4/carrier/deliveryOptions?postcode=4131519', null, null, HOST);
  const pickup = (menu.body || []).find((o) => o.method === 'pickupPoint');
  if (!pickup) fail(`no pickup option: ${menu.text.slice(0, 160)}`);
  const pts = pickup.points || [];
  if (!pts.some((p) => /Camp Street/.test(p.name))) fail(`Camp Street store missing from pickup points: ${JSON.stringify(pts).slice(0, 200)}`);
  const berbice = await call('GET', '/tmf-api/shippingOrderManagement/v4/carrier/deliveryOptions?postcode=6010101', null, null, HOST);
  const bpts = ((berbice.body || []).find((o) => o.method === 'pickupPoint') || {}).points || [];
  if (!bpts.some((p) => /Port Mourant/.test(p.name)) || bpts.some((p) => /Camp Street/.test(p.name))) fail('region filter wrong for Berbice');
  console.log(`  delivery: ${pts.length} Georgetown-region stores, Berbice shows Port Mourant only`);

  /* 4. payments: MMG is a redirect PSP; PayPal is not offered */
  const methods = (await call('GET', '/tmf-api/paymentManagement/v4/payment/methods', devi)).body || [];
  if (!methods.some((m) => m.method === 'mmg' && m.redirect)) fail(`mmg missing: ${JSON.stringify(methods)}`);
  if (methods.some((m) => m.method === 'paypal')) fail('PayPal still offered in Guyana');
  const session = await call('POST', '/tmf-api/paymentManagement/v4/payment/session', devi, { method: 'mmg', amount: { value: 1000, unit: 'GYD' }, returnUrl: 'http://shop.enet.localhost:8080/shop/cart' });
  if (session.status >= 300 || !session.body?.sessionId) fail(`mmg session: ${session.status} ${session.text.slice(0, 160)}`);
  const confirm = await call('POST', '/tmf-api/paymentManagement/v4/payment/confirm', devi, { provider: 'mmg', sessionId: session.body.sessionId });
  if (confirm.status >= 300) fail(`mmg confirm: ${confirm.status} ${confirm.text.slice(0, 160)}`);
  console.log(`  payments: methods ${methods.map((m) => m.method).join('/')} · MMG session ${session.body.sessionId} confirmed → ${confirm.body?.status || confirm.status}`);

  /* 5. porting: +592 goes to the Guyanese clearinghouse under the PUC rule */
  const me = (await call('GET', '/tmf-api/party/v4/individual?limit=1', devi)).body?.[0];
  if (!me) fail('devi has no party');
  const portIn = (phoneNumber, otherOperator) => call('POST', '/tmf-api/numberPortingManagement/v1/numberPortingOrder', devi,
    { direction: 'portIn', phoneNumber, country: 'GY', otherOperator, relatedParty: [{ id: me.id, role: 'customer' }] });
  const port = await portIn('+5926391234', 'One Communications');
  if (port.status >= 300) fail(`GY port-in refused: ${port.status} ${port.text.slice(0, 160)}`);
  if (port.body.status !== 'scheduled' || port.body.clearinghouse !== 'portingxs') fail(`GY port not scheduled via Porting XS: ${JSON.stringify(port.body).slice(0, 200)}`);
  const bad = await portIn('+59212345', 'x');
  if (bad.body?.status !== 'rejected') fail(`malformed +592 number not rejected: ${JSON.stringify(bad.body).slice(0, 160)}`);
  console.log(`  porting: +592 639 1234 scheduled via ${port.body.clearinghouse} (${port.body.regulator}), cutover ${String(port.body.scheduledCutover).slice(0, 16)}; short number rejected`);

  /* 6. WhatsApp: a message leaves through the tenant's Business line */
  await call('PATCH', `/tmf-api/party/v4/individual/${me.id}`, devi, { contactMedium: [{ mediumType: 'phone', preferred: true, characteristic: { phoneNumber: '+592 710 1234' } }] });
  const msg = await call('POST', '/tmf-api/communicationManagement/v4/communicationMessage', staff, { messageType: 'whatsapp', subject: `Install ${run}`, content: `Your ENet technician is booked (${run}).`, relatedParty: [{ id: me.id, role: 'customer', '@referredType': 'Individual' }], sender: { name: 'ENet' } });
  if (msg.status >= 300) fail(`whatsapp message: ${msg.status} ${msg.text.slice(0, 160)}`);
  let sent = null;
  for (let i = 0; i < 10 && !sent; i++) { await sleep(1000); const out = await (await fetch('http://localhost:8153/outbox?to=5927101234')).json(); sent = out.find((m) => (m.body || '').includes(String(run))); }
  if (!sent) fail('WhatsApp mock never received the message');
  console.log(`  whatsapp: ${sent.id} to ${sent.to} via phone-id ${sent.phoneId}`);

  /* 7. the catalog is ENet's published lineup */
  const offers = (await call('GET', '/tmf-api/productCatalogManagement/v4/productOffering?limit=98', null, null, HOST)).body || [];
  // the gateway edge-caches catalog lists (browse cache); an off-by-one limit is a key the shop never uses, so it reads through
  const prices = Object.fromEntries(((await call('GET', '/tmf-api/productCatalogManagement/v4/productOfferingPrice?limit=99', null, null, HOST)).body || []).map((p) => [p.id, p]));
  const priceOf = (name) => { const o = offers.find((x) => x.name === name); return o && (o.productOfferingPrice || []).map((r) => prices[r.id]).find((p) => p && p.priceType === 'recurring')?.price?.value; };
  for (const [name, v] of [['Orange 30 Days 5G', 3500], ['Orange 30 Days Extra 5G', 5000], ['OnFiber 300', 8900], ['OnFiber 1 Gig', 26300], ['DreamTV Wireless Ultimate', 5400]]) {
    if (priceOf(name) !== v) fail(`${name}: ${priceOf(name)} != ${v}`);
  }
  for (const stale of ['ENet Prepaid 50 GB', 'OnFiber 350', 'ENet TV Complete']) if (offers.some((o) => o.name === stale)) fail(`stale offer still on sale: ${stale}`);
  console.log(`  catalog: ${offers.length} offers, published prices hold, no invented names`);
  /* 8. VAT is per PRICE (TMF620 tax): residential internet data zero-rated, the rest 14% */
  const priceList = Object.values(prices);
  const rateOf = (name) => ((priceList.find((p) => p.name === name) || {}).tax || [{}])[0].taxRate;
  if (rateOf('OnFiber 300 monthly') !== 0) fail(`OnFiber 300 should be zero-rated, got ${rateOf('OnFiber 300 monthly')}`);
  if (rateOf('Orange 30 Days 5G monthly') !== 14) fail(`Orange 30 Days 5G should carry 14%, got ${rateOf('Orange 30 Days 5G monthly')}`);
  if (rateOf('SIM activation') !== 14) fail('SIM activation should carry 14%');
  console.log('  vat: fibre monthly 0% (zero-rated), mobile plans and one-time charges 14%');

  /* 9. delivery tiers are the carrier config's: the interior is days by boat or air */
  const interior = await call('GET', '/tmf-api/shippingOrderManagement/v4/carrier/deliveryOptions?postcode=8010101', null, null, HOST);
  const homeInterior = (interior.body || []).find((o) => o.method === 'home');
  if (!homeInterior || !/boat|air/.test(homeInterior.eta || '')) fail(`interior ETA missing: ${JSON.stringify(homeInterior)}`);
  const homeCoast = ((await call('GET', '/tmf-api/shippingOrderManagement/v4/carrier/deliveryOptions?postcode=4131519', null, null, HOST)).body || []).find((o) => o.method === 'home');
  if (!/same day/.test(homeCoast?.eta || '')) fail(`Georgetown ETA wrong: ${homeCoast?.eta}`);
  console.log(`  delivery tiers: Georgetown "${homeCoast.eta}" · Region 8 "${homeInterior.eta}"`);

  /* 10. the power reality has a product, and the brand is the operator's own logo */
  const battery = offers.find((o) => o.name === 'ONT Battery Backup');
  if (!battery) fail('ONT Battery Backup missing');
  const stock = (await call('GET', `/tmf-api/productStockManagement/v4/productStock?productOfferingId=${battery.id}`, null, null, HOST)).body || [];
  if (!stock.length) fail('battery backup not stocked');
  const logo = await fetch(`${HOST}/tmf-api/documentManagement/v4/document/brand-logo`);
  if (!logo.ok || !/image\/png/.test(logo.headers.get('content-type') || '')) fail(`brand logo not served as PNG: ${logo.status} ${logo.headers.get('content-type')}`);
  console.log(`  power + brand: battery backup stocked (${stock[0].availableQuantity?.amount}), PNG logo served`);
  /* 11. the shop window and the front door are the tenant's too */
  const banners = await (await fetch(`${HOST}/tmf-api/documentManagement/v4/document/banners`)).json();
  if (!Array.isArray(banners) || banners.length < 1) fail(`no banners: ${JSON.stringify(banners).slice(0, 120)}`);
  if (!banners.every((b) => b.attachmentUrl && b.link)) fail('banner without image or destination');
  const img = await fetch(`${HOST}${banners[0].attachmentUrl}`);
  if (!img.ok || !/image\//.test(img.headers.get('content-type') || '')) fail('banner image not served publicly');
  for (const k of ['supportWhatsapp', 'supportPhone', 'privacyUrl']) if (!cfg[k]) fail(`manifest lacks ${k}`);
  console.log(`  shop window: ${banners.length} banners (first → ${banners[0].link}); front door WhatsApp ${cfg.supportWhatsapp}`);
  console.log('PASS guyana_market_test');
  process.exit(0); // keep-alive sockets must not hold the runner open
})().catch((e) => { console.error('FAIL', e.message); process.exit(1); });
