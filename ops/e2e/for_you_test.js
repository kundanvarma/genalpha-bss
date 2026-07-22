/* Personalization v2 — THE INDIVIDUALIZED SHOP. Suite #60.
 *
 *  - a signed-in customer's "For you" rail is genuinely THEIRS: consented
 *    browsing stitches into interests, the TMF680 ranking supplies the
 *    candidates, and the governed LLM writes ONE caption grounded in the
 *    customer's own trail ("...after your look at devices")
 *  - a customer who never consented gets a rail with NO browsing-derived
 *    content: zero interests, a generic caption — personalization stays
 *    a reward for consent, never a default
 *  - the caption is a GOVERNED call: it lands metered on the AI ledger
 *    (yesterday's control plane pays for itself on day one)
 *  - the rail caches per party: a browsing session costs ONE model call,
 *    not one per page view
 *  - the storefront renders it: caption and rail visible in the shop
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();

async function token(request, user, pass) {
  const res = await request.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const staff = await token(ctx.request, 'demo', 'demo');

  /* ---------- two customers: one with a consented trail, one without ---------- */
  const mint = async (tag) => {
    const email = `${tag}-${run}@example.com`;
    const login = await (await ctx.request.post(
      `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`, { headers: H(staff),
        data: { email, givenName: 'Fory', familyName: `${tag}${run}` } })).json();
    await ctx.request.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
      id: login.id, givenName: 'Fory', familyName: `${tag}${run}`,
      contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
    return { id: login.id, email, password: login.temporaryPassword };
  };
  const alice = await mint('alice');
  const bob = await mint('bob');

  // Alice browses devices AS A CONSENTING GUEST, then signs in — the
  // stitch moment makes her browsing HER data
  const page = await (await browser.newContext()).newPage();
  await page.goto(`${API}/shop/`);
  await page.locator('[data-testid=consent-banner]').waitFor({ timeout: 15000 });
  await page.click('[data-testid=consent-accept]');
  await page.locator('text=Samsung').first().click();
  await page.waitForTimeout(1500);
  await page.goBack();
  await page.locator('.cards').first().waitFor({ timeout: 10000 });
  await page.locator('.who >> text=Sign in').click();
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', alice.email);
  await page.fill('input[name="password"]', alice.password);
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.nav', { timeout: 20000 });
  await sleep(3000); // the stitch settles

  /* ---------- 1. Alice's rail is HERS ---------- */
  const aliceTok = await token(ctx.request, alice.email, alice.password);
  let mine = null;
  for (let i = 0; i < 8 && !(mine && mine.interests.length); i++) {
    await sleep(2000);
    const res = await ctx.request.get(`${API}/ai/v1/forYou`, { headers: H(aliceTok) });
    if (res.status() !== 200) fail('forYou answered ' + res.status());
    mine = await res.json();
  }
  if (!mine.items.length) fail('Alice has no rail at all');
  if (!mine.interests.some((i) => /device/i.test(i))) {
    fail('Alice\'s consented browsing did not become interests: ' + JSON.stringify(mine.interests));
  }
  if (!/device/i.test(String(mine.caption))) {
    fail('the caption is not grounded in HER trail: ' + mine.caption);
  }
  console.log(`OK HER RAIL: ${mine.items.length} picks, interests ${JSON.stringify(mine.interests)},`
    + ` caption "${mine.caption}" — grounded in what SHE looked at, by consent`);

  /* ---------- 2. Bob's rail has no borrowed trail ---------- */
  const bobTok = await token(ctx.request, bob.email, bob.password);
  const bobRes = await ctx.request.get(`${API}/ai/v1/forYou`, { headers: H(bobTok) });
  if (bobRes.status() !== 200) fail('Bob forYou answered ' + bobRes.status());
  const bobs = await bobRes.json();
  if (bobs.interests.length !== 0) {
    fail('Bob never consented to anything yet has interests: ' + JSON.stringify(bobs.interests));
  }
  if (/device/i.test(String(bobs.caption))) {
    fail('Bob\'s caption mentions browsing he never consented to share: ' + bobs.caption);
  }
  console.log('OK NOT HIS TRAIL: Bob (no consent, no browsing) gets a rail with zero interests'
    + ' and a caption that borrows nothing — personalization is a reward for consent,'
    + ' never a default');

  /* ---------- 3. the caption is governed ---------- */
  const audit = await (await ctx.request.get(`${API}/ai/v1/audit`, { headers: H(staff) })).json();
  const captionRow = audit.find((a) => a.useCase === 'for-you-caption'
    && (Date.now() - Date.parse(a.createdAt)) < 300000);
  if (!captionRow || !(captionRow.tokens > 0) || !(captionRow.costMicros > 0)) {
    fail('the caption call is not on the governed ledger: ' + JSON.stringify(captionRow || {}));
  }
  console.log(`OK GOVERNED: the caption cost ${captionRow.tokens} tokens /`
    + ` ${captionRow.costMicros} micros on the control-plane ledger — personalization spends`
    + ' inside the same budget as every other AI feature');

  /* ---------- 4. the rail caches per party ---------- */
  const again = await (await ctx.request.get(`${API}/ai/v1/forYou`,
    { headers: H(aliceTok) })).json();
  if (again.cached !== true) fail('the second call within the window was not cached');
  console.log('OK CACHED: the second look inside the window is served from cache — one model'
    + ' call per browsing session, not one per page view');

  /* ---------- 5. the storefront renders it ---------- */
  await page.goto(`${API}/shop/`);
  const caption = page.locator('[data-testid=foryou-caption]');
  await caption.waitFor({ timeout: 15000 }).catch(() => fail('the shop never showed the caption'));
  const text = await caption.textContent();
  if (!/device/i.test(text)) fail('the shop caption is not Alice\'s: ' + text);
  await page.locator('[data-testid=recommended] .card, [data-testid=recommended] a')
    .first().waitFor({ timeout: 10000 })
    .catch(() => fail('the rail rendered no cards'));
  console.log('OK ON THE SHELF: the shop shows HER caption over HER rail — the individualized'
    + ' shop is a page, not an API');

  /* ---------- 6. the rail follows her onto the APP ---------- */
  const app = await (await browser.newContext()).newPage();
  await app.goto(`${API}/app/`);
  await app.locator('[data-testid="signin"]').click();
  await app.waitForSelector('input[name="username"]', { timeout: 20000 });
  await app.fill('input[name="username"]', alice.email);
  await app.fill('input[name="password"]', alice.password);
  await app.click('input[type="submit"], button[type="submit"]');
  await app.locator('[data-testid="recs-card"]').waitFor({ timeout: 30000 });
  const appCaption = app.locator('[data-testid="app-foryou-caption"]');
  await appCaption.waitFor({ timeout: 15000 })
    .catch(() => fail('the app never showed her caption'));
  const appText = await appCaption.textContent();
  if (!/device/i.test(appText)) fail('the app caption is not Alice\'s: ' + appText);
  console.log('OK IN HER POCKET: the same caption over the same rail on the mobile app —'
    + ' one governed rail, every channel (and the 5-minute cache means the app visit cost'
    + ' zero extra model calls)');

  console.log('\nALL FOR-YOU CHECKS PASSED — the signed-in shop is individualized: consented'
    + ' browsing becomes interests, interests become a rail with a grounded caption, the'
    + ' caption is metered and budgeted, none of it exists for the customer who said no —'
    + ' and the rail follows the customer onto the app.');
  await browser.close();
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
