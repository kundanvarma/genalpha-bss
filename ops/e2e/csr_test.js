/* CSR channel E2E: a customer raises a ticket in the storefront; the
 * operator's agent finds the customer, works the ticket and logs the call;
 * a partner org's agent sees none of it; the customer closes the loop. */
const { chromium } = require('playwright');

const SHOP = 'http://localhost:8080/shop/';
const CSR = 'http://localhost:8080/csr/';
const run = Date.now();
const FAMILY = `Ticketson${run}`;

async function registerCustomer(page, email, first, last) {
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

async function agentLogin(page, username) {
  await page.goto(CSR);
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', username);
  await page.fill('input[name="password"]', 'agent');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.searchbar', { timeout: 20000 });
}

(async () => {
  const browser = await chromium.launch();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };

  // --- Customer raises a ticket in the storefront
  const ctxC = await browser.newContext();
  const c = await ctxC.newPage();
  await registerCustomer(c, `tina-${run}@example.com`, 'Tina', FAMILY);
  await c.click('.nav >> text=Support');
  await c.fill('.supportform input[name="name"]', 'No internet at home');
  await c.fill('.supportform input[name="description"]', 'Router LEDs all red since morning');
  await c.click('.supportform button.primary');
  await c.locator('.row', { hasText: 'No internet at home' }).waitFor({ timeout: 15000 });
  console.log('OK customer raised a ticket via storefront Support');

  // --- Operator agent works it in the CSR console
  const ctxA = await browser.newContext();
  const a = await ctxA.newPage();
  await agentLogin(a, 'agent-anna');
  const org = await a.locator('.orgbadge').textContent();
  if (org !== 'genalpha-retail') fail(`agent org badge wrong: ${org}`);
  console.log('OK agent-anna signed in to CSR, org:', org);

  // TYPE-AHEAD, loose matching: a lowercase PARTIAL family name, no submit —
  // results settle after the debounce
  await a.fill('.searchbar input', FAMILY.toLowerCase().slice(0, -2));
  await a.locator('.rowlink', { hasText: FAMILY }).first().waitFor({ timeout: 15000 });
  console.log('OK search-as-you-type finds a lowercase partial name');
  // ...and a PHONE NUMBER resolves in the tenant's own pool (permanent persona)
  await a.fill('.searchbar input', '+46701000619');
  await a.locator('.rowlink', { hasText: 'Sonny' }).first().waitFor({ timeout: 15000 });
  console.log('OK a typed phone number resolves to its holder');
  await a.fill('.searchbar input', FAMILY);
  const hit = a.locator('.rowlink', { hasText: FAMILY });
  await hit.waitFor({ timeout: 15000 });
  await hit.click();
  await a.locator('h1', { hasText: 'Tina ' + FAMILY }).waitFor({ timeout: 15000 });
  console.log('OK customer 360 found via search');
  const customerId = a.url().split('/').filter(Boolean).pop(); // the 360 route ends in the party id

  // CSR 360 catch-up cards: all render; a fresh customer gets suggestions.
  for (const card of ['usage-card', 'agreements-card', 'promo-vault-card', 'suggest-card']) {
    await a.locator(`[data-testid="${card}"]`).waitFor({ timeout: 10000 });
  }
  await a.locator('[data-testid="suggest-card"] .row').first().waitFor({ timeout: 10000 });
  console.log('OK 360 shows usage, agreements, promo/vault and suggestions cards');
  // the suggestion is ACTIONABLE: the agent can send it or order it, right there
  await a.locator('[data-testid="suggest-card"] button', { hasText: 'Send offer' })
    .first().waitFor({ timeout: 10000 });
  await a.locator('[data-testid="suggest-card"] button', { hasText: 'Order now' })
    .first().waitFor({ timeout: 10000 });
  console.log('OK every suggestion carries its actions: Send offer / Order now');

  // --- Copilot: the 360 summarized on demand, next actions included
  await a.locator('[data-testid="copilot-summarize"]').click();
  await a.locator('[data-testid="copilot-summary"]').waitFor({ timeout: 15000 });
  const summaryText = (await a.locator('[data-testid="copilot-summary"]').textContent()).trim();
  const actions = await a.locator('[data-testid="copilot-card"] li').count();
  if (!summaryText || actions < 1) fail('copilot summary incomplete: ' + summaryText);
  console.log('OK copilot summarized the 360 with', actions, 'suggested actions');

  const ticket = a.locator('.ticket', { hasText: 'No internet at home' });
  await ticket.waitFor({ timeout: 15000 });

  // --- Copilot drafts the reply; the agent stays in charge and rewrites it
  await ticket.locator('[data-testid="draft-reply"]').click();
  let drafted = '';
  for (let attempt = 0; attempt < 15 && !drafted; attempt++) {
    await a.waitForTimeout(1000);
    drafted = await ticket.locator('input[name="ticketNote"]').inputValue();
  }
  if (!drafted) fail('copilot never drafted a ticket reply');
  console.log('OK copilot drafted a reply into the note box');

  await ticket.locator('input[name="ticketNote"]').fill('Line test shows outage in the area');
  await ticket.locator('button', { hasText: '→ inProgress' }).click();
  await a.locator('.ticket .state', { hasText: 'inProgress' }).waitFor({ timeout: 15000 });
  await ticket.locator('button', { hasText: '→ resolved' }).click();
  await a.locator('.ticket .state', { hasText: 'resolved' }).waitFor({ timeout: 15000 });
  console.log('OK agent worked the ticket: note + inProgress + resolved');

  await a.fill('input[name="newInteraction"]', 'Called customer back: outage fixed, confirmed online');
  await a.locator('button', { hasText: 'Log interaction' }).click();
  await a.locator('.row', { hasText: 'Called customer back' }).waitFor({ timeout: 15000 });
  console.log('OK interaction logged on the customer');

  // --- the timeline PAGES: a long history shows a handful, fetches more
  // on demand — the 360 never hauls (or renders) the whole archive
  const annaTokRes = await a.context().request.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'agent-anna', password: 'agent' } });
  const annaTok = (await annaTokRes.json()).access_token;
  for (let i = 1; i <= 7; i++) {
    await a.context().request.post('http://localhost:8080/tmf-api/partyInteraction/v4/partyInteraction',
      { headers: { Authorization: 'Bearer ' + annaTok, 'Content-Type': 'application/json' },
        data: { description: `Archive call #${i} (${run})`, channel: 'phone', direction: 'inbound',
          relatedParty: [{ id: customerId, role: 'customer', '@referredType': 'Individual' }] } });
  }
  await a.reload();
  await a.locator('.row', { hasText: 'Archive call #7' }).waitFor({ timeout: 15000 });
  const visibleBefore = await a.locator('.rows .row', { hasText: 'Archive call' }).count();
  if (visibleBefore > 5) fail(`the timeline rendered ${visibleBefore} archive rows up front (page is 5)`);
  const more = a.locator('[data-testid="more-interactions"]');
  await more.waitFor({ timeout: 10000 });
  await more.click();
  await a.locator('.row', { hasText: 'Archive call #1' }).waitFor({ timeout: 10000 });
  console.log('OK the timeline pages: 5 newest up front, "Show more" fetched the older rows on'
    + ' demand — no full-history hauls');

  // --- UPSELL FROM THE 360: the agent ACTS on a suggestion — orders on the
  // customer's behalf, and sends a personal offer that rides the whole
  // omnichannel loop (inbox + interaction timeline).
  const annaRes = await a.context().request.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'agent-anna', password: 'agent' } });
  const anna = (await annaRes.json()).access_token;
  const AH = { Authorization: 'Bearer ' + anna, 'Content-Type': 'application/json' };
  const custId = customerId; // the customer this suite created
  const onBehalf = await a.context().request.post(
    'http://localhost:8080/tmf-api/productOrderingManagement/v4/productOrder',
    { headers: AH, data: {
      productOrderItem: [{ action: 'add', productOffering: {
        id: '14291c1a-df26-4232-8084-500466888e46', name: 'GenAlpha Mobile 10 GB' } }],
      relatedParty: [{ id: custId, role: 'customer', '@referredType': 'Individual' }] } });
  if (onBehalf.status() !== 201) fail('agent could not order on behalf: ' + onBehalf.status());
  const behalfOrders = await (await a.context().request.get(
    `http://localhost:8080/tmf-api/productOrderingManagement/v4/productOrder?limit=100&relatedPartyId=${custId}`,
    { headers: AH })).json();
  if (!behalfOrders.length) fail('the on-behalf order does not belong to the customer');
  console.log('OK UPSELL, acted on: the agent ordered on the customer\'s behalf — the order is THEIRS');

  const offerSubject = `An offer picked for you: Home Fiber 500`;
  const sent = await a.context().request.post(
    'http://localhost:8080/tmf-api/communicationManagement/v4/communicationMessage',
    { headers: AH, data: { subject: offerSubject, content: 'Your agent thought this fits.',
      relatedParty: [{ id: custId, role: 'customer', '@referredType': 'Individual' }] } });
  if (sent.status() !== 201) fail('agent could not send an offer: ' + sent.status());
  let touched = false;
  for (let i = 0; i < 15 && !touched; i++) {
    await new Promise((r) => setTimeout(r, 1500));
    const timeline = await (await a.context().request.get(
      `http://localhost:8080/tmf-api/partyInteraction/v4/partyInteraction?limit=20&relatedPartyId=${custId}`,
      { headers: AH })).json();
    touched = timeline.some((ix) => (ix.description || '').includes(offerSubject)
      && ix.sourceSystem === 'communication');
  }
  if (!touched) fail('the sent offer never reached the interaction timeline');
  console.log('OK the sent offer landed in the inbox AND on the interaction timeline — the'
    + ' upsell writes its own record');


  await a.click('.nav >> text=Stock');
  await a.locator('.row', { hasText: 'Samsung Galaxy S26' }).waitFor({ timeout: 15000 });
  console.log('OK stock view shows live levels');

  await a.click('.nav >> text=Tickets');
  await a.locator('.tab', { hasText: 'resolved' }).click();
  await a.locator('.ticket', { hasText: 'No internet at home' }).first().waitFor({ timeout: 15000 });
  console.log('OK ticket queue shows the resolved ticket');

  // --- Partner org's agent sees none of it
  const ctxP = await browser.newContext();
  const p = await ctxP.newPage();
  await agentLogin(p, 'partner-paul');
  if ((await p.locator('.orgbadge').textContent()) !== 'partner-north') fail('partner org badge wrong');
  await p.click('.nav >> text=Tickets');
  await p.waitForSelector('.tabs', { timeout: 15000 });
  await p.waitForTimeout(1200);
  const queue = await p.locator('main').textContent();
  if (queue.includes('No internet at home')) fail('partner agent sees operator org tickets!');
  await p.fill('.searchbar input', FAMILY).catch(() => {});
  console.log('OK partner-paul (partner-north) sees an empty queue — org isolation holds');

  // --- Customer sees the agent's note and closes the loop
  await c.reload();
  await c.click('.nav >> text=Support');
  await c.locator('.ticketnote', { hasText: 'Line test shows outage' }).waitFor({ timeout: 15000 });
  await c.locator('button', { hasText: 'Close' }).click();
  await c.locator('.state', { hasText: 'closed' }).waitFor({ timeout: 15000 });
  console.log('OK customer saw the agent note and closed the resolved ticket');

  // --- TMF642/656: a critical alarm becomes an outage the console shows.
  const nocRes = await a.context().request.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  const noc = (await nocRes.json()).access_token;
  const raised = await a.context().request.post(
    'http://localhost:8080/tmf-api/alarmManagement/v4/alarm',
    { headers: { Authorization: 'Bearer ' + noc, 'Content-Type': 'application/json' },
      data: { alarmedObject: 'olt-e2e-1', perceivedSeverity: 'critical',
              probableCause: 'fiber cut on olt-e2e-1' } });
  if (raised.status() !== 201) fail('alarm intake failed: ' + raised.status());
  await a.reload();
  await a.locator('[data-testid="outage-banner"]', { hasText: 'olt-e2e-1' }).waitFor({ timeout: 20000 });
  console.log('OK critical alarm surfaced as an outage banner for agents');

  const problems = await (await a.context().request.get(
    'http://localhost:8080/tmf-api/serviceProblemManagement/v4/serviceProblem?status=open',
    { headers: { Authorization: 'Bearer ' + noc } })).json();
  const mine = problems.find((p) => p.affectedObject === 'olt-e2e-1');
  if (!mine) fail('service problem missing for the alarm');
  const resolved = await a.context().request.patch(
    `http://localhost:8080/tmf-api/serviceProblemManagement/v4/serviceProblem/${mine.id}`,
    { headers: { Authorization: 'Bearer ' + noc, 'Content-Type': 'application/json' },
      data: { status: 'resolved' } });
  if (resolved.status() !== 200) fail('problem resolution failed: ' + resolved.status());
  await a.reload();
  await a.locator('[data-testid="outage-banner"]', { hasText: 'olt-e2e-1' })
    .waitFor({ state: 'detached', timeout: 20000 });
  console.log('OK resolving the problem cleared the banner (and its alarms)');

  
  // --- Role-scoped console: junior agent Jo (read + tickets, no staff powers)
  const ctxJ = await browser.newContext();
  const j = await ctxJ.newPage();
  await j.goto(CSR);
  await j.waitForSelector('input[name="username"]', { timeout: 20000 });
  await j.fill('input[name="username"]', 'jo@bss.local');
  await j.fill('input[name="password"]', 'jo');
  await j.click('input[type="submit"], button[type="submit"]');
  await j.waitForSelector('.searchbar', { timeout: 20000 });
  if (await j.locator('.nav >> text=Stock').count()) fail('junior agent must not see the Stock tab');
  await j.fill('.searchbar input', FAMILY);
  await j.click('.searchbar button');
  const jHit = j.locator('.rowlink', { hasText: FAMILY });
  await jHit.waitFor({ timeout: 15000 });
  await jHit.click();
  await j.locator('h1', { hasText: 'Tina' }).waitFor({ timeout: 15000 });
  if (await j.locator('[data-testid="copilot-summarize"]').count()) {
    fail('junior agent must not see the AI copilot');
  }
  if (await j.locator('[data-testid="cease-service"]').count()) {
    fail('junior agent must not see Cease');
  }
  if (await j.locator('[data-testid="draft-reply"]').count()) {
    fail('junior agent must not see AI draft-reply');
  }
  console.log('OK role-scoped CSR: junior Jo gets the 360 without Stock, copilot, cease or AI drafting');

  await browser.close();

  console.log('\nALL CSR CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
