/* Personalization made discoverable: token chips + {{ autocomplete.
 *   - a message field shows "Insert: First name / Last name / Brand / Promo"
 *     chips; clicking one drops {{party.firstName}} into the copy
 *   - typing {{ opens an autocomplete of the same tokens
 *   - the Campaigns message box has it too
 */
const { chromium } = require('playwright');

const CONSOLE = 'http://localhost:8080/console/';

(async () => {
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1200, height: 1200 } });
  await page.goto(CONSOLE);
  await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo'); await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });

  /* ---------- Journeys: a message field offers the tokens ---------- */
  await page.locator('.tab', { hasText: 'Journeys' }).click();
  await page.waitForSelector('.stepcard', { timeout: 10000 });
  const steps = async () => JSON.parse(await page.inputValue('[name="steps"]'));

  // the "Message" (body) row is the wide stepfield with a textarea; click its First-name chip
  const msgRow = page.locator('.stepbody .stepfield.wide').filter({ has: page.locator('textarea') }).first();
  if (!(await msgRow.locator('[data-testid="token-party.firstName"]').count())) fail('no personalization chips on the message field');
  await msgRow.locator('.stepbody textarea, textarea').first().click();
  await msgRow.locator('[data-testid="token-party.firstName"]').click();
  await page.waitForTimeout(200);
  if (!((await steps())[0].content || '').includes('{{party.firstName}}')) {
    fail('the First-name chip did not insert the token: ' + JSON.stringify((await steps())[0]));
  }
  console.log('OK a "First name" chip inserted {{party.firstName}} into the message — no syntax to memorize');

  /* ---------- typing {{ opens an autocomplete ---------- */
  const subj = page.locator('.stepbody .stepfield').filter({ hasText: 'Subject' }).locator('input').first();
  await subj.click();
  await subj.type('Hi {{');
  await page.waitForSelector('[data-testid="token-drop"]', { timeout: 5000 });
  await page.locator('[data-testid="tokenopt-party.firstName"]').click();
  await page.waitForTimeout(150);
  if (!((await steps())[0].subject || '').includes('{{party.firstName}}')) {
    fail('the {{ autocomplete did not insert the token: ' + JSON.stringify((await steps())[0]));
  }
  console.log('OK typing "{{" opened an autocomplete and inserting a token personalized the subject');

  /* ---------- Campaigns message box has it too ---------- */
  await page.locator('.tab', { hasText: 'Campaigns' }).click();
  await page.waitForSelector('[name="messageContent"]', { timeout: 10000 });
  const campRow = page.locator('.field').filter({ has: page.locator('[name="messageContent"]') });
  if (!(await campRow.locator('[data-testid="token-party.firstName"]').count())) fail('the campaign message box has no token chips');
  console.log('OK the Campaigns message box offers the same personalization tokens');

  await browser.close();
  console.log('\nALL TOKEN-PICKER CHECKS PASSED — the message box is a template editor: chips and a {{ '
    + 'autocomplete offer {{party.firstName}} & co, so personalizing by name is a click, not a syntax to learn.');
})();
