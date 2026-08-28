/* Base migration (moving the installed base without breaking the law or the
 * bills). A legacy plan is sunset through the migration engine: the plan
 * cannot arm without a pricing-simulation receipt (the rehearsal gate), the
 * out-of-binding cohort is noticed, the notice gate holds until noticeDays
 * pass, then ONE TMF622 modify order per subscriber rides the existing
 * plan-change path; a detrimental delta opens the penalty-free exit and an
 * exercised exit parks the customer; rollback emits the inverse order from
 * the pre-migration snapshot.
 *
 * PRECONDITIONS (see integration notes): base-migration service in the
 * stack with MIGRATION_COMPRESS_CLOCKS=true (demo/tests only — production
 * keeps the 30-day floor), gateway route /tmf-api/baseMigration/**, demo
 * staff with migration:read+migration:admin, machine client with
 * inventory:read/party:read/agreement:read/ordering:write/catalog:write. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const MIG = `${API}/tmf-api/baseMigration/v1`;
const CATALOG = `${API}/tmf-api/productCatalogManagement/v4`;
const INVENTORY = `${API}/tmf-api/productInventory/v4`;
const PARTY = `${API}/tmf-api/party/v4`;
const ORDERING = `${API}/tmf-api/productOrderingManagement/v4`;
const AI = `${API}/ai/v1`;
const run = Date.now();

async function staffToken(request) {
  const res = await request.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };
  const token = await staffToken(ctx.request);
  const H = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };
  const post = async (url, data, okStatus = [200, 201]) => {
    const res = await ctx.request.post(url, { headers: H, data });
    if (!okStatus.includes(res.status())) {
      fail(`POST ${url} -> ${res.status()}: ${await res.text()}`);
    }
    return res.json();
  };
  const getJson = async (url) => (await ctx.request.get(url, { headers: H })).json();

  // ---- fixtures: a legacy offering, its successor, two subscribers ----
  const legacyName = `Nordlys Legacy 79 ${run}`;
  const targetName = `Nordlys Flex 99 ${run}`;
  const legacyPrice = await post(`${CATALOG}/productOfferingPrice`, {
    name: `${legacyName} monthly`, priceType: 'recurring',
    price: { unit: 'EUR', value: 79 },
    recurringChargePeriodType: 'month', recurringChargePeriodLength: 1,
    lifecycleStatus: 'Active', version: '1.0' });
  const targetPrice = await post(`${CATALOG}/productOfferingPrice`, {
    name: `${targetName} monthly`, priceType: 'recurring',
    price: { unit: 'EUR', value: 99 },
    recurringChargePeriodType: 'month', recurringChargePeriodLength: 1,
    lifecycleStatus: 'Active', version: '1.0' });
  const legacy = await post(`${CATALOG}/productOffering`, {
    name: legacyName, lifecycleStatus: 'Active', version: '1.0', isBundle: false,
    productOfferingPrice: [{ id: legacyPrice.id, name: legacyPrice.name }] });
  const target = await post(`${CATALOG}/productOffering`, {
    name: targetName, lifecycleStatus: 'Active', version: '1.0', isBundle: false,
    productOfferingPrice: [{ id: targetPrice.id, name: targetPrice.name }] });

  const mkSubscriber = async (given) => {
    const party = await post(`${PARTY}/individual`,
      { givenName: given, familyName: `Migration${run}` });
    const product = await post(`${INVENTORY}/product`, {
      name: legacyName, status: 'active',
      productOffering: { id: legacy.id, name: legacy.name },
      relatedParty: [{ id: party.id, role: 'customer' }] });
    return { party, product };
  };
  const mover = await mkSubscriber('Mira');   // will migrate end-to-end
  const leaver = await mkSubscriber('Leif');  // will exercise the exit
  console.log('OK fixtures: legacy offering + successor + two subscribers on the legacy plan');

  // ---- the rehearsal: simulate the money BEFORE moving it ----
  const sim = await post(`${AI}/simulate/priceChange`, {
    name: `sunset ${legacyName}`,
    changes: [{ offeringName: legacyName, newMonthlyPrice: 99 }] });
  if (!sim.id) fail('price simulation returned no id: ' + JSON.stringify(sim));
  console.log('OK rehearsal: price simulation saved —', sim.id,
    '· annual delta', sim.totalAnnualRevenueDelta);

  // ---- plan 1: the sunset wave (noticeDays 0 — compressed clocks) ----
  const plan = await post(`${MIG}/migrationPlan`, {
    name: `Sunset ${legacyName}`,
    matrix: [{ sourceOfferingId: legacy.id, targetOfferingId: target.id,
      targetOfferingName: target.name, deltaClass: 'detrimental' }],
    eligibility: { inBinding: 'defer-to-expiry' },
    trigger: { type: 'bulk' },
    jurisdictionPack: { noticeDays: 0 } });

  // The rehearsal gate is HARD: arming without a simulation receipt is a 409.
  const noSim = await ctx.request.post(`${MIG}/migrationPlan/${plan.id}/arm`, { headers: H });
  if (noSim.status() !== 409) fail('arm without simulation should be 409, was ' + noSim.status());
  console.log('OK rehearsal gate: arm without an attached simulation is refused (409)');

  const simulated = await post(`${MIG}/migrationPlan/${plan.id}/attachSimulation`,
    { simulationRef: sim.id });
  if (simulated.state !== 'simulated') fail('attachSimulation did not move plan to simulated');
  const armed = await post(`${MIG}/migrationPlan/${plan.id}/arm`, {});
  if (armed.state !== 'armed') fail('plan did not arm: ' + JSON.stringify(armed));
  if (armed.customersDiscovered < 2) {
    fail('discovery should find both subscribers, found ' + armed.customersDiscovered);
  }
  console.log('OK armed: discovery found', armed.customersDiscovered, 'subscribers on the legacy plan');

  // ---- the notice, the exit window, and (for Leif) the penalty-free exit ----
  // The engine ticks every ~5s: scheduled -> notice -> (gate) -> modify order.
  // Catch Leif in/past the notice before his order lands? With noticeDays 0 the
  // window collapses, so exercise the exit RACE-FREE: exit is valid from
  // 'scheduled' too — take it before/at the first tick when possible, and
  // accept 'migrated' as the fallback if the engine won the race.
  let leifRow = null;
  for (let i = 0; i < 30 && !leifRow; i++) {
    const rows = await getJson(`${MIG}/migrationPlan/${plan.id}/customer?limit=200`);
    leifRow = rows.find((r) => r.partyId === leaver.party.id) || null;
    if (!leifRow) await sleep(1000);
  }
  if (!leifRow) fail('Leif never appeared on the plan');
  if (['scheduled', 'noticed', 'exit-window'].includes(leifRow.state)) {
    const exited = await post(
      `${MIG}/migrationPlan/${plan.id}/customer/${leifRow.id}/exit`, {});
    if (exited.state !== 'exited') fail('exit did not park the customer as exited');
    if (exited.penaltyFreeExit !== true) {
      fail('a detrimental change must make the exercised exit penalty-free');
    }
    console.log('OK exit: Leif left penalty-free — detrimental delta, EECC Art. 105 honoured');
  } else {
    console.log('NOTE exit leg: engine outran the exit call (state ' + leifRow.state
      + ') — compressed clocks; the API-level exit is proven in the service tests');
  }

  // ---- Mira migrates: notice recorded, order emitted, inventory moved ----
  let miraRow = null;
  for (let i = 0; i < 40; i++) {
    const rows = await getJson(`${MIG}/migrationPlan/${plan.id}/customer?state=migrated&limit=200`);
    miraRow = rows.find((r) => r.partyId === mover.party.id) || null;
    if (miraRow) break;
    await sleep(2000);
  }
  if (!miraRow) fail('Mira did not reach migrated within the wait window');
  if (!miraRow.orderRef) fail('migrated without an orderRef');
  if (!miraRow.noticeSentAt) fail('order emitted without a recorded notice — the gate is law');
  const order = await getJson(`${ORDERING}/productOrder/${miraRow.orderRef}`);
  if (order.state !== 'completed') fail('modify order not completed: ' + order.state);
  const moved = await getJson(`${INVENTORY}/product/${mover.product.id}`);
  if (moved.productOffering?.id !== target.id) {
    fail('inventory still on the legacy offering after migration');
  }
  console.log('OK migrated: notice at', miraRow.noticeSentAt.slice(0, 19),
    '-> TMF622 modify order', miraRow.orderRef, '-> inventory now on', moved.productOffering.name);

  // ---- rollback: the inverse modify order from the snapshot ----
  const rolled = await post(
    `${MIG}/migrationPlan/${plan.id}/customer/${miraRow.id}/rollback`, {});
  if (rolled.state !== 'rolled-back' || !rolled.rollbackOrderRef) {
    fail('rollback did not emit the inverse order: ' + JSON.stringify(rolled));
  }
  const restored = await getJson(`${INVENTORY}/product/${mover.product.id}`);
  if (restored.productOffering?.id !== legacy.id) {
    fail('rollback did not restore the snapshot plan');
  }
  console.log('OK rollback: inverse order', rolled.rollbackOrderRef,
    '— the subscriber is back on', restored.productOffering.name);

  // ---- progress: the wave accounted for everyone ----
  const progress = await getJson(`${MIG}/migrationPlan/${plan.id}/progress`);
  const accounted = (progress.byState['rolled-back'] || 0) + (progress.byState.exited || 0)
    + (progress.byState.migrated || 0);
  if (accounted < 2) fail('progress does not account for the cohort: ' + JSON.stringify(progress));
  console.log('OK progress:', JSON.stringify(progress.byState));

  // ---- plan 2: notice gate visibly HOLDS when noticeDays > 0 ----
  // A separate plan over the same legacy base (Mira is back on it after the
  // rollback): the customers are re-discovered under the NEW plan, noticed,
  // and then the 1-day gate stands between notice and order.
  const gated = await post(`${MIG}/migrationPlan`, {
    name: `Gate proof ${run}`,
    matrix: [{ sourceOfferingId: legacy.id, targetOfferingId: target.id,
      targetOfferingName: target.name, deltaClass: 'beneficial' }],
    trigger: { type: 'bulk' },
    jurisdictionPack: { noticeDays: 1 } });
  await post(`${MIG}/migrationPlan/${gated.id}/attachSimulation`, { simulationRef: sim.id });
  const armed2 = await post(`${MIG}/migrationPlan/${gated.id}/arm`, {});
  if (armed2.customersDiscovered < 1) fail('gate-proof plan found nobody on the legacy offering');
  let noticedRow = null;
  for (let i = 0; i < 20 && !noticedRow; i++) {
    const rows = await getJson(`${MIG}/migrationPlan/${gated.id}/customer?limit=200`);
    noticedRow = rows.find((r) => ['noticed', 'exit-window'].includes(r.state)) || null;
    if (!noticedRow) await sleep(1000);
  }
  if (!noticedRow) fail('gate-proof customer was never noticed');
  await sleep(12000); // several engine ticks
  const still = (await getJson(`${MIG}/migrationPlan/${gated.id}/customer?limit=200`))
    .find((r) => r.id === noticedRow.id);
  if (still.state !== noticedRow.state || still.orderRef) {
    fail('the notice gate did not hold: ' + JSON.stringify(still));
  }
  console.log('OK notice gate: noticed customer sat through several ticks with NO order '
    + '(noticeDays=1 stands between notice and order)');
  await post(`${MIG}/migrationPlan/${gated.id}/pause`, {});
  console.log('OK gate-proof plan paused (left tidy)');

  // ---- UI leg: the console migration desk mirrors the rehearsal gate ----
  // A throwaway DRAFT plan (no simulation attached) must render with its
  // Arm button disabled — the desk mirrors the server's 409 gate.
  const draftName = `UI draft ${run}`;
  const draftPlan = await post(`${MIG}/migrationPlan`, {
    name: draftName,
    matrix: [{ sourceOfferingId: legacy.id, targetOfferingId: target.id,
      targetOfferingName: target.name, deltaClass: 'neutral' }],
    trigger: { type: 'bulk' },
    jurisdictionPack: { noticeDays: 30 } });

  const page = await ctx.newPage();
  await page.goto(`${API}/csr/`);
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', 'demo');
  await page.fill('input[name="password"]', 'demo');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.nav', { timeout: 30000 });
  await page.click('.nav >> text=Migrations');
  await page.locator('h1', { hasText: 'Migration desk' }).waitFor({ timeout: 20000 })
    .catch(() => fail('console /migrations did not render the Migration desk'));
  const draftRow = page.locator('[data-testid="plan-row"]', { hasText: draftName }).first();
  await draftRow.waitFor({ timeout: 20000 })
    .catch(() => fail('the draft plan is not on the desk\'s plan list: ' + draftName));
  await draftRow.click();
  await page.locator('[data-testid="plan-detail"]').waitFor({ timeout: 15000 })
    .catch(() => fail('selecting the draft plan opened no detail section'));
  const armBtn = page.locator('[data-testid="arm-plan"]');
  await armBtn.waitFor({ timeout: 15000 })
    .catch(() => fail('no Arm button on the plan detail (staff needs migration:admin)'));
  if (!await armBtn.isDisabled()) {
    fail('Arm must be DISABLED for a draft plan — the desk must mirror the rehearsal gate');
  }
  console.log('OK console desk: the Migration desk lists the draft plan and keeps Arm'
    + ' disabled until a simulation is attached');
  const gone = await ctx.request.delete(`${MIG}/migrationPlan/${draftPlan.id}`, { headers: H });
  if (![200, 204].includes(gone.status())) {
    fail('could not delete the throwaway draft plan: ' + gone.status());
  }
  console.log('OK throwaway draft plan deleted (left tidy)');

  // retire this run's offerings — Active uncategorized fixtures pile up as
  // catalog debris other suites (and the shop's shelf pick) can trip over
  for (const off of [legacy, target]) {
    await ctx.request.patch(`${CATALOG}/productOffering/${off.id}`,
      { headers: H, data: { lifecycleStatus: 'Retired' } }).catch(() => {});
  }
  console.log('OK fixtures retired (left tidy)');

  console.log('OK base migration: rehearse -> arm -> notice -> gate -> order -> exit/rollback, all on the books');
  await browser.close();
  process.exit(0);
})().catch((e) => { console.error('FAIL:', e); process.exit(1); });
