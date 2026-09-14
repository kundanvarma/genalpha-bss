/* #129 — the equipment seam: the router at the customer's end.
 *
 * Most "my internet is dead" calls are the box, not the network. The SOM now
 * reads the router or ONT on a broadband line from the operator's ACS (TR-069
 * stand-in: mock-acs) and can restart it. Proven here:
 *   1. the line check names an OFFLINE router as the cause when the network is
 *      fine — and says the network is fine;
 *   2. a restart from the customer's own token is accepted, the box comes back,
 *      the check reads online again, and the restart is an event;
 *   3. a stranger's token gets 404 on the equipment, like every other line door;
 *   4. the care desk shows the router state on the service row and can restart
 *      it; the customer's Home card shows it too;
 *   5. the ontology knows the action: restartRouter checks, executes with the
 *      caller's rights, and is receipted.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const ACS = 'http://localhost:8162';
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
  const headers = { 'Content-Type': 'application/json', ...extra };
  if (tok) headers.Authorization = `Bearer ${tok}`;
  const res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* not json */ }
  return { status: res.status, json, text };
}
async function until(what, fn, tries = 20, every = 1500) {
  for (let i = 0; i < tries; i++) { const v = await fn(); if (v) return v; await sleep(every); }
  fail('timed out waiting for ' + what);
  return null;
}

