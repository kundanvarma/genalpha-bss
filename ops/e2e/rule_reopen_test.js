/* Reopen a rule and it tells you what it does. Suite #249.
 *
 * Ticket #159. A saved rule keeps only its derived JSON-logic condition — the
 * kind the operator picked, and the plain inputs behind it, were never stored.
 * Every field on the form after the kind is gated on the kind. So opening a
 * saved rule showed its name and its message and NOTHING ELSE, while the form
 * quietly held the real values underneath.
 *
 * Measured on the live form before the fix, for the rule `50percent disc`:
 *
 *     ruleKind        visible   value ""      <- the cause
 *     adjustmentType  HIDDEN    percent
 *     adjustmentValue HIDDEN    50
 *     condition       HIDDEN    {"var":"verifiedIdentity"}
 *
 * Three consequences, in rising order: you cannot review a rule; you cannot
 * safely edit one, because Save submits whatever the hidden fields hold; and it
 * hides mistakes. A rule that adds **+50%** to every verified customer looked
 * identical to one that takes 50 off a phone. That is how a surcharge shipped
 * as a discount, and nobody could see it on the screen that owns it.
 *
 * What this suite proves, in a real browser against the real console:
 *
 *  - EVERY KIND READS BACK. A rule of each authored shape is created through
 *    the API exactly as the form writes it, then reopened. The kind must come
 *    back right, and every field that kind gates must be VISIBLE and hold the
 *    value it was saved with.
 *  - THE SIGN IS ON THE SCREEN. The surcharge rule from the ticket is rebuilt
 *    and reopened: +50 must be readable, beside a label that says positive is a
 *    surcharge. This is the rung that would have caught the original mistake.
 *  - A HAND-WRITTEN CONDITION IS NEVER A BLACK BOX. A rule whose condition this
 *    form did not author falls back to "advanced" and shows the raw JSON-logic,
 *    rather than an empty form that implies the rule does nothing.
 *
 * HONEST LIMITS: this asserts what the form DISPLAYS on reopen. It does not
 * re-save and diff the result — that is #162's territory, which is about what a
 * rule binds to and what its sign means, not about whether you can see it.
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
const tag = `R159-${Date.now()}`;

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
    /* ---------- an offering for the rules that name one ---------- */
    const offering = await created('POST', `${CAT}/productOffering`, staff, {
      name: `${tag} Phone`, description: 'suite fixture', lifecycleStatus: 'In design',
      version: '1.0', isBundle: false, isSellable: true });
    made.push([`${CAT}/productOffering`, offering.id]);

    /* ---------- one rule per authored shape, written as the form writes them ---------- */
    const rule = async (name, extra) => {
      const r = await created('POST', `${POL}/policyRule`, staff,
        { name: `${tag} ${name}`, priority: 100, enabled: false, message: `${tag} ${name}`, ...extra });
      made.push([`${POL}/policyRule`, r.id]);
      return r;
    };

    const cases = [
      { label: 'the surcharge from the ticket', kind: 'price-verified',
        rule: await rule('verified surcharge', { domain: 'pricing', effect: 'adjust',
          condition: JSON.stringify({ var: 'verifiedIdentity' }),
          adjustmentType: 'percent', adjustmentValue: 50 }),
        // the sign is the whole point: +50 is a SURCHARGE
        expect: { adjustmentType: 'percent', adjustmentValue: '50', 'adjustmentValue.direction': 'surcharge' } },

      { label: 'a discount when an item is in the cart', kind: 'price-when-item',
        rule: await rule('item discount', { domain: 'pricing', effect: 'adjust',
          condition: JSON.stringify({ in: [offering.id, { var: 'offeringIds' }] }),
          adjustmentType: 'amount', adjustmentValue: -50 }),
        expect: { adjustmentType: 'amount', adjustmentValue: '50', 'adjustmentValue.direction': 'discount', offeringA: offering.id } },

      { label: 'a quantity cap', kind: 'quantity-cap',
        rule: await rule('max three', { domain: 'order', effect: 'deny',
          condition: JSON.stringify({ '>': [{ var: `quantityByOffering.${offering.id}` }, 3] }) }),
        expect: { maxQuantity: '3', offeringA: offering.id } },

      { label: 'a volume deal', kind: 'price-volume',
        rule: await rule('volume ten', { domain: 'pricing', effect: 'adjust',
          condition: JSON.stringify({ '>=': [{ var: 'memberCount' }, 10] }),
          adjustmentType: 'percent', adjustmentValue: -15 }),
        expect: { minMembers: '10', adjustmentValue: '15', 'adjustmentValue.direction': 'discount' } },

      { label: 'a campaign on a configured choice', kind: 'price-characteristic',
        rule: await rule('icy blue', { domain: 'pricing', effect: 'adjust',
          condition: JSON.stringify({ in: ['color:Icy Blue', { var: 'characteristicValues' }] }),
          adjustmentType: 'amount', adjustmentValue: -200 }),
        expect: { characteristicName: 'color', characteristicValue: 'Icy Blue', adjustmentValue: '200' } },

      { label: 'a hand-written condition this form never authored', kind: 'advanced',
        rule: await rule('hand written', { domain: 'order', effect: 'deny',
          condition: JSON.stringify({ and: [{ '==': [{ var: 'deliveryMethod' }, 'home'] },
            { '==': [{ var: 'addressSource' }, 'manual'] }] }) }),
        expect: { condition: 'deliveryMethod' } },
    ];
    ok(`${cases.length} rules created, one per authored shape`);

    /* ---------- the console ---------- */
    browser = await chromium.launch();
    const page = await browser.newPage({ viewport: { width: 1600, height: 1200 } });
    const signIn = async () => {
      if (await page.locator('input[name="username"]').count()) {
        await page.fill('input[name="username"]', 'demo');
        await page.fill('input[name="password"]', 'demo');
        await page.click('input[type="submit"], button[type="submit"]');
      }
      await page.waitForSelector('#username', { timeout: 30000 });
    };
    await page.goto(`${API}/console/`);
    await page.waitForSelector('input[name="username"], #username', { timeout: 30000 });
    await signIn();

    /** Open one rule's form from a cold load — the drawer must not be mid-slide. */
    const openRule = async (name) => {
      await page.evaluate(() => sessionStorage.removeItem('bss.console.tab')).catch(() => {});
      await page.goto(`${API}/console/`);
      await signIn();
      // EXACT: hasText is a substring match, and the console also carries
      // 'CPQ rules' and 'Guided rules'. The loose locator opened the quote
      // desk and every rule here looked missing.
      await page.locator('.tab', { hasText: /^Rules$/ }).first().click();
      await page.waitForSelector('#listing-body tr', { timeout: 20000 });
      // NOT the list filter: it searches the page you are on, not the list
      // (#160). Paging is the only honest way to reach a row from here.
      const row = page.locator('#listing-body tr', { hasText: name });
      for (let hop = 0; hop < 40 && !(await row.count()); hop++) {
        if (await page.locator('#next').isDisabled()) break;
        const before = await page.locator('#listing-body').innerText();
        await page.click('#next');
        await page.waitForFunction((prev) => (document.getElementById('listing-body')?.innerText || '') !== prev,
          before, { timeout: 20000 }).catch(() => {});
      }
      if (!(await row.count())) fail(`the rule "${name}" is not in the listing`);
      await row.first().locator('[data-testid="row-open"]').click();
      await page.waitForSelector('[data-field="ruleKind"]', { timeout: 20000 });
      await page.waitForTimeout(400); // the drawer settles, the controls fill
    };

    /** What the operator can actually SEE and read in a field. */
    const shown = async (field) => {
      const wrap = page.locator(`[data-field="${field.split('.')[0]}"]`);
      if (!(await wrap.count())) return { present: false };
      const visible = await wrap.isVisible();
      const control = page.locator(`[name="${field}"]`).first();
      const value = (await control.count()) ? await control.inputValue().catch(() => '') : '';
      return { present: true, visible, value };
    };

    for (const c of cases) {
      await openRule(c.rule.name);

      const kind = await shown('ruleKind');
      if (kind.value !== c.kind) {
        fail(`${c.label}: reopened as kind "${kind.value}", expected "${c.kind}"`
          + ' — an empty kind is the defect itself: every field it gates stays hidden');
      }

      for (const [field, expected] of Object.entries(c.expect)) {
        const got = await shown(field);
        if (!got.present) fail(`${c.label}: the form has no ${field} field at all`);
        if (!got.visible) {
          fail(`${c.label}: ${field} is HIDDEN while holding "${got.value}"`
            + ' — the rule is fine, the screen will not show it');
        }
        const hit = field === 'condition' || field === 'offeringA'
          ? String(got.value).includes(expected)
          : String(got.value) === String(expected);
        if (!hit) fail(`${c.label}: ${field} shows "${got.value}", saved as "${expected}"`);
      }
      ok(`${c.label} — reopened as ${c.kind}, every saved value on screen`);
    }

    /* ---------- the sign, which is what the mistake turned on ---------- */
    await openRule(`${tag} verified surcharge`);
    const direction = (await shown('adjustmentValue.direction')).value;
    const value = (await shown('adjustmentValue')).value;
    if (direction !== 'surcharge') fail(`a +50 rule reopened as "${direction}", not a surcharge`);
    if (String(value) !== '50') fail(`the amount reads "${value}", not 50`);
    ok('a +50% rule reopens as Surcharge / 50 — the direction is read back, not inferred by the reader');

    console.log('\nPASS rule_reopen_test — a saved rule now says what it does');
  } finally {
    if (browser) await browser.close();
    for (const [path, id] of made.reverse()) {
      await call('DELETE', `${path}/${id}`, staff).catch(() => {});
    }
    console.log('fixtures removed');
  }
})().catch((e) => { console.error('\nFAIL ' + e.message); process.exit(1); });
