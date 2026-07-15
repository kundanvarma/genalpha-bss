/* SIM self-care E2E: activation mints a SIM (ICCID + PUK) alongside the
 * number. From /shop My services the owner sees the masked ICCID, reveals
 * their PUK on request, and pushes a new PIN to the card through the SIM
 * platform seam. Scoping proven: another customer's token gets a 404 for the
 * same service — existence never leaks. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const SHOP = `${API}/shop/`;
const run = Date.now();

async function register(page, email, first, last) {
  await page.goto(SHOP);
  await page.click('.who >> text=Sign in');
  await page.waitForSelector('a[href*="registration"], input[name="username"]', { timeout: 20000 });
  await page.click('a[href*="registration"]');
  await page.waitForSelector('input[name="email"]');
  await page.fill('input[name="firstName"]', first);
  await page.fill('input[name="lastName"]', last);
  await page.fill('input[name="email"]', email);
  await page.fill('input[name="password"]', 'Passw0rd!');
  await page.fill('input[name="password-confirm"]', 'Passw0rd!');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.nav', { timeout: 20000 });
}

const shopToken = (page) => page.evaluate(() => sessionStorage.getItem('bss.shop.token'));

async function apiCall(page, method, path, token, body) {
  return page.evaluate(async ({ method, path, token, body }) => {
    const res = await fetch(path, {
      method,
      headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: res.status, body: await res.json().catch(() => ({})) };
  }, { method, path, token, body });
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

  const offers = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`)).json();
  const plan = offers.find((o) => o.name.includes('Unlimited 5G') && !o.isBundle);

  // --- Sam orders a plan; activation mints number AND SIM
  const page = await (await browser.newContext()).newPage();
  await register(page, `sam-${run}@example.com`, 'Sam', 'Simsson');
  const sam = await shopToken(page);
  await apiCall(page, 'POST', '/tmf-api/productOrderingManagement/v4/productOrder', sam, {
    productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name } }],
  });
  let service = null;
  for (let i = 0; i < 30 && !service; i++) {
    await page.waitForTimeout(2000);
    const svcs = await apiCall(page, 'GET', '/tmf-api/serviceInventory/v4/service', sam);
    service = (svcs.body || []).find?.((s) => s.state === 'active'
      && (s.supportingResource || []).some((r) => r.value)) || null;
  }
  if (!service) fail('service never activated');

  const sim = await apiCall(page, 'GET', `/tmf-api/serviceInventory/v4/service/${service.id}/sim`, sam);
  if (sim.status !== 200) fail('no SIM minted at activation: ' + sim.status);
  if (sim.body.puk) fail('PUK leaked without reveal!');
  if (!/^•••• \d{5}$/.test(sim.body.iccid)) fail('unexpected masked ICCID: ' + sim.body.iccid);
  console.log('OK activation minted a SIM:', sim.body.iccid, '(PUK not in the default response)');

  // --- /shop My services: SIM card, PUK reveal, OTA PIN reset
  await page.click('.nav >> text=My page');
  await page.waitForSelector('[data-testid=sim-card]', { timeout: 15000 });
  const shownIccid = await page.locator('[data-testid=sim-iccid]').textContent();
  if (!shownIccid.includes(sim.body.iccid.slice(-5))) fail('UI shows wrong ICCID: ' + shownIccid);
  await page.click('[data-testid=show-puk]');
  await page.waitForSelector('[data-testid=sim-puk]', { timeout: 10000 });
  const uiPuk = await page.locator('[data-testid=sim-puk]').textContent();
  const apiPuk = (await apiCall(page, 'GET',
    `/tmf-api/serviceInventory/v4/service/${service.id}/sim?reveal=true`, sam)).body.puk;
  if (!/^\d{8}$/.test(uiPuk) || uiPuk !== apiPuk) fail(`PUK mismatch: ui=${uiPuk} api=${apiPuk}`);
  console.log('OK PUK revealed on request in /shop:', uiPuk);

  await page.fill('[data-testid=pin-input]', '4321');
  await page.click('[data-testid=reset-pin]');
  await page.waitForSelector('[data-testid=pin-done]', { timeout: 10000 });
  console.log('OK new PIN pushed to the card through the SIM-platform seam');

  // bad PIN refused
  const badPin = await apiCall(page, 'POST',
    `/tmf-api/serviceInventory/v4/service/${service.id}/sim/resetPin`, sam, { newPin: '12' });
  if (badPin.status !== 400) fail('2-digit PIN should be refused, got ' + badPin.status);
  console.log('OK malformed PIN refused:', badPin.body.message);

  // --- scoping: another customer gets a 404 for Sam's SIM
  const page2 = await (await browser.newContext()).newPage();
  await register(page2, `eve-${run}@example.com`, 'Eve', 'Else');
  const eve = await shopToken(page2);
  const foreign = await apiCall(page2, 'GET',
    `/tmf-api/serviceInventory/v4/service/${service.id}/sim?reveal=true`, eve);
  if (foreign.status !== 404) fail("Eve reached Sam's SIM: " + foreign.status);
  const foreignPin = await apiCall(page2, 'POST',
    `/tmf-api/serviceInventory/v4/service/${service.id}/sim/resetPin`, eve, { newPin: '0000' });
  if (foreignPin.status !== 404) fail("Eve reset Sam's PIN: " + foreignPin.status);
  console.log("OK scoping: another customer gets 404 for Sam's SIM and PIN — existence never leaks");

  // --- THE CALL-IN: "I forgot my PIN." The agent reveals the PUK (identity
  // checked, disclosure LOGGED) and pushes a new PIN — and Sam is TOLD his
  // PIN changed, because silent credential changes are a gift to fraudsters.
  const annaRes = await ctx.request.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'agent-anna', password: 'agent' } });
  const anna = (await annaRes.json()).access_token;
  const AH = { Authorization: 'Bearer ' + anna, 'Content-Type': 'application/json' };
  const samParty = JSON.parse(Buffer.from(sam.split('.')[1], 'base64url').toString()).sub;

  const agentReveal = await (await ctx.request.get(
    `http://localhost:8080/tmf-api/serviceInventory/v4/service/${service.id}/sim?reveal=true`,
    { headers: AH })).json();
  if (agentReveal.puk !== apiPuk) fail('the agent read a different PUK than the card holds');
  await ctx.request.post('http://localhost:8080/tmf-api/partyInteraction/v4/partyInteraction',
    { headers: AH, data: { description: `PUK disclosed for Sam's line after identity verification`,
      channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console',
      relatedParty: [{ id: samParty, role: 'customer', '@referredType': 'Individual' }] } });
  console.log('OK the agent revealed the PUK for the caller — and the disclosure is on the record');

  const agentPin = await ctx.request.post(
    `http://localhost:8080/tmf-api/serviceInventory/v4/service/${service.id}/sim/resetPin`,
    { headers: AH, data: { newPin: '7777' } });
  if (agentPin.status() !== 200) fail('the agent could not reset the PIN: ' + agentPin.status());
  let warned = false;
  for (let i = 0; i < 20 && !warned; i++) {
    await new Promise((r) => setTimeout(r, 1500));
    const inboxRes = await apiCall(page, 'GET',
      '/tmf-api/communicationManagement/v4/communicationMessage?limit=50', sam);
    warned = (inboxRes.body || []).some?.((m) => (m.subject || '').includes('SIM PIN was changed'));
  }
  if (!warned) fail('the customer was never told their PIN changed');
  console.log('OK Sam was TOLD: "Your SIM PIN was changed — if this was not you, contact us"');

  // --- "I LOST MY SIM": the agent blocks the old card and issues a new one.
  // The number never moves (it lives on the service); the old ICCID dies at
  // the platform FIRST; the new card has its own PUK; and Sam is warned —
  // a silent SIM swap is the textbook account-takeover.
  const replaced = await (await ctx.request.post(
    `http://localhost:8080/tmf-api/serviceInventory/v4/service/${service.id}/sim/replace`,
    { headers: AH, data: { reason: 'lost' } })).json();
  if (replaced.oldSim?.status !== 'blocked') fail('a LOST card must be blocked: ' + JSON.stringify(replaced));
  if (!replaced.iccid || replaced.iccid === replaced.oldSim.iccid) {
    fail('the replacement is not a new card: ' + JSON.stringify(replaced));
  }
  const newSim = await apiCall(page, 'GET',
    `/tmf-api/serviceInventory/v4/service/${service.id}/sim?reveal=true`, sam);
  if (!newSim.body.iccid.includes(replaced.iccid.slice(-5))) {
    fail('the service does not carry the new card: ' + JSON.stringify(newSim.body));
  }
  if (newSim.body.puk === apiPuk) fail('the new card reused the old PUK');
  if (!/^\d{8}$/.test(newSim.body.puk)) fail('the new PUK is not revealable: ' + newSim.body.puk);
  console.log('OK LOST SIM replaced: old card blocked at the platform, new card on the SAME'
    + ' number with its OWN PUK');

  const badReason = await ctx.request.post(
    `http://localhost:8080/tmf-api/serviceInventory/v4/service/${service.id}/sim/replace`,
    { headers: AH, data: { reason: 'because' } });
  if (badReason.status() !== 400) fail('nonsense replacement reasons must be refused: ' + badReason.status());

  let swapWarned = false;
  for (let i = 0; i < 20 && !swapWarned; i++) {
    await new Promise((r) => setTimeout(r, 1500));
    const inboxRes = await apiCall(page, 'GET',
      '/tmf-api/communicationManagement/v4/communicationMessage?limit=50', sam);
    swapWarned = (inboxRes.body || []).some?.((m) => (m.subject || '').includes('new SIM was issued'));
  }
  if (!swapWarned) fail('the customer was never told their SIM was replaced');
  console.log('OK Sam was WARNED: "A new SIM was issued for your number — if this was not you,'
    + ' contact us immediately"');

  await browser.close();
  console.log('\nALL SIM CHECKS PASSED — minted at activation, PUK on request, OTA PIN reset,'
    + ' owner-scoped — the call-in flow (PUK on the record, PIN reset with a receipt) and the'
    + ' lost-SIM replacement: old card dead first, same number, new PUK, loud warning.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
