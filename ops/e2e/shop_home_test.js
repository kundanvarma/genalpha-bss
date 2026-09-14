/* #128 — the customer's Home: "is everything okay, and what should I do next?"
 *
 * Ivan's B2C review, built: a signed-in customer lands on Home, not on the shop;
 * five destinations (Home · Services · Billing · Shop · Support) and three
 * header utilities (cart, inbox, profile); Home is exception-first — attention
 * only when something needs it (an incident on THEIR line read from the same
 * ontology context the care desk uses, a paused line, an open bill, data near
 * its limit, an order in progress), then their services as cards with the
 * actions that service can take, money and usage, open work, recent activity,
 * and one recommendation below their needs. The shop no longer asks an existing
 * broadband customer for a postcode. Support is guided: check the line first.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const SHOP = `${API}/shop/`;
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
  return page.evaluate(() => sessionStorage.getItem('bss.shop.token'));
}

(async () => {
  const noc = await token('demo', 'demo');
  const browser = await chromium.launch();
  const page = await (await browser.newContext({ viewport: { width: 1366, height: 860 } })).newPage();

  /* ---------- a guest lands on the shop, with Shop + Support and a cart utility ---------- */
  await page.goto(SHOP);
  await page.waitForSelector('.nav', { timeout: 20000 });
  const guestNav = await page.locator('.nav').innerText();
  if (!/Shop/.test(guestNav) || !/Support/.test(guestNav) || /My page|Offers/.test(guestNav)) fail('guest nav: ' + guestNav);
  await page.locator('.who .cartlink').waitFor({ timeout: 5000 });
  console.log('OK guest: Shop · Support, cart in the header');

  /* ---------- a new customer with a line ---------- */
  const carl = await register(page, `hilde-${run}@example.com`, 'Hilde', `Home${run}`);
  const sub = JSON.parse(atob(carl.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).sub;
  let mobile = null;
  for (let o = 0; o < 1000 && !mobile; o += 100) {
    const rows = (await call('GET', `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100&offset=${o}`, carl)).json;
    if (!Array.isArray(rows) || !rows.length) break;
    mobile = rows.find((x) => x.name.includes('Mobile 10 GB') && x.lifecycleStatus !== 'Retired');
  }
  if (!mobile) fail('need the seed plan GenAlpha Mobile 10 GB');
  const order = await call('POST', `${API}/tmf-api/productOrderingManagement/v4/productOrder`, carl, {
    productOrderItem: [{ action: 'add', productOffering: { id: mobile.id, name: mobile.name } }] }, { 'X-Channel': 'web' });
  if (order.status !== 201) fail(`order: ${order.status} ${order.text.slice(0, 200)}`);
  const service = await until('the line to go active', async () => {
    const svcs = (await call('GET', `${API}/tmf-api/serviceInventory/v4/service`, carl)).json || [];
    return svcs.find?.((s) => s.state === 'active' && (s.supportingResource || []).some((r) => r.value)) || null;
  }, 60, 2000);
  const number = service.supportingResource[0].value;
  console.log('OK Hilde has an active line', number);

  /* ---------- signed in: Home is the default, five destinations, three utilities ---------- */
  await page.goto(SHOP);
  await page.locator('[data-testid="home"]').waitFor({ timeout: 30000 });
  const nav = (await page.locator('.nav').innerText()).replace(/\s+/g, ' ');
  for (const w of ['Home', 'Services', 'Billing', 'Shop', 'Support']) if (!nav.includes(w)) fail('nav lacks ' + w + ': ' + nav);
  if (/My page|My orders|My bills|Offers|Family|My devices/.test(nav)) fail('old destinations still primary: ' + nav);
  if ((await page.locator('.nav a').count()) !== 5) fail('primary nav must have exactly five links');
  await page.locator('.who .cartlink').first().waitFor({ timeout: 5000 });
  await page.locator('[data-testid="profile-menu"]').waitFor({ timeout: 5000 });
  await page.locator('[data-testid="profile-menu"] summary').click();
  await page.locator('[data-testid="profile-menu"] >> text="Family"').waitFor({ state: 'visible', timeout: 5000 });
  await page.locator('[data-testid="profile-menu"] summary').click();
  console.log('OK signed in: Home is the default; Home · Services · Billing · Shop · Support; cart, inbox and profile in the header');

  /* ---------- exception-first: a healthy customer sees no attention items ---------- */
  await page.locator('[data-testid="home-greeting"]', { hasText: 'Hilde' }).waitFor({ timeout: 10000 });
  await page.locator('[data-testid="home-attention"]', { hasText: 'Reading' }).waitFor({ state: 'detached', timeout: 45000 }).catch(() => {});
  if (await page.locator('[data-testid^="attention-"]').count()) fail('a healthy customer must see no attention items: ' + await page.locator('[data-testid="home-attention"]').innerText());
  await page.locator('[data-testid="home-health"]', { hasText: /looks good/ }).waitFor({ timeout: 10000 });
  const card = page.locator('[data-testid="service-card-mobile"]', { hasText: number });
  await card.waitFor({ timeout: 15000 });
  const cardText = await card.innerText();
  if (!/Check my line/.test(cardText) || !/data/i.test(cardText)) fail('mobile card lacks its facts/actions: ' + cardText.slice(0, 200));
  await page.locator('[data-testid="home-money"]').waitFor({ timeout: 5000 });
  await page.locator('[data-testid="home-work"]', { hasText: /No open work|in progress/ }).waitFor({ timeout: 10000 });
  await page.locator('[data-testid="home-activity"]').waitFor({ timeout: 5000 });
  await page.locator('[data-testid="home-recommended"]').waitFor({ timeout: 5000 });
  const order1 = await page.evaluate(() => [...document.querySelectorAll('[data-testid^="home-"]')].map((e) => e.getAttribute('data-testid')));
  const idx = (k) => order1.indexOf(k);
  if (!(idx('home-services') < idx('home-money') && idx('home-money') < idx('home-work') && idx('home-work') < idx('home-activity') && idx('home-activity') < idx('home-recommended'))) fail('zone order: ' + order1.join(','));
  const fold = await page.evaluate(() => ({ money: document.querySelector('[data-testid="home-money"]').getBoundingClientRect().top, work: document.querySelector('[data-testid="home-work"]').getBoundingClientRect().top }));
  if (fold.money > 860 || fold.work > 900) console.log(`-- note: money at ${Math.round(fold.money)}px, open work at ${Math.round(fold.work)}px (viewport 860)`);
  console.log('OK Home zones in order (services → money → open work → activity → recommendation); the recommendation sits last');

  /* ---------- an incident on HER line: the same ontology context the desk reads, with her own token ---------- */
  const alarm = await call('POST', `${API}/tmf-api/alarmManagement/v4/alarm`, noc, {
    alarmedObject: service.id, perceivedSeverity: 'critical', probableCause: `home outage ${run}` });
  if (alarm.status !== 201) fail(`alarm: ${alarm.status}`);
  const problem = await until('the alarm to become a problem', async () => {
    const open = (await call('GET', `${API}/tmf-api/serviceProblemManagement/v4/serviceProblem?status=open`, noc)).json || [];
    return open.find((p) => p.affectedObject === service.id) || null;
  });
  await page.reload();
  await page.locator('[data-testid="attention-incident"]').waitFor({ timeout: 45000 });
  const incident = await page.locator('[data-testid="attention-incident"]').innerText();
  if (!/known problem|on your line/i.test(incident)) fail('the incident is not said in the customer\'s words: ' + incident);
  await page.locator('[data-testid="home-health"]', { hasText: /attention/ }).waitFor({ timeout: 5000 });
  console.log('OK an incident on her line is the first thing on Home, in her words: "' + incident.slice(0, 80) + '"');
  await call('PATCH', `${API}/tmf-api/serviceProblemManagement/v4/serviceProblem/${problem.id}`, noc, { status: 'resolved' });

  /* ---------- a paused line: attention with the way back ---------- */
  const paused = await call('POST', `${API}/tmf-api/serviceInventory/v4/service/${service.id}/suspend`, carl, { days: 30 });
  if (paused.status >= 300) fail(`pause: ${paused.status} ${paused.text.slice(0, 200)}`);
  await page.reload();
  await page.locator('[data-testid="attention-paused"]').waitFor({ timeout: 45000 });
  await page.locator('[data-testid="attention-paused"] button', { hasText: 'Resume' }).click();
  await page.locator('[data-testid="attention-paused"]').waitFor({ state: 'detached', timeout: 30000 });
  const after = (await call('GET', `${API}/tmf-api/serviceInventory/v4/service/${service.id}`, carl)).json;
  if (after.state !== 'active') fail('resume from Home did not resume the line: ' + after.state);
  console.log('OK a paused line is an attention item with Resume, and Resume works from Home');

  /* ---------- the shop is account-aware; Support is guided ---------- */
  await page.click('.nav >> text=Shop');
  await page.locator('[data-testid="banner-strip"], .hero').first().waitFor({ timeout: 20000 });
  const heroH = await page.evaluate(() => { const el = document.querySelector('[data-testid="banner-strip"] .banner.lead, .hero'); return el ? el.getBoundingClientRect().height : 0; });
  if (heroH > 420) fail(`the hero still dominates the first viewport: ${Math.round(heroH)}px`);
  console.log('OK the shop hero is', Math.round(heroH), 'px tall');
  await page.click('.nav >> text=Support');
  await page.locator('[data-testid="support-guide"]').waitFor({ timeout: 15000 });
  const steps = await page.locator('[data-testid="support-step"]').count();
  if (steps < 3) fail('support is not guided: ' + steps + ' steps');
  await page.locator('[data-testid="support-guide"] [data-testid="diagnose-line"]').first().click();
  await page.locator('[data-testid="diagnosis"]').first().waitFor({ timeout: 20000 });
  console.log('OK Support is guided: check my line first, then what we found, then how to reach us');

  await browser.close();
  console.log('\nPASS shop_home_test — the customer\'s Home answers "is everything okay, and what should I do next?"');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