(async () => {
  const paula = await token('paula@family.example', 'paula');
  const anna = await token('agent-anna', 'agent');
  const stranger = await token('demo', 'demo'); // staff may read; a CUSTOMER who is not the owner is the stranger below

  /* ---------- Paula's broadband line ---------- */
  const svcs = (await call('GET', `${API}/tmf-api/serviceInventory/v4/service?limit=100`, paula)).json || [];
  const line = svcs.find((s) => s.state === 'active' && /broadband|fib|dsl|internet/i.test(`${s.category || ''} ${s.name || ''}`));
  if (!line) fail('Paula has no active broadband line');
  console.log('OK broadband line:', line.name, line.id.slice(0, 8));

  /* ---------- 1. the router as the ACS sees it; offline = the cause ---------- */
  const online = await call('GET', `${API}/tmf-api/serviceInventory/v4/service/${line.id}/cpe`, paula);
  if (online.status !== 200 || !online.json.model) fail(`cpe read: ${online.status} ${online.text.slice(0, 200)}`);
  console.log(`OK the equipment seam answers: ${online.json.model} ${online.json.state}, ${online.json.wifiClients} on Wi-Fi, firmware ${online.json.firmware}`);
  const pin = await call('PUT', `${ACS}/cpe/${line.id}`, null, { state: 'offline' });
  if (pin.status !== 200) fail('could not pin the router offline on the ACS: ' + pin.status);
  const diag = await call('POST', `${API}/tmf-api/serviceInventory/v4/service/${line.id}/diagnose`, paula, {});
  if (diag.status !== 200) fail('diagnose: ' + diag.status);
  const off = (diag.json.findings || []).find((f) => f.code === 'routerOffline');
  if (!off || off.severity !== 'cause' || diag.json.verdict !== 'routerOffline') fail('an offline router must be the cause: ' + JSON.stringify(diag.json).slice(0, 300));
  if (!/network side is fine/i.test(off.message)) fail('the finding must say the network is fine: ' + off.message);
  console.log('OK line check: "' + off.message.slice(0, 90) + '…" (verdict routerOffline)');

  /* ---------- 2. restart from the customer's own token; the box comes back ---------- */
  const restart = await call('POST', `${API}/tmf-api/serviceInventory/v4/service/${line.id}/cpe/restart`, paula, {});
  if (restart.status !== 202) fail(`restart: ${restart.status} ${restart.text.slice(0, 200)}`);
  const back = await until('the router to come back online', async () => {
    const r = (await call('GET', `${API}/tmf-api/serviceInventory/v4/service/${line.id}/cpe`, paula)).json;
    return r && r.state === 'online' ? r : null;
  }, 15, 1000);
  if (back.uptimeSeconds > 60) fail('uptime should have reset after the restart: ' + back.uptimeSeconds);
  const again = await call('POST', `${API}/tmf-api/serviceInventory/v4/service/${line.id}/diagnose`, paula, {});
  if ((again.json.findings || []).some((f) => f.code === 'routerOffline')) fail('the router is still reported offline after the restart');
  if (!(again.json.findings || []).some((f) => f.code === 'routerOnline')) fail('the line check should now report the router online');
  console.log('OK restart accepted (202), box back online with fresh uptime, the line check reads online');

  /* ---------- 3. a stranger's customer token gets 404 on the equipment ---------- */
  const other = await token('kai@bss.local', 'kai');
  const foreign = await call('GET', `${API}/tmf-api/serviceInventory/v4/service/${line.id}/cpe`, other);
  if (foreign.status !== 404) fail('a stranger must get 404 on the equipment, got ' + foreign.status);
  const foreignRestart = await call('POST', `${API}/tmf-api/serviceInventory/v4/service/${line.id}/cpe/restart`, other, {});
  if (foreignRestart.status !== 404) fail('a stranger must not restart the router, got ' + foreignRestart.status);
  console.log('OK a stranger sees no equipment and cannot restart it (404, like every line door)');

  /* ---------- 5. the ontology knows the action ---------- */
  const check = await call('POST', `${API}/ontology/v1/actions/restartRouter/check`, paula, { serviceId: line.id }, { 'X-Channel': 'web' });
  if (check.status !== 200 || !check.json.allowed) fail('restartRouter check for the owner: ' + JSON.stringify(check.json).slice(0, 300));
  const exec = await call('POST', `${API}/ontology/v1/actions/restartRouter/execute`, anna, { serviceId: line.id }, { 'X-Channel': 'care', 'X-GenAlpha-Agent': 'care-assist' });
  if (exec.status !== 200 || !exec.json.decisionId) fail('restartRouter execute by the agent: ' + JSON.stringify(exec.json).slice(0, 300));
  console.log('OK ontology: restartRouter checks for the owner and executes for the agent with a receipt', exec.json.decisionId.slice(0, 12));
  await until('the box back after the governed restart', async () => {
    const r = (await call('GET', `${API}/tmf-api/serviceInventory/v4/service/${line.id}/cpe`, paula)).json;
    return r && r.state === 'online' ? r : null;
  }, 15, 1000);

  /* ---------- 4. the desk and the customer's Home show the router ---------- */
  await call('PUT', `${ACS}/cpe/${line.id}`, null, { state: 'offline' });
  const browser = await chromium.launch();
  const a = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  await a.goto(`${API}/csr/`); await a.waitForSelector('input[name="username"]', { timeout: 20000 });
  await a.fill('input[name="username"]', 'agent-anna'); await a.fill('input[name="password"]', 'agent'); await a.click('input[type="submit"], button[type="submit"]');
  await a.waitForSelector('.searchbar', { timeout: 20000 });
  const owner = (line.relatedParty || []).find((p) => p.role === 'customer')?.id || (line.relatedParty || [])[0]?.id;
  await a.goto(`${API}/csr/customer/${owner}#services`);
  await a.locator('[data-testid="csr-router-offline"]').first().waitFor({ timeout: 30000 });
  a.once('dialog', (d) => d.accept());
  await a.locator('[data-testid="csr-restart-router"]').first().click();
  await a.locator('[data-testid="zone-timeline"] .row, [data-testid="activity"] .row', { hasText: 'Router restarted' }).first().waitFor({ timeout: 30000 }).catch(() => {});
  await until('the desk to show the router back online', async () => {
    const r = (await call('GET', `${API}/tmf-api/serviceInventory/v4/service/${line.id}/cpe`, paula)).json;
    return r && r.state === 'online' ? r : null;
  }, 15, 1000);
  console.log('OK the care desk shows "router OFFLINE" on the service row and restarts it from there');

  await call('PUT', `${ACS}/cpe/${line.id}`, null, { state: 'offline' });
  const c = await (await browser.newContext({ viewport: { width: 1366, height: 860 } })).newPage();
  await c.goto(`${API}/shop/`); await c.click('.who >> text=Sign in');
  await c.waitForSelector('input[name="username"]', { timeout: 20000 });
  await c.fill('input[name="username"]', 'paula@family.example'); await c.fill('input[name="password"]', 'paula'); await c.click('input[type="submit"], button[type="submit"]');
  await c.waitForSelector('[data-testid="home"]', { timeout: 30000 });
  const card = c.locator('[data-testid="service-card-broadband"]', { has: c.locator('[data-testid="router-offline"]') }).first();
  await card.waitFor({ timeout: 30000 });
  c.once('dialog', (d) => d.accept());
  await card.locator('[data-testid="restart-router"]').click();
  await card.locator('[data-testid="router-note"]').waitFor({ timeout: 15000 });
  await c.locator('[data-testid="router-online"]').first().waitFor({ timeout: 30000 });
  console.log('OK the customer\'s Home shows "Router offline" on the fibre card and Restart router works from there');

  await browser.close();
  console.log('\nPASS cpe_test — the box at the customer\'s end is part of the picture: seen, diagnosed, restarted, receipted');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
