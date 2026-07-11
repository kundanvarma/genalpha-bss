/* Martech E2E: a staffer defines a welcome journey (trigger + message +
 * promo code); a customer's first order fires it exactly once through
 * TMF681, a second order stays silent, and nova's tenant sees none of it. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();

async function staffToken(request, realm) {
  const res = await request.post(
    `http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };
  const genalpha = await staffToken(ctx.request, 'bss');
  const nova = await staffToken(ctx.request, 'nova');
  const as = (token) => ({ Authorization: 'Bearer ' + token });
  const json = { 'Content-Type': 'application/json' };

  // --- Marketing defines the journey: order placed -> welcome message + code
  const subject = `Welcome aboard ${run}!`;
  const created = await ctx.request.post(`${API}/tmf-api/campaignManagement/v4/campaign`, {
    headers: { ...as(genalpha), ...json },
    data: {
      name: `Welcome journey ${run}`,
      triggerEventType: 'ProductOrderCreateEvent',
      promotionCode: 'WELCOME10',
      message: { subject, content: 'Use {code} on your next order.' },
    },
  });
  if (created.status() !== 201) fail('campaign create: ' + created.status() + ' ' + await created.text());
  const campaign = await created.json();
  if (campaign.status !== 'active') fail('campaign not active on create');
  console.log('OK campaign defined:', campaign.name);

  // --- A digital plan to buy
  const offers = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=50`,
    { headers: as(genalpha) })).json();
  const plan = offers.find((o) => (o.name || '').includes('Unlimited'));
  if (!plan) fail('no plan offering in catalog');

  // --- First order: the journey fires, message carries the substituted code
  const party = `martech-${run}`;
  const order = (n) => ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: { ...as(genalpha), ...json },
    data: {
      productOrderItem: [{ action: 'add', productOffering: { id: plan.id } }],
      relatedParty: [{ id: party, role: 'customer' }],
    },
  });
  if ((await order(1)).status() !== 201) fail('first order rejected');

  const inbox = async () => {
    const msgs = await (await ctx.request.get(
      `${API}/tmf-api/communicationManagement/v4/communicationMessage?relatedPartyId=${party}&limit=50`,
      { headers: as(genalpha) })).json();
    return msgs.filter((m) => m.subject === subject);
  };
  let hits = [];
  for (let attempt = 0; attempt < 20 && !hits.length; attempt++) {
    await new Promise((r) => setTimeout(r, 2000));
    hits = await inbox();
  }
  if (hits.length !== 1) fail('expected the welcome message once, got ' + hits.length);
  if (hits[0].content !== 'Use WELCOME10 on your next order.') {
    fail('promo code not templated into the message: ' + hits[0].content);
  }
  console.log('OK first order fired the journey; message carries WELCOME10');

  // --- Second order: once per customer means once
  if ((await order(2)).status() !== 201) fail('second order rejected');
  await new Promise((r) => setTimeout(r, 8000));
  hits = await inbox();
  if (hits.length !== 1) fail('journey fired again on the second order: ' + hits.length);
  console.log('OK second order stayed silent — once per customer holds');

  // --- The execution ledger agrees
  const executions = await (await ctx.request.get(
    `${API}/tmf-api/campaignManagement/v4/campaign/${campaign.id}/execution`,
    { headers: as(genalpha) })).json();
  if (executions.length !== 1 || executions[0].party.id !== party) {
    fail('execution ledger wrong: ' + JSON.stringify(executions));
  }
  console.log('OK execution ledger records exactly one send to', party);

  // --- Tenant walls: nova staff neither sees nor could have triggered it
  const novaView = await (await ctx.request.get(
    `${API}/tmf-api/campaignManagement/v4/campaign`, { headers: as(nova) })).json();
  if (novaView.some((c) => c.id === campaign.id)) fail("nova staff can see genalpha's campaign");
  console.log('OK campaigns are tenant-local (nova sees', novaView.length, 'of its own)');

  // --- Paused campaigns stay quiet for NEW customers
  const paused = await ctx.request.patch(
    `${API}/tmf-api/campaignManagement/v4/campaign/${campaign.id}`,
    { headers: { ...as(genalpha), ...json }, data: { status: 'paused' } });
  if ((await paused.json()).status !== 'paused') fail('pause failed');
  const party2 = `martech-${run}-b`;
  await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: { ...as(genalpha), ...json },
    data: {
      productOrderItem: [{ action: 'add', productOffering: { id: plan.id } }],
      relatedParty: [{ id: party2, role: 'customer' }],
    },
  });
  await new Promise((r) => setTimeout(r, 8000));
  const msgs2 = await (await ctx.request.get(
    `${API}/tmf-api/communicationManagement/v4/communicationMessage?relatedPartyId=${party2}&limit=50`,
    { headers: as(genalpha) })).json();
  if (msgs2.some((m) => m.subject === subject)) fail('paused campaign still fired');
  console.log('OK paused campaign stays silent for new customers');

  // --- Marketing self-serve: the same journey, defined in the console GUI
  const page = await browser.newPage();
  await page.goto('http://localhost:8080/console/');
  await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  await page.locator('.tab', { hasText: 'Campaigns' }).click();

  const guiName = `Console journey ${run}`;
  const guiSubject = `From the console ${run}`;
  await page.fill('input[name="name"]', guiName);
  await page.selectOption('select[name="triggerEventType"]', 'ProductOrderCreateEvent');
  await page.waitForFunction(() =>
    document.querySelectorAll('select[name="promotionCode"] option').length > 1);
  await page.selectOption('select[name="promotionCode"]', 'WELCOME10');
  await page.fill('input[name="messageSubject"]', guiSubject);
  await page.fill('textarea[name="messageContent"]', 'Console says: use {code}.');
  await page.click('#save');
  const row = page.locator('#listing-body tr', { hasText: guiName });
  await row.waitFor({ timeout: 10000 });
  console.log('OK marketing created a campaign in the console, no curl in sight');

  // --- The reached counter goes 0 -> 1 when a customer's order fires it
  const partyGui = `martech-${run}-gui`;
  await ctx.request.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: { ...as(genalpha), ...json },
    data: {
      productOrderItem: [{ action: 'add', productOffering: { id: plan.id } }],
      relatedParty: [{ id: partyGui, role: 'customer' }],
    },
  });
  let reached = '';
  for (let attempt = 0; attempt < 20 && !reached.startsWith('1 customer'); attempt++) {
    await page.waitForTimeout(2000);
    await page.locator('.tab', { hasText: 'Campaigns' }).click(); // re-render the list
    await row.waitFor({ timeout: 10000 });
    await page.waitForFunction((name) => {
      const tr = [...document.querySelectorAll('#listing-body tr')]
        .find((r) => r.textContent.includes(name));
      return tr && !tr.textContent.includes('…');
    }, guiName, { timeout: 10000 });
    reached = (await row.locator('td').nth(4).textContent()).trim();
  }
  if (!reached.startsWith('1 customer')) fail('reached counter never hit 1: ' + reached);
  const guiHits = (await (await ctx.request.get(
    `${API}/tmf-api/communicationManagement/v4/communicationMessage?relatedPartyId=${partyGui}&limit=50`,
    { headers: as(genalpha) })).json()).filter((m) => m.subject === guiSubject);
  if (guiHits.length !== 1 || guiHits[0].content !== 'Console says: use WELCOME10.') {
    fail('console-defined journey misdelivered: ' + JSON.stringify(guiHits));
  }
  console.log('OK console campaign fired once; reached counter shows', reached);

  // --- Pause is one click
  await row.locator('button', { hasText: 'Pause' }).click();
  await page.locator('#listing-body tr', { hasText: guiName })
    .locator('button', { hasText: 'Resume' }).waitFor({ timeout: 10000 });
  console.log('OK paused from the GUI (button flipped to Resume)');

  await browser.close();
  console.log('\nALL MARTECH CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
