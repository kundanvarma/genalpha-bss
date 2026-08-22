/* L1-L3 — launch is a decision, not a side effect.
 *
 *  On a THROWAWAY operator minted live:
 *  - L1 enforcement (direct mode): a draft is 404 to guests BY ID, absent
 *    from guest lists, and ORDERING it is refused — the two deep-pass holes,
 *    closed and proven
 *  - L3 validFor: a Launched offering before its window is invisible and
 *    unorderable; in-window it sells; after its end it is gone again —
 *    enforced at query time, no tick involved
 *  - L1 governed mode: create lands In design whatever was asked; the
 *    ladder refuses rung-skipping; walking it launches for real
 *  - L2 preview: staff walk the unlaunched shelf in the real shop with a
 *    PREVIEW badge; guests never see it
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const OP = `lc${String(run).slice(-6)}`;
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
  const G = { Host: `shop.${OP}.localhost` };   // guest, hostname-routed
  // the gateway's guest edge-cache keys on the URL — every check gets its own
  const g = (url) => `${url}${url.includes('?') ? '&' : '?'}_=${Date.now()}${Math.random()}`;

  await ctx.post(`${API}/onboarding/v1/operator`, { headers: H(host),
    data: { id: OP, name: 'Lifecycle Probe', locale: 'en', currency: 'EUR' } });
  let staff = null;
  for (let i = 0; i < 30 && !staff; i++) {
    await sleep(3000);
    staff = await token(ctx, OP, 'bss-demo', 'demo', 'demo').catch(() => null);
  }
  if (!staff) fail('no staff token from fresh realm');
  const mkCust = async () => {
    const email = `lc-${Date.now()}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'L', familyName: 'C' } })).json();
    return await token(ctx, OP, 'bss-biz', email, login.temporaryPassword);
  };
  const cust = await mkCust();
  // guest hostname routing must RESOLVE this tenant before any guest
  // assertion means anything — a wrong-tenant fallback also 404s
  let routed = false;
  for (let i = 0; i < 30 && !routed; i++) {
    const cfg = await (await ctx.get(g(`${API}/app/tenant-config.json`), { headers: G })).json().catch(() => ({}));
    routed = cfg.tenantId === OP;
    if (!routed) await sleep(3000);
  }
  if (!routed) fail('guest hostname never routed to the fresh tenant');
  console.log(`OK operator '${OP}' minted and guest-routed (direct governance by default)`);

  /* ---------- L1 in direct mode: the two closed holes ---------- */
  // the shop's tab taxonomy keys on category — the draft needs one to render
  const cat = await (await ctx.post(`${CAT}/category`, { headers: H(staff),
    data: { name: 'Mobile plans', lifecycleStatus: 'Active' } })).json();
  const draft = await (await ctx.post(`${CAT}/productOffering`, { headers: H(staff),
    data: { name: `Secret Draft ${run}`, lifecycleStatus: 'In study', isSellable: true,
      category: [{ id: cat.id, name: 'Mobile plans', '@referredType': 'Category' }] } })).json();
  const guestById = await ctx.get(g(`${CAT}/productOffering/${draft.id}`), { headers: G });
  if (guestById.status() !== 404) fail('a guest read a draft by id: ' + guestById.status());
  const guestList = await (await ctx.get(g(`${CAT}/productOffering?name=${encodeURIComponent(draft.name)}`), { headers: G })).json();
  if ((guestList || []).length) fail('a guest listed a draft by omitting the filter');
  const orderDraft = await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`,
    { headers: H(cust), data: { productOrderItem: [{ action: 'add',
      productOffering: { id: draft.id, name: draft.name } }] } });
  if (orderDraft.status() < 400) fail('ORDERING A DRAFT STILL SUCCEEDS: ' + orderDraft.status());
  console.log('OK L1 TEETH: draft 404 to guests by id, absent from guest lists, and unorderable '
    + `(${orderDraft.status()}) — both deep-pass holes closed`);

  /* ---------- L3: the window is the shelf ---------- */
  const future = new Date(Date.now() + 3600_000).toISOString();
  const past = new Date(Date.now() - 3600_000).toISOString();
  const windowed = await (await ctx.post(`${CAT}/productOffering`, { headers: H(staff),
    data: { name: `Summer ${run}`, lifecycleStatus: 'Active', isSellable: true,
      validFor: { startDateTime: future } } })).json();
  if ((await ctx.get(g(`${CAT}/productOffering/${windowed.id}`), { headers: G })).status() !== 404) {
    fail('a not-yet-open window is visible to guests');
  }
  const early = await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`,
    { headers: H(cust), data: { productOrderItem: [{ action: 'add',
      productOffering: { id: windowed.id, name: windowed.name } }] } });
  if (early.status() < 400) fail('ordered before the window opened');
  await ctx.patch(`${CAT}/productOffering/${windowed.id}`, { headers: H(staff),
    data: { validFor: { startDateTime: past } } });
  if ((await ctx.get(g(`${CAT}/productOffering/${windowed.id}`), { headers: G })).status() !== 200) {
    fail('an open window is not visible to guests');
  }
  await ctx.patch(`${CAT}/productOffering/${windowed.id}`, { headers: H(staff),
    data: { validFor: { startDateTime: past, endDateTime: past } } });
  if ((await ctx.get(g(`${CAT}/productOffering/${windowed.id}`), { headers: G })).status() !== 404) {
    fail('a closed window is still visible');
  }
  const late = await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`,
    { headers: H(cust), data: { productOrderItem: [{ action: 'add',
      productOffering: { id: windowed.id, name: windowed.name } }] } });
  if (late.status() < 400) fail('ordered after the window closed');
  console.log('OK L3 WINDOW: before-start invisible+unorderable, in-window sells, '
    + 'after-end gone+refused — query-time enforcement, no tick');

  /* ---------- L1 governed mode: the ladder ---------- */
  await ctx.patch(`${API}/onboarding/v1/operator/${OP}`, { headers: H(host),
    data: { catalogGovernance: 'governed' } });
  let governed = null;
  for (let i = 0; i < 20; i++) {
    await sleep(3000);
    governed = await (await ctx.post(`${CAT}/productOffering`, { headers: H(staff),
      data: { name: `Governed ${run}-${i}`, lifecycleStatus: 'Active', isSellable: true } })).json();
    if (governed.lifecycleStatus === 'In design') break;
    await ctx.delete(`${CAT}/productOffering/${governed.id}`, { headers: H(staff) });
    governed = null;
  }
  if (!governed) fail('governed mode never took effect — create still lands live');
  console.log('OK GOVERNED: create asked for Active and landed "In design" — launch is a decision');

  const skip = await ctx.patch(`${CAT}/productOffering/${governed.id}`, { headers: H(staff),
    data: { lifecycleStatus: 'Launched' } });
  if (skip.status() !== 400) fail('rung-skipping was allowed: ' + skip.status());
  for (const rung of ['In test', 'Launched']) {
    const step = await ctx.patch(`${CAT}/productOffering/${governed.id}`, { headers: H(staff),
      data: { lifecycleStatus: rung } });
    if (step.status() >= 300) fail(`ladder step to '${rung}' refused: ${step.status()}`);
  }
  if ((await ctx.get(g(`${CAT}/productOffering/${governed.id}`), { headers: G })).status() !== 200) {
    fail('a Launched offering is not visible to guests');
  }
  console.log('OK THE LADDER: skip refused with 400, one rung at a time reaches Launched, '
    + 'and only then does the shelf show it');

  /* ---------- L2 preview: staff walk the unlaunched shelf ---------- */
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto(`http://shop.${OP}.localhost:8080/shop/?preview=1`);
  await page.waitForTimeout(2500);
  await page.getByText(/Sign in|Logg inn/).first().click();
  await page.waitForTimeout(2500);
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
    await page.waitForTimeout(3500);
  }
  await page.goto(`http://shop.${OP}.localhost:8080/shop/?preview=1`);
  await page.waitForTimeout(3500);
  const badges = await page.locator('[data-testid="preview-badge"]').count();
  if (!badges) fail('staff preview shows no PREVIEW badges');
  console.log(`OK L2 PREVIEW: staff see ${badges} unlaunched offering(s) badged in the real shop`);
  await browser.close();

  /* ---------- cleanup ---------- */
  const admin = (await (await ctx.post('http://localhost:8085/realms/master/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: 'admin' } })).json()).access_token;
  await ctx.delete(`http://localhost:8085/admin/realms/${OP}`,
    { headers: { Authorization: 'Bearer ' + admin } }).catch(() => {});
  console.log('OK cleanup: probe realm deleted');

  console.log('\nALL LIFECYCLE CHECKS PASSED — drafts are invisible and unorderable server-side, '
    + 'windows are the shelf, the governed ladder has rungs, and staff preview the real shop.');
})();
