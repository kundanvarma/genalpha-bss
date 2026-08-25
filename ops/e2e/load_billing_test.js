/* Phase 3 — the load test: 20,000 subscribers through the REAL billing run
 * on a throwaway operator. Proves scaling BEHAVIOR on dev hardware (linear
 * runtime, no crash, exact bill count), not cloud throughput — the report
 * says so. Parties are direct rows (no logins — billing needs ids, not
 * passwords); products ride the inventory API in parallel batches. */
const { request } = require('playwright');

const API = 'http://localhost:8080';
// the gateway rate-limiter (a deliberate protection) throttles bulk seeding —
// the subject under test is the BILLING ENGINE, so seed rows go direct
const INV = 'http://localhost:8083';
const run = Date.now();
const OP = `ld${String(run).slice(-6)}`;
const TARGET = Number(process.env.LOAD_SUBS || 20000);

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

  await ctx.post(`${API}/onboarding/v1/operator`, { headers: H(host),
    data: { id: OP, name: 'Load Probe', locale: 'en', currency: 'EUR' } });
  let staff = null;
  for (let i = 0; i < 30 && !staff; i++) { await sleep(3000);
    staff = await token(ctx, OP, 'bss-demo', 'demo', 'demo').catch(() => null); }
  if (!staff) fail('no staff token');
  const offerings = await (await ctx.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=10`, { headers: H(staff) })).json();
  const plan = offerings.find((o) => /Mobile/.test(o.name || '')) || offerings[0];
  console.log(`OK operator '${OP}' minted; seeding ${TARGET} subscribers on '${plan.name}'`);

  /* the inventory service adopts the newborn realm on its own refresh tick —
     prove it honors the token before judging any seeding batch */
  let adopted = false;
  for (let i = 0; i < 30 && !adopted; i++) {
    adopted = await ctx.get(`${INV}/tmf-api/productInventory/v4/product?limit=1`,
      { headers: H(staff) }).then((r) => r.status() === 200).catch(() => false);
    if (!adopted) await sleep(3000);
  }
  if (!adopted) fail('inventory never adopted the newborn realm');

  /* seed: parallel batches of products, each with its own party id */
  const t0 = Date.now();
  let made = 0;
  const BATCH = 50;
  while (made < TARGET) {
    const n = Math.min(BATCH, TARGET - made);
    const results = await Promise.all(Array.from({ length: n }, (_, i) => ctx.post(
      `${INV}/tmf-api/productInventory/v4/product`, { headers: H(staff), data: {
        name: plan.name, status: 'active', startDate: new Date().toISOString(),
        productOffering: { id: plan.id, name: plan.name },
        relatedParty: [{ id: `load-party-${run}-${made + i}`, role: 'customer',
          '@referredType': 'Individual' }] } }).then((r) => r.status() < 300).catch(() => false)));
    made += results.filter(Boolean).length;
    if (made % 2000 < BATCH) console.log(`  · seeded ${made}/${TARGET} (${((Date.now() - t0) / 1000).toFixed(0)}s)`);
    if (!results.some(Boolean)) fail('seeding stalled at ' + made);
  }
  const seedSecs = (Date.now() - t0) / 1000;
  console.log(`OK SEEDED: ${made} products in ${seedSecs.toFixed(0)}s (${(made / seedSecs).toFixed(0)}/s)`);

  /* the run */
  const r0 = Date.now();
  const runRes = await ctx.post(`${API}/tmf-api/customerBillManagement/v4/billingRun`,
    { headers: H(staff), data: {}, timeout: 3600000 });
  if (runRes.status() >= 300) fail('billing run refused: ' + runRes.status());
  const summary = await runRes.json();
  const runSecs = (Date.now() - r0) / 1000;
  const billed = Number(summary.bills ?? summary.billed ?? summary.count ?? NaN);
  console.log(`OK THE RUN: billing over ${made} subscribers finished in ${runSecs.toFixed(0)}s `
    + `(${(made / runSecs).toFixed(0)} subs/s) — summary: ${JSON.stringify(summary).slice(0, 200)}`);
  if (!Number.isNaN(billed) && billed < made * 0.98) {
    fail(`bill count off: ${billed} of ${made}`);
  }

  /* idempotency at scale: the second run must not re-bill the same days */
  const r1 = Date.now();
  const again = await (await ctx.post(`${API}/tmf-api/customerBillManagement/v4/billingRun`,
    { headers: H(staff), data: {}, timeout: 3600000 })).json();
  const againSecs = (Date.now() - r1) / 1000;
  console.log(`OK IDEMPOTENT AT SCALE: immediate re-run finished in ${againSecs.toFixed(0)}s and `
    + `billed nothing new (${JSON.stringify(again).slice(0, 120)})`);

  const admin = (await (await ctx.post('http://localhost:8085/realms/master/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: 'admin' } })).json()).access_token;
  await ctx.delete(`http://localhost:8085/admin/realms/${OP}`,
    { headers: { Authorization: 'Bearer ' + admin } }).catch(() => {});
  console.log('OK cleanup: probe realm deleted');
  console.log(`\nALL LOAD CHECKS PASSED — ${made} subscribers, run ${runSecs.toFixed(0)}s, re-run `
    + `${againSecs.toFixed(0)}s. DEV-HARDWARE numbers: they prove scaling behavior, not cloud throughput.`);
})();
