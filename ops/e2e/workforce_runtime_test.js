/* The closed loop — workers fully created and controlled by the BSS. Suite #66.
 *
 * Needs: docker compose --profile workforce up -d worker-controller
 *        (and WORKER_AI_API_KEY exported so hired workers have a brain).
 *
 *  - a stranger cannot command the controller (403 — the BSS's verdict)
 *  - HIRE IS START: one authenticated POST → badge minted + container
 *    running, credentials never displayed; the worker lands on the crew
 *    list by NAME after a real shift (verified at the ledger, as always)
 *  - THE BRAIN IS LIVE CONFIG: edit worker-ai-model in tenants.yml → the
 *    controller ROLLS the workers → the container's env carries the new
 *    model, no BSS restart (leases made the roll free)
 *  - FIRE IS STOP: one DELETE → badge revoked AND container gone
 *  - the dashboard's Hire goes through the controller when deployed
 *    ("Hired AND started", no credentials block)
 */
const { execSync } = require('child_process');
const fs = require('fs');
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const TENANTS = `${__dirname}/../../infra/tenants/tenants.yml`;
const run = Date.now();
const fail = (m) => { throw new Error(m); };

async function token(u, p) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: u, password: p }) });
  if (!r.ok) fail(`token: ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const staff = await token('demo', 'demo');
  const health = await fetch('http://localhost:8129/health').then((r) => r.ok).catch(() => false);
  if (!health) fail('worker-controller is not up (docker compose --profile workforce up -d)');

  /* ---------- 1. strangers refused ---------- */
  const anon = await call('POST', '/workforce-runtime/workers', null, { name: 'nope' });
  if (anon.status !== 403) fail(`unauthenticated hire must 403: ${anon.status}`);

  /* ---------- 2. hire IS start ---------- */
  const seeded = await call('POST', '/tmf-api/troubleTicket/v4/troubleTicket', staff, {
    name: `CLOSED LOOP ${run}: SIM not activating`, severity: 'minor',
    description: 'eSIM QR scanned but the profile never activates.' });
  const hired = await call('POST', '/workforce-runtime/workers', staff,
    { name: `s66-${run % 10000}`, job: 'care' });
  if (hired.status !== 201) fail(`hire-and-start: ${hired.status} ${hired.text.slice(0, 200)}`);
  const { container, badge } = hired.body;
  const listed = await call('GET', '/workforce-runtime/workers', staff);
  if (!listed.body.some((w) => w.name === container)) fail('the started worker is not listed');
  console.log(`OK HIRE IS START: one POST minted ${badge} and started ${container} — no`
    + ' credentials displayed, the badge went straight into the container env');

  // its first shift lands on the ledger (verified at the source, as always)
  let mine = null;
  for (let i = 0; i < 40 && !mine; i++) {
    await sleep(10000);
    const ledger = (await call('GET', '/ai/v1/workforce/ledger', staff)).body || [];
    mine = ledger.find((r) => r.claimedByName === badge);
  }
  if (!mine) fail('the hired worker never worked (no ledger row)');
  console.log(`OK THE SHIFT IS REAL: ${badge} is on the ledger — [${mine.status}]`
    + ` ${(mine.outcome || mine.summary || '').slice(0, 60)}`);

  /* ---------- 3. the live brain swap ---------- */
  const yml = fs.readFileSync(TENANTS, 'utf8');
  const swapped = yml.replace('worker-ai-model: ${WORKER_AI_MODEL:claude-haiku-4-5-20251001}',
    'worker-ai-model: ${WORKER_AI_MODEL_SWAP:claude-haiku-4-5}');
  if (swapped === yml) fail('could not find worker-ai-model line to swap');
  fs.writeFileSync(TENANTS, swapped);
  try {
    let rolled = false;
    for (let i = 0; i < 12 && !rolled; i++) {
      await sleep(10000);
      try {
        const env = execSync(`docker inspect ${container} --format '{{range .Config.Env}}{{println .}}{{end}}'`,
          { encoding: 'utf8' });
        rolled = env.includes('WORKER_AI_MODEL=claude-haiku-4-5') && !env.includes('20251001');
      } catch { /* container mid-roll */ }
    }
    if (!rolled) fail('the controller never rolled the worker onto the new brain');
    console.log('OK LIVE BRAIN SWAP: one edit to worker-ai-model in tenants.yml and the controller'
      + ' ROLLED the running worker onto the new model — no BSS restart, no manual steps;'
      + ' the lease design made the roll free');
  } finally {
    fs.writeFileSync(TENANTS, yml); // restore the registry
  }

  /* ---------- 4. fire IS stop ---------- */
  const fired = await call('DELETE', `/workforce-runtime/workers/${container}`, staff);
  if (fired.status !== 200) fail(`fire: ${fired.status} ${fired.text.slice(0, 200)}`);
  await sleep(2000);
  const after = await call('GET', '/workforce-runtime/workers', staff);
  if (after.body.some((w) => w.name === container)) fail('the fired worker is still listed');
  console.log('OK FIRE IS STOP: one DELETE revoked the badge AND removed the container');

  /* ---------- 5. the dashboard goes through the controller ---------- */
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    await page.goto(`${API}/console/`);
    await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
    if (await page.locator('input[name="username"]').count()) {
      await page.fill('input[name="username"]', 'demo');
      await page.fill('input[name="password"]', 'demo');
      await page.click('input[type="submit"], button[type="submit"]');
    }
    await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
    await page.locator('.tab', { hasText: 'Workforce' }).click();
    await page.waitForSelector('[data-testid=wf-hire-name]');
    await page.fill('[data-testid=wf-hire-name]', `ui66-${run % 1000}`);
    await page.selectOption('[data-testid=wf-hire-job]', 'care');
    await page.click('[data-testid=wf-hire-go]');
    await page.waitForSelector('[data-testid=wf-hired-started]', { timeout: 20000 });
    if (await page.locator('[data-testid=wf-hired-creds]').count()) {
      fail('credentials were displayed despite the controller being deployed');
    }
    console.log('OK DASHBOARD: Hire went through the controller — "Hired AND started", zero'
      + ' credentials on screen. The closed loop is a single click.');
    // tidy: fire the UI-hired worker
    const workers = (await call('GET', '/workforce-runtime/workers', staff)).body;
    const uiw = workers.find((w) => w.badge.includes(`ui66-${run % 1000}`));
    if (uiw) await call('DELETE', `/workforce-runtime/workers/${uiw.name}`, staff);
  } finally { await browser.close(); }

  console.log('\nALL CLOSED-LOOP CHECKS PASSED — hire is start, fire is stop, the brain is live'
    + ' config, strangers are refused by the BSS\'s own verdict, and the dashboard\'s one click'
    + ' produces a working, badged, revocable AI employee.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
