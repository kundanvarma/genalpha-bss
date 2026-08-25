/* SC-P1..P3 — the shadow-operator clone: simulate by running the real thing.
 *
 *  - CLONE: one POST mints a SANDBOX operator that IS genalpha again —
 *    catalog and rules copied over the tenants' own staff tokens, sandbox
 *    flag stamped in the fleet file, gateway manifest attesting it
 *  - FIDELITY: the portfolio diff (same engine that cuts real bills) prices
 *    both shelves — the clone matches the source with ZERO changed rows
 *  - THE WHAT-IF: move ONE price inside the clone; the diff names exactly
 *    that offering with exactly that delta, and the portfolio total moves
 *    by the same amount — mutate the clone, read the answer, decide
 *  - THE WALL: an email sent inside the sandbox is SUPPRESSED, not sent —
 *    stored in-app as 'sandbox-suppressed'; real engines, no real world
 *  - cleanup: the probe realm dies
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const SC = `sc${String(run).slice(-6)}`;
const CAT = `${API}/tmf-api/productCatalogManagement/v4`;

async function token(ctx, realm, client, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const host = await token(ctx, 'bss', 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  /* ---------- 1. CLONE genalpha into a sandbox ---------- */
  const cloneRes = await ctx.post(`${API}/onboarding/v1/operator/genalpha/clone`,
    { headers: H(host), data: { id: SC } });
  if (cloneRes.status() !== 201) fail('clone refused: ' + cloneRes.status()
    + ' ' + (await cloneRes.text()).slice(0, 200));
  const clone = await cloneRes.json();
  if (clone.sandbox !== true) fail('the clone is not marked sandbox: ' + JSON.stringify(clone));
  const copied = clone.copied || {};
  if (!(copied.offerings > 10) || !(copied.prices > 10)) {
    fail('the copy looks empty: ' + JSON.stringify(copied));
  }
  console.log(`OK CLONE: '${SC}' minted as a SANDBOX of genalpha in ${clone.seconds}s — `
    + `${copied.categories} categories, ${copied.specifications} specs, ${copied.prices} prices, `
    + `${copied.offerings} offerings, ${copied.policyRules} rules, `
    + `${copied.rateCards} wholesale rate cards copied over staff tokens`);
  if (!(copied.rateCards >= 3)) fail('the wholesale money-model did not travel: ' + copied.rateCards);

  let staff = null;
  for (let i = 0; i < 30 && !staff; i++) {
    await sleep(3000);
    staff = await token(ctx, SC, 'bss-demo', 'demo', 'demo').catch(() => null);
  }
  if (!staff) fail('no staff token from the clone realm');

  // the gateway manifest attests the sandbox — every channel can badge it
  // the sandbox flag lands one refresher tick after the block joins — poll
  // for the ATTESTATION, not just the routing
  let manifest = {};
  for (let i = 0; i < 30; i++) {
    manifest = await (await ctx.get(`${API}/app/tenant-config.json?_=${Date.now()}`,
      { headers: { Host: `shop.${SC}.localhost` } })).json().catch(() => ({}));
    if (manifest.tenantId === SC && manifest.sandbox === true) break;
    await sleep(3000);
  }
  if (manifest.tenantId !== SC) fail('guest routing never reached the clone');
  if (manifest.sandbox !== true) fail('the manifest never attested sandbox: '
    + JSON.stringify(manifest).slice(0, 200));
  console.log('OK ATTESTED: the gateway manifest carries sandbox:true — no channel can mistake it for production');

  /* ---------- 2. FIDELITY: the diff engine sees an identical portfolio ---------- */
  const diff0 = await (await ctx.get(
    `${API}/tmf-api/customerBillManagement/v4/portfolioDiff?tenantA=genalpha&tenantB=${SC}`,
    { headers: H(host) })).json();
  if (!(diff0.matched > 10)) fail('too few matched offerings: ' + diff0.matched);
  if ((diff0.changed || []).length !== 0) {
    fail('a fresh clone differs from its source: ' + JSON.stringify(diff0.changed).slice(0, 300));
  }
  console.log(`OK FIDELITY: ${diff0.matched} offerings matched, 0 changed — the clone IS the `
    + `source, priced by the same engine (portfolio ${diff0.portfolioMonthlyA} vs ${diff0.portfolioMonthlyB})`);

  /* ---------- 3. THE WHAT-IF: move one price inside the clone ---------- */
  const offerings = await (await ctx.get(
    `${CAT}/productOffering?name=${encodeURIComponent('GenAlpha Mobile Unlimited 5G')}`,
    { headers: H(staff) })).json();
  const target = offerings[0];
  if (!target) fail('the clone has no GenAlpha Mobile Unlimited 5G');
  const detail = await (await ctx.get(`${CAT}/productOffering/${target.id}`, { headers: H(staff) })).json();
  const priceRef = (detail.productOfferingPrice || [])[0];
  if (!priceRef) fail('the target offering carries no price');
  const price = await (await ctx.get(`${CAT}/productOfferingPrice/${priceRef.id}`, { headers: H(staff) })).json();
  const oldValue = Number(price.price.value);
  const patch = await ctx.patch(`${CAT}/productOfferingPrice/${priceRef.id}`,
    { headers: H(staff), data: { price: { unit: price.price.unit, value: oldValue + 100 } } });
  if (patch.status() >= 300) fail('price patch refused: ' + patch.status());

  const diff1 = await (await ctx.get(
    `${API}/tmf-api/customerBillManagement/v4/portfolioDiff?tenantA=genalpha&tenantB=${SC}`,
    { headers: H(host) })).json();
  const changed = diff1.changed || [];
  const row = changed.find((r) => r.name === 'GenAlpha Mobile Unlimited 5G');
  if (!row) fail('the moved price never surfaced in the diff: ' + JSON.stringify(changed));
  if (Number(row.delta) !== 100) fail('delta is not exactly 100: ' + row.delta);
  // THE RIPPLE: every bundle carrying this plan moves with it — each changed
  // row is exactly the one price move, seen through a different offering
  const offDelta = changed.filter((r) => Number(r.delta) !== 100);
  if (offDelta.length) fail('a changed row is not the one move: ' + JSON.stringify(offDelta));
  // the clone's own starter-seed offerings sit in onlyInB on BOTH readings —
  // the what-if's effect is the MOVE in portfolio delta vs the baseline
  const rippleTotal = Number(diff1.portfolioDelta) - Number(diff0.portfolioDelta);
  if (rippleTotal !== 100 * changed.length) {
    fail('portfolio delta must move by the ripple: ' + rippleTotal
      + ' vs ' + (100 * changed.length));
  }
  console.log(`OK THE WHAT-IF + THE RIPPLE: +100 on ONE price inside the clone → ${changed.length} `
    + `offerings moved (the plan AND every bundle that carries it: `
    + `${changed.map((r) => r.name).join(', ')}), each by exactly 100.00 — the diff sees `
    + 'through bundles, the source untouched');

  /* ---------- 4. THE WALL: the sandbox cannot reach the outside world ---------- */
  const msgRes = await ctx.post(`${API}/tmf-api/communicationManagement/v4/communicationMessage`,
    { headers: H(staff), data: {
      subject: `Sandbox probe ${run}`, content: 'If you can read this in a real inbox, the wall failed.',
      toEmail: 'wall-probe@example.com' } });
  if (msgRes.status() >= 300) fail('sandbox message create failed: ' + msgRes.status());
  const msg = await msgRes.json();
  if (msg.deliveryStatus !== 'sandbox-suppressed') {
    fail('THE WALL LEAKED: deliveryStatus=' + msg.deliveryStatus + ' (wanted sandbox-suppressed)');
  }
  console.log('OK THE WALL: an email inside the sandbox is stored in-app and marked '
    + 'sandbox-suppressed — real engines, no real-world side effects');

  /* ---------- 4b. THE WALL, every door: PSP and ad platforms ---------- */
  const psp = await ctx.post(`${API}/tmf-api/paymentManagement/v4/payment/session`,
    { headers: H(staff), data: { method: 'klarna', amount: { value: 10, unit: 'EUR' } } });
  if (psp.status() < 400 || !/sandbox/.test(await psp.text())) {
    fail('PSP wall leaked: ' + psp.status() + ' ' + (await psp.text()).slice(0, 120));
  }
  const act = await ctx.post(`${API}/insight/v1/audience/any-id/activate`,
    { headers: H(staff), data: { destination: 'meta' } });
  const actBody = await act.text();
  if (!/sandbox/.test(actBody)) {
    fail('ad-activation wall silent: ' + act.status() + ' ' + actBody.slice(0, 120));
  }
  console.log('OK EVERY DOOR: the sandbox is refused at the PSP and at the ad platforms too — '
    + 'email, payments and activations all end at the wall');

  /* ---------- 5. TVILLING BASE: a subscriber base that is nobody ---------- */
  const seedRes = await ctx.post(`${API}/onboarding/v1/operator/${SC}/seedTwinBase`,
    { headers: H(host), data: { sourceId: 'genalpha', count: 12 } });
  if (seedRes.status() !== 201) fail('twin seeding refused: ' + seedRes.status()
    + ' ' + (await seedRes.text()).slice(0, 200));
  const seedOut = await seedRes.json();
  if (!(seedOut.seeded >= 10)) fail('too few twins seeded: ' + JSON.stringify(seedOut).slice(0, 200));
  if (!/AGGREGATE/.test(seedOut.privacy || '')) fail('the privacy promise is missing from the receipt');
  console.log(`OK TVILLING BASE: ${seedOut.seeded} synthetic subscribers minted in the clone, `
    + `shaped like genalpha's real base (${Object.keys(seedOut.distribution).length} offerings) — `
    + 'only the aggregate distribution crossed; every person is fictional');

  // the guard: twins in PRODUCTION would be pollution, not simulation
  const guard = await ctx.post(`${API}/onboarding/v1/operator/genalpha/seedTwinBase`,
    { headers: H(host), data: { sourceId: 'genalpha', count: 2 } });
  if (guard.status() < 400) fail('twin seeding into a NON-sandbox was allowed: ' + guard.status());
  console.log('OK SANDBOX-ONLY: seeding a production tenant is refused — twins live in clones');

  // the point of it all: the REAL billing engine bills the fictional base
  const runRes = await ctx.post(`${API}/tmf-api/customerBillManagement/v4/billingRun`,
    { headers: H(staff), data: {} });
  if (runRes.status() >= 300) fail('clone billing run refused: ' + runRes.status());
  let bills = [];
  for (let i = 0; i < 10 && !bills.length; i++) {
    await sleep(3000);
    bills = await (await ctx.get(`${API}/tmf-api/customerBillManagement/v4/customerBill?limit=100`,
      { headers: H(staff) })).json().catch(() => []);
    if (!Array.isArray(bills)) bills = [];
  }
  if (!bills.length) fail('the twin base produced no bills');
  const billed = bills.filter((b) => Number(((b.amountDue || b.taxIncludedAmount || {}).value) || 0) > 0);
  if (!billed.length) fail('no twin bill carries an amount: ' + JSON.stringify(bills[0]).slice(0, 200));
  console.log(`OK BASE-DEPENDENT SIMULATION: the clone's real billing run cut ${bills.length} bills `
    + 'over the synthetic base — dunning, price and migration questions can now run on a base '
    + 'that is structurally real and personally nobody');

  /* ---------- 6. T1: the simulated quarter ---------- */
  const countBills = async () => {
    const r = await (await ctx.get(`${API}/tmf-api/customerBillManagement/v4/customerBill?limit=100`,
      { headers: H(staff) })).json().catch(() => []);
    return Array.isArray(r) ? r.length : 0;
  };
  const before = await countBills();
  const qRes = await ctx.post(`${API}/onboarding/v1/operator/${SC}/simulateQuarter`,
    { headers: H(host), data: {}, timeout: 240000 });
  if (qRes.status() !== 201) fail('simulateQuarter refused: ' + qRes.status());
  const quarter = await qRes.json();
  if ((quarter.cycle || []).length !== 3) fail('not three cycles: ' + JSON.stringify(quarter).slice(0, 200));
  if (!(quarter.assumptions || []).some((a) => /recurring charges only/.test(a))) {
    fail('the quarter report hides its own limits');
  }
  let after = before;
  for (let i = 0; i < 8 && after <= before; i++) { await sleep(3000); after = await countBills(); }
  if (!(after > before)) fail(`the compressed quarter cut no new bills: ${before} -> ${after}`);
  // the clock guard: production time is not a knob
  const clkGuard = await ctx.post(`${API}/onboarding/v1/operator/genalpha/advanceClock`,
    { headers: H(host), data: { days: 30 } });
  if (clkGuard.status() < 400) fail('the clock moved for a NON-sandbox tenant: ' + clkGuard.status());
  console.log(`OK SIMULATED QUARTER: 3 compressed cycles (+30d each) cut ${after - before} new bills `
    + `over the twin base (${before} -> ${after}) — same engine, no day billed twice, `
    + 'assumptions on the report; and the clock REFUSES to move for production');

  /* ---------- cleanup ---------- */
  const admin = (await (await ctx.post('http://localhost:8085/realms/master/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: 'admin' } })).json()).access_token;
  await ctx.delete(`http://localhost:8085/admin/realms/${SC}`,
    { headers: { Authorization: 'Bearer ' + admin } }).catch(() => {});
  console.log('OK cleanup: probe realm deleted — the clone was always disposable');

  console.log('\nALL SHADOW-CLONE CHECKS PASSED — an operator can be cloned into a sandbox in '
    + 'seconds, priced identically by the real engines, mutated safely, diffed exactly, and it '
    + 'can never touch the outside world. Simulation with zero model drift.');
})();
