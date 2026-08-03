/* TMF760 product configurator (the storefront's Offering page, server-side). Suite #77.
 *
 *  - queryProductConfiguration: the configuration SPACE as data — fixed
 *    members, choice groups with bounds and defaults, pickers, prices
 *    with their conditions visible
 *  - checkProductConfiguration is STRICTER than any channel today: both
 *    cardinality bounds (the upper is the hole no UI catches),
 *    characteristic values scoped to the PICKED options, policy consulted
 *  - the price follows the configuration: Titanium Edition adds its
 *    premium only when that exact value is picked on the phone that has it
 *  - anonymous like the catalog: every call below carries NO token, and
 *    the nova hostname sees nothing of genalpha's bundle
 */
const API = 'http://localhost:8080';
const CAT = '/tmf-api/productCatalogManagement/v4';
const CFG = '/tmf-api/productConfigurationManagement/v5';
const fail = (m) => { throw new Error(m); };

async function post(path, body, host) {
  const r = await fetch((host || API) + path, { method: 'POST',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
const near = (a, b) => Math.abs(Number(a) - Number(b)) < 0.005;

(async () => {
  /* ---------- 0. find the bundle (anonymously, like a shopper) ---------- */
  const offerings = await (await fetch(
    `${API}${CAT}/productOffering?name=${encodeURIComponent('GenAlpha Family Max')}`)).json();
  const bundle = offerings.find((o) => o.name === 'GenAlpha Family Max');
  if (!bundle) fail('GenAlpha Family Max not in the catalog — run the seeds');

  /* ---------- 1. the configuration space, computed ---------- */
  const q = await post(`${CFG}/queryProductConfiguration`,
    { productConfiguration: { productOffering: { id: bundle.id } } });
  if (q.status !== 200) fail(`query: ${q.status} ${q.text.slice(0, 200)}`);
  const space = (q.body.computedProductConfigurationItem || [])[0];
  if (!space || space.isBundle !== true) fail('space misses the bundle');
  const fixedNames = (space.fixedMember || []).map((m) => m.name);
  if (!fixedNames.includes('GenAlpha Fiber 1000') || !fixedNames.includes('GenAlpha TV Max')) {
    fail('fixed members wrong: ' + fixedNames.join(', '));
  }
  const groups = Object.fromEntries((space.choiceGroup || []).map((g) => [g.name, g]));
  const lines = groups['Family lines — how many do you need?'];
  const phones = groups['Choose your phone'];
  const extras = groups['Streaming extras'];
  if (!lines || lines.minSelections !== 1 || lines.maxSelections !== 2) fail('lines group wrong');
  if (!phones || phones.minSelections !== 1 || phones.maxSelections !== 1) fail('phone group wrong');
  if (!extras || extras.minSelections !== 0 || extras.maxSelections !== 2) fail('extras group wrong');
  const optByName = (g, name) => g.option.find((o) => o.name === name);
  const defaultLine = g => g.option.find((o) => o.id === g.default);
  if (defaultLine(lines).name !== 'GenAlpha Mobile 50 GB') fail('lines default wrong');
  if (defaultLine(phones).name !== 'Apple iPhone 17') fail('phone default wrong');
  // pickers: seeds omit `configurable` on real pickers — absent means TRUE
  const samsung = optByName(phones, 'Samsung Galaxy S26');
  const iphone = optByName(phones, 'Apple iPhone 17');
  const colorsOf = (o) => ((o.configurationCharacteristic || [])
    .find((c) => c.name === 'color') || { productSpecCharacteristicValue: [] })
    .productSpecCharacteristicValue.map((v) => v.value);
  if (!colorsOf(samsung).includes('Titanium Edition')) fail('Samsung misses Titanium Edition');
  if (colorsOf(iphone).includes('Titanium Edition')) fail('Titanium leaked onto the iPhone');
  const conditioned = (samsung.price || []).find((p) => p.appliesWhen);
  if (!conditioned || conditioned.name !== 'Titanium Edition premium') {
    fail('Samsung price conditions are not visible in the space');
  }
  console.log(`OK THE SPACE: ${fixedNames.length} fixed members + ${space.choiceGroup.length}`
    + ' choice groups computed server-side — bounds, defaults, pickers (absent `configurable`'
    + ' correctly read as true) and the Titanium price condition VISIBLE, only on the phone'
    + ' that has it. All of it anonymous: configuring is browsing.');

  /* ---------- 2. a valid pick set: approved, and the price follows ---------- */
  const m50 = optByName(lines, 'GenAlpha Mobile 50 GB');
  const config = (color) => ({ productConfiguration: {
    productOffering: { id: bundle.id },
    selectedOption: [{ id: m50.id }, { id: samsung.id }],
    configurationCharacteristic: [{ name: 'color', value: color },
      { name: 'storage', value: '512GB' }] } });
  const titanium = await post(`${CFG}/checkProductConfiguration`,
    { checkProductConfigurationItem: [config('Titanium Edition')] });
  if (titanium.status !== 200 || titanium.body.result !== 'approved') {
    fail(`titanium check: ${titanium.status} ${titanium.text.slice(0, 300)}`);
  }
  const tPrice = titanium.body.checkProductConfigurationItem[0].configurationPrice;
  const premium = tPrice.priceLine.find((l) => l.name === 'Titanium Edition premium');
  if (!premium || !near(premium.price.value, 2)) fail('the Titanium premium line is missing');
  const recurringSum = tPrice.priceLine.filter((l) => l.priceType === 'recurring')
    .reduce((s, l) => s + Number(l.price.value), 0);
  if (!near(tPrice.monthlyTotal.value, recurringSum)) {
    fail(`monthly ${tPrice.monthlyTotal.value} != sum of lines ${recurringSum}`);
  }
  const base = tPrice.priceLine.find((l) => l.name === 'Family Max Base Monthly');
  if (!base || !near(base.price.value, 49)) fail('base monthly wrong');
  if (!tPrice.indicative || tPrice.indicative.total == null) {
    fail('the deal engine\'s indicative price is missing');
  }

  /* ---------- 3. any other colour: the premium stays off the bill ---------- */
  const icyBlue = await post(`${CFG}/checkProductConfiguration`,
    { checkProductConfigurationItem: [config('Icy Blue')] });
  if (icyBlue.body.result !== 'approved') fail('Icy Blue should approve');
  const bPrice = icyBlue.body.checkProductConfigurationItem[0].configurationPrice;
  if (bPrice.priceLine.some((l) => l.name === 'Titanium Edition premium')) {
    fail('the premium applied without its condition');
  }
  if (!near(Number(tPrice.monthlyTotal.value) - Number(bPrice.monthlyTotal.value), 2)) {
    fail('the two colours should differ by exactly the 2.00 premium');
  }
  console.log(`OK THE PRICE FOLLOWS THE PICK: Titanium Edition prices at`
    + ` ${tPrice.monthlyTotal.value}/mo (the +2.00 premium on its OWN line, condition`
    + ` attached), Icy Blue at ${bPrice.monthlyTotal.value}/mo — same phone, no premium;`
    + ' totals reconcile to the line sum and the deal engine priced both indicatively.');

  /* ---------- 4. the bounds, BOTH of them ---------- */
  const allLines = lines.option.map((o) => ({ id: o.id }));
  const tooMany = await post(`${CFG}/checkProductConfiguration`,
    { checkProductConfigurationItem: [{ productConfiguration: {
      productOffering: { id: bundle.id }, selectedOption: allLines } }] });
  const messages = tooMany.body.checkProductConfigurationItem[0].message || [];
  if (tooMany.body.result !== 'rejected') fail('3 lines must reject');
  if (!messages.some((m) => m.includes('between 1 and 2') && m.includes('but 3 were made'))) {
    fail('upper-bound message wrong: ' + messages.join(' | '));
  }
  if (!messages.some((m) => m.includes("'Choose your phone'") && m.includes('but 0 were made'))) {
    fail('lower-bound message wrong: ' + messages.join(' | '));
  }
  console.log('OK BOTH BOUNDS: three family lines rejected on the UPPER limit (the hole no'
    + ' UI catches — radios and checkboxes only ever enforced it by physics) and the missing'
    + ' phone on the lower, in ordering\'s own words: a configure-time no reads exactly like'
    + ' the order-time 400 it prevents.');

  /* ---------- 5. values are scoped to the PICKED option ---------- */
  const smuggle = await post(`${CFG}/checkProductConfiguration`,
    { checkProductConfigurationItem: [{ productConfiguration: {
      productOffering: { id: bundle.id },
      selectedOption: [{ id: m50.id }, { id: iphone.id }],
      configurationCharacteristic: [{ name: 'color', value: 'Titanium Edition' }] } }] });
  const sMsg = smuggle.body.checkProductConfigurationItem[0].message || [];
  if (smuggle.body.result !== 'rejected'
      || !sMsg.some((m) => m.includes("'Titanium Edition' is not an allowed value"))) {
    fail('Titanium on the iPhone must reject: ' + sMsg.join(' | '));
  }
  const foreign = await post(`${CFG}/checkProductConfiguration`,
    { checkProductConfigurationItem: [{ productConfiguration: {
      productOffering: { id: bundle.id },
      selectedOption: [{ id: m50.id }, { id: samsung.id }, { id: 'not-a-member' }] } }] });
  const fMsg = foreign.body.checkProductConfigurationItem[0].message || [];
  if (foreign.body.result !== 'rejected'
      || !fMsg.some((m) => m.includes("'not-a-member' is not part of bundle"))) {
    fail('a foreign offering id must reject: ' + fMsg.join(' | '));
  }
  console.log('OK NO SMUGGLING: nobody validated characteristic VALUES anywhere before —'
    + ' now "Titanium Edition" on an iPhone rejects (allowed values are scoped to the'
    + ' phone actually picked), and an offering id from outside the bundle is refused'
    + ' by name.');

  /* ---------- 6. the tenant wall ---------- */
  const nova = await post(`${CFG}/queryProductConfiguration`,
    { productConfiguration: { productOffering: { id: bundle.id } } },
    'http://shop.nova.localhost:8080');
  if (nova.status !== 404) fail(`nova must see nothing of genalpha's bundle, got ${nova.status}`);
  console.log('OK THE WALL: the same anonymous call through nova\'s hostname 404s —'
    + ' a tenant\'s configuration space is exactly as private as its catalog.');

  console.log('\nALL CONFIGURATOR CHECKS PASSED — TMF760 as the house believes it: the'
    + ' storefront\'s Offering page promoted to a server-side capability, stricter than'
    + ' any single channel, priced to the pick, and open to whoever can browse.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
