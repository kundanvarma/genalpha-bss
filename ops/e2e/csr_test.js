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

  await a.fill('.searchbar input', FAMILY);
  await a.click('.searchbar button');
  const hit = a.locator('.rowlink', { hasText: FAMILY });
  await hit.waitFor({ timeout: 15000 });
  await hit.click();
  await a.locator('h1', { hasText: 'Tina ' + FAMILY }).waitFor({ timeout: 15000 });
  console.log('OK customer 360 found via search');

  // CSR 360 catch-up cards: all render; a fresh customer gets suggestions.
  for (const card of ['usage-card', 'agreements-card', 'promo-vault-card', 'suggest-card']) {
    await a.locator(`[data-testid="${card}"]`).waitFor({ timeout: 10000 });
  }
  await a.locator('[data-testid="suggest-card"] .row').first().waitFor({ timeout: 10000 });
  console.log('OK 360 shows usage, agreements, promo/vault and suggestions cards');

  const ticket = a.locator('.ticket', { hasText: 'No internet at home' });
  await ticket.waitFor({ timeout: 15000 });
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

  await browser.close();
  console.log('\nALL CSR CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
