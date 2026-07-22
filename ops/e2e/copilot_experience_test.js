/* Copilot-authored experience rules — personalization, chatted into
 * existence. Suite #61.
 *
 *  - a product owner TELLS the copilot what guests should see ("banner
 *    for device browsers, pin the 60 GB plan") — the copilot proposes an
 *    EXPERIENCE RULE, the validator checks it, one click applies it with
 *    the owner's own token (the model never writes)
 *  - the rule is a policy row (domain 'personalization') — the same
 *    rules-as-data the insight experience endpoint already reads
 *  - THE REAL PROOF is a guest: a brand-new consenting visitor browses
 *    devices and the shop greets them with the CHATTED banner over the
 *    CHATTED pin — chat to storefront, no deploy, no JSON
 *  - deleted as data afterwards (suite hygiene: #34's defaults return)
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();

async function token(request, user, pass) {
  const res = await request.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { throw new Error(m); }; // throws so cleanup ALWAYS runs
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const staff = await token(ctx.request, 'demo', 'demo');
  let ruleId = null;

  const cleanup = async () => {
    if (!ruleId) {
      const rules = await (await ctx.request.get(
        `${API}/tmf-api/policyManagement/v4/policyRule?limit=100`, { headers: H(staff) })).json();
      ruleId = (rules.find?.((r) => r.name === 'Device browsers welcome') || {}).id || null;
    }
    if (ruleId) {
      await ctx.request.delete(`${API}/tmf-api/policyManagement/v4/policyRule/${ruleId}`,
        { headers: H(staff) });
    }
  };

  try {
    /* ---------- 1. the owner chats the rule into a proposal ---------- */
    const page = await browser.newPage();
    await page.goto(`${API}/console/`);
    await page.waitForSelector('input[name="username"]', { timeout: 20000 });
    await page.fill('input[name="username"]', 'pat@bss.local');
    await page.fill('input[name="password"]', 'pat');
    await page.click('input[type="submit"], button[type="submit"]');
    await page.locator('.tab', { hasText: 'Copilot' }).click();
    await page.waitForSelector('#copilot-input', { timeout: 10000 });
    await page.fill('#copilot-input',
      'When guests have been browsing Devices, show them the banner and pin the 60 GB plan');
    await page.click('#copilot-send');
    await page.locator('[data-testid=copilot-proposal]').waitFor({ timeout: 20000 });
    const card = await page.locator('[data-testid=copilot-proposal]').textContent();
    if (!/experience rule/.test(card) || !/Phone week/.test(card) || !/Devices/.test(card)) {
      fail('the proposal card does not describe the experience rule: ' + card.slice(0, 300));
    }
    console.log('OK CHATTED: "what should device browsers see" became a validated experience-rule'
      + ' proposal — banner, interest and pin, readable on one card');

    /* ---------- 2. one click applies it, as data ---------- */
    await page.locator('[data-testid=copilot-create]').click();
    await page.locator('[data-testid=copilot-created]').waitFor({ timeout: 20000 });
    const rules = await (await ctx.request.get(
      `${API}/tmf-api/policyManagement/v4/policyRule?limit=100`, { headers: H(staff) })).json();
    const made = rules.find((r) => r.name === 'Device browsers welcome'
      && r.domain === 'personalization');
    if (!made || !made.enabled) fail('the experience rule did not land in policy: '
      + JSON.stringify(made || {}));
    ruleId = made.id;
    console.log(`OK APPLIED: policy row ${made.id.slice(0, 8)}… — domain personalization,`
      + ' created with the OWNER\'s token (the model never writes), disable-able in Rules');

    /* ---------- 3. the real proof: a guest sees the chatted shop ---------- */
    const guest = await (await browser.newContext()).newPage();
    await guest.goto(`${API}/shop/`);
    await guest.locator('[data-testid=consent-banner]').waitFor({ timeout: 15000 });
    await guest.click('[data-testid=consent-accept]');
    await guest.locator('text=Samsung').first().click();
    await guest.waitForTimeout(1500);
    await guest.goBack();
    await guest.locator('.cards').first().waitFor({ timeout: 10000 });
    let seen = false;
    for (let i = 0; i < 20 && !seen; i++) {
      await guest.reload();
      seen = await guest.locator('[data-testid=personal-banner]', { hasText: 'Phone week' })
        .waitFor({ timeout: 6000 }).then(() => true).catch(() => false);
    }
    if (!seen) fail('the guest never saw the chatted banner');
    const pick = await guest.locator('[data-testid=personal-pick]').textContent()
      .catch(() => '');
    if (!/60 GB/.test(pick)) fail('the chatted pin is not on the guest\'s page: ' + pick);
    console.log('OK ON THE GUEST\'S PAGE: a brand-new consenting visitor browsed devices and was'
      + ' greeted with the CHATTED banner over the CHATTED pin — conversation to storefront,'
      + ' no deploy, no JSON');

    /* ---------- 4. deleted as data ---------- */
    await cleanup();
    const gone = await (await ctx.request.get(
      `${API}/tmf-api/policyManagement/v4/policyRule?limit=100`, { headers: H(staff) })).json();
    if (gone.some((r) => r.id === ruleId)) fail('the rule survived deletion');
    console.log('OK DELETED AS DATA: the rule is gone and the shop\'s defaults return —'
      + ' personalization an operator can chat into existence and switch off in one click');

    console.log('\nALL COPILOT-EXPERIENCE CHECKS PASSED — the product owner talked, the'
      + ' validator checked, one click made it a policy row, and the next consenting guest'
      + ' saw the difference. Rules as data; authoring as conversation.');
    await browser.close();
  } catch (e) {
    await cleanup().catch(() => {});
    throw e;
  }
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
