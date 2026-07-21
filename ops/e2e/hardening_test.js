/* P0 hardening — the boring engineering, proven the house way.
 *
 *  - TWO REPLICAS, ONE PAPER BILL: billing runs twice against one
 *    database (docker compose run — same network, same env, no compose
 *    surgery), a bill is cut while both replicas tick every 3 seconds,
 *    and the RECEIVER counts: the mock print house must hold exactly
 *    ONE copy. The tick_lock row is the mechanism; the mock's ledger
 *    is the receipt.
 *  - THE WIDE RING: every path now has a rate ceiling, not just the
 *    dealer surface. The suite swaps the gateway to a small ceiling,
 *    bursts anonymous knocks into a 429 with Retry-After, shows an
 *    authenticated caller rides a DIFFERENT bucket, and swaps back.
 *  - A RESTORE THAT HAPPENED: a sentinel customer is created, the
 *    fleet is dumped, the dump is restored into a THROWAWAY container,
 *    and the sentinel must be found in the copy. An untested backup is
 *    a hope; this one is a fact.
 */
const { execSync } = require('child_process');
const { request } = require('playwright');

const API = 'http://localhost:8080';
const DIST = 'http://localhost:8124';
const REPO = `${__dirname}/../..`;
const run = Date.now();
const PLAN = { id: '14291c1a-df26-4232-8084-500466888e46', name: 'GenAlpha Mobile 10 GB' };
const ENV = { ...process.env, PATH: '/opt/homebrew/bin:' + process.env.PATH };

const sh = (cmd, timeout = 240000) =>
  execSync(cmd, { cwd: REPO, env: ENV, timeout }).toString().trim();

