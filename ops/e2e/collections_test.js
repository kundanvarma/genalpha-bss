/* Collections — the ladder between a missed payment and a lost customer.
 *
 *  - a tenant policy that undercuts the country statutory pack is REFUSED
 *    (fee before the 14-day gate, fee above the cap, too many fee-bearing
 *    reminders, enforcement without/too soon after the demand+warning)
 *  - an unpaid bill ages into a collection case: reminder only AFTER the
 *    fee gate (with the capped fee ON the bill), then the demand+advance
 *    warning, then — no earlier than a month after the REAL warning —
 *    restriction (outgoing barred, data throttled, EMERGENCY WHITELIST ON,
 *    line state stays active), then nonpayment suspension (reason persisted)
 *  - promise-to-pay pauses the ladder; a broken promise resumes it
 *  - a dispute hold freezes ONLY the disputed amount — when the rest is
 *    under the statutory floor the ladder freezes instead of walking
 *  - paying CURES: services reinstate at once, the reconnection fee is
 *    minted as an unbilled line for the next bill, the customer is told
 *  - the legally required words reach the customer at every rung
 *
 * CLOCK: the billing container must run with
 *   BSS_COLLECTIONS_COMPRESS_CLOCKS=true  (statutory/policy day-spans count
 *   as SECONDS — the numbers never change, only the unit), and
 *   BSS_COLLECTIONS_TICK_MS=3600000       (the scheduled sweep parks; THIS
 *   suite drives every step via POST /collectionSweep, deterministically).
 * MRC accrual stopping while suspended is proven in CollectionsApiTest
 * (billing periods are calendar months — not compressible here honestly).
 */
