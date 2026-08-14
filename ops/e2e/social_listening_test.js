/* #3b social listening: the inbound ear. Pull brand mentions from social, score
 * sentiment, summarize the mood — so a marketer sees what's said ABOUT the brand,
 * not just what they push out.
 *   seed mentions on the brand handle -> sync -> sentiment summary + feed
 *   -> console Social listening pane shows the mood and the mentions.
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const CONSOLE = 'http://localhost:8080/console/';
const SOCIAL = 'http://localhost:8122';
const HANDLE = 'genalpha-brand';
const run = Date.now();
const SYNC = `${API}/insight/v1/listening/sync`;
const SUMMARY = `${API}/insight/v1/listening/summary`;
const FEED = `${API}/insight/v1/listening/mentions`;

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'bss', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const J = { 'Content-Type': 'application/json' };

  const good = `love the fibre ${run}`;
  const bad = `outage again ${run}`;
  const meh = `just switched ${run}`;
  const seed = (m) => ctx.post(`${SOCIAL}/v1/${HANDLE}/mentions`, { headers: J, data: m });

  /* ---------- the market says things about the brand ---------- */
  await seed({ text: good, platform: 'x', author: `ada${run}` });
  await seed({ text: `great speed ${run}`, platform: 'instagram', author: `sam${run}` });
  await seed({ text: bad, platform: 'x', author: `leo${run}` });
  await seed({ text: meh, platform: 'x', author: `mia${run}` });
  console.log('OK 4 brand mentions posted on the handle (2 positive, 1 negative, 1 neutral)');

  /* ---------- the BSS pulls them in and scores sentiment ---------- */
  const sync = await (await ctx.post(SYNC, { headers: H(staff), data: {} })).json();
  if (!(sync.ingested >= 4)) fail('sync did not ingest the new mentions: ' + JSON.stringify(sync));
  console.log(`OK sync pulled ${sync.ingested} mentions and scored them`);

  const again = await (await ctx.post(SYNC, { headers: H(staff), data: {} })).json();
  if (again.ingested !== 0) fail('re-sync double-counted (not idempotent): ' + JSON.stringify(again));
  console.log('OK re-sync ingested 0 — idempotent per mention');

  /* ---------- the summary shows the mood ---------- */
  const sum = await (await ctx.get(SUMMARY, { headers: H(staff) })).json();
  if (!(sum.total >= 4) || !(sum.sentiment.positive >= 2) || !(sum.sentiment.negative >= 1)) {
    fail('the sentiment summary is wrong: ' + JSON.stringify(sum));
  }
  const feed = await (await ctx.get(FEED, { headers: H(staff) })).json();
  const g = feed.find((m) => m.text === good);
  const b = feed.find((m) => m.text === bad);
  const n = feed.find((m) => m.text === meh);
  if (!g || g.sentiment !== 'positive') fail('a positive mention was mis-scored: ' + JSON.stringify(g));
  if (!b || b.sentiment !== 'negative') fail('a negative mention was mis-scored: ' + JSON.stringify(b));
  if (!n || n.sentiment !== 'neutral') fail('a neutral mention was mis-scored: ' + JSON.stringify(n));
  console.log('OK sentiment scored correctly (love=positive, outage=negative, neutral=neutral); summary reflects the mood');

  /* ================= the console listening pane ================= */
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1200, height: 1200 } });
  await page.goto(CONSOLE);
  await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo'); await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  await page.locator('.tab', { hasText: 'Social listening' }).click();
  await page.waitForSelector('[data-testid="social-listening"]', { timeout: 10000 });
  await page.locator('[data-testid="listening-sync"]').click();
  await page.waitForFunction(() => (document.querySelector('[data-testid="listening-summary"]')?.textContent || '').includes('Mentions:'), { timeout: 10000 });
  await page.waitForSelector('[data-testid="mention-row"]', { timeout: 10000 });
  const rows = await page.locator('[data-testid="mention-row"]').count();
  if (rows < 1) fail('the console listening feed rendered no mentions');
  console.log(`OK the console Social listening pane shows the mood and ${rows} mentions in the feed`);

  await browser.close();
  console.log('\nALL SOCIAL-LISTENING CHECKS PASSED — the martech module has an inbound EAR: it pulls brand '
    + 'mentions, scores sentiment, and shows the mood + feed. Outbound campaigns and inbound listening in one stack.');
})();
