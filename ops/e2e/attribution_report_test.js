/* Campaign attribution / lift reporting: a marketer does not want one campaign's
 * stats at a time — they want the PORTFOLIO. One readout across every campaign
 * and journey: reach, holdout-vs-treated lift, and — the number that matters —
 * INCREMENTAL revenue (the money the message actually made, holdout-adjusted),
 * never the gross a message cannon would claim. A program with no control group
 * contributes reach and gross but NOT incrementality: you cannot measure lift you
 * never left room to see.
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const CONSOLE = 'http://localhost:8080/console/';
const run = Date.now();
const SEGMENT = `AttrCat${run}`;
const CAMPAIGN = `${API}/tmf-api/campaignManagement/v4/campaign`;
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;

async function token(ctx, client, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  const PLAN_ID = (await (await ctx.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`)).json())
    .find((o) => o.name === 'GenAlpha Mobile 10 GB')?.id;
  if (!PLAN_ID) fail('GenAlpha Mobile 10 GB missing — run seed_catalog_taxonomy');

  /* ---------- a run-unique segment of six consented, stitched customers ---------- */
  const people = [];
  for (let i = 0; i < 6; i++) {
    const email = `attr-${i}-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: `Attr${i}`, familyName: `R${run}` } })).json();
    const vid = `attr-vis-${i}-${run}`;
    await ctx.post(`${API}/insight/v1/consent`, { headers: { 'Content-Type': 'application/json' },
      data: { visitorId: vid, analytics: true, personalization: true } });
    await ctx.post(`${API}/insight/v1/event`, { headers: { 'Content-Type': 'application/json' },
      data: { visitorId: vid, type: 'view', category: SEGMENT } });
    const tok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
    await ctx.post(`${API}/insight/v1/stitch`, { headers: H(tok), data: { visitorId: vid } });
    people.push({ id: login.id, email, tok });
  }
  console.log('OK a run-unique segment of six consented customers');

  /* ---------- two campaigns (one with a holdout, one without) + a journey ---------- */
  const subjA = `Attr A ${run}`;
  const campA = await (await ctx.post(CAMPAIGN, { headers: H(staff), data: {
    name: `Attr measured ${run}`, segmentName: SEGMENT, holdoutPercent: 50, conversionWindowDays: 7,
    message: { subject: subjA, content: 'Measured with a control group.' } } })).json();
  const campB = await (await ctx.post(CAMPAIGN, { headers: H(staff), data: {
    name: `Attr blast ${run}`, segmentName: SEGMENT, holdoutPercent: 0, conversionWindowDays: 7,
    message: { subject: `Attr B ${run}`, content: 'No control group — pure blast.' } } })).json();
  const journey = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Attr journey ${run}`, segmentName: SEGMENT, holdoutPercent: 0,
    steps: [{ type: 'message', subject: `Attr J ${run}`, content: 'Welcome.' }] } })).json();

  await ctx.post(`${CAMPAIGN}/${campA.id}/execute`, { headers: H(staff), data: {} });
  await ctx.post(`${CAMPAIGN}/${campB.id}/execute`, { headers: H(staff), data: {} });
  await ctx.post(`${JOURNEY}/${journey.id}/enroll`, { headers: H(staff), data: {} });

  const statsA = await (await ctx.get(`${CAMPAIGN}/${campA.id}/stats`, { headers: H(staff) })).json();
  if (statsA.reached + statsA.heldOut !== 6) fail('campA ledger wrong: ' + JSON.stringify(statsA));
  if (statsA.reached < 1 || statsA.heldOut < 1) fail('50% holdout produced an empty variant: ' + JSON.stringify(statsA));
  console.log(`OK campA measured (${statsA.reached} treated / ${statsA.heldOut} held out), campB blasted, journey enrolled`);

  /* ---------- a TREATED customer converts (found via their inbox — holdout got silence) ---------- */
  const inboxOf = async (p) => (await (await ctx.get(
    `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=100`, { headers: H(p.tok) })).json())
    .map((m) => m.subject);
  let converter = null;
  for (const p of people) {
    for (let i = 0; i < 8; i++) {
      if ((await inboxOf(p)).includes(subjA)) { converter = p; break; }
      await sleep(1000);
    }
    if (converter) break;
  }
  if (!converter) fail('no treated customer found to convert');
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(converter.tok), data: {
      productOrderItem: [{ action: 'add', productOffering: { id: PLAN_ID, name: 'GenAlpha Mobile 10 GB' } }] } });
  for (let i = 0; i < 30; i++) {
    const s = await (await ctx.get(`${CAMPAIGN}/${campA.id}/stats`, { headers: H(staff) })).json();
    if (s.conversions.treated >= 1) break;
    await sleep(2500);
  }
  console.log('OK a treated customer converted inside the window — campA now has measurable lift');

  /* ---------- THE PORTFOLIO REPORT ---------- */
  const report = await (await ctx.get(`${CAMPAIGN}/attribution`, { headers: H(staff) })).json();
  const byId = Object.fromEntries((report.programs || []).map((p) => [p.id, p]));

  const rowA = byId[campA.id]; const rowB = byId[campB.id]; const rowJ = byId[journey.id];
  if (!rowA || !rowB || !rowJ) fail('the portfolio is missing one of the three programs');
  if (rowA.type !== 'campaign' || rowJ.type !== 'journey') fail('program types are wrong');

  // Each program row must agree with that program's own /stats — the report does not invent numbers.
  if (rowA.reached !== statsA.reached || rowA.heldOut !== statsA.heldOut) {
    fail('campA row disagrees with its own /stats: ' + JSON.stringify(rowA));
  }
  if (!(rowA.liftPoints > 0)) fail('campA should show positive lift in the portfolio: ' + JSON.stringify(rowA));
  if (!rowA.revenue || !(Number(rowA.revenue.incremental) > 0)) {
    fail('campA (with a control group) must report incremental revenue: ' + JSON.stringify(rowA.revenue));
  }
  console.log(`OK campA in the portfolio: ${rowA.liftPoints}pt lift, ${rowA.revenue.incremental} incremental (holdout-adjusted)`);

  // The honesty rule: campB has NO holdout, so it can show gross but NOT incrementality.
  if (rowB.heldOut !== 0) fail('campB should have no holdout');
  if (rowB.revenue && rowB.revenue.incremental !== null) {
    fail('campB has no control group and must NOT claim incremental revenue: ' + JSON.stringify(rowB.revenue));
  }
  console.log('OK campB (no control group) reports reach + gross but NULL incremental — no lift it never left room to measure');

  /* ---------- the portfolio totals are an honest rollup of the rows ---------- */
  const progs = report.programs;
  const sum = (f) => progs.reduce((a, p) => a + f(p), 0);
  const port = report.portfolio;
  if (port.totalReached !== sum((p) => p.reached)) fail('totalReached is not the sum of program reach');
  if (port.totalHeldOut !== sum((p) => p.heldOut)) fail('totalHeldOut is not the sum of program holdouts');
  if (port.conversions.treated !== sum((p) => p.conversions.treated)) fail('portfolio treated conversions do not tally');
  const grossSum = Math.round(sum((p) => (p.revenue ? Number(p.revenue.treated) : 0)) * 100) / 100;
  if (Math.abs(Number(port.revenue.grossAttributed) - grossSum) > 0.01) {
    fail('portfolio gross revenue is not the sum of program revenue: ' + JSON.stringify(port.revenue));
  }
  if (!(Number(port.revenue.incremental) > 0)) fail('portfolio incremental revenue should be positive: ' + JSON.stringify(port.revenue));
  console.log(`OK portfolio rollup checks out: ${port.totalReached} reached across ${port.programs} programs, `
    + `${port.revenue.grossAttributed} gross / ${port.revenue.incremental} incremental`);

  /* ---------- by-channel split ---------- */
  const bc = report.byChannel;
  if (!bc.campaign || !bc.journey) fail('byChannel is missing a channel');
  const campReach = sum((p) => (p.type === 'campaign' ? p.reached : 0));
  if (bc.campaign.reached !== campReach) fail('byChannel campaign reach does not tally');
  console.log(`OK by channel: ${bc.campaign.programs} campaigns reached ${bc.campaign.reached}, `
    + `${bc.journey.programs} journeys reached ${bc.journey.reached}`);

  /* ================= the console Attribution pane ================= */
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1300, height: 1300 } });
  await page.goto(CONSOLE);
  await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'demo'); await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  await page.locator('.tab', { hasText: 'Attribution' }).click();
  await page.waitForSelector('[data-testid="attribution"]', { timeout: 10000 });
  await page.waitForSelector('[data-testid="kpi-incremental"]', { timeout: 10000 });
  await page.waitForSelector('[data-testid="attribution-row"]', { timeout: 10000 });
  const rowCount = await page.locator('[data-testid="attribution-row"]').count();
  if (rowCount < 1) fail('the console attribution table rendered no programs');
  const noHoldout = await page.locator('[data-testid="attribution-row"]', { hasText: 'no holdout' }).count();
  if (noHoldout < 1) fail('the console did not surface the no-holdout honesty label on any program');
  console.log(`OK the console Attribution pane shows portfolio KPIs and ${rowCount} programs, `
    + `with the "no holdout → no lift" honesty label visible`);
  await browser.close();

  console.log('\nALL ATTRIBUTION CHECKS PASSED — one portfolio readout across every campaign and journey: '
    + 'per-program holdout-vs-treated lift, a blended portfolio, incremental revenue that is holdout-adjusted, '
    + 'and a hard honesty rule — no control group, no incrementality claim.');
})();
