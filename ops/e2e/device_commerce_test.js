/* Device commerce — the device lifecycle as first-class BSS state.
 *
 *  - trade-in: residual-table estimate with published defect haircuts; a
 *    blacklisted IMEI quotes exactly zero
 *  - operator-book financing: subsidy books a contract asset at activation,
 *    each instalment unwinds a slice, upgrade eligibility at 50 % paid,
 *    swap writes the remainder off against the graded trade-in
 *  - mock-bank financing: upfront payout (full recognition + residual-value
 *    guarantee in the ledger), early settlement = remaining + flat fee
 *  - BNPL via the Klarna PSP adapter: origination verifies the checkout
 *    payment; settlement is the provider's, never an operator write-off
 *  - grading delta: up refunds through the PSP path; down waits for the
 *    customer to accept the revised value
 *  - withdrawal inside 14 days refunds principal + standard shipping and
 *    reverses the subsidy postings
 *
 * API-driven through the gateway, then the browser faces: selfcare
 * "My devices" (paula's seeded agreement) and the console device desk
 * (agreement worklist + the residual table). */

const { chromium } = require('playwright');
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const D = '/tmf-api/deviceCommerce/v1';
const P = '/tmf-api/paymentManagement/v4';
const run = Date.now();
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const ok = (m) => console.log('OK ' + m);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}),
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}

/** The outbox relays every ~2 s and revenue's listener posts idempotently —
 * poll for the journal entry keyed on the posting's sourceRef. */
async function journalEntry(staff, sourceRef, tries = 20) {
  for (let i = 0; i < tries; i++) {
    const r = await call('GET', `/revenue/v1/journalEntry?sourceRef=${encodeURIComponent(sourceRef)}`, staff);
    if (r.status === 200 && Array.isArray(r.body) && r.body.length > 0) return r.body[0];
    await sleep(2000);
  }
  return null;
}
const lineOn = (entry, code) => (entry.lines || []).find((l) => l.accountCode === code);

