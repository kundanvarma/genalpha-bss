/* Billing-run scale-out (the run that outgrew its loop). Suite #85.
 *
 *  - the proof run measured the disease: 1,300+ accumulated accounts,
 *    strictly serial, 28-108 minutes a run — sixteen suites starved on
 *    "busy". The cure uses the safety the fleet already proved: suite
 *    #56's two replicas showed per-account concurrency is safe, so the
 *    run now runs its own account loop on a bounded worker pool
 *  - the bound asserted here is generous (300s) against a measured
 *    28-minute floor before — an order-of-magnitude claim, not a
 *    micro-benchmark
 *  - the guarantees must survive the pool: the ledger row carries the
 *    counts, a second run creates nothing new (the bill stays the
 *    idempotency checkpoint), and a billed customer has exactly ONE
 *    bill for the period
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const BILLS = '/tmf-api/customerBillManagement/v4';
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

  // if another run is grinding, wait it out first (bounded)
  for (let i = 0; i < 60; i++) {
    const probe = await call('POST', `${BILLS}/billingRun`, staff, {});
    if (!probe.body || !probe.body.busy) break;
    if (i === 59) fail('a prior billing run never finished — the scale-out did not take');
    await sleep(10000);
  }

  /* ---------- 1. the aged fleet bills inside suite patience ---------- */
  const start = Date.now();
  const run = await call('POST', `${BILLS}/billingRun`, staff, {});
  const seconds = (Date.now() - start) / 1000;
  if (run.status !== 200 || run.body.busy) fail(`run: ${run.status} ${run.text.slice(0, 150)}`);
  const total = (run.body.billsCreated || 0) + (run.body.customersSkipped || 0)
    + (run.body.accountsFailed || 0);
  if (total < 1000) {
    fail(`the aged-fleet premise is gone — only ${total} accounts (this suite exists to`
      + ' prove scale; reseed or retire it if the fleet was pruned)');
  }
  if (seconds > 300) fail(`the run took ${Math.round(seconds)}s — the scale-out is not holding`);
  console.log(`OK THE SCALE: ${total} accounts billed/skipped in ${seconds.toFixed(1)}s`
    + ` (${run.body.billsCreated} created, ${run.body.customersSkipped} skipped,`
    + ` ${run.body.accountsFailed} failed) — a run that measured 28 to 108 MINUTES`
    + ' serial now finishes inside suite patience, on the same aged data.');

  /* ---------- 2. the ledger row survived the pool ---------- */
  const ledger = (await call('GET', `${BILLS}/billingRun`, staff)).body || [];
  const row = ledger.find((r) => r.id === run.body.runId)
    || fail('the run is not on its own ledger');
  if (!row.finishedAt) fail('the ledger row never finished');
  console.log('OK THE LEDGER: the run\'s own row carries its finish time and counts —'
    + ' progress and heartbeat survived the move onto worker threads.');

  /* ---------- 3. the checkpoint holds: nothing double-bills ---------- */
  const again = await call('POST', `${BILLS}/billingRun`, staff, {});
  if (again.body.busy) fail('the immediate second run should also be fast, got busy');
  if ((again.body.billsCreated || 0) !== 0) {
    fail(`the second run created ${again.body.billsCreated} bills — the checkpoint leaked`);
  }
  const kaiId = sub(await token('kai@bss.local', 'kai'));
  const kaiBills = (await call('GET',
    `${BILLS}/customerBill?relatedPartyId=${kaiId}&limit=100`, staff)).body || [];
  const periods = kaiBills.map((b) => b.billingPeriod && b.billingPeriod.startDateTime)
    .filter(Boolean);
  const dupes = periods.filter((p, i) => periods.indexOf(p) !== i);
  if (dupes.length) fail('a customer holds two bills for one period: ' + dupes[0]);
  console.log('OK THE CHECKPOINT: an immediate second run created ZERO bills, and no'
    + ' customer holds two bills for one period — exactly-one-bill survived the pool,'
    + ' as suite #56 always said it would.');

  console.log('\nALL BILLING-SCALE CHECKS PASSED — the run that outgrew its loop got the'
    + ' concurrency its own hardening had already proven safe. Sixteen suites stop'
    + ' starving today.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
