/* Console controls for the auto-refresh scheduler: an ops card in the Audience
 * builder shows status + JVM heap and pauses/resumes/runs it — no restart, no
 * curl. Resume at the end so the stack is left as found.
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const CONSOLE = 'http://localhost:8080/console/';

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const apictx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const staff = await token(apictx, 'bss', 'demo', 'demo');
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };
  const schedStatus = async () => (await apictx.get(`${API}/insight/v1/refresh/status`, { headers: H })).json();

  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1200, height: 1200 } });
  await page.goto(CONSOLE);
  await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo'); await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  await page.locator('.tab', { hasText: 'Audience builder' }).click();
  await page.waitForSelector('[data-testid="scheduler-card"]', { timeout: 10000 });

  /* ---------- the card shows status + heap ---------- */
  const statusText = await page.locator('[data-testid="scheduler-status"]').textContent();
  if (!/JVM heap \d+ \/ \d+ MB/.test(statusText)) fail('the scheduler card does not show JVM heap: ' + statusText);
  if (!/runs \d+/.test(statusText)) fail('the scheduler card does not show run activity');
  console.log('OK the Audience builder shows an auto-refresh ops card with activity + JVM heap');

  /* ---------- Pause from the console (no restart) ---------- */
  await page.locator('[data-testid="scheduler-pause"]').click();
  await page.waitForFunction(() => /paused/.test(document.querySelector('[data-testid="scheduler-enabled"]')?.textContent || ''), { timeout: 8000 });
  if ((await schedStatus()).enabled !== false) fail('Pause did not disable the scheduler (API)');
  console.log('OK Pause stopped the scheduler from the console — API confirms enabled=false');

  /* ---------- Resume ---------- */
  await page.locator('[data-testid="scheduler-resume"]').click();
  await page.waitForFunction(() => /running/.test(document.querySelector('[data-testid="scheduler-enabled"]')?.textContent || ''), { timeout: 8000 });
  if ((await schedStatus()).enabled !== true) fail('Resume did not re-enable the scheduler (API)');
  console.log('OK Resume re-enabled it — API confirms enabled=true');

  /* ---------- Run now ---------- */
  const before = (await schedStatus()).totalRuns;
  await page.locator('[data-testid="scheduler-run"]').click();
  await page.waitForTimeout(1200);
  if (!((await schedStatus()).totalRuns > before)) fail('Run now did not trigger a sweep');
  console.log('OK Run now triggered a manual sweep from the console');

  await browser.close();
  console.log('\nALL SCHEDULER-CONSOLE CHECKS PASSED — ops can watch the auto-refresh (activity + heap) and '
    + 'pause/resume/run it from the console, no restart and no curl.');
})();
