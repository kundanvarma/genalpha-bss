/* A rule cannot quietly charge more, or quietly apply to everything. Suite #251.
 *
 * Ticket #162. A pricing rule could be saved that binds to NO product and
 * charges more while being called a discount. Both happened in one sitting and
 * neither was visible. What was live on the demo tenant:
 *
 *     name:       50percent disc
 *     enabled:    true
 *     adjustment: percent  50.0            <- POSITIVE means surcharge
 *     condition:  {"var":"verifiedIdentity"}   <- every verified customer, any product
 *     message:    "50 percent discount"
 *
 * The operator meant *50 off one phone*. What was saved charged every
 * identity-verified customer 50% MORE on everything they buy.
 *
 * Two traps did it, and this suite holds both shut:
 *
 *  - THE SIGN WAS A LABEL. `adjustmentValue` meant "negative = discount,
 *    positive = surcharge", stated only in the field's caption. Typing 50 for
 *    "50 off" produced a surcharge in silence. It is now two controls — which
 *    way, and how much — so the direction is a choice rather than a convention
 *    nobody reads. The wire format is unchanged: still one signed number.
 *  - THE REACH WAS INVISIBLE. A pricing rule that names no product applies to
 *    every basket that matches. Saving one now asks, in the operator's own
 *    numbers, and says ADDS where the rule adds. It is a confirmation, not a
 *    block: a basket-wide fee is a legitimate rule.
 *
 * Also proven: a kind with no Item field says WHY in the field's place, rather
 * than leaving a hole an operator reads as a broken screen.
 *
 * HONEST LIMITS: the warning fires on reach, not on intent — it cannot know
 * that "50 percent discount" in the message contradicts a +50 adjustment. It
 * asks the question; the operator still answers it. And it is a browser
 * confirm(), so an operator who clicks through habitually is not protected;
 * what it buys is that the reach was stated once, in numbers, before the save.
 *
 * Everything this suite creates, it deletes.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const POL = '/tmf-api/policyManagement/v4';
const CAT = '/tmf-api/productCatalogManagement/v4';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const tag = `R162-${Date.now()}`;

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  const j = await r.json();
  if (!j.access_token) fail(`token(${user}) refused`);
  return j.access_token;
}
async function call(method, p, tok, body) {
  const r = await fetch(API + p, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}),
      'Cache-Control': 'no-cache' },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* a refusal need not be JSON */ }
  return { status: r.status, body: json, text };
}
async function created(method, p, tok, body) {
  const r = await call(method, p, tok, body);
  if (r.status !== 201 && r.status !== 200) fail(`${method} ${p}: ${r.status} ${r.text.slice(0, 200)}`);
  return r.body;
}