(async () => {
  const staff = await token('demo', 'demo');
  const device = `phone-e2e-${run}`;
  const party = `device-party-${run}`;

  /* ---------- 1. residual table + instant estimate ---------- */
  let r = await call('POST', `${D}/tradeInResidual`, staff,
    { deviceRef: device, ageMonths: 12, baseValue: 300 });
  if (r.status !== 200) fail(`residual upsert: ${r.status} ${r.text}`);
  r = await call('POST', `${D}/tradeInValuation`, staff,
    { imei: `35${String(run).slice(-10)}01`, deviceRef: device, conditionAnswers: { ageMonths: 12 } });
  if (r.status !== 201 || Number(r.body.estimatedValue) !== 300) {
    fail(`clean estimate should be 300: ${r.text}`);
  }
  r = await call('POST', `${D}/tradeInValuation`, staff,
    { imei: `35${String(run).slice(-10)}02`, deviceRef: device,
      conditionAnswers: { ageMonths: 12, screenCracked: true } });
  if (Number(r.body.estimatedValue) !== 180) fail(`cracked screen should cost 40%: ${r.text}`);
  ok(`instant estimate off the residual table: clean 300, cracked screen 180 (−40 %)`);

  /* ---------- 2. blacklisted IMEI quotes zero ---------- */
  const hotImei = `35${String(run).slice(-10)}99`;
  await call('POST', `${D}/deviceFlag`, staff, { imei: hotImei, reason: 'stolen' });
  r = await call('POST', `${D}/tradeInValuation`, staff, { imei: hotImei, deviceRef: device });
  if (Number(r.body.estimatedValue) !== 0) fail(`blacklisted IMEI must quote zero: ${r.text}`);
  ok('a blacklisted IMEI quotes exactly zero');

  /* ---------- 3. operator-book agreement: subsidy → contract asset ---------- */
  // the checkout payment covers device + standard shipping (refund needs a PSP path)
  const pay1 = await call('POST', `${P}/payment`, staff, {
    description: `device checkout ${run}`, amount: { unit: 'EUR', value: 729.90 },
    relatedParty: [{ id: party, role: 'customer' }],
    paymentMethod: { '@type': 'bankCard', token: `dev-${run}-1`, lastFourDigits: '4242' } });
  if (pay1.status >= 300) fail(`checkout payment: ${pay1.status} ${pay1.text}`);
  // checkout money is settled money: capture the authorization so a later
  // grading delta has a captured payment to refund against
  r = await call('PATCH', `${P}/payment/${pay1.body.id}`, staff, { status: 'captured' });
  if (r.status >= 300) fail(`checkout capture: ${r.status} ${r.text}`);

  r = await call('POST', `${D}/deviceAgreement`, staff, {
    principal: 720, termMonths: 24, financingModel: 'OPERATOR_BOOK',
    totalCostOfOwnership: 720, subsidyAmount: 240, shippingCost: 9.90,
    imei: `35${String(run).slice(-10)}01`, deviceRef: device,
    orderRef: `device-order-${run}`, paymentRef: pay1.body.id,
    upgradeRule: { paidSharePct: 50 },
    relatedParty: [{ id: party, role: 'customer' }] });
  if (r.status !== 201 || r.body.status !== 'active') fail(`operator-book create: ${r.status} ${r.text}`);
  if (Number(r.body.monthlyAmount) !== 30) fail(`monthly should be 30: ${r.text}`);
  const ob = r.body;
  ok(`operator-book agreement ${ob.id.slice(0, 8)}… active: 24 × 30, TCO 720 on the offer face`);

  const activation = await journalEntry(staff, `device-activation:${ob.id}`);
  if (!activation) fail('no device-activation journal entry — the subsidy never hit the ledger');
  if (!lineOn(activation, '1250') || Number(lineOn(activation, '1250').debit) !== 240
      || !lineOn(activation, '4030') || Number(lineOn(activation, '4030').credit) !== 240) {
    fail('activation posting should be DR 1250 / CR 4030 for 240: ' + JSON.stringify(activation.lines));
  }
  ok('IFRS 15: subsidy 240 booked as contract asset (DR 1250 / CR 4030)');

  /* ---------- 4. schedule → unwind → eligibility → swap (write-off math) ---------- */
  let notYet = await call('POST', `${D}/deviceAgreement/${ob.id}/swap`, staff, {});
  if (notYet.status !== 409) fail(`swap before eligibility should 409: ${notYet.status}`);
  for (let m = 0; m < 12; m++) {
    r = await call('POST', `${D}/deviceAgreement/${ob.id}/recordInstallment`, staff, {});
    if (r.status !== 200) fail(`recordInstallment #${m + 1}: ${r.status} ${r.text}`);
  }
  const unwind = await journalEntry(staff, `device-unwind:${ob.id}:1`);
  if (!unwind || Number(lineOn(unwind, '1250').credit) !== 10) {
    fail('first unwind should credit 1250 by 10.00 (240/24): ' + JSON.stringify(unwind && unwind.lines));
  }
  ok('monthly unwind posts: 10.00/month against the contract asset');
  r = await call('GET', `${D}/deviceAgreement/${ob.id}/upgradeEligibility`, staff);
  if (r.body.eligible !== true) fail(`should be eligible at 50% paid: ${r.text}`);

  const tradeIn = await call('POST', `${D}/tradeInValuation`, staff, {
    imei: `35${String(run).slice(-10)}01`, deviceRef: device,
    conditionAnswers: { ageMonths: 12 }, agreementRef: ob.id,
    relatedParty: [{ id: party, role: 'customer' }] });
  await call('POST', `${D}/tradeInValuation/${tradeIn.body.id}/accept`, staff);
  r = await call('POST', `${D}/deviceAgreement/${ob.id}/swap`, staff,
    { tradeInValuationId: tradeIn.body.id });
  if (r.status !== 200 || r.body.status !== 'swapped') fail(`swap: ${r.status} ${r.text}`);
  const s = r.body.settlement;
  if (Number(s.remainingPrincipal) !== 360 || Number(s.writeOff) !== 60) {
    fail(`write-off math: 360 remaining − 300 trade-in = 60, got ${JSON.stringify(s)}`);
  }
  const replay = await call('POST', `${D}/deviceAgreement/${ob.id}/swap`, staff,
    { tradeInValuationId: tradeIn.body.id });
  if (replay.body.status !== 'swapped') fail('swap replay should be free');
  ok('upgrade at eligibility: 360 remaining written off against 300 graded → 60 program cost (idempotent)');

  const swapEntry = await journalEntry(staff, `device-swap:${ob.id}`);
  if (!swapEntry || Number(lineOn(swapEntry, '1300').debit) !== 300
      || Number(lineOn(swapEntry, '5210').debit) !== 60) {
    fail('swap posting: DR 1300 300 / DR 5210 60 expected: ' + JSON.stringify(swapEntry && swapEntry.lines));
  }
  ok('swap postings: trade-in inventory 300, write-off 60, subsidy remainder cleared');

  /* ---------- 5. mock-bank: payout + RVG + early settlement quote ---------- */
  r = await call('POST', `${D}/deviceAgreement`, staff, {
    principal: 600, termMonths: 12, financingModel: 'THIRD_PARTY_LOAN',
    totalCostOfOwnership: 600, residualValue: 150, deviceRef: device,
    upgradeRule: { paidSharePct: 50 },
    relatedParty: [{ id: party, role: 'customer' }] });
  if (r.status !== 201 || !r.body.payoutReceivedAt) fail(`mock-bank create/payout: ${r.text}`);
  const bank = r.body;
  if (!(bank.externalAgreementNo || '').startsWith('MB-')) fail('no external agreement number from the bank');
  r = await call('GET', `${D}/deviceAgreement/${bank.id}/earlySettlementQuote`, staff);
  if (Number(r.body.amount) !== 649 || Number(r.body.fee) !== 49) {
    fail(`bank quote should be 600 + 49: ${r.text}`);
  }
  ok(`mock-bank agreement ${bank.externalAgreementNo}: payout received, early settlement 600 + 49 fee`);

  const payout = await journalEntry(staff, `device-payout:${bank.id}`);
  if (!payout || Number(lineOn(payout, '4030').credit) !== 600
      || Number(lineOn(payout, '2500').credit) !== 150) {
    fail('payout posting: CR 4030 600 + CR 2500 150 (RVG) expected: '
      + JSON.stringify(payout && payout.lines));
  }
  ok('payout postings: full recognition at payout + residual-value guarantee accrued');

  for (let m = 0; m < 6; m++) {
    await call('POST', `${D}/deviceAgreement/${bank.id}/recordInstallment`, staff, {});
  }
  const bankTradeIn = await call('POST', `${D}/tradeInValuation`, staff, {
    imei: `35${String(run).slice(-10)}03`, deviceRef: device, conditionAnswers: { ageMonths: 12 } });
  await call('POST', `${D}/tradeInValuation/${bankTradeIn.body.id}/accept`, staff);
  r = await call('POST', `${D}/deviceAgreement/${bank.id}/swap`, staff,
    { tradeInValuationId: bankTradeIn.body.id });
  if (Number(r.body.settlement.settlementAmount) !== 349) {
    fail(`bank swap settles the quote (300 + 49): ${JSON.stringify(r.body.settlement)}`);
  }
  ok('mock-bank upgrade: settlement quote fetched and settled (300 remaining + 49 fee)');

  /* ---------- 6. BNPL via the Klarna PSP adapter ---------- */
  await call('PUT', `${P}/paymentProvider`, staff, {
    provider: 'klarna', displayName: 'Klarna', baseUrl: 'http://mock-klarna:8080',
    secretRef: 'KLARNA_API_KEY', webhookSecretRef: 'KLARNA_WEBHOOK_SECRET',
    methods: ['card', 'klarna'], isDefault: false });
  const sess = await call('POST', `${P}/payment/session`, staff,
    { method: 'klarna', amount: { value: 500.00, unit: 'EUR' },
      returnUrl: 'http://localhost:8080/shop/cart' });
  if (sess.status !== 200) fail(`klarna session: ${sess.status} ${sess.text}`);
  const bnplPay = await call('POST', `${P}/payment/confirm`, staff,
    { provider: 'klarna', sessionId: sess.body.sessionId });
  if (bnplPay.status !== 200 || !bnplPay.body.id) fail(`klarna confirm: ${bnplPay.status} ${bnplPay.text}`);

  const noPay = await call('POST', `${D}/deviceAgreement`, staff, {
    principal: 500, termMonths: 12, financingModel: 'BNPL', totalCostOfOwnership: 500,
    paymentRef: `bogus-${run}`, relatedParty: [{ id: party, role: 'customer' }] });
  if (noPay.status !== 400) fail(`BNPL with a bogus payment must be refused: ${noPay.status}`);

  r = await call('POST', `${D}/deviceAgreement`, staff, {
    principal: 500, termMonths: 12, financingModel: 'BNPL', totalCostOfOwnership: 500,
    deviceRef: device, paymentRef: bnplPay.body.id, upgradeRule: { month: 6 },
    relatedParty: [{ id: party, role: 'customer' }] });
  if (r.status !== 201 || r.body.titleHolder !== 'provider') fail(`BNPL create: ${r.status} ${r.text}`);
  const bnpl = r.body;
  for (let m = 0; m < 6; m++) {
    await call('POST', `${D}/deviceAgreement/${bnpl.id}/recordInstallment`, staff, {});
  }
  const bnplTradeIn = await call('POST', `${D}/tradeInValuation`, staff, {
    imei: `35${String(run).slice(-10)}04`, deviceRef: device, conditionAnswers: { ageMonths: 12 } });
  await call('POST', `${D}/tradeInValuation/${bnplTradeIn.body.id}/accept`, staff);
  r = await call('POST', `${D}/deviceAgreement/${bnpl.id}/swap`, staff,
    { tradeInValuationId: bnplTradeIn.body.id });
  if (r.body.settlement.settlementDelegated !== true
      || r.body.settlement.providerSettlementStatus !== 'settled') {
    fail(`BNPL settlement is the provider's: ${JSON.stringify(r.body.settlement)}`);
  }
  ok('BNPL: Klarna payment verified at origination (bogus ref refused); settlement delegated to the provider');

  /* ---------- 7. grading delta: up refunds, down waits for the customer ---------- */
  const gradeUp = await call('POST', `${D}/tradeInValuation`, staff, {
    imei: `35${String(run).slice(-10)}05`, deviceRef: device,
    conditionAnswers: { ageMonths: 12 }, paymentRef: pay1.body.id,
    relatedParty: [{ id: party, role: 'customer' }] });
  await call('POST', `${D}/tradeInValuation/${gradeUp.body.id}/accept`, staff);
  await call('POST', `${D}/tradeInValuation/${gradeUp.body.id}/inTransit`, staff);
  r = await call('POST', `${D}/tradeInValuation/${gradeUp.body.id}/grading`, staff,
    { partnerRef: 'grading-partner-mock', finalGrade: 'A', finalValue: 320, note: 'mint' });
  if (r.body.status !== 'settled' || Number(r.body.delta) !== 20 || !r.body.refundRef) {
    fail(`grade-up should refund 20 via the PSP: ${r.text}`);
  }
  ok(`grading up (+20) refunded through the payment rail: ${String(r.body.refundRef).slice(0, 12)}…`);

  const gradeDown = await call('POST', `${D}/tradeInValuation`, staff, {
    imei: `35${String(run).slice(-10)}06`, deviceRef: device, conditionAnswers: { ageMonths: 12 } });
  await call('POST', `${D}/tradeInValuation/${gradeDown.body.id}/accept`, staff);
  r = await call('POST', `${D}/tradeInValuation/${gradeDown.body.id}/grading`, staff,
    { partnerRef: 'grading-partner-mock', finalGrade: 'C', finalValue: 250, note: 'scratched' });
  if (r.body.status !== 'revalued' || Number(r.body.delta) !== -50) {
    fail(`grade-down should wait as revalued: ${r.text}`);
  }
  r = await call('POST', `${D}/tradeInValuation/${gradeDown.body.id}/acceptRevaluation`, staff);
  if (r.body.status !== 'settled') fail(`accepting the revaluation settles: ${r.text}`);
  ok('grading down (−50) waited for the customer, then settled on acceptance');

  /* ---------- 8. withdrawal inside 14 days: refund incl. shipping ---------- */
  const pay2 = await call('POST', `${P}/payment`, staff, {
    description: `device checkout ${run}-wd`, amount: { unit: 'EUR', value: 729.90 },
    relatedParty: [{ id: party, role: 'customer' }],
    paymentMethod: { '@type': 'bankCard', token: `dev-${run}-2`, lastFourDigits: '4242' } });
  r = await call('PATCH', `${P}/payment/${pay2.body.id}`, staff, { status: 'captured' });
  if (r.status >= 300) fail(`withdrawal checkout capture: ${r.status} ${r.text}`);
  r = await call('POST', `${D}/deviceAgreement`, staff, {
    principal: 720, termMonths: 24, financingModel: 'OPERATOR_BOOK',
    totalCostOfOwnership: 720, subsidyAmount: 240, shippingCost: 9.90,
    deviceRef: device, paymentRef: pay2.body.id,
    relatedParty: [{ id: party, role: 'customer' }] });
  const wd = r.body;
  r = await call('POST', `${D}/withdrawalCase`, staff, { agreementId: wd.id });
  if (r.status !== 201 || r.body.status !== 'refunded') fail(`withdrawal: ${r.status} ${r.text}`);
  if (Number(r.body.refundAmount) !== 729.90 || !r.body.refundRef) {
    fail(`withdrawal must refund 720 + 9.90 shipping via the PSP: ${r.text}`);
  }
  r = await call('GET', `${D}/deviceAgreement/${wd.id}`, staff);
  if (r.body.status !== 'withdrawn') fail('agreement should be withdrawn');
  const wdEntry = await journalEntry(staff, `device-withdrawal:${wd.id}`);
  if (!wdEntry || Number(lineOn(wdEntry, '1250').credit) !== 240) {
    fail('withdrawal must reverse the subsidy (CR 1250 240): '
      + JSON.stringify(wdEntry && wdEntry.lines));
  }
  ok('withdrawal inside 14 days: 729.90 refunded (incl. shipping), subsidy postings reversed');

  /* ---------- 9. UI faces: shop "My devices" + console device desk ---------- */
  const browser = await chromium.launch();

  // selfcare: paula's SEEDED operator-book agreement (seed_device_commerce.py)
  const shop = await (await browser.newContext()).newPage();
  await shop.goto(`${API}/shop/`);
  await shop.locator('.who >> text=Sign in').click();
  await shop.waitForSelector('input[name="username"]', { timeout: 20000 });
  await shop.fill('input[name="username"]', 'paula@family.example');
  await shop.fill('input[name="password"]', 'paula');
  await shop.click('input[type="submit"], button[type="submit"]');
  await shop.waitForSelector('.nav', { timeout: 30000 });
  await shop.click('.nav >> text=My devices');
  const card = shop.locator('[data-testid^="device-agreement-"]').first();
  await card.waitFor({ timeout: 20000 }).catch(() =>
    fail('shop /devices shows no agreement card for paula — is seed_device_commerce.py applied?'));
  const cardText = (await card.textContent()) || '';
  if (!/Monthly instalments — on your bill|Pay later|Bank financing/.test(cardText)) {
    fail('the agreement card carries no financing label: ' + cardText.slice(0, 160));
  }
  const paidShare = card.locator('[data-testid="paid-share"]');
  if (!await paidShare.count()) {
    fail('no paid-share line on paula\'s agreement (expected on an active financed agreement)');
  }
  if (!/Paid so far: \d+%/.test((await paidShare.textContent()) || '')) {
    fail('paid-share does not state the percentage: ' + await paidShare.textContent());
  }
  await card.locator('[data-testid="paid-share-bar"]').waitFor({ timeout: 5000 }).catch(() =>
    fail('no paid-share progress bar on the agreement card'));
  ok('shop "My devices": paula\'s seeded agreement renders with its financing label and paid-share bar');

  // console: the device desk — agreement worklist + the residual table
  // (this run upserted a residual row above, so the table cannot be empty)
  const csr = await (await browser.newContext()).newPage();
  await csr.goto(`${API}/csr/`);
  await csr.waitForSelector('input[name="username"]', { timeout: 20000 });
  await csr.fill('input[name="username"]', 'demo');
  await csr.fill('input[name="password"]', 'demo');
  await csr.click('input[type="submit"], button[type="submit"]');
  await csr.waitForSelector('.nav', { timeout: 30000 });
  await csr.click('.nav >> text=Devices');
  await csr.locator('h1', { hasText: 'Device desk' }).waitFor({ timeout: 20000 }).catch(() =>
    fail('console /devices did not render the Device desk (device:read nav/route missing?)'));
  await csr.locator('[data-testid="agreement-list"]').waitFor({ timeout: 20000 }).catch(() =>
    fail('no agreement list on the device desk'));
  await csr.locator('[data-testid="residual-row"]').first().waitFor({ timeout: 20000 }).catch(() =>
    fail('the residual table shows no rows — this run upserted one, so the desk is not reading it'));
  ok('console device desk: agreement worklist and the residual table both render');

  await browser.close();

  console.log('OK device_commerce: trade-in, three financing models, swap saga, grading deltas,'
    + ' withdrawal and the subsidy subledger all proven');
  process.exit(0);
})().catch((e) => fail(e.message || String(e)));
