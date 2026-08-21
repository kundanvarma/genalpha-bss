/* Console journey editing — the marketer's edit flow, in the browser.
 *
 *  - the Journeys pane now has an Edit row action (noEdit dropped)
 *  - Edit loads the EXISTING journey into the form: name, and the step
 *    builder shows the real steps (not the blank starter)
 *  - changing a message subject in the step card and saving PATCHes the
 *    journey: new copy persisted, stepsEditedAt stamped
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

  const name = `ConsoleEdit ${run}`;
  const j = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name, triggerEventType: 'IndividualCreateEvent', holdoutPercent: 0,
    steps: [
      { type: 'message', stage: 'Welcome', subject: `CE Hello ${run}`, content: 'hi' },
      { type: 'wait', days: 1 },
      { type: 'message', stage: 'Offer', subject: `CE Offer V1 ${run}`, content: 'copy' },
    ] } })).json();
  if (!j.id) fail('journey not created: ' + JSON.stringify(j));
  console.log('OK a live journey to edit');

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

  let row = page.locator('#listing-body tr', { hasText: name });
  for (let hop = 0; hop < 40 && !(await row.count()); hop++) {
    if (await page.locator('#next').isDisabled()) break;
    await page.click('#next');
    await page.waitForTimeout(300);
    row = page.locator('#listing-body tr', { hasText: name });
  }
  if (!(await row.count())) fail('the journey row was not found in the console');

  /* ---------- Edit is offered and loads the real journey ---------- */
  const editBtn = row.locator('button', { hasText: 'Edit' });
  if (!(await editBtn.count()) || (await editBtn.isHidden())) fail('the Journeys pane offers no Edit action');
  await editBtn.click();
  await page.waitForSelector('#editor-title', { timeout: 10000 });
  if (!(await page.locator('#editor-title').textContent()).includes(name)) fail('the editor did not load the journey');
  const cards = page.locator('[data-testid="step-card"]');
  if ((await cards.count()) !== 3) fail(`the step builder shows ${await cards.count()} cards, expected the journey's 3`);
  const offerSubject = cards.nth(2).locator('input[placeholder^="Subject"]');
  if ((await offerSubject.inputValue()) !== `CE Offer V1 ${run}`) fail('the offer step did not load its existing subject');
  console.log('OK Edit loads the existing journey into the step builder');

  /* ---------- change the copy and save ---------- */
  await offerSubject.fill(`CE Offer V2 ${run}`);
  await page.locator('#save', { hasText: 'Save changes' }).click();
  await page.waitForTimeout(1500);
  if (!(await page.locator('#editor-error').isHidden())) {
    fail('save failed: ' + (await page.locator('#editor-error').textContent()));
  }

  const after = (await (await ctx.get(JOURNEY, { headers: H(staff) })).json())
    .find((x) => x.id === j.id);
  if (!after) fail('the journey vanished after the edit');
  if (!JSON.stringify(after.steps).includes(`CE Offer V2 ${run}`)) fail('the edited subject was not persisted');
  if (!after.stepsEditedAt) fail('the console edit did not stamp stepsEditedAt');
  console.log('OK saving PATCHed the journey: new copy persisted, stepsEditedAt stamped');

  await browser.close();
  console.log('\nALL CONSOLE-EDIT CHECKS PASSED — a marketer can open a live journey, see its real steps in '
    + 'the builder, fix the copy and save, and the edit lands stamped on the same journey.');
})();
