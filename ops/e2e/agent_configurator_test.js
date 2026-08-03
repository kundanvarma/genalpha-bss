/* The agent channel becomes a configurator client (TMF760 P2). Suite #78.
 *
 *  - YESTERDAY'S GAP, proven first: a bundle bought flat through ACP
 *    (id + quantity, no picks) is REFUSED by ordering's cardinality gate —
 *    the agent channel literally could not sell a configured bundle
 *  - configure_product's HTTP path: rejected picks answer with the
 *    configurator's own messages; approved picks answer with the price
 *    and an ORDER-READY configuration
 *  - an ACP session with a configuration is validated and priced by the
 *    configurator (never by the cart), and complete places a TMF622 order
 *    with nested children — colour and storage riding the phone item,
 *    exactly the shape the storefront submits
 *  - the delegated-token model is unchanged: the same RFC 8693 exchange,
 *    the same idempotent complete
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = '/tmf-api/productCatalogManagement/v4';
const CFG = '/tmf-api/productConfigurationManagement/v5';
const fail = (m) => { throw new Error(m); };

async function form(data) {
  const res = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(data) });
  if (!res.ok) fail(`token endpoint ${res.status}: ${await res.text()}`);
  return res.json();
}
async function call(method, path, { body, headers } = {}) {
  const res = await fetch(API + path, { method,
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(headers || {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await res.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: res.status, body: json, text };
}
const near = (a, b) => Math.abs(Number(a) - Number(b)) < 0.005;

(async () => {
  /* ---------- 0. the space, as the agent tool reads it ---------- */
  const offerings = await (await fetch(
    `${API}${CAT}/productOffering?name=${encodeURIComponent('GenAlpha Family Max')}`)).json();
  const bundle = offerings.find((o) => o.name === 'GenAlpha Family Max')
    || fail('GenAlpha Family Max not in the catalog');
  const q = await call('POST', `${CFG}/queryProductConfiguration`,
    { body: { productConfiguration: { productOffering: { id: bundle.id } } } });
  const space = q.body.computedProductConfigurationItem[0];
  const group = (name) => space.choiceGroup.find((g) => g.name === name);
  const opt = (g, name) => g.option.find((o) => o.name === name);
  const m50 = opt(group('Family lines — how many do you need?'), 'GenAlpha Mobile 50 GB');
  const samsung = opt(group('Choose your phone'), 'Samsung Galaxy S26');

  /* ---------- 1. yesterday's gap, demonstrated ---------- */
  const flat = await call('POST', '/acp/checkout_sessions',
    { body: { items: [{ id: bundle.id, quantity: 1 }] } });
  if (flat.status !== 201) fail(`flat bundle session should open: ${flat.status}`);
  const kai = await form({ grant_type: 'password', client_id: 'bss-demo',
    username: 'kai@bss.local', password: 'kai' });
  const delegated = (await form({
    grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange',
    client_id: 'bss-agent', client_secret: 'agent-secret',
    subject_token: kai.access_token,
    subject_token_type: 'urn:ietf:params:oauth:token-type:access_token',
    requested_token_type: 'urn:ietf:params:oauth:token-type:access_token',
  })).access_token;
  const flatDone = await call('POST', `/acp/checkout_sessions/${flat.body.id}/complete`, {
    body: { payment_data: { token: 'spt_agent_demo_token' } },
    headers: { Authorization: `Bearer ${delegated}`, 'Idempotency-Key': `flat-${flat.body.id}` },
  });
  if (flatDone.status < 400) fail('a flat bundle purchase should be REFUSED, got ' + flatDone.status);
  if (!flatDone.text.includes('selection(s)')) {
    fail('the refusal should be the cardinality gate: ' + flatDone.text.slice(0, 200));
  }
  console.log('OK THE GAP WAS REAL: a bundle bought flat (id + quantity, the only shape the'
    + ' agent channel had) is refused by ordering\'s cardinality gate — "'
    + (flatDone.body.message || '').slice(0, 90) + '…". An agent could not sell this yesterday.');

  /* ---------- 2. the configurator says no with reasons, yes with a price ---------- */
  const badCheck = await call('POST', `${CFG}/checkProductConfiguration`,
    { body: { checkProductConfigurationItem: [{ productConfiguration: {
      productOffering: { id: bundle.id },
      selectedOption: group('Family lines — how many do you need?').option.map((o) => ({ id: o.id })),
    } }] } });
  const badItem = badCheck.body.checkProductConfigurationItem[0];
  if (badItem.state !== 'rejected' || !(badItem.message || []).length) {
    fail('over-picked lines should reject with messages');
  }
  const configuration = {
    selectedOption: [{ id: m50.id }, { id: samsung.id }],
    configurationCharacteristic: [{ name: 'color', value: 'Titanium Edition' },
      { name: 'storage', value: '512GB' }],
  };
  const goodCheck = await call('POST', `${CFG}/checkProductConfiguration`,
    { body: { checkProductConfigurationItem: [{ productConfiguration: {
      productOffering: { id: bundle.id }, ...configuration } }] } });
  const goodItem = goodCheck.body.checkProductConfigurationItem[0];
  if (goodItem.state !== 'approved') fail('valid picks should approve: ' + JSON.stringify(goodItem.message));
  const monthly = Number(goodItem.configurationPrice.monthlyTotal.value);
  const onceOff = Number(goodItem.configurationPrice.oneTimeTotal.value);
  const echoed = goodItem.productConfiguration;
  const phoneEcho = echoed.selectedOption.find((o) => o.id === samsung.id);
  if (!(phoneEcho.characteristic || []).some((c) => c.name === 'color' && c.value === 'Titanium Edition')) {
    fail('the order-ready echo should put colour on the PHONE');
  }
  const planEcho = echoed.selectedOption.find((o) => o.id === m50.id);
  if ((planEcho.characteristic || []).length) fail('the plan must not carry the phone\'s picks');
  console.log(`OK CONFIGURE_PRODUCT: rejected picks answer with the configurator's reasons`
    + ` ("${badItem.message[0].slice(0, 60)}…"); approved picks answer ${monthly}/mo +`
    + ` ${onceOff} due now AND an order-ready echo — colour and storage on the Samsung,`
    + ' nothing on the plan.');

  /* ---------- 3. the configured session: priced by the configurator ---------- */
  const badSession = await call('POST', '/acp/checkout_sessions',
    { body: { items: [{ id: bundle.id, quantity: 1,
      configuration: { selectedOption: [{ id: m50.id }] } }] } });
  if (badSession.status !== 400 || !badSession.text.includes("'Choose your phone'")) {
    fail(`an invalid configuration must 400 with the reason: ${badSession.status} ${badSession.text.slice(0, 150)}`);
  }
  const session = await call('POST', '/acp/checkout_sessions',
    { body: { items: [{ id: bundle.id, quantity: 1, configuration }] } });
  if (session.status !== 201) fail(`configured session: ${session.status} ${session.text.slice(0, 300)}`);
  const line = session.body.line_items[0];
  if (!near(line.unit_price.amount, monthly) || line.price_type !== 'recurring') {
    fail(`the session line should carry the configurator's monthly ${monthly}, got ${line.unit_price.amount}`);
  }
  if (!near(line.due_now.amount, onceOff)) fail('due-now should be the one-time total');
  const total = session.body.totals.find((t) => t.type === 'total');
  if (!near(total.amount, onceOff)) fail('session total should be the due-now charges');
  console.log(`OK THE SESSION: the configured bundle priced by the CONFIGURATOR, not the cart —`
    + ` ${line.unit_price.amount}/mo recurring (bills on the first invoice), ${total.amount}`
    + ` due now; an invalid pick set never opened a session at all, refused in the`
    + ' configurator\'s words.');

  /* ---------- 4. complete: a real order with nested children ---------- */
  const done = await call('POST', `/acp/checkout_sessions/${session.body.id}/complete`, {
    body: { payment_data: { token: 'spt_agent_demo_token' } },
    headers: { Authorization: `Bearer ${delegated}`, 'Idempotency-Key': `cfg-${session.body.id}` },
  });
  if (done.status !== 200) fail(`complete: ${done.status} ${done.text.slice(0, 300)}`);
  const orderId = done.body.order && done.body.order.id;
  if (!orderId) fail('no order on the completed session');
  const order = await call('GET',
    `/tmf-api/productOrderingManagement/v4/productOrder/${orderId}`,
    { headers: { Authorization: `Bearer ${kai.access_token}` } });
  if (order.status !== 200) fail(`kai cannot read his own order: ${order.status}`);
  if (order.body.category !== 'agenticCommerce') fail('the channel marker is missing');
  const bundleItem = (order.body.productOrderItem || [])
    .find((i) => i.productOffering && i.productOffering.id === bundle.id);
  if (!bundleItem) fail('the order misses the bundle item');
  const children = bundleItem.productOrderItem || [];
  if (children.length !== 2) fail(`expected 2 nested children, got ${children.length}`);
  const phoneChild = children.find((c) => c.productOffering.id === samsung.id);
  const picks = ((phoneChild.product || {}).productCharacteristic || []);
  if (!picks.some((c) => c.name === 'color' && c.value === 'Titanium Edition')
      || !picks.some((c) => c.name === 'storage' && c.value === '512GB')) {
    fail('the phone child lost its picks: ' + JSON.stringify(picks));
  }
  console.log(`OK THE ORDER: complete placed ${orderId} through the SAME cardinality gate that`
    + ' refused the flat purchase — the bundle item carries nested children, the Samsung child'
    + ' carries color=Titanium Edition + storage=512GB (billing rates the premium from exactly'
    + ' these), and kai reads the order with his own eyes. Category: agenticCommerce.');

  console.log('\nALL AGENT-CONFIGURATOR CHECKS PASSED — the agent channel is a configurator'
    + ' client: what it could not sell yesterday it configures, prices, validates and orders'
    + ' today, with the TMF760 check as the single authority every channel shares.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