(async () => {
  const staff = await token('demo', 'demo');
  const made = [];
  let browser = null;
  try {
    const offering = await created('POST', `${CAT}/productOffering`, staff, {
      name: `${tag} Phone`, lifecycleStatus: 'Active', version: '1.0', isSellable: true });
    made.push([`${CAT}/productOffering`, offering.id]);

    browser = await chromium.launch();
    const page = await browser.newPage({ viewport: { width: 1600, height: 1200 } });
    const signIn = async () => {
      if (await page.locator('input[name="username"]').count()) {
        await page.fill('input[name="username"]', 'demo');
        await page.fill('input[name="password"]', 'demo');
        await page.click('input[type="submit"], button[type="submit"]');
      }
      await page.waitForSelector('#username', { timeout: 40000 });
    };
    const openRules = async () => {
      await page.evaluate(() => sessionStorage.removeItem('bss.console.tab')).catch(() => {});
      await page.goto(`${API}/console/`);
      await signIn();
      await page.locator('.tab', { hasText: /^Rules$/ }).first().click();
      await page.waitForSelector('[data-field="ruleKind"]', { timeout: 30000 });
    };
    await page.goto(`${API}/console/`);
    await page.waitForSelector('input[name="username"], #username', { timeout: 40000 });
    await signIn();
    await openRules();

    /* ---------- the sign is two decisions, not one convention ---------- */

    await page.selectOption('[name="ruleKind"]', 'price-always');
    await page.waitForTimeout(400);
    const way = page.locator('[name="adjustmentValue.direction"]');
    if (!(await way.count())) fail('the adjustment is still one signed number — the sign is still a convention in a caption');
    if (!(await way.isVisible())) fail('the direction control exists but is hidden');
    const options = await way.locator('option').allInnerTexts();
    if (!options.some((o) => /discount/i.test(o)) || !options.some((o) => /surcharge/i.test(o))) {
      fail(`the direction control offers ${JSON.stringify(options)} — it must name both ways`);
    }
    ok(`the sign is a choice: ${JSON.stringify(options)}`);

    const caption = await page.locator('[data-field="adjustmentValue"]').innerText();
    if (/negative *=/.test(caption)) {
      fail('the caption still explains the sign convention — that is the thing that failed');
    }
    ok('the caption no longer asks the operator to remember what a minus sign means');

    /* ---------- a kind with no Item says why ---------- */

    const note = page.locator('[data-absent="offeringA"]');
    if (!(await note.count()) || !(await note.isVisible())) {
      fail('a kind with no Item field leaves a hole and no reason — the screen reads as broken');
    }
    const noteText = await note.innerText();
    if (!/basket/i.test(noteText)) fail(`the absent-item note reads "${noteText}" without saying what it applies to`);
    ok(`where the Item field would be: "${noteText}"`);

    // and it is gone again for a kind that DOES name an item
    await page.selectOption('[name="ruleKind"]', 'price-when-item');
    await page.waitForTimeout(400);
    if (await page.locator('[data-absent="offeringA"]').isVisible()) {
      fail('the note still claims there is no item to name, on the kind that names one');
    }
    if (!(await page.locator('[data-field="offeringA"]').isVisible())) fail('the Item field is hidden on the kind that needs it');
    ok('on the kind that names an item, the field is there and the note is gone');

    /* ---------- saving a basket-wide rule asks first ---------- */

    await page.selectOption('[name="ruleKind"]', 'price-always');
    await page.waitForTimeout(400);
    await page.fill('[name="name"]', `${tag} basket wide`);
    await page.fill('[name="message"]', `${tag} basket wide`);
    await page.selectOption('[name="adjustmentType"]', 'percent');
    await page.selectOption('[name="adjustmentValue.direction"]', 'surcharge');
    await page.fill('[name="adjustmentValue"]', '50');

    let asked = null;
    page.on('dialog', async (d) => { asked = d.message(); await d.dismiss(); });
    await page.click('#save');
    await page.waitForTimeout(1500);

    if (!asked) fail('a pricing rule that names no product saved without a word — the defect is unchanged');
    if (!/every/i.test(asked)) fail(`the warning reads "${asked}" without saying it applies to every matching basket`);
    if (!/adds/i.test(asked)) fail(`the warning reads "${asked}" without saying the rule ADDS`);
    if (!/50/.test(asked)) fail(`the warning reads "${asked}" without the operator's own number`);
    ok(`saving it asks: "${asked}"`);

    // dismissed means NOT saved
    const after = (await call('GET', `${POL}/policyRule?limit=100`, staff)).body || [];
    if (after.some((r) => r.name === `${tag} basket wide`)) {
      fail('the rule was saved even though the warning was dismissed');
    }
    ok('dismissing the warning did not save the rule');

    /* ---------- a rule that names a product saves without a warning ---------- */

    asked = null;
    await page.selectOption('[name="ruleKind"]', 'price-when-item');
    await page.waitForTimeout(400);
    await page.fill('[name="name"]', `${tag} one phone`);
    await page.fill('[name="message"]', `${tag} one phone`);
    await page.selectOption('[name="adjustmentType"]', 'amount');
    await page.selectOption('[name="adjustmentValue.direction"]', 'discount');
    await page.fill('[name="adjustmentValue"]', '50');
    await page.selectOption('[name="offeringA"]', offering.id).catch(() => {});
    await page.click('#save');
    await page.waitForTimeout(2500);

    if (asked) fail(`a rule naming one product still warned: "${asked}" — the warning must be about reach, not noise`);
    const saved = ((await call('GET', `${POL}/policyRule?limit=100`, staff)).body || [])
      .find((r) => r.name === `${tag} one phone`);
    if (!saved) fail('a rule naming one product did not save');
    made.push([`${POL}/policyRule`, saved.id]);
    if (Number(saved.adjustmentValue) !== -50) {
      fail(`"Discount, 50" was stored as ${saved.adjustmentValue} — the control must still write one signed number`);
    }
    ok('a rule naming one product saves without a warning, and "Discount 50" is stored as -50');

    console.log('\nPASS rule_sign_test — the direction is a choice, and reach is stated before it is saved');
  } finally {
    if (browser) await browser.close();
    for (const [path, id] of made.reverse()) {
      await call('DELETE', `${path}/${id}`, staff).catch(() => {});
    }
    console.log('fixtures removed');
  }
})().catch((e) => { console.error('\nFAIL ' + e.message); process.exit(1); });
