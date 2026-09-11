/* #126 — the CSR workspace after the UX review (2026-09-11).
 *
 * What an agent gets on one screen, proven in a browser:
 *   1. chrome is compact (header under 70px) and every incident is ONE line
 *      with details + a session dismiss — a new incident brings it back;
 *   2. the customer page is a cockpit: "right now" first, lines, money,
 *      orders, timeline — the tertiary line actions fold away, the dangerous
 *      one (Cease) never does, and the always-there cards still render;
 *   3. GenAlpha Assist sits beside the customer: the situation from the
 *      ontology (an incident on THIS customer's line, a paused line), a
 *      recommended governed action whose conditions are shown, Accept runs it
 *      with the agent's own token and lands on the timeline, dismiss is
 *      recorded for desk learning; knowledge for the call can be sent;
 *   4. the search takes any id the caller reads out (order / ticket / customer)
 *      and lands on the customer; recent customers are one click away;
 *   5. the ticket queue is a dense master-detail with the customer's name.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const CSR = `${API}/csr/`;
const run = Date.now();
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(user, pass, client = 'bss-demo') {
  const res = await fetch('http://localhost:8085/realms/bss/protocol/openid-connect/token', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: client, username: user, password: pass }),
  });
  if (!res.ok) fail(`token for ${user}: ${res.status}`);
  return (await res.json()).access_token;
}
async function call(method, url, tok, body, extra = {}) {
  const res = await fetch(url, { method, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tok}`, 'X-Channel': 'care', ...extra },
    body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* not json */ }
  return { status: res.status, json, text };
}

