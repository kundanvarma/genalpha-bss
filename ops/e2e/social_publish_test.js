/* #3c organic publishing: put a post OUT on the brand's own handle — the
 * outbound broadcast side of social, in the same stack as listening + campaigns.
 *   publish a post -> it lands on the handle (platform is the record)
 *   -> the console Social pane composes + publishes + shows the feed.
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const CONSOLE = 'http://localhost:8080/console/';
const SOCIAL = 'http://localhost:8122';
const HANDLE = 'genalpha-brand';
const run = Date.now();
const PUBLISH = `${API}/insight/v1/social/publish`;
const POSTS = `${API}/insight/v1/social/posts`;

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const staff = await token(ctx, 'bss', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  /* ---------- publish an organic post via the martech module ---------- */
  const msg = `New fibre plans just dropped ${run}`;
  const res = await (await ctx.post(PUBLISH, { headers: H(staff), data: { content: msg } })).json();
  if (!res.published || !res.id) fail('publish failed: ' + JSON.stringify(res));
  console.log(`OK published an organic post to the brand handle (permalink ${res.permalink})`);

  /* ---------- it is on the handle (the platform is the record) ---------- */
  const platform = await (await ctx.get(`${SOCIAL}/v1/${HANDLE}/posts`, { headers: { Authorization: 'Bearer x' } })).json();
  if (!(platform.data || []).some((p) => p.message === msg)) fail('the post is not on the platform handle');
  const feed = await (await ctx.get(POSTS, { headers: H(staff) })).json();
  if (!feed.some((p) => p.message === msg)) fail('the post is not in the module feed');
  console.log('OK the post is live on the handle and readable back through the module');

  /* ================= the console compose + publish ================= */
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
  await page.waitForSelector('[data-testid="publish-card"]', { timeout: 10000 });
  const consoleMsg = `Console post ${run}`;
  await page.fill('[data-testid="publish-text"]', consoleMsg);
  await page.locator('[data-testid="publish-btn"]').click();
  await page.waitForFunction(() => /published/.test(document.querySelector('[data-testid="publish-note"]')?.textContent || ''), { timeout: 10000 });
  const onPlatform = await (await ctx.get(`${SOCIAL}/v1/${HANDLE}/posts`, { headers: { Authorization: 'Bearer x' } })).json();
  if (!(onPlatform.data || []).some((p) => p.message === consoleMsg)) fail('the console-published post is not on the handle');
  console.log('OK the console composed + published a post that landed on the handle');

  await browser.close();
  console.log('\nALL SOCIAL-PUBLISH CHECKS PASSED — organic publishing rounds out social: the martech module '
    + 'listens (mentions/sentiment), captures leads, AND broadcasts to the brand handle — inbound + outbound in one place.');
})();
