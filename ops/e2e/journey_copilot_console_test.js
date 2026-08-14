/* Growth Copilot in the console — the clickable conversational panel.
 *   - Growth workspace has a "Growth Copilot" tab
 *   - describe onboarding -> the copilot asks a question -> answer -> a
 *     journey proposal card -> Create applies it into a real journey
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
  const apictx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const staff = await token(apictx, 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1200, height: 1200 } });
  await page.goto(CONSOLE);
  await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo'); await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });

  /* ---------- the Growth Copilot tab exists and opens a chat ---------- */
  await page.locator('.tab', { hasText: 'Growth Copilot' }).click();
  await page.waitForSelector('#growth-copilot-input', { timeout: 10000 });
  console.log('OK the Growth workspace has a Growth Copilot chat');

  /* ---------- describe onboarding -> a clarifying question ---------- */
  await page.fill('#growth-copilot-input', `welcome new customers ${run} when they sign up`);
  await page.click('#growth-copilot-send');
  await page.waitForFunction(() => {
    const b = [...document.querySelectorAll('#growth-copilot-log .copilot-ai')].pop();
    return b && b.textContent && b.textContent !== '…' && b.textContent.length > 10;
  }, { timeout: 15000 });
  console.log('OK the copilot answered with a clarifying question');

  /* ---------- answer -> a journey proposal card ---------- */
  await page.fill('#growth-copilot-input', 'yes, a short series with an activation nudge and a check-in');
  await page.click('#growth-copilot-send');
  await page.waitForSelector('[data-testid="growth-proposal"]', { timeout: 15000 });
  const cardText = await page.locator('[data-testid="growth-proposal"]').first().textContent();
  if (!/Journey/.test(cardText)) fail('the proposal card is not a journey: ' + cardText.slice(0, 120));
  console.log('OK the copilot proposed a journey as a review card');

  /* ---------- Create = apply -> a real journey ---------- */
  const before = new Set((await (await apictx.get(JOURNEY, { headers: H(staff) })).json()).map((j) => j.id));
  await page.locator('[data-testid="growth-create"]').first().click();
  await page.waitForFunction(() => {
    const b = document.querySelector('[data-testid="growth-create"]');
    return b && /Created/.test(b.textContent);
  }, { timeout: 15000 });
  await page.waitForTimeout(1000);
  const list = await (await apictx.get(JOURNEY, { headers: H(staff) })).json();
  const made = list.find((j) => !before.has(j.id));
  if (!made) fail('Create in the copilot did not make a real journey');
  if (!Array.isArray(made.steps) || made.steps.length < 3) fail('the created journey lost its steps');
  console.log(`OK Create applied the proposal — a real ${made.steps.length}-step journey now exists`);

  await apictx.delete(`${JOURNEY}/${made.id}`, { headers: H(staff) });
  await browser.close();
  console.log('\nALL GROWTH-COPILOT-CONSOLE CHECKS PASSED — a marketer chats in the console, the copilot '
    + 'clarifies then proposes a journey, and Create applies it into a real running journey.');
})();
