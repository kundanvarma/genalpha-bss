/* Usage policy — pools, caps, passes, and the €50 wall.
 *
 * Proves, API-driven through the gateway (plus the usage service's internal
 * OCS door, off-gateway by design):
 *  - POOL: a household pool shared across members with a per-member hard cap;
 *    the concurrent-usage race asserted at the reservation layer — parallel
 *    records can never over-allocate the pool.
 *  - CONTENT SERVICES: the statutory floor rejects a too-low cap; barring is
 *    free, always available, and blocks the charge; block-on-breach at the cap.
 *  - ROAMING: the default financial limit exists unasked, cuts off at 100 %,
 *    and an explicit (audited) continue restores service.
 *  - AUTO TOP-UP: consent required, one boost per breach window (replay-safe),
 *    per-cycle cap respected, disabled means nothing fires.
 *  - TRAVEL PASS: zone-tagged usage inside the pass window rates free; outside
 *    the window it rates like home overage. First zone record says so.
 *
 * UI (leg 6, after the API proof): the selfcare faces — the seeded
 * "Family data pool" card with member caps on /family, and the roaming
 * limit card under usage controls on My page (both seeded for paula by
 * seed_usage_policy.py).
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const USAGE_DIRECT = 'http://localhost:8097';    // internal OCS door, not routed
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const U = `${API}/tmf-api/usageManagement/v4`;
const C = `${API}/tmf-api/usageConsumption/v4`;
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const ok = (m) => console.log('OK ' + m);
const run = Date.now();
const P = (s) => `UP${run % 100000} ${s}`;    // unique usage-spec names per run
const iso = (d) => d.toISOString();
const daysAgo = (n) => new Date(Date.now() - n * 86400000);

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const staff = await (await ctx.request.post(KC, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json();
  if (!staff.access_token) fail('no staff token — is the stack up?');
  const H = { Authorization: `Bearer ${staff.access_token}`, 'Content-Type': 'application/json' };
  const post = (p, data) => ctx.request.post(`${U}${p}`, { headers: H, data });
  const patch = (p, data) => ctx.request.patch(`${U}${p}`, { headers: H, data });
  const put = (p, data) => ctx.request.put(`${U}${p}`, { headers: H, data });
  const get = (p) => ctx.request.get(`${U}${p}`, { headers: H });

  const allowance = async (offeringId, usageType, gb, price, boost) => {
    const r = await post('/usageAllowance', {
      productOffering: { id: offeringId, name: 'Plan ' + offeringId }, usageType,
      allowance: { value: gb, units: 'GB' }, overagePrice: { unit: 'EUR', value: price },
      boost: !!boost });
    if (r.status() !== 201) fail(`allowance ${offeringId}: ${r.status()}`);
  };
  const usage = (party, offeringId, usageType, gb, extra = {}) => post('/usage', {
    usageType, usageCharacteristic: { value: gb, units: 'GB' },
    productOffering: { id: offeringId },
    relatedParty: [{ id: party, role: 'customer' }], ...extra });

  /* ---------- 1. POOL: lifecycle + member hard cap + the reservation race ---------- */
  const poolSpec = P('pool data');
  await allowance(`po-pool-${run}`, poolSpec, 1, 1.0);
  const owner = `up-owner-${run}`, m1 = `up-m1-${run}`, m2 = `up-m2-${run}`;
  const pool = await (await post('/allowancePool', {
    ownerPartyId: owner, name: 'Family pool', usageType: poolSpec, poolGB: 10 })).json();
  if (!pool.id) fail('pool not created: ' + JSON.stringify(pool));
  for (const [p, caps] of [[m1, { hardLimitGB: 2, softLimitGB: 1 }], [m2, {}]]) {
    const r = await post(`/allowancePool/${pool.id}/member`, { partyId: p, ...caps });
    if (r.status() !== 201) fail(`add member ${p}: ${r.status()}`);
  }
  // m1 burns 3 GB: the pool grants exactly the 2 GB hard cap, 1 GB falls back
  if ((await usage(m1, `po-pool-${run}`, poolSpec, 3)).status() !== 201) fail('m1 usage');
  let state = await (await get(`/allowancePool/${pool.id}`)).json();
  const m1row = state.member.find((m) => m.partyId === m1);
  if (m1row.consumedGB !== 2) fail(`member hard cap leaked: consumed ${m1row.consumedGB}`);
  ok('POOL: member hard cap holds — 3 GB burned, pool granted exactly the 2 GB cap');

  // THE RACE: 12 concurrent 1 GB records vs 8 GB of remaining pool — the
  // reserve-then-commit layer must never grant past the pool
  const burst = await Promise.all(Array.from({ length: 12 },
    () => usage(m2, `po-pool-${run}`, poolSpec, 1)));
  if (burst.some((r) => r.status() !== 201)) fail('burst ingest failed');
  state = await (await get(`/allowancePool/${pool.id}`)).json();
  if (state.consumedGB !== 10) fail(`pool over/under-allocated under contention: ${state.consumedGB}`);
  if (state.remainingGB !== 0) fail(`remaining should be 0, got ${state.remainingGB}`);
  ok('POOL: 12 concurrent records vs 8 GB remaining — consumed exactly 10, never over');

  // rating charges only the unpooled leftover: m1 has 1 GB unpooled vs 1 GB allowance
  const period = { periodStart: iso(daysAgo(15)).slice(0, 10), periodEnd: iso(daysAgo(-1)).slice(0, 10) };
  const m1charges = await (await post('/rateUsage', { relatedPartyId: m1, ...period })).json();
  if (m1charges.length !== 0) fail('pool-covered usage was charged: ' + JSON.stringify(m1charges));
  ok('POOL: pool-covered GB never rates against the personal allowance');

  /* ---------- 2. CONTENT SERVICES: statutory floor + free barring ---------- */
  const cc = `up-content-${run}`;
  let r = await patch(`/spendPolicy/content?partyId=${cc}`, { limit: 100 });
  if (r.status() !== 400) fail(`content cap below the floor accepted: ${r.status()}`);
  r = await patch(`/spendPolicy/content?partyId=${cc}`, { limit: 250 });
  if (r.status() !== 200) fail(`content cap at the floor refused: ${r.status()}`);
  ok('CONTENT: 100 NOK cap rejected (statutory floor 250), 250 accepted');

  await patch(`/spendPolicy/content?partyId=${cc}`, { barred: true });
  const charge = (party, chargeClass, value, unit) => post('/spendMeter/charge',
    { partyId: party, chargeClass, amount: { value, unit } });
  let res = await (await charge(cc, 'content', 50, 'NOK')).json();
  if (res.accepted !== false) fail('barred content charge accepted');
  ok('CONTENT: barring (free, always available) blocks the carrier-billing charge');
  await patch(`/spendPolicy/content?partyId=${cc}`, { barred: false });
  res = await (await charge(cc, 'content', 260, 'NOK')).json();
  if (res.accepted !== true || res.meter[0].blocked !== true) fail('breach did not block');
  res = await (await charge(cc, 'content', 10, 'NOK')).json();
  if (res.accepted !== false) fail('post-breach content charge accepted');
  ok('CONTENT: block-on-breach at the cap — the next charge is refused');

  /* ---------- 3. ROAMING: default limit, cut-off, explicit continue ---------- */
  const cr = `up-roam-${run}`;
  const meters = await (await get(`/spendPolicy?partyId=${cr}`)).json();
  const roam = meters.find((m) => m.meterType === 'roaming');
  if (!roam.enabled || roam.limit.value !== 50) fail('roaming default limit missing: ' + JSON.stringify(roam));
  ok('ROAMING: the default financial limit (50 EUR) exists without being asked for');
  await charge(cr, 'roaming', 45, 'EUR');
  res = await (await charge(cr, 'roaming', 10, 'EUR')).json();
  if (res.meter[0].blocked !== true) fail('no cut-off at 100%');
  res = await (await charge(cr, 'roaming', 5, 'EUR')).json();
  if (res.accepted !== false) fail('charge accepted past the cut-off');
  ok('ROAMING: hard cut-off at 100% — further roaming charges refused');
  r = await post('/roamingLimit/continue', { partyId: cr });
  const cont = await r.json();
  if (r.status() !== 200 || cont.continueElected !== true) fail('roaming continue failed');
  res = await (await charge(cr, 'roaming', 5, 'EUR')).json();
  if (res.accepted !== true) fail('charge refused after explicit continue');
  ok('ROAMING: the explicit (audited) continue restores service for the cycle');

  /* ---------- 4. AUTO TOP-UP: consent, replay-safe windows, cycle cap ---------- */
  const at = `up-at-${run}`, atSpec = P('at data');
  await allowance(`po-atp-${run}`, atSpec, 10, 2.5);
  await allowance(`po-atb-${run}`, atSpec, 5, 0, true);
  if ((await usage(at, `po-atp-${run}`, atSpec, 9)).status() !== 201) fail('at usage');
  r = await put(`/autoTopupPolicy?partyId=${at}`, { enabled: true, boostOfferingId: `po-atb-${run}` });
  if (r.status() !== 400) fail(`auto top-up enabled without consent: ${r.status()}`);
  r = await put(`/autoTopupPolicy?partyId=${at}`, { enabled: true, consent: true,
    boostOfferingId: `po-atb-${run}`, trigger: 'depletion', maxBoostsPerCycle: 2 });
  if (r.status() !== 200 || !(await r.json()).consentAt) fail('auto top-up enable failed');
  ok('AUTO TOP-UP: never default-on — consent refused/recorded correctly');

  const breach = (windowId) => ctx.request.post(`${USAGE_DIRECT}/internal/ocs/usageThreshold`,
    { headers: { 'Content-Type': 'application/json' },
      data: { tenantId: 'genalpha', partyId: at, percentUsed: 100, threshold: 100, windowId } });
  const allowed = async () => {
    const rep = await (await ctx.request.get(
      `${C}/queryUsageConsumption?relatedPartyId=${at}`, { headers: H })).json();
    const b = (rep.bucket || []).find((x) => x.name === atSpec);
    return b ? b.allowedValue : null;
  };
  await breach('w1');
  if (await allowed() !== 15) fail('first breach did not buy the boost (10+5 expected)');
  await breach('w1');
  if (await allowed() !== 15) fail('replayed breach window bought twice');
  ok('AUTO TOP-UP: one boost per breach window — the replay is a no-op');
  await breach('w2');
  if (await allowed() !== 20) fail('second window did not buy');
  await breach('w3');
  if (await allowed() !== 20) fail('per-cycle cap ignored');
  ok('AUTO TOP-UP: per-cycle cap (2) holds — the third window buys nothing');
  await put(`/autoTopupPolicy?partyId=${at}`, { enabled: false });
  await breach('w4');
  if (await allowed() !== 20) fail('disabled policy still bought');
  ok('AUTO TOP-UP: disabled means disabled');

  /* ---------- 5. TRAVEL PASS: zone + validity window in the rating pass ---------- */
  const tp = `up-tp-${run}`, tpSpec = P('tp data');
  await allowance(`po-tp-${run}`, tpSpec, 10, 2.0);
  r = await post('/travelPass', { partyId: tp, usageType: tpSpec, zone: 'world-1',
    amountGB: 5, validFrom: iso(daysAgo(1)), validityDays: 3 });
  if (r.status() !== 201) fail(`travel pass: ${r.status()}`);
  if ((await usage(tp, `po-tp-${run}`, tpSpec, 10)).status() !== 201) fail('tp home usage');
  const z1 = await (await usage(tp, `po-tp-${run}`, tpSpec, 3, { zone: 'world-1' })).json();
  if (z1.zoneEntered !== true) fail('first zone record did not announce the zone');
  ok('TRAVEL PASS: first record from a new zone raises ZoneEnteredEvent (pricing info rides it)');
  await usage(tp, `po-tp-${run}`, tpSpec, 1, { zone: 'world-1' });
  // outside the window (before validFrom): rates like home overage
  await usage(tp, `po-tp-${run}`, tpSpec, 2, { zone: 'world-1', usageDate: iso(daysAgo(10)) });
  const tpCharges = await (await post('/rateUsage', { relatedPartyId: tp, ...period })).json();
  if (tpCharges.length !== 1 || tpCharges[0].amount.value !== 4)
    fail('pass window mis-rated: ' + JSON.stringify(tpCharges));
  ok('TRAVEL PASS: 4 GB in-window zone usage free on the pass; 2 GB out-of-window charged (4.00 EUR)');
  const again = await (await post('/rateUsage', { relatedPartyId: tp, ...period })).json();
  if (again.length !== 1) fail('re-rating not idempotent');
  ok('TRAVEL PASS: re-rating is idempotent');

  /* ---------- 6. UI faces: the family pool card + the roaming limit card ---------- */
  const page = await (await browser.newContext()).newPage();
  await page.goto(`${API}/shop/`);
  await page.locator('.who >> text=Sign in').click();
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', 'paula@family.example');
  await page.fill('input[name="password"]', 'paula');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.nav', { timeout: 30000 });

  await page.click('.nav >> text=Family');
  const poolCard = page.locator('[data-testid^="data-pool-"]', { hasText: 'Family data pool' }).first();
  await poolCard.waitFor({ timeout: 20000 }).catch(() =>
    fail('the seeded "Family data pool" card is not on /family — is seed_usage_policy.py applied?'));
  const remaining = (await poolCard.locator('[data-testid="pool-remaining"]').textContent()) || '';
  if (!/GB left of/.test(remaining)) fail('pool card does not state remaining-of-total: ' + remaining);
  if (!await poolCard.locator('[data-testid^="pool-member-"]').count()) {
    fail('no member rows on the pool card — paula manages the pool, the caps must show');
  }
  if (!await poolCard.locator('[data-testid="pool-hard-input"]').first().count()) {
    fail('member rows carry no cap inputs — the per-member caps face is missing');
  }
  ok('UI: /family shows the seeded "Family data pool" with remaining-of-total and per-member caps');

  await page.click('.nav >> text=My page');
  await page.locator('[data-testid="usage-controls"]').waitFor({ timeout: 30000 }).catch(() =>
    fail('the usage-controls card is not on My page (it hides only when the usage-policy component is absent)'));
  const roaming = page.locator('[data-testid="roaming-controls"]');
  await roaming.waitFor({ timeout: 15000 }).catch(() =>
    fail('no roaming limit card under usage controls — paula\'s 50 EUR default is seeded'));
  if (!await roaming.locator('[data-testid="roaming-limit-input"]').count()) {
    fail('the roaming card has no limit input — raising/lowering the limit is part of the face');
  }
  ok('UI: My page usage controls show the roaming limit card with its editable limit');

  await browser.close();
  console.log('OK usage_policy: pool + caps + roaming wall + auto top-up + travel pass + the selfcare faces all proven');
})().catch((e) => fail(e.message || String(e)));