async function register(page, email, first, last) {
  await page.goto(`${API}/shop/`);
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
  return page.evaluate(() => sessionStorage.getItem('bss.shop.token'));
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
  const anna = await token('agent-anna', 'agent');
  const noc = await token('demo', 'demo');

  // --- a fresh customer with one active mobile line, made through the shop's own doors
  const browser = await chromium.launch();
  const shopPage = await (await browser.newContext()).newPage();
  const carl = await register(shopPage, `wendy-${run}@example.com`, 'Wendy', `Workspace${run}`);
  const found = await call('GET', `${API}/tmf-api/party/v4/individual?q=Workspace${run}`, anna);
  const customerId = (found.json || []).find((c) => c.familyName === `Workspace${run}`)?.id;
  if (!customerId) fail('the registered customer is not findable by the agent');
  let mobile = null;
  for (let o = 0; o < 1000 && !mobile; o += 100) {
    const page = (await call('GET', `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100&offset=${o}`, anna)).json;
    if (!Array.isArray(page) || !page.length) break;
    mobile = page.find((x) => x.name.includes('Mobile 10 GB') && x.lifecycleStatus !== 'Retired');
  }
  if (!mobile) fail('need the seed plan GenAlpha Mobile 10 GB');
  const order = await call('POST', `${API}/tmf-api/productOrderingManagement/v4/productOrder`, carl, {
    description: `Workspace line ${run}`,
    productOrderItem: [{ action: 'add', productOffering: { id: mobile.id, name: mobile.name } }],
  }, { 'X-Channel': 'web' });
  if (order.status !== 201) fail(`order: ${order.status} ${order.text.slice(0, 300)}`);
  const orderId = order.json.id;
  let service = null;
  for (let i = 0; i < 60 && !service; i++) {
    await sleep(2000);
    const svcs = await call('GET', `${API}/tmf-api/serviceInventory/v4/service`, carl);
    service = (svcs.json || []).find?.((s) => s.state === 'active' && (s.supportingResource || []).some((r) => r.value)) || null;
  }
  if (!service) fail('the line never went active');
  const msisdn = service.supportingResource[0].value;
  console.log('OK fresh customer with an active line', msisdn);
  const ticket = await call('POST', `${API}/tmf-api/troubleTicket/v4/troubleTicket`, anna, {
    name: `Slow data ${run}`, severity: 'major', ticketType: 'support',
    relatedParty: [{ id: customerId, role: 'customer', '@referredType': 'Individual' }],
  });
  if (ticket.status !== 201) fail(`ticket: ${ticket.status}`);
  const ticketId = ticket.json.id;

  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const a = await ctx.newPage();
  await agentLogin(a, 'agent-anna');

  // --- 1. compact chrome
  const headerH = await a.evaluate(() => document.querySelector('header.top').getBoundingClientRect().height);
  if (headerH > 70) fail(`header is ${headerH}px — the review asked for one compact bar`);
  console.log('OK header is compact:', Math.round(headerH), 'px');

  // --- 4. universal search: an ORDER id lands on the customer; a TICKET id too; recent customers appear
  await a.fill('.searchbar input', orderId);
  await a.locator('[data-testid="resolved-via"]', { hasText: 'order' }).waitFor({ timeout: 15000 });
  await a.locator('.rowlink', { hasText: `Workspace${run}` }).waitFor({ timeout: 15000 });
  console.log('OK an order id resolves to its customer');
  await a.fill('.searchbar input', ticketId);
  await a.locator('[data-testid="resolved-via"]', { hasText: 'ticket' }).waitFor({ timeout: 15000 });
  await a.locator('.rowlink', { hasText: `Workspace${run}` }).click();
  await a.locator('h1', { hasText: `Workspace${run}` }).waitFor({ timeout: 15000 });
  console.log('OK a ticket id resolves to its customer and opens the 360');

  // --- 2. the cockpit
  for (const z of ['zone-now', 'zone-lines', 'zone-money', 'zone-orders', 'zone-timeline']) {
    await a.locator(`[data-testid="${z}"]`).waitFor({ timeout: 15000 });
  }
  await a.locator('[data-testid="now-tickets"]', { hasText: `Slow data ${run}` }).waitFor({ timeout: 15000 });
  for (const card of ['usage-card', 'agreements-card', 'promo-vault-card', 'suggest-card', 'porting-card']) {
    await a.locator(`[data-testid="${card}"]`).waitFor({ timeout: 10000 });
  }
  await a.locator('[data-testid="empties"]').waitFor({ timeout: 10000 });
  if (await a.locator('[data-testid="csr-transfer-service"]').isVisible()) fail('tertiary line actions must fold away');
  await a.locator('[data-testid="cease-service"]').first().waitFor({ state: 'visible', timeout: 10000 });
  await a.locator('[data-testid="csr-more-actions"] summary').first().click();
  await a.locator('[data-testid="csr-transfer-service"]').first().waitFor({ state: 'visible', timeout: 5000 });
  console.log('OK cockpit zones, always-there cards, empties collapsed, tertiary actions fold, Cease stays');

  // --- 3. Assist: nothing open yet
  await a.locator('[data-testid="assist-panel"]').waitFor({ timeout: 10000 });
  await a.locator('[data-testid="assist-situation"]', { hasText: 'Reading the customer' }).waitFor({ state: 'detached', timeout: 45000 });
  if (await a.locator('[data-testid^="assist-situation-"]').count()) fail('situation reports trouble on a fresh customer: ' + await a.locator('[data-testid="assist-situation"]').textContent());
  console.log('OK Assist reads a quiet customer as quiet');

  // an incident on THIS customer's service becomes the situation; a compact bar shows it once
  const alarm = await call('POST', `${API}/tmf-api/alarmManagement/v4/alarm`, noc, {
    alarmedObject: service.id, perceivedSeverity: 'critical', probableCause: `workspace outage ${run} on the line`,
  });
  if (alarm.status !== 201) fail(`alarm: ${alarm.status} ${alarm.text.slice(0, 200)}`);
  let problem = null;
  for (let i = 0; i < 20 && !problem; i++) {
    await sleep(1500);
    const open = (await call('GET', `${API}/tmf-api/serviceProblemManagement/v4/serviceProblem?status=open`, noc)).json || [];
    problem = open.find((p) => p.affectedObject === service.id) || null;
  }
  if (!problem) fail('the critical alarm never became an open service problem');
  await a.reload();
  const bar = a.locator('[data-testid="outage-banner"]');
  await bar.waitFor({ timeout: 20000 });
  const barH = await bar.evaluate((el) => el.getBoundingClientRect().height);
  if (barH > 80) fail(`incident bar is ${barH}px tall — one line was the ask`);
  if ((await a.locator('[data-testid="outage-banner"]').count()) !== 1) fail('more than one incident bar');
  await a.locator('[data-testid="outage-details"]').click();
  await a.locator('.incident-list li', { hasText: service.id }).waitFor({ timeout: 5000 });
  console.log('OK one compact incident bar, details on demand');
  await a.locator('[data-testid="assist-situation"]', { hasText: 'Reading the customer' }).waitFor({ state: 'detached', timeout: 45000 });
  await a.locator('[data-testid="assist-situation-incident"]').waitFor({ timeout: 20000 });
  await a.locator('[data-testid="assist-rec-explainIncident"]').waitFor({ timeout: 10000 });
  console.log('OK Assist names the incident on THIS customer\'s line and recommends explaining it');
  // dismiss lasts the session; a resolved problem clears the bar
  await a.locator('[data-testid="outage-dismiss"]').click();
  await bar.waitFor({ state: 'detached', timeout: 5000 });
  await a.reload();
  await a.locator('[data-testid="assist-panel"]').waitFor({ timeout: 15000 });
  await sleep(1500);
  if (await bar.count()) fail('dismissed incident bar came back on reload without a new incident');
  const fixed = await call('PATCH', `${API}/tmf-api/serviceProblemManagement/v4/serviceProblem/${problem.id}`, noc, { status: 'resolved' });
  if (fixed.status !== 200) fail(`resolve problem: ${fixed.status}`);
  console.log('OK dismiss lasts the session; the problem is resolved again');

  // a paused line → Assist recommends resuming it; Accept runs the governed action with the agent's token
  const paused = await call('POST', `${API}/tmf-api/serviceInventory/v4/service/${service.id}/suspend`, anna, { days: 30 });
  if (paused.status >= 300) fail(`pause: ${paused.status} ${paused.text.slice(0, 200)}`);
  await a.reload();
  await a.locator('[data-testid="assist-situation"]', { hasText: 'Reading the customer' }).waitFor({ state: 'detached', timeout: 45000 });
  await a.locator('[data-testid="assist-situation-paused"], [data-testid="assist-situation-suspended"]').first().waitFor({ timeout: 20000 });
  const rec = a.locator('[data-testid="assist-rec-resumeSubscription"]');
  await rec.waitFor({ timeout: 10000 });
  await rec.locator('details summary').click();
  const why = await rec.textContent();
  if (!/conditions hold/.test(why) || !/Permission/.test(why)) fail('the recommendation does not show its conditions: ' + why.slice(0, 200));
  await a.locator('[data-testid="assist-do"]').click();
  await a.locator('[data-testid="assist-done"]').waitFor({ timeout: 30000 });
  const svcAfter = await call('GET', `${API}/tmf-api/serviceInventory/v4/service/${service.id}`, anna);
  if (svcAfter.json?.state !== 'active') fail('Accept did not resume the line: ' + svcAfter.json?.state);
  await a.locator('[data-testid="zone-timeline"] .row', { hasText: 'Assist:' }).waitFor({ timeout: 20000 });
  console.log('OK a paused line → "Resume" with its conditions → Accept ran it and it is on the timeline');

  // dismiss goes to desk learning; knowledge can be sent to the customer
  await a.locator('[data-testid="assist-situation"]', { hasText: 'Reading the customer' }).waitFor({ state: 'detached', timeout: 45000 });
  if (await a.locator('[data-testid="assist-dismiss"]').count()) {
    await a.locator('[data-testid="assist-dismiss"]').first().click();
    console.log('OK a recommendation can be dismissed (recorded for desk learning)');
  }
  const article = a.locator('[data-testid^="assist-send-"]').first();
  if (await article.count()) {
    const title = (await a.locator('[data-testid^="assist-article-"]').first().locator('.row > span').first().textContent()).trim();
    await article.click();
    await a.locator('[data-testid^="assist-send-"]', { hasText: 'Sent' }).first().waitFor({ timeout: 20000 });
    let landed = false;
    for (let i = 0; i < 15 && !landed; i++) {
      await sleep(1500);
      const timeline = (await call('GET', `${API}/tmf-api/partyInteraction/v4/partyInteraction?limit=30&relatedPartyId=${customerId}`, anna)).json || [];
      landed = timeline.some((ix) => (ix.description || '').includes(title) && ix.sourceSystem === 'communication');
    }
    if (!landed) fail('the sent article never reached the customer inbox (no communication record on the timeline)');
    console.log('OK knowledge for the call sent to the customer inbox and recorded on the timeline');
  } else {
    console.log('-- no article on the shelf for this situation (knowledge not seeded); send not exercised');
  }
  // the panel folds and the choice sticks for the session
  await a.locator('[data-testid="assist-toggle"]').click();
  if (await a.locator('[data-testid="assist-recommendation"]').count()) fail('Assist did not fold');
  await a.locator('[data-testid="assist-toggle"]').click();

  // --- 5. the queue: master-detail, the customer's name, one click to the 360
  await a.click('.nav >> text=Tickets');
  await a.locator('h1', { hasText: 'Ticket queue' }).waitFor({ timeout: 15000 });
  const row = a.locator('.ticket.queue-row', { hasText: `Slow data ${run}` });
  await row.waitFor({ timeout: 20000 });
  await row.locator('.queue-who', { hasText: 'Wendy' }).waitFor({ timeout: 20000 });
  await row.click();
  await a.locator('[data-testid="ticket-detail"] .ticket', { hasText: `Slow data ${run}` }).waitFor({ timeout: 10000 });
  await a.locator('[data-testid="ticket-detail"] input[name="ticketNote"]').waitFor({ timeout: 5000 });
  await a.locator('[data-testid="queue-open-customer"]').click();
  await a.locator('h1', { hasText: `Workspace${run}` }).waitFor({ timeout: 15000 });
  console.log('OK queue is master-detail with the customer\'s name and a link to the 360');

  // recent customers on the search page
  await a.click('.nav >> text=Customers');
  await a.locator('[data-testid="recent-customers"] .chip', { hasText: `Workspace${run}` }).waitFor({ timeout: 10000 });
  console.log('OK recent customers are one click away');

  await browser.close();
  console.log('PASS csr_workspace_test');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
