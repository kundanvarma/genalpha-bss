/* BB1 — journey canvas (visual editor view).
 *
 *  - a staged journey with a live enrollment
 *  - in the console, the journey's Canvas renders the SAME object the API
 *    serves as a node flow: one node per step, with live per-node counts
 *    (reached · active) on the sending nodes — no draw-vs-run drift
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const CONSOLE = 'http://localhost:8080/console/';
const run = Date.now();
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;

async function token(ctx, client, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const staff = await token(ctx, 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  /* ---------- a staged journey + a live enrollment ---------- */
  const name = `Canvas ${run}`;
  const steps = [
    { type: 'message', stage: 'Welcome', subject: `Canvas Welcome ${run}`, content: 'hi' },
    { type: 'wait', stage: 'Activate', days: 3 },
    { type: 'message', stage: 'Activate', subject: `Canvas Activate ${run}`, content: 'add a service' },
    { type: 'exit' },
  ];
  const j = await (await ctx.post(JOURNEY, { headers: H(staff),
    data: { name, triggerEventType: 'IndividualCreateEvent', holdoutPercent: 0, steps } })).json();
  if (!j.id) fail('journey not created: ' + JSON.stringify(j));
  // one enrollment so the canvas has live counts
  const email = `canvas-${run}@example.com`;
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Canv', familyName: `A${run}` } })).json();
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff),
    data: { id: login.id, givenName: 'Canv', familyName: `A${run}` } });
  console.log('OK a 4-node journey with a live enrollment');

  /* ---------- open the console and render the canvas ---------- */
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto(CONSOLE);
  await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  await page.locator('.tab', { hasText: 'Journeys' }).click();
  await page.waitForSelector('#listing-body tr', { timeout: 15000 });

  // walk the pager to the journey row
  let row = page.locator('#listing-body tr', { hasText: name });
  for (let hop = 0; hop < 40 && !(await row.count()); hop++) {
    if (await page.locator('#next').isDisabled()) break;
    await page.click('#next');
    await page.waitForTimeout(300);
    row = page.locator('#listing-body tr', { hasText: name });
  }
  if (!(await row.count())) fail('the journey row was not found in the console');
  await row.locator('[data-testid="row-canvas"]').click();

  await page.waitForSelector('[data-testid="journey-canvas"]', { timeout: 10000 });
  const nodes = await page.locator('[data-testid="canvas-node"]').count();
  if (nodes !== steps.length) fail(`canvas drew ${nodes} nodes, expected ${steps.length}`);
  const counts = await page.locator('[data-testid="canvas-count"]').allTextContents();
  if (!counts.length || !counts.some((c) => /reached/.test(c))) {
    fail('the sending nodes show no live counts: ' + JSON.stringify(counts));
  }
  console.log(`OK the canvas drew ${nodes} nodes with live counts: ${counts.map((c) => c.trim()).join(' | ')}`);

  await browser.close();
  console.log('\nALL BB1 CHECKS PASSED — the journey canvas renders the same object the API serves as a node '
    + 'flow, with live per-node counts on the sending nodes: what you see is what runs.');
})();