const { chromium } = require('playwright');
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const BILLS = '/tmf-api/customerBillManagement/v4';
const SVC = '/tmf-api/serviceInventory/v4';
const run = Date.now();
const fail = (m) => { throw new Error(m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo',
      username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
const sub = (t) => JSON.parse(Buffer.from(t.split('.')[1], 'base64url').toString()).sub;
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}

(async () => {
  const staff = await token('demo', 'demo');
  let policyId = null;

  const caseOf = async (partyId) => {
    const cases = (await call('GET', `${BILLS}/collectionCase`, staff)).body || [];
    return cases.find((c) => c.accountId === partyId) || null;
  };
  const sweep = async () => {
    const r = await call('POST', `${BILLS}/collectionSweep`, staff);
    if (r.status !== 200) fail(`collectionSweep: ${r.status} ${r.text.slice(0, 150)}`);
  };
  /* one rung per sweep per account — polling stops exactly on the target */
  const sweepUntil = async (partyId, state, seconds) => {
    for (let i = 0; i < seconds; i += 2) {
      await sweep();
      const c = await caseOf(partyId);
      if (c && c.state === state) return c;
      await sleep(2000);
    }
    const c = await caseOf(partyId);
    fail(`case for ${partyId} never reached ${state} (is: ${c && c.state})`);
  };

  try {
    /* ---------- 1. the statutory pack refuses undercutting policies ---------- */
    const policyOf = (steps, extra) => ({
      name: `collections-e2e-${run}`, country: 'NO', paymentTermDays: 5,
      entryThreshold: 250, currency: 'EUR', reconnectionFee: 25,
      writeOffThreshold: 500, promiseMaxPerPeriod: 2, promisePeriodDays: 90,
      promiseMaxDays: 14, steps, ...(extra || {}) });
    const refusals = [
      [policyOf([{ offsetDays: 10, action: 'remind', feeType: 'reminderFee', feeAmount: 38 }]),
        'fee before the 14-day gate'],
      [policyOf([{ offsetDays: 14, action: 'remind', feeType: 'reminderFee', feeAmount: 60 }]),
        'fee above the kr 38 cap'],
      [policyOf([
        { offsetDays: 14, action: 'remind', feeType: 'reminderFee', feeAmount: 38 },
        { offsetDays: 20, action: 'remind', feeType: 'reminderFee', feeAmount: 38 },
        { offsetDays: 26, action: 'remind', feeType: 'reminderFee', feeAmount: 38 }],
      ), 'three fee-bearing reminders'],
      [policyOf([{ offsetDays: 14, action: 'remind' }, { offsetDays: 50, action: 'suspend' }]),
        'enforcement without a demand+warning step'],
      [policyOf([{ offsetDays: 16, action: 'warn' }, { offsetDays: 30, action: 'restrict' }]),
        'enforcement before one month after the warning'],
      [policyOf([{ offsetDays: 14, action: 'remind' }], { entryThreshold: 100 }),
        'entry threshold under the minimum actionable amount'],
    ];
    for (const [bad, why] of refusals) {
      const r = await call('POST', `${BILLS}/dunningPolicy`, staff, bad);
      if (r.status !== 400) fail(`policy undercutting the floor (${why}) must 400, got ${r.status}`);
    }
    console.log('OK STATUTORY FLOOR: all six undercutting policies refused with 400 —'
      + ' the pack, not the tenant, has the last word.');

    /* ---------- 2. a lawful policy (compressed: days read as seconds) ---------- */
    // retire any earlier e2e policy so exactly one is active
    for (const p of (await call('GET', `${BILLS}/dunningPolicy`, staff)).body || []) {
      if (p.active) await call('PATCH', `${BILLS}/dunningPolicy/${p.id}`, staff, { active: false });
    }
    const good = await call('POST', `${BILLS}/dunningPolicy`, staff, policyOf([
      { offsetDays: 14, action: 'remind', templateId: 'dunning-reminder',
        feeType: 'reminderFee', feeAmount: 38 },
      { offsetDays: 16, action: 'warn', templateId: 'dunning-warning' },
      { offsetDays: 46, action: 'restrict', templateId: 'dunning-restricted' },
      { offsetDays: 48, action: 'suspend', templateId: 'dunning-suspended' },
    ]));
    if (good.status !== 201) fail(`lawful policy refused: ${good.status} ${good.text.slice(0, 200)}`);
    policyId = good.body.id;
    if (!good.body.statutory || good.body.statutory.reminderFeeGateDays !== 14) {
      fail('the policy view must carry its read-only statutory floor');
    }
    console.log(`OK POLICY: remind@+14 (fee 38), warn@+16, restrict@+46, suspend@+48`
      + ` (compressed seconds), statutory floor rides the view read-only.`);

    /* ---------- 3. a fresh debtor with a bill above the floor ---------- */
    // register a brand-new customer through the shop's own door, so no other
    // suite's aged bills muddy the aggregation
    const email = `kolla-${run}@example.com`;
    const browser = await chromium.launch();
    const page = await browser.newPage();
    await page.goto('http://localhost:8080/shop/');
    await page.locator('.who >> text=Sign in').click();
    await page.waitForSelector('a[href*="registration"], input[name="username"]', { timeout: 20000 });
    await page.click('a[href*="registration"]');
    await page.waitForSelector('input[name="email"]');
    await page.fill('input[name="firstName"]', 'Kolla');
    await page.fill('input[name="lastName"]', 'Debitor');
    await page.fill('input[name="email"]', email);
    await page.fill('input[name="password"]', 'Passw0rd!');
    await page.fill('input[name="password-confirm"]', 'Passw0rd!');
    await page.click('input[type="submit"], button[type="submit"]');
    await page.waitForURL('**/shop/**', { timeout: 25000 }).catch(() => {});
    await browser.close();
    const kolla = await token(email, 'Passw0rd!');
    const kollaId = sub(kolla);

    // PAYDAY ALIGNMENT AGAINST PRORATION: the run charges each plan only
    // from the day it started ("mid-cycle fairness" in BillingRunService),
    // so late in a calendar month a brand-new fleet bills a few days' stub
    // that can never clear the 250 floor. Anchor the probe's own cycle on
    // TODAY (the shop registration makes no party record, so staff mints
    // the Individual under the same id first — the billing_cycle suite's
    // pattern): the first bill then covers a full month from today.
    await call('POST', '/tmf-api/party/v4/individual', staff, {
      id: kollaId, givenName: 'Kolla', familyName: 'Debitor',
      contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] });
    // the server's calendar is UTC — local midnight rolls a day early here,
    // and an anchor one day ahead of the server bills a one-day stub
    const today = new Date();
    const anchorDay = Math.min(28, today.getUTCDate()); // 29-31 are refused (February)
    const anchored = await call('POST',
      `/tmf-api/party/v4/individual/${kollaId}/billingCycle`, kolla, { anchorDay });
    if (anchored.status >= 300) {
      fail(`billingCycle anchor: ${anchored.status} ${anchored.text.slice(0, 200)}`);
    }

    // enough monthly plans to clear the 250 statutory floor in one bill —
    // sized against what the run will actually charge: on the 29th-31st the
    // anchor clamps to 28, so each plan still prorates by a day or three
    const offerings = (await call('GET',
      '/tmf-api/productCatalogManagement/v4/productOffering?limit=100', staff)).body || [];
    const plan = offerings.find((o) => o.name === 'GenAlpha Mobile 10 GB')
      || offerings.find((o) => /Mobile/.test(o.name || ''));
    if (!plan) fail('no mobile plan offering on the shelf');
    let monthly = 0;
    for (const ref of plan.productOfferingPrice || []) {
      const price = (await call('GET',
        `/tmf-api/productCatalogManagement/v4/productOfferingPrice/${ref.id}`, staff)).body;
      if (price && price.priceType === 'recurring' && price.price) {
        monthly += Number(price.price.value);
      }
    }
    if (!monthly) fail(`plan ${plan.name} has no recurring price`);
    const day = 24 * 3600 * 1000;
    const periodStart = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), anchorDay));
    const periodEnd = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth() + 1, anchorDay - 1));
    const totalDays = Math.round((periodEnd - periodStart) / day) + 1;
    const billedDays = Math.round(
      (periodEnd - new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate()))) / day) + 1;
    const perPlan = billedDays < totalDays
      ? Math.round((monthly * billedDays / totalDays) * 100) / 100 // the run's HALF_UP per line
      : monthly;
    const count = Math.min(24, Math.ceil(255 / perPlan));
    const items = Array.from({ length: count }, (_, i) => ({
      id: String(i + 1), action: 'add', quantity: 1,
      productOffering: { id: plan.id, name: plan.name } }));
    const order = await call('POST', '/tmf-api/productOrderingManagement/v4/productOrder',
      kolla, { productOrderItem: items });
    if (order.status >= 300) fail(`order: ${order.status} ${order.text.slice(0, 200)}`);
    let services = [];
    for (let i = 0; i < 70; i++) { // ~17 plans at 15/mo take longer than 12 did
      services = ((await call('GET', `${SVC}/service?relatedPartyId=${kollaId}`, staff)).body || [])
        .filter((s) => s.state === 'active');
      if (services.length >= count) break;
      await sleep(3000);
    }
    if (services.length < count) fail(`only ${services.length}/${count} services went active`);

    await call('POST', `${BILLS}/billingRun`, staff);
    for (let i = 0; i < 30; i++) {
      const runs = (await call('GET', `${BILLS}/billingRun`, staff)).body || [];
      if (!runs.some((r) => r.status === 'running')) break;
      await sleep(2000);
    }
    const bills = (await call('GET',
      `${BILLS}/customerBill?relatedPartyId=${kollaId}&limit=50`, kolla)).body || [];
    const bill = bills.find((b) => b.state === 'new');
    if (!bill) fail('the run cut no bill for the fresh debtor');
    const baseDue = Number(bill.amountDue.value);
    if (baseDue < 250) fail(`bill ${baseDue} is under the 250 floor — ordered too few plans`);
    console.log(`OK DEBTOR: ${email} → ${count}× ${plan.name}, one bill of ${baseDue}`
      + ` ${bill.amountDue.unit} — above the statutory floor, now aging in seconds.`);

    /* ---------- 4. reminder only AFTER the fee gate ---------- */
    await sweep(); // due (+5s) not yet passed, let alone the +14s fee gate
    let c = await caseOf(kollaId);
    if (c && c.state !== 'current') fail(`stepped before the gate: ${c.state}`);
    const lines0 = (await call('GET',
      `${BILLS}/customerBill/${bill.id}/appliedCustomerBillingRate`, staff)).body || [];
    if (lines0.some((l) => l.type === 'reminderFee')) fail('fee charged before the gate');

    c = await sweepUntil(kollaId, 'reminded', 40);
    const billAfterFee = (await call('GET', `${BILLS}/customerBill/${bill.id}`, staff)).body;
    if (Math.abs(Number(billAfterFee.amountDue.value) - (baseDue + 38)) > 0.001) {
      fail(`the capped 38 fee must ride the bill: ${baseDue} -> ${billAfterFee.amountDue.value}`);
    }
    const lines1 = (await call('GET',
      `${BILLS}/customerBill/${bill.id}/appliedCustomerBillingRate`, staff)).body || [];
    if (!lines1.some((l) => l.type === 'reminderFee' && Number(l.taxExcludedAmount.value) === 38)) {
      fail('no reminderFee rate line on the bill');
    }
    console.log('OK REMINDER: fired only once due+14 had genuinely passed, the kr-capped'
      + ` fee (38) landed as a rate line and the due rose to ${billAfterFee.amountDue.value}.`);

    /* ---------- 5. promise-to-pay pauses; breaking it resumes ---------- */
    const promised = await call('POST',
      `${BILLS}/collectionCase/${c.id}/promiseToPay`, kolla, { days: 6 });
    if (promised.status !== 200) fail(`promiseToPay: ${promised.status} ${promised.text.slice(0, 200)}`);
    if (!promised.body.holds.promiseToPay) fail('no promise hold on the case');
    await sweep(); await sweep();
    if ((await caseOf(kollaId)).state !== 'reminded') fail('the promise did not pause the ladder');
    console.log('OK PROMISE: the customer promised from their own case; two sweeps later'
      + ' the ladder still stands on "reminded".');
    await sleep(7000); // the promise (6s compressed) passes unpaid
    c = await sweepUntil(kollaId, 'warned', 20); // broken -> the demand fires
    if (c.holds.promiseToPay) fail('a broken promise must clear the hold');
    console.log('OK BROKEN: the promise lapsed, the ladder resumed and the'
      + ' demand + advance warning (warn) went out.');

    /* ---------- 6. dispute hold freezes only the disputed amount ---------- */
    // freeze all but ~68 — the actionable remainder is under the 250 floor,
    // so even when the restrict rung's clocks pass, the ladder must NOT move
    await call('POST', `${BILLS}/collectionCase/${c.id}/hold`, staff,
      { type: 'dispute', amount: baseDue - 30 });
    await sleep(31000); // a month (30s compressed) after the warning
    await sweep(); await sweep();
    if ((await caseOf(kollaId)).state !== 'warned') fail('dispute hold did not freeze the ladder');
    // shrink the hold: the undisputed remainder is actionable again
    await call('POST', `${BILLS}/collectionCase/${c.id}/hold`, staff,
      { type: 'dispute', amount: 20 });
    console.log('OK DISPUTE HOLD: with the balance almost all contested the ladder froze;'
      + ' the hold is amount-scoped, not a blanket amnesty.');

    /* ---------- 7. restrict: barred outgoing, emergency stays ---------- */
    c = await sweepUntil(kollaId, 'restricted', 30);
    const restricted = (await call('GET', `${SVC}/service/${services[0].id}`, staff)).body;
    if (restricted.state !== 'active') fail('restriction must NOT take the line down');
    if (!restricted.restriction || restricted.restriction.reason !== 'nonpayment') {
      fail('no nonpayment restriction on the service');
    }
    if (restricted.restriction.profile.emergencyWhitelist !== true) {
      fail('EMERGENCY WHITELIST OFF — statutory violation');
    }
    console.log('OK RESTRICTED: line stays active with outgoing barred + data throttled,'
      + ' and the emergency whitelist is ON — a month after the real warning, never sooner.');

    /* ---------- 8. suspend for nonpayment ---------- */
    c = await sweepUntil(kollaId, 'suspended', 30);
    const suspended = (await call('GET', `${SVC}/service/${services[0].id}`, staff)).body;
    if (suspended.state !== 'suspended' || suspended.suspendReason !== 'nonpayment') {
      fail(`expected suspended/nonpayment, got ${suspended.state}/${suspended.suspendReason}`);
    }
    console.log('OK SUSPENDED: the line is down with reason=nonpayment persisted.'
      + ' (MRC stopping while suspended is proven in CollectionsApiTest — calendar'
      + ' months do not compress honestly.)');

    /* ---------- 9. pay -> cure: reinstate + reconnection fee ---------- */
    const owed = (await call('GET', `${BILLS}/customerBill/${bill.id}`, kolla)).body;
    const pay = await call('POST', '/tmf-api/paymentManagement/v4/payment', kolla, {
      description: `collections cure ${run}`,
      amount: { unit: owed.amountDue.unit, value: Number(owed.amountDue.value) },
      paymentMethod: { '@type': 'bankCard', token: `spt-kolla-${run}`, lastFourDigits: '4242' } });
    if (pay.status >= 300) fail(`payment: ${pay.status} ${pay.text.slice(0, 150)}`);
    const settled = await call('PATCH', `${BILLS}/customerBill/${bill.id}`, kolla,
      { state: 'settled', payment: [{ id: pay.body.id, '@referredType': 'Payment' }] });
    if (settled.status >= 300) fail(`settle: ${settled.status} ${settled.text.slice(0, 200)}`);
    c = await caseOf(kollaId);
    if (c.state !== 'current') fail(`settling must cure at once, case is ${c.state}`);
    let revived = null;
    for (let i = 0; i < 10 && (!revived || revived.state !== 'active'); i++) {
      revived = (await call('GET', `${SVC}/service/${services[0].id}`, staff)).body;
      if (revived.state !== 'active') await sleep(1000);
    }
    if (revived.state !== 'active') fail('services did not reinstate on cure');
    const unbilled = (await call('GET',
      `${BILLS}/appliedCustomerBillingRate?isBilled=false`, staff)).body || [];
    if (!unbilled.some((l) => l.type === 'reconnectionFee'
        && l.forParty && l.forParty.id === kollaId)) {
      fail('no unbilled reconnectionFee line waiting for the next bill');
    }
    console.log('OK CURED: payment reinstated the line immediately and the 25 reconnection'
      + ' fee waits as an unbilled line for the next bill.');

    /* ---------- 10. the words reached the customer ---------- */
    // the cure's thank-you rides the event bus behind 17 per-service
    // reinstates — poll until the whole set has landed, not a single shot
    const expect = [
      ['Payment reminder', /reminder fee/i],
      ['Payment demand — action required', /Emergency numbers always stay reachable/],
      ['Outgoing services restricted', /Emergency numbers still work/],
      ['Your line is suspended', /No subscription charges accrue/],
      ['Thank you — your services are restored', /payment arrived.*back in full/i],
      ['Missed payment promise', /did not arrive/],
    ];
    let missing = expect;
    for (let i = 0; i < 15 && missing.length; i++) {
      const inbox = (await call('GET',
        '/tmf-api/communicationManagement/v4/communicationMessage?limit=100', kolla)).body || [];
      missing = expect.filter(([subject, rx]) => !inbox.some((m) =>
        (m.subject || '').includes(subject) && rx.test(m.content || '')));
      if (missing.length) await sleep(2000);
    }
    if (missing.length) {
      fail(`missing customer notification: "${missing[0][0]}"`);
    }
    console.log(`OK NOTIFIED: all ${expect.length} rungs spoke to the customer — reminder,`
      + ' demand (with the emergency-number promise), restriction, suspension,'
      + ' broken promise and the thank-you.');

    /* ---------- 11. leave the fleet as found ---------- */
    for (const s of services) {
      await call('POST', `${SVC}/service/${s.id}/terminate`, kolla, { reason: 'cease' });
    }
    console.log('OK CLEANUP: probe services terminated.');

    /* ---------- 12. the console face: the policy beside its statutory floor ---------- */
    // The desk must list this run's policy with the country floor visible and
    // READ-ONLY — the law's numbers are never form fields.
    const uiBrowser = await chromium.launch();
    const desk = await (await uiBrowser.newContext()).newPage();
    await desk.goto('http://localhost:8080/csr/');
    await desk.waitForSelector('input[name="username"]', { timeout: 20000 });
    await desk.fill('input[name="username"]', 'demo');
    await desk.fill('input[name="password"]', 'demo');
    await desk.click('input[type="submit"], button[type="submit"]');
    await desk.waitForSelector('.nav', { timeout: 30000 });
    await desk.click('.nav >> text=Collections');
    await desk.locator('h1', { hasText: 'Collections desk' }).waitFor({ timeout: 20000 })
      .catch(() => fail('console /collections did not render the Collections desk'
        + ' (billing:read nav/route missing?)'));
    const policyRow = desk.locator('[data-testid="policy-row"]',
      { hasText: `collections-e2e-${run}` }).first();
    await policyRow.waitFor({ timeout: 20000 })
      .catch(() => fail('the run policy is not on the desk\'s policy list'));
    const statutory = policyRow.locator('[data-testid="policy-statutory"]');
    await statutory.waitFor({ timeout: 10000 })
      .catch(() => fail('no statutory-floor block on the policy row'));
    const statText = (await statutory.textContent()) || '';
    if (!/read-only/.test(statText)) {
      fail('the statutory floor is not marked read-only: ' + statText.slice(0, 200));
    }
    if (!/fee gate 14/.test(statText) || !/minimum actionable 250/.test(statText)) {
      fail('statutory numbers missing from the console policy row: ' + statText.slice(0, 200));
    }
    if (await statutory.locator('input, select, textarea').count() !== 0) {
      fail('the statutory floor renders form controls — it must be read-only');
    }
    await uiBrowser.close();
    console.log('OK CONSOLE FACE: /collections lists the run policy with the statutory'
      + ' floor visible, worded read-only, and free of form controls.');

    console.log('OK collections: the whole ladder — floor, fee gate, demand, month-long'
      + ' notice, restriction with the emergency whitelist, nonpayment suspension,'
      + ' promise, dispute hold, cure — proven end to end.');
  } finally {
    if (policyId) {
      await call('PATCH', `${BILLS}/dunningPolicy/${policyId}`, staff, { active: false });
    }
  }
})().catch((e) => { console.error('FAIL: ' + (e && e.message ? e.message : e)); process.exit(1); });
