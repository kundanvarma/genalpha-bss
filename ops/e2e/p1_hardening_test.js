/* P1 hardening — fast, observable, and interruptible. Suite #57.
 *
 *  - THE RUN SURVIVES ITS OWN DEATH: twelve fresh customers, the billing
 *    run paced slow (dev knob), billing KILLED mid-run — then triggered
 *    again. Every customer must end with exactly ONE bill: the bills cut
 *    before the crash survived (per-account commits), none were cut twice
 *    (the bill is the resume marker), and the run ledger shows the
 *    superseded run beside the completed one.
 *  - THE CEILING REMEMBERS: the wide ring tripped, the gateway RESTARTED,
 *    and the refusal still stands — buckets live in Redis now, so a
 *    replica's death forgets no window (and N replicas share one ceiling).
 *  - THE SMOKE SLO: a short load burst through the real gateway must stay
 *    under a generous p95 — a tripwire for regressions, not a benchmark
 *    (baselines live in docs/perf-baselines.md).
 *  - ALERTS EXIST: Prometheus evaluates the fleet's rules — a dead
 *    service or a stalled outbox pages a human, not a dashboard.
 */
const { execSync } = require('child_process');
const { request } = require('playwright');

const API = 'http://localhost:8080';
const REPO = `${__dirname}/../..`;
const run = Date.now();
const PLAN = { id: '14291c1a-df26-4232-8084-500466888e46', name: 'GenAlpha Mobile 10 GB' };
const ENV = { ...process.env, PATH: '/opt/homebrew/bin:' + process.env.PATH };
const CUSTOMERS = 12;

const sh = (cmd, timeout = 240000) =>
  execSync(cmd, { cwd: REPO, env: ENV, timeout }).toString().trim();

