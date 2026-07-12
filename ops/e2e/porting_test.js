/* Number porting (keep your number). A Norwegian customer ports their number
 * in through NRDB (the national clearinghouse), the cutover completes, and
 * when they order a mobile plan the orchestrator activates on the PORTED
 * number instead of drawing a fresh one from the pool. A bad number is
 * rejected by the clearinghouse; country rules apply. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const PORT = `${API}/tmf-api/numberPortingManagement/v1`;
const run = Date.now();

async function staffToken(request) {
  const res = await request.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };
  const token = await staffToken(ctx.request);
  const H = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };
  const party = `porttest-${run}`;
  const number = '+4790' + String(run).slice(-6);

  // 1. The clearinghouse rejects a malformed number (country rules apply).
  const bad = await (await ctx.request.post(`${PORT}/numberPortingOrder`, {
    headers: H, data: { direction: 'portIn', phoneNumber: '12345', country: 'NO', otherOperator: 'OtherTelco' },
  })).json();
  if (bad.status !== 'rejected' || !bad.rejectReason) fail('bad number should be rejected: ' + JSON.stringify(bad));
  console.log('OK a malformed number is rejected by the clearinghouse:', bad.clearinghouse, '·', bad.regulator);

  // 2. A valid Norwegian port-in is validated and scheduled by the clearinghouse.
  const created = await ctx.request.post(`${PORT}/numberPortingOrder`, {
    headers: H,
    data: { direction: 'portIn', phoneNumber: number, country: 'NO', otherOperator: 'OtherTelco',
            relatedParty: [{ id: party, role: 'customer' }] },
  });
  if (created.status() !== 201) fail('port-in create failed: ' + created.status());
  const port = await created.json();
  if (port.status !== 'scheduled' || !port.scheduledCutover) fail('port-in not scheduled: ' + JSON.stringify(port));
  console.log('OK port-in scheduled via', port.clearinghouse, '— cutover', port.scheduledCutover.slice(0, 16),
    '· regulator', port.regulator);

  // 3. Before cutover, the number is not yet the customer's.
  let ported = await (await ctx.request.get(`${PORT}/portedNumber?relatedPartyId=${party}`, { headers: H })).json();
  if (ported.phoneNumber) fail('number should not be available before cutover');

  // 4. Cutover completes.
  const done = await ctx.request.post(`${PORT}/numberPortingOrder/${port.id}/complete`, { headers: H });
  if ((await done.json()).status !== 'completed') fail('cutover did not complete');
  ported = await (await ctx.request.get(`${PORT}/portedNumber?relatedPartyId=${party}`, { headers: H })).json();
  if (ported.phoneNumber !== number) fail('ported number not available after cutover');
  console.log('OK cutover completed — the number is now the customer\'s');

  // 5. Order a mobile plan → the orchestrator activates on the PORTED number.
  const offers = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=50`, { headers: H })).json();
  const plan = offers.find((o) => (o.name || '').includes('Unlimited'));
  await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H, data: { productOrderItem: [{ action: 'add', productOffering: { id: plan.id } }],
      relatedParty: [{ id: party, role: 'customer' }] },
  });
  let kept = false;
  for (let attempt = 0; attempt < 20 && !kept; attempt++) {
    await new Promise((r) => setTimeout(r, 2000));
    const services = await (await ctx.request.get(
      `${API}/tmf-api/serviceInventory/v4/service?relatedPartyId=${party}`, { headers: H })).json();
    kept = services.some((s) => (s.supportingResource || []).some((r) => r.value === number));
  }
  if (!kept) fail('service did not activate on the ported number');
  console.log('OK the plan activated on the ported number', number, '— they kept their number, no pool draw');

  // 6. The channel option: a customer keeps their number from the storefront.
  const page = await ctx.newPage();
  const run2 = Date.now();
  const keepNum = '+4790' + String(run2).slice(-6);
  await page.goto('http://localhost:8080/shop/');
  await page.locator('text=Sign in').first().click();
  await page.waitForSelector('a[href*="registration"], input[name="username"]', { timeout: 20000 });
  await page.click('a[href*="registration"]');
  await page.waitForSelector('input[name="email"]');
  await page.fill('input[name="firstName"]', 'Keep'); await page.fill('input[name="lastName"]', 'Number');
  await page.fill('input[name="email"]', `keepnum-${run2}@example.com`);
  await page.fill('input[name="password"]', 'Passw0rd!'); await page.fill('input[name="password-confirm"]', 'Passw0rd!');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.hero', { timeout: 30000 });
  await page.locator('.card:has(h2:text-is("GenAlpha Mobile Unlimited 5G"))').first().click();
  await page.waitForSelector('.pricetable', { timeout: 15000 });
  await page.click('button.primary.big');
  await page.waitForURL('**/cart');
  await page.getByText('Keep my current number (port it in)').click();
  await page.fill('input[name="portNumber"]', keepNum);
  await page.fill('input[name="portProvider"]', 'OtherTelco');
  await page.click('button.primary.big');
  await page.waitForURL('**/orders', { timeout: 30000 });
  const custToken = await page.evaluate(() => sessionStorage.getItem('bss.shop.token'));
  let keptUi = false;
  for (let attempt = 0; attempt < 25 && !keptUi; attempt++) {
    await new Promise((r) => setTimeout(r, 2500));
    const services = await (await ctx.request.get(`${API}/tmf-api/serviceInventory/v4/service`,
      { headers: { Authorization: 'Bearer ' + custToken } })).json();
    keptUi = services.some((s) => (s.supportingResource || []).some((r) => r.value === keepNum));
  }
  if (!keptUi) fail('storefront keep-your-number did not activate on the ported number');
  await page.goto('http://localhost:8080/shop/services');
  await page.locator('[data-testid="my-number"]', { hasText: keepNum }).waitFor({ timeout: 15000 });
  console.log('OK a customer kept their number from the storefront — ported via NRDB, activated, shown in My services');

  // 7. Port-OUT: a customer takes their number to another operator. The number
  // must be one we hold — so port it in first, then out (the full lifecycle).
  const outParty = `portout-${Date.now()}`;
  const outNum = '+4790' + String(Date.now()).slice(-6);
  const pin = await (await ctx.request.post(`${PORT}/numberPortingOrder`, {
    headers: H, data: { direction: 'portIn', phoneNumber: outNum, country: 'NO',
      otherOperator: 'OtherTelco', relatedParty: [{ id: outParty, role: 'customer' }] } })).json();
  await ctx.request.post(`${PORT}/numberPortingOrder/${pin.id}/complete`, { headers: H });
  const offers2 = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=50`, { headers: H })).json();
  const plan2 = offers2.find((o) => (o.name || '').includes('Unlimited'));
  await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H, data: { productOrderItem: [{ action: 'add', productOffering: { id: plan2.id } }],
      relatedParty: [{ id: outParty, role: 'customer' }] } });
  let active = false;
  for (let attempt = 0; attempt < 20 && !active; attempt++) {
    await new Promise((r) => setTimeout(r, 2000));
    const svcs = await (await ctx.request.get(
      `${API}/tmf-api/serviceInventory/v4/service?relatedPartyId=${outParty}`, { headers: H })).json();
    active = svcs.some((sv) => sv.state === 'active' && (sv.supportingResource || []).some((r) => r.value === outNum));
  }
  if (!active) fail('could not set up an active service on the number to port out');

  const before = (await (await ctx.request.get(`${API}/ai/v1/churnModel`, { headers: H })).json()).labeledOutcomes;
  const portOut = await (await ctx.request.post(`${PORT}/numberPortingOrder`, {
    headers: H, data: { direction: 'portOut', phoneNumber: outNum, country: 'NO',
      otherOperator: 'RivalTelco', relatedParty: [{ id: outParty, role: 'customer' }] } })).json();
  if (portOut.status !== 'scheduled') fail('port-out not scheduled: ' + JSON.stringify(portOut));
  console.log('OK port-out scheduled via', portOut.clearinghouse);

  // The cutover ceases the service and releases the number.
  let ceased = false;
  for (let attempt = 0; attempt < 25 && !ceased; attempt++) {
    await new Promise((r) => setTimeout(r, 2000));
    const svcs = await (await ctx.request.get(
      `${API}/tmf-api/serviceInventory/v4/service?relatedPartyId=${outParty}`, { headers: H })).json();
    ceased = svcs.some((sv) => sv.state === 'terminated')
      && !svcs.some((sv) => (sv.supportingResource || []).some((r) => r.value === outNum));
  }
  if (!ceased) fail('service was not ceased / number not released after port-out');
  console.log('OK port-out cutover ceased the service and released the number');

  // And the departure is recorded as a churn outcome for the model.
  let recorded = false;
  for (let attempt = 0; attempt < 10 && !recorded; attempt++) {
    await new Promise((r) => setTimeout(r, 1500));
    const after = (await (await ctx.request.get(`${API}/ai/v1/churnModel`, { headers: H })).json()).labeledOutcomes;
    recorded = after > before;
  }
  if (!recorded) fail('port-out was not recorded as a churn outcome');
  console.log('OK the port-out was recorded as a churn outcome — a real departure the model learns from');

  // 9. The agent side: the CSR console shows the customer's porting orders and
  // can cease a service (releasing its number) — the assisted-channel half of
  // "keep your number / leave with it".
  const csrFamily = `Ceaseworth${run}`;
  const person = await (await ctx.request.post(`${API}/tmf-api/party/v4/individual`, {
    headers: H, data: { givenName: 'Cora', familyName: csrFamily } })).json();
  const csrNum = '+4791' + String(Date.now()).slice(-6);
  const csrPin = await (await ctx.request.post(`${PORT}/numberPortingOrder`, {
    headers: H, data: { direction: 'portIn', phoneNumber: csrNum, country: 'NO',
      otherOperator: 'OtherTelco', relatedParty: [{ id: person.id, role: 'customer' }] } })).json();
  await ctx.request.post(`${PORT}/numberPortingOrder/${csrPin.id}/complete`, { headers: H });
  await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H, data: { productOrderItem: [{ action: 'add', productOffering: { id: plan2.id } }],
      relatedParty: [{ id: person.id, role: 'customer' }] } });
  let csrActive = false;
  for (let attempt = 0; attempt < 20 && !csrActive; attempt++) {
    await new Promise((r) => setTimeout(r, 2000));
    const svcs = await (await ctx.request.get(
      `${API}/tmf-api/serviceInventory/v4/service?relatedPartyId=${person.id}`, { headers: H })).json();
    csrActive = svcs.some((sv) => sv.state === 'active');
  }
  if (!csrActive) fail('no active service for the CSR cease check');

  const csrCtx = await browser.newContext();
  const agent = await csrCtx.newPage();
  agent.on('dialog', (d) => d.accept());       // the Cease confirm
  await agent.goto('http://localhost:8080/csr/');
  await agent.waitForSelector('input[name="username"]', { timeout: 20000 });
  await agent.fill('input[name="username"]', 'agent-anna');
  await agent.fill('input[name="password"]', 'agent');
  await agent.click('input[type="submit"], button[type="submit"]');
  await agent.waitForSelector('.searchbar', { timeout: 20000 });
  await agent.fill('.searchbar input', csrFamily);
  await agent.click('.searchbar button');
  const hit = agent.locator('.rowlink', { hasText: csrFamily });
  await hit.waitFor({ timeout: 15000 });
  await hit.click();
  await agent.locator('h1', { hasText: 'Cora ' + csrFamily }).waitFor({ timeout: 15000 });

  const portRow = agent.locator('[data-testid="porting-card"] .row', { hasText: csrNum });
  await portRow.waitFor({ timeout: 15000 });
  console.log('OK CSR 360 shows the porting order:', (await portRow.textContent()).trim().slice(0, 60));

  const ceaseBtn = agent.locator('[data-testid="cease-service"]').first();
  await ceaseBtn.waitFor({ timeout: 15000 });
  await ceaseBtn.click();
  // Ceasing releases the number, so the service row (keyed on its number)
  // leaves the card entirely — and the API confirms the terminated state.
  await agent.locator('[data-testid="cease-service"]').first()
    .waitFor({ state: 'detached', timeout: 20000 });
  let ceasedByAgent = false;
  for (let attempt = 0; attempt < 10 && !ceasedByAgent; attempt++) {
    await new Promise((r) => setTimeout(r, 1000));
    const svcs = await (await ctx.request.get(
      `${API}/tmf-api/serviceInventory/v4/service?relatedPartyId=${person.id}`, { headers: H })).json();
    ceasedByAgent = svcs.some((sv) => sv.state === 'terminated');
  }
  if (!ceasedByAgent) fail('agent cease did not terminate the service');
  console.log('OK agent ceased the service from the CSR console — number released, state terminated');

  await browser.close();
  console.log('\nALL PORTING CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