async function token(ctx, realm, user, pass) {
  for (let i = 0; i < 10; i++) {
    try {
      const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
        { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
      const tok = (await res.json()).access_token;
      if (tok) return tok;
    } catch (transient) { /* keycloak mid-boot is not a verdict */ }
    await new Promise((r) => setTimeout(r, 3000));
  }
  throw new Error(`no token for ${user}@${realm}`);
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const BILLS = `${API}/tmf-api/customerBillManagement/v4`;
  const staff = await token(ctx, 'bss', 'demo', 'demo');

  /* ---------- 1. TWO REPLICAS, ONE PAPER BILL ---------- */
  console.log('raising a second billing replica (same database, same ticks) ...');
  const replica = sh('docker compose run --no-deps -d billing');
  try {
    // wait until the twin's Spring context is up and ticking
    let twinUp = false;
    for (let i = 0; i < 40 && !twinUp; i++) {
      await sleep(3000);
      try {
        twinUp = execSync(`docker logs ${replica} 2>&1 | grep -c "Started BillingApplication"`,
          { env: ENV }).toString().trim() !== '0';
      } catch (notYet) { /* grep exit 1 = not yet */ }
    }
    if (!twinUp) fail('the second billing replica never finished booting');
    console.log('OK the twin is up — two schedulers now race for every tick');

    // a fresh customer, an order, a bill — while both replicas tick
    const email = `twin-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'Twin', familyName: `Proof${run}` } })).json();
    await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
      id: login.id, givenName: 'Twin', familyName: `Proof${run}`,
      contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
    const cust = await token(ctx, 'bss', email, login.temporaryPassword);
    await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
      headers: H(cust), data: { productOrderItem: [{ action: 'add', productOffering: PLAN }] } });
    let bill = null;
    for (let i = 0; i < 30 && !bill; i++) {
      await sleep(2000);
      await ctx.post(`${BILLS}/billingRun`, { headers: H(staff) });
      const list = await (await ctx.get(`${BILLS}/customerBill?limit=50`, { headers: H(cust) })).json();
      bill = (Array.isArray(list) ? list : []).find((b) => b.state === 'new') || null;
    }
    if (!bill) fail('no bill was cut for the twin-proof customer');

    // the print job must arrive — and then arrive NO MORE
    let copies = [];
    for (let i = 0; i < 20 && copies.length === 0; i++) {
      await sleep(1500);
      copies = (await (await ctx.get(`${DIST}/invoices?billNo=${bill.billNo}`)).json())
        .filter((j) => j.channel === 'print');
    }
    if (copies.length === 0) fail('the print job never reached the distribution partner');
    await sleep(12000); // four more tick cycles in BOTH replicas — the race window
    copies = (await (await ctx.get(`${DIST}/invoices?billNo=${bill.billNo}`)).json())
      .filter((j) => j.channel === 'print');
    if (copies.length !== 1) {
      fail(`the print house holds ${copies.length} copies of ${bill.billNo} — the tick fired twice`);
    }
    // and the mechanism is visible: the lock table shows the leases
    const locks = sh(`docker exec bss-postgres psql -U postgres -d billing -tA -c `
      + `"SELECT count(DISTINCT name) FROM tick_lock"`);
    if (Number(locks) < 2) fail('tick_lock shows fewer than 2 ticks ever claimed: ' + locks);
    console.log(`OK ONE PAPER BILL: two replicas ticked for ${bill.billNo}, the print house`
      + ` received exactly one copy, and tick_lock holds ${locks} named leases — the lock is`
      + ' a row, and it held');
  } finally {
    try { sh(`docker rm -f ${replica}`); } catch (gone) { /* already removed */ }
  }
  console.log('OK the twin is retired — back to one replica, nothing to clean up');

  /* ---------- 2. A RESTORE THAT HAPPENED ---------- */
  const sentinelFamily = `Drill${run}`;
  const sEmail = `drill-${run}@example.com`;
  const sLogin = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email: sEmail, givenName: 'Restore', familyName: sentinelFamily } })).json();
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
    id: sLogin.id, givenName: 'Restore', familyName: sentinelFamily,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: sEmail } }] } });
  console.log(`sentinel customer "Restore ${sentinelFamily}" exists — dumping the fleet ...`);
  const backupOut = sh('ops/backup.sh');
  if (!backupOut.includes('backup: wrote')) fail('backup.sh did not write a dump: ' + backupOut);
  const drillOut = sh(`ops/restore-drill.sh --expect party_account `
    + `"SELECT 1 FROM individual WHERE family_name='${sentinelFamily}'"`, 420000);
  if (!drillOut.includes('restore-drill: PASSED')) fail('the drill did not pass: ' + drillOut);
  if (!drillOut.includes('sentinel found')) fail('the sentinel did not survive: ' + drillOut);
  console.log('OK RESTORED, PROVABLY: the dump came back in a throwaway container and the'
    + ' sentinel row was found in the copy — this backup is a fact, not a hope');

  /* ---------- 3. THE WIDE RING ---------- */
  console.log('swapping the gateway to a 15-knock ceiling ...');
  sh('GLOBAL_RATE_CAPACITY=15 docker compose up -d gateway');
  await sleep(15000);
  let refused = null;
  for (let i = 0; i < 30; i++) {
    const res = await ctx.get(`${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=1`);
    if (res.status() === 429) { refused = res; break; }
  }
  if (!refused) fail('30 anonymous knocks and the wide ring never refused');
  if (!refused.headers()['retry-after']) fail('the 429 carries no Retry-After');
  // a person with a token rides their OWN bucket — the burst above was ip:
  const calm = await ctx.get(`${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=1`,
    { headers: H(staff) });
  if (calm.status() === 429) fail('an authenticated caller was throttled by the anonymous burst');
  console.log('OK THE WIDE RING: anonymous knock #16+ met a 429 with Retry-After, while an'
    + ' authenticated caller (a different bucket: the token\'s sub) sailed through — fairness'
    + ' per subject, not one shared choke');
  sh('docker compose up -d gateway'); // back to the 1200 default
  await sleep(15000);
  const restored = await ctx.get(`${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=1`,
    { headers: H(staff) });
  if (restored.status() === 429) fail('the gateway did not return to the generous default');
  console.log('OK RESTORED: the ceiling is back to 1200/min — the dial is an env var, not a build');

  console.log('\nALL P0 HARDENING CHECKS PASSED — scale-out no longer double-fires the ticks,'
    + ' every path has a ceiling, and the backup provably restores. The boring engineering,'
    + ' with receipts.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