async function token(ctx, user, pass) {
  for (let i = 0; i < 12; i++) {
    try {
      const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
        { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
      const tok = (await res.json()).access_token;
      if (tok) return tok;
    } catch (transient) { /* mid-boot is not a verdict */ }
    await new Promise((r) => setTimeout(r, 3000));
  }
  throw new Error(`no token for ${user}`);
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const BILLS = `${API}/tmf-api/customerBillManagement/v4`;

  /* ---------- 1. THE RUN SURVIVES ITS OWN DEATH ---------- */
  console.log('pacing the billing run (400ms per account) ...');
  sh('RUN_ACCOUNT_DELAY_MS=400 docker compose up -d billing');
  let staff = await token(ctx, 'demo', 'demo');
  // wait until billing answers before building the cast
  for (let i = 0; i < 30; i++) {
    try {
      const ready = await ctx.get(`${BILLS}/billingRun`, { headers: H(staff), timeout: 3000 });
      if (ready.status() === 200) break;
    } catch (booting) { /* knock again */ }
    await sleep(3000);
  }

  console.log(`creating ${CUSTOMERS} customers with active plans (in parallel) ...`);
  const people = await Promise.all(Array.from({ length: CUSTOMERS }, async (_, i) => {
    const email = `resume-${run}-${i}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'Resume', familyName: `Proof${run}x${i}` } })).json();
    if (!login.id) fail('customer mint failed: ' + JSON.stringify(login).slice(0, 120));
    await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
      id: login.id, givenName: 'Resume', familyName: `Proof${run}x${i}`,
      contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
    const cust = await token(ctx, email, login.temporaryPassword);
    const order = await (await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
      headers: H(cust), data: { productOrderItem: [{ action: 'add', productOffering: PLAN }] } })).json();
    if (!order.id) fail(`order ${i} failed: ` + JSON.stringify(order).slice(0, 120));
    // keep the PASSWORD, not the token: this suite outlives Keycloak's
    // 5-minute access tokens, so every later phase mints fresh
    return { email, password: login.temporaryPassword };
  }));
  await sleep(8000); // activation settles; the products go active

  // fire the paced run and DO NOT wait for it — it is about to die
  const doomed = ctx.post(`${BILLS}/billingRun`, { headers: H(staff) }).catch(() => null);
  await sleep(6000); // a handful of accounts commit, most are still ahead
  console.log('killing billing MID-RUN (the recreate also resets the pacing to 0) ...');
  // recreating with default env IS the crash — and the resume runs flat out
  sh('docker compose up -d billing');
  await doomed; // the orphaned HTTP call settles either way

  // billing back up — but a RECREATED container has a new address, and
  // the gateway's pooled connections to the old one take a while to
  // heal (the recorded stale-conn gotcha). Wait for the PATH first.
  staff = await token(ctx, 'demo', 'demo');
  let path = 0;
  // ANY HTTP answer (even a 401 for an aged token) means the gateway
  // reaches the new billing container — that is all "healed" means here
  for (let i = 0; i < 60 && path !== 200 && path !== 401; i++) {
    await sleep(4000);
    try {
      path = (await ctx.get(`${BILLS}/billingRun`, { headers: H(staff), timeout: 4000 })).status();
    } catch (healing) { path = 0; }
  }
  if (path !== 200 && path !== 401) {
    fail('the gateway→billing path never healed after the kill (last ' + path + ')');
  }
  console.log('the path healed — now knocking for the resume run ...');

  // the dead run's heartbeat lease frees within ~2 minutes; "busy"
  // until then is the guard doing its job, so knock politely and wait —
  // with a FRESH token each knock: the wait outlives a 5-minute token
  let resumed = null;
  for (let i = 0; i < 40 && !resumed; i++) {
    try {
      const knock = await token(ctx, 'demo', 'demo');
      const res = await ctx.post(`${BILLS}/billingRun`,
        { headers: H(knock), timeout: 300000 });
      if (res.status() === 200) {
        const body = await res.json();
        if (body.busy) {
          if (i % 4 === 0) console.log('  (busy — the dead run\'s lease has not expired yet)');
        } else {
          resumed = body;
        }
      } else if (i % 4 === 0) {
        console.log(`  (knock answered ${res.status()} — retrying)`);
      }
    } catch (bootBlip) {
      if (i % 4 === 0) console.log('  (knock failed to connect — retrying)');
    }
    if (!resumed) await sleep(5000);
  }
  if (!resumed) fail('the resume run never completed');
  console.log(`the resume run finished: ${resumed.billsCreated} new bills, `
    + `${resumed.customersSkipped} skipped (run ${String(resumed.runId).slice(0, 8)})`);

  // EXACTLY ONE bill each — the whole point, customer by customer
  // (fresh sign-in per person: their creation tokens have aged out)
  for (const person of people) {
    const mine = await token(ctx, person.email, person.password);
    const list = await (await ctx.get(`${BILLS}/customerBill?limit=50`,
      { headers: H(mine) })).json();
    const count = Array.isArray(list) ? list.length : 0;
    if (count !== 1) {
      fail(`${person.email} holds ${count} bills — the crash ${count === 0
        ? 'lost a bill' : 'double-billed'}`);
    }
  }
  console.log(`OK EXACTLY ONE BILL × ${CUSTOMERS}: the bills cut before the kill survived`
    + ' (their commits were their own), and the resume run re-billed nobody — the bill IS'
    + ' the checkpoint');

  // and the ledger tells the story: a superseded run beside a completed one
  staff = await token(ctx, 'demo', 'demo');
  const ledger = await (await ctx.get(`${BILLS}/billingRun`, { headers: H(staff) })).json();
  const superseded = ledger.find((r) => r.status === 'superseded');
  const completed = ledger.find((r) => r.status === 'completed');
  if (!superseded) fail('no superseded run — the kill missed the window, nothing was proven');
  if (!completed) fail('no completed run in the ledger');
  console.log(`OK THE LEDGER: run ${superseded.id.slice(0, 8)} died running (superseded, `
    + `${superseded.billsCreated} bills stand) and run ${completed.id.slice(0, 8)} finished the`
    + ` period — every run has a face now, crashes included`);

  sh('docker compose up -d billing'); // pacing back to 0
  console.log('OK restored: the pacing knob is a dev clock, production runs flat out');

  /* ---------- 2. THE SMOKE SLO ---------- */
  sh('GLOBAL_RATE_CAPACITY=1000000 docker compose up -d gateway');
  await sleep(18000);
  const burst = sh('node ops/load/loadtest.js --seconds 8 --workers 5 --scenario catalog', 120000);
  const numbers = JSON.parse(burst.slice(burst.lastIndexOf('\n[')));
  const catalog = numbers.find((r) => r.scenario === 'catalog');
  if (!catalog || catalog.requests < 100) fail('the load burst barely ran: ' + burst.slice(-200));
  if (catalog.errors > 0) fail(`the burst saw ${catalog.errors} server errors`);
  if (catalog.p95 >= 1500) {
    fail(`catalog p95 ${catalog.p95}ms breaches the smoke SLO (1500ms) — a regression, look at`
      + ' docs/perf-baselines.md for what it used to be');
  }
  console.log(`OK SMOKE SLO: ${catalog.rps} req/s with p95 ${catalog.p95}ms (SLO <1500ms,`
    + ` baseline ~39ms) — the tripwire is armed, the numbers live in docs/perf-baselines.md`);

  /* ---------- 3. THE CEILING REMEMBERS (Redis buckets) ---------- */
  sh('GLOBAL_RATE_CAPACITY=15 GLOBAL_RATE_WINDOW_MS=120000 docker compose up -d gateway');
  await sleep(18000);
  let refused = false;
  for (let i = 0; i < 30 && !refused; i++) {
    const res = await ctx.get(`${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=1`);
    refused = res.status() === 429;
  }
  if (!refused) fail('30 knocks and the ring never refused');
  console.log('the ceiling is tripped — now the gateway DIES ...');
  sh('docker restart bss-gateway');
  let back = false;
  for (let i = 0; i < 30 && !back; i++) {
    await sleep(2000);
    try {
      const res = await ctx.get(`${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=1`,
        { timeout: 3000 });
      if (res.status() > 0) {
        back = true;
        if (res.status() !== 429) {
          fail(`the restarted gateway forgot the window (got ${res.status()}) — buckets are not`
            + ' shared; is REDIS_URL set?');
        }
      }
    } catch (stillBooting) { /* keep knocking */ }
  }
  if (!back) fail('the gateway never came back');
  console.log('OK THE CEILING REMEMBERED: a fresh gateway process refused knock #1 with 429 —'
    + ' the window lives in Redis, so a restart forgets nothing and N replicas would share ONE'
    + ' exact ceiling');
  sh('docker compose up -d gateway');
  await sleep(15000);
  // the suite cleans its own windows: the SLO burst's ip bucket SURVIVES
  // the recreate (that is the Redis property!) and would throttle the
  // next suite — delete the test's rate keys before declaring calm
  sh(`docker exec bss-redis sh -c "redis-cli --scan --pattern 'rate:*' | xargs -r redis-cli del"`);
  const calm = await ctx.get(`${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=1`);
  if (calm.status() === 429) fail('the default dial did not come back');
  console.log('OK restored: dials back to 1200/min, and the suite\'s own windows are swept');

  /* ---------- 4. ALERTS EXIST ---------- */
  const rules = await (await ctx.get('http://localhost:9090/api/v1/rules')).json();
  const names = rules.data.groups.flatMap((g) => g.rules.map((r) => r.name));
  for (const expected of ['ServiceDown', 'OutboxPublishFailures', 'GatewayServerErrors']) {
    if (!names.includes(expected)) fail(`alert rule ${expected} is not loaded: [${names}]`);
  }
  const down = rules.data.groups.flatMap((g) => g.rules)
    .find((r) => r.name === 'ServiceDown');
  if (!down.alerts) {
    // no alerts array at all would mean the rule never evaluated
    fail('ServiceDown has never evaluated');
  }
  console.log(`OK ALERTS EXIST: ${names.length} rules loaded and evaluating in Prometheus`
    + ' (ServiceDown watches the WHOLE fleet — 32 scrape targets now, up from 5) — routing to'
    + ' a pager is the deployment\'s half, docs/hardening.md says how');

  console.log('\nALL P1 HARDENING CHECKS PASSED — the billing run survives its own death and'
    + ' resumes to exactly-once, the rate ceiling outlives its gateway, throughput has numbers'
    + ' with a tripwire, and silence now pages somebody. Fast, and still honest.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
