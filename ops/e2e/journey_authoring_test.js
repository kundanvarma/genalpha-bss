/* Journey authoring in the console — the three wired UI pieces:
 *   1. "✨ Draft" — describe the journey, the copilot fills the Steps box
 *   2. a Priority field (NBA arbitration)
 *   3. a Status field (draft/active/paused/archived lifecycle)
 * and Create makes a real journey from the drafted, edited object.
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
  await page.waitForSelector('#ai-draft', { timeout: 15000 });

  /* ---------- piece 2 + 3: the new fields render ---------- */
  if (!(await page.locator('[name="priority"]').count())) fail('the Priority field is missing from the form');
  if (!(await page.locator('[name="status"]').count())) fail('the Status field is missing from the form');
  console.log('OK the form now has Priority (NBA) and Status (lifecycle) fields');

  /* ---------- the step builder replaces raw JSON: it starts with a card ---------- */
  if (!(await page.locator('[data-testid="step-card"]').count())) fail('the step builder shows no step cards');
  console.log('OK the Steps field is a stage-by-stage builder (step cards), not a raw JSON box');

  /* ---------- piece 1: describe it, the copilot fills the builder ---------- */
  const brief = `welcome new fibre customers ${run} and nudge them to activate`;
  await page.fill('#ai-brief', brief);
  await page.click('#ai-draft');
  let parsed = [];
  for (let i = 0; i < 25; i++) {
    try { parsed = JSON.parse(await page.inputValue('[name="steps"]')); } catch { parsed = []; }
    if (parsed.filter((s) => s.type === 'message').length >= 2) break;
    await page.waitForTimeout(1000);
  }
  const msgs = parsed.filter((s) => s.type === 'message');
  if (msgs.length < 2) fail('the AI draft did not fill multiple stage messages: ' + JSON.stringify(parsed));
  // the draft populated the visual builder, not just the JSON
  const cardCount = await page.locator('[data-testid="step-card"]').count();
  if (cardCount < msgs.length) fail(`the builder shows ${cardCount} cards for ${msgs.length} drafted messages`);
  const draftedName = await page.inputValue('[name="name"]');
  if (!draftedName) fail('the draft did not fill the Name');
  console.log(`OK "✨ Draft" filled the builder with ${cardCount} step cards (${msgs.length} stage messages) and a name`);

  /* ---------- set priority + status, then Create ---------- */
  const before = new Set((await (await apictx.get(JOURNEY, { headers: H(staff) })).json()).map((j) => j.id));
  await page.fill('[name="priority"]', '50');
  await page.selectOption('[name="status"]', 'draft'); // draft so it doesn't run/pollute
  await page.click('#save');
  await page.waitForTimeout(2500);

  /* ---------- it created a real journey with exactly what was authored ---------- */
  const list = await (await apictx.get(JOURNEY, { headers: H(staff) })).json();
  const made = list.find((j) => !before.has(j.id)); // the new one, whatever the (redacted) name
  if (!made) fail('the console did not create the journey');
  if (made.status !== 'draft') fail('the chosen Status was not applied: ' + made.status);
  if (made.priority !== 50) fail('the chosen Priority was not applied: ' + made.priority);
  if (!Array.isArray(made.steps) || made.steps.filter((s) => s.type === 'message').length < 2) {
    fail('the created journey lost the drafted steps');
  }
  console.log(`OK Create made a real journey — status "${made.status}", priority ${made.priority}, ${made.steps.length} nodes`);

  // tidy up
  await apictx.delete(`${JOURNEY}/${made.id}`, { headers: H(staff) });
  await browser.close();
  console.log('\nALL JOURNEY-AUTHORING CHECKS PASSED — describe -> AI drafts the steps, set priority + lifecycle, '
    + 'Create makes a real journey: the whole thing is clickable in the console.');
})();
