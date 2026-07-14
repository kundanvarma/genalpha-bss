/* Personalization phase 1 (component #33, insight): consent first, then
 * first-party signals, then the experience.
 *
 *  - a CONSENTING guest's browsing personalizes the home page ("because you
 *    were looking at Devices") — first-party breadcrumbs only
 *  - a REJECTING guest generates NOTHING: no rows, no personalization —
 *    asserted at the source, not just the pixel
 *  - revoking consent DELETES what was held
 *  - operator EXPERIENCE RULES (policy, domain 'personalization', data not
 *    code) override the coded default: banner copy + a pinned offering
 *  - login STITCHES the browser profile to the customer — consent-gated
 *  - the tenant wall holds
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();

async function token(request, realm, client, user, pass) {
  const res = await request.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const staff = await token(ctx.request, 'bss', 'bss-demo', 'demo', 'demo');
  const profileOf = async (vid) => ctx.request.get(
    `${API}/insight/v1/profile?visitorId=${vid}`, { headers: H(staff) });

  const browse = async (page) => {
    await page.locator('text=Samsung').first().click();
    await page.waitForTimeout(1200);
    await page.goBack();
    await page.locator('.cards').first().waitFor({ timeout: 10000 });
  };
  const visitorOf = (page) => page.evaluate(() => localStorage.getItem('bss.shop.visitor'));

  /* ---------- the consenting guest ---------- */
  const yes = await (await browser.newContext()).newPage();
  await yes.goto(`${API}/shop/`);
  await yes.locator('[data-testid=consent-banner]').waitFor({ timeout: 15000 });
  await yes.click('[data-testid=consent-accept]');
  await browse(yes);
  // cold-start friendly: the first insight round-trips (machine token mint,
  // JIT) can outlive one page load — poll a few reloads before judging
  let bannerUp = false;
  for (let i = 0; i < 5 && !bannerUp; i++) {
    await yes.reload();
    bannerUp = await yes.locator('[data-testid=personal-banner]', { hasText: 'Devices' })
      .waitFor({ timeout: 8000 }).then(() => true).catch(() => false);
  }
  if (!bannerUp) fail('the consenting guest never saw the personalized home');
  const yesVid = await visitorOf(yes);
  const yesProfile = await (await profileOf(yesVid)).json();
  if (!yesProfile.analyticsConsent || yesProfile.eventCount < 1) {
    fail('the consenting guest left no trace where there should be one: ' + JSON.stringify(yesProfile));
  }
  console.log('OK a consenting guest browsed Devices — the home page says so, first-party only');

  /* ---------- the rejecting guest: NOTHING, asserted at the source ---------- */
  const no = await (await browser.newContext()).newPage();
  await no.goto(`${API}/shop/`);
  await no.locator('[data-testid=consent-banner]').waitFor({ timeout: 15000 });
  await no.click('[data-testid=consent-reject]');
  await browse(no);
  // even a rude client that ignores the choice gets dropped server-side
  const noVid = await visitorOf(no);
  await ctx.request.post(`${API}/insight/v1/event`, { headers: { 'Content-Type': 'application/json' },
    data: { visitorId: noVid, type: 'view', category: 'Devices' } });
  await no.reload();
  await no.waitForTimeout(2500);
  if (await no.locator('[data-testid=personal-banner]').count()) {
    fail('a rejecting guest got personalized');
  }
  const noProfile = await (await profileOf(noVid)).json();
  if (noProfile.analyticsConsent || noProfile.eventCount !== 0) {
    fail('a rejecting guest left breadcrumbs: ' + JSON.stringify(noProfile));
  }
  console.log('OK a rejecting guest generates NOTHING — no rows even when the client'
    + ' misbehaves, and the page stays default');

  /* ---------- revoking consent deletes what was held ---------- */
  await ctx.request.post(`${API}/insight/v1/consent`, { headers: { 'Content-Type': 'application/json' },
    data: { visitorId: yesVid, analytics: false, personalization: false } });
  const wiped = await (await profileOf(yesVid)).json();
  if (wiped.eventCount !== 0) fail('revoked consent did not delete the breadcrumbs');
  console.log('OK revoking consent DELETED the held breadcrumbs — the promise on the banner is real');

  /* ---------- the operator's experience rule (data, not code) ---------- */
  const offers = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`, { headers: H(staff) })).json();
  const pinnedOffer = offers.find((o) => o.name === 'GenAlpha Mobile 60 GB 5G')
    || offers.find((o) => !o.isBundle);
  const rule = await (await ctx.request.post(`${API}/tmf-api/policyManagement/v4/policyRule`,
    { headers: H(staff), data: { name: `Device browsers pitch ${run}`, domain: 'personalization',
      effect: 'experience', priority: 10, enabled: true,
      condition: JSON.stringify({ in: ['Devices', { var: 'interests' }] }),
      message: 'Phone fans: pair it with the 60 GB 5G plan.',
      experience: { teaserOfferingId: pinnedOffer.id } } })).json();
  const ruled = await (await browser.newContext()).newPage();
  await ruled.goto(`${API}/shop/`);
  await ruled.locator('[data-testid=consent-banner]').waitFor({ timeout: 15000 });
  await ruled.click('[data-testid=consent-accept]');
  await browse(ruled);
  await ruled.reload();
  await ruled.locator('[data-testid=personal-banner]', { hasText: 'Phone fans' }).waitFor({ timeout: 15000 });
  await ruled.locator('[data-testid=personal-pick]', { hasText: pinnedOffer.name }).waitFor({ timeout: 10000 });
  await ctx.request.delete(`${API}/tmf-api/policyManagement/v4/policyRule/${rule.id}`, { headers: H(staff) });
  console.log('OK an operator EXPERIENCE RULE took over the banner and pinned an offering'
    + ' — authored as data, deleted as data');

  /* ---------- login stitches, consent-gated ---------- */
  const login = await (await ctx.request.post(
    `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`, { headers: H(staff),
      data: { email: `perso-${run}@example.com`, givenName: 'Perso', familyName: `Nal${run}` } })).json();
  const ruledVid = await visitorOf(ruled);
  await ruled.locator('.who >> text=Sign in').click();
  await ruled.waitForSelector('input[name="username"]', { timeout: 20000 });
  await ruled.fill('input[name="username"]', `perso-${run}@example.com`);
  await ruled.fill('input[name="password"]', login.temporaryPassword);
  await ruled.click('input[type="submit"], button[type="submit"]');
  await ruled.waitForSelector('.nav', { timeout: 20000 });
  let stitched = null;
  for (let i = 0; i < 10 && stitched !== login.id; i++) {
    await new Promise((r) => setTimeout(r, 1500));
    stitched = ((await (await profileOf(ruledVid)).json()) || {}).partyId;
  }
  if (stitched !== login.id) fail('the login did not stitch the visitor to the party: ' + stitched);
  console.log('OK signing in STITCHED this browser\'s profile to the customer — the'
    + ' guest\'s interests now belong to a known person, by consent');

  /* ---------- fusion: the known customer's recommendations lean their way ---------- */
  const persoTok = await token(ctx.request, 'bss', 'bss-biz',
    `perso-${run}@example.com`, login.temporaryPassword);
  let deviceFirst = false;
  for (let i = 0; i < 10 && !deviceFirst; i++) {
    const recs = await (await ctx.request.get(
      `${API}/tmf-api/recommendationManagement/v4/recommendation`,
      { headers: H(persoTok) })).json();
    const first = recs[0]?.recommendationItem?.[0]?.offering;
    if (first) {
      const off = await (await ctx.request.get(
        `${API}/tmf-api/productCatalogManagement/v4/productOffering/${first.id}`,
        { headers: H(staff) })).json();
      deviceFirst = (off.category || []).some((c) => c.name === 'Devices');
    }
    if (!deviceFirst) await new Promise((r) => setTimeout(r, 1500));
  }
  if (!deviceFirst) fail('the known customer\'s device interest did not lead the recommendations');
  console.log('OK FUSION: the stitched customer\'s TMF680 recommendations now lead with'
    + ' Devices — BSS ranking plus consented browsing, one answer');

  /* ---------- the analytics seam: nova brings its own GA4 ---------- */
  const NOVA = 'http://shop.nova.localhost:8080';
  const novaVid = `nova-vis-${run}`;
  await ctx.request.post(`${NOVA}/insight/v1/consent`, { headers: { 'Content-Type': 'application/json' },
    data: { visitorId: novaVid, analytics: true, personalization: true } });
  await ctx.request.post(`${NOVA}/insight/v1/event`, { headers: { 'Content-Type': 'application/json' },
    data: { visitorId: novaVid, type: 'view', category: 'Mobilabonnement', offeringId: 'x-demo' } });
  let forwarded = [];
  for (let i = 0; i < 10 && !forwarded.length; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    forwarded = (await (await ctx.request.get(
      'http://localhost:8120/events?measurement_id=G-NOVA-DEMO')).json())
      .filter((e) => e.client_id === novaVid);
  }
  if (!forwarded.length || forwarded[0].name !== 'view_item') {
    fail('nova\'s consented event never reached its own analytics: ' + JSON.stringify(forwarded));
  }
  // genalpha is internal-only: the earlier genalpha visitors must NOT be there
  const all = await (await ctx.request.get(
    'http://localhost:8120/events?measurement_id=G-NOVA-DEMO')).json();
  if (all.some((e) => e.client_id === ruledVid || e.client_id === yesVid)) {
    fail('a genalpha visitor leaked into nova\'s analytics property');
  }
  console.log('OK the ANALYTICS SEAM: nova\'s consented events land in nova\'s own "GA4"'
    + ' (Measurement Protocol, mock in dev) — genalpha stays first-party-only, same binary');

  /* ---------- P3: the platform's audiences come BACK as rule context ---------- */
  await ctx.request.post('http://localhost:8120/audiences', { headers: { 'Content-Type': 'application/json' },
    data: { client_id: novaVid, audiences: ['high-value-browsers'] } });
  const novaExp = await (await ctx.request.get(
    `${NOVA}/insight/v1/experience?visitorId=${novaVid}`)).json();
  if (!(novaExp.segments || []).includes('high-value-browsers')) {
    fail('the analytics audience never came back as a segment: ' + JSON.stringify(novaExp));
  }
  console.log('OK AUDIENCE IMPORT: the segment nova\'s own analytics computed'
    + ' (\'high-value-browsers\') is now rule-addressable context — the seam flows both ways');

  /* ---------- P3: social attribution from the campaign tag ---------- */
  const socialVid = `social-vis-${run}`;
  await ctx.request.post(`${API}/insight/v1/consent`, { headers: { 'Content-Type': 'application/json' },
    data: { visitorId: socialVid, analytics: true, personalization: true } });
  await ctx.request.post(`${API}/insight/v1/event`, { headers: { 'Content-Type': 'application/json' },
    data: { visitorId: socialVid, type: 'view', category: 'Mobile plans', utmSource: 'instagram' } });
  const socialExp = await (await ctx.request.get(
    `${API}/insight/v1/experience?visitorId=${socialVid}`)).json();
  if (socialExp.channel !== 'social') {
    fail('an instagram utm did not classify as social: ' + JSON.stringify(socialExp));
  }
  console.log('OK SOCIAL ATTRIBUTION: utm_source=instagram classifies the visit as'
    + ' channel=social — rules can greet the platform they came from');

  /* ---------- P3: next best offer, with the WHY ---------- */
  const annaTok = await token(ctx.request, 'bss', 'bss-csr', 'agent-anna', 'agent');
  const nbo = await (await ctx.request.post(`${API}/ai/v1/nextBestOffer`,
    { headers: H(annaTok), data: { partyId: login.id } })).json();
  if (!nbo.offer || !nbo.offer.name) fail('NBO returned no offer: ' + JSON.stringify(nbo).slice(0, 200));
  if (!(nbo.reason || '').includes('Devices')) {
    fail('the NBO reason is not grounded in the customer\'s interest: ' + nbo.reason);
  }
  const nboOff = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering/${nbo.offer.id}`,
    { headers: H(staff) })).json();
  if (!(nboOff.category || []).some((c) => c.name === 'Devices')) {
    fail('the NBO offer is not from the interest category: ' + nboOff.name);
  }
  console.log('OK NEXT BEST OFFER: "' + nbo.offer.name + '" — reason: "' + nbo.reason
    + '" — TMF680 candidates, the model only supplied the WHY');

  /* ---------- tenant wall ---------- */
  const novaStaff = await token(ctx.request, 'nova', 'bss-demo', 'demo', 'demo');
  const cross = await ctx.request.get(
    `http://shop.nova.localhost:8080/insight/v1/profile?visitorId=${ruledVid}`,
    { headers: H(novaStaff) });
  if (cross.status() === 200) fail('a genalpha visitor profile leaked into nova');
  console.log('OK the tenant wall: nova cannot see genalpha\'s visitors');

  await browser.close();
  console.log('\nALL PERSONALIZATION CHECKS PASSED — consent first (and honest), first-party'
    + ' breadcrumbs, operator experience rules as data, the login stitch, tenant walls.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
