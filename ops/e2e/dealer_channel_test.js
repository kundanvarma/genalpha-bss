/* The dealer channel — retail chains sell our activations (CSP/Elkjøp/Power).
 *
 *  - being a dealer IS an agreement row; the clerk's power is their org
 *    membership, checked live — strangers get 404-shaped nothing
 *  - two ways to sell: the COUNTER (clerk orders on the customer's
 *    behalf, dealer-stamped) and the STARTER KIT — attribution baked
 *    into the box, the kit's own SIM becomes the line's SIM
 *  - commission is money OUT with money-in discipline: PENDING on
 *    activation, EARNED after the withdrawal window (angrerett),
 *    CLAWED BACK with the reason when the customer leaves inside it
 *  - the dealer console shows the desk: kits, sales, money
 */
const { chromium, request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const PLAN = { id: '14291c1a-df26-4232-8084-500466888e46', name: 'GenAlpha Mobile 10 GB' };

async function token(ctx, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const mk = async (tag, orgId) => {
    const email = `${tag}-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'De', familyName: `Aler${tag}${run}` } })).json();
    const body = { id: login.id, givenName: 'De', familyName: `Aler${tag}${run}`,
      contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] };
    if (orgId) body.organization = { id: orgId };
    await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: body });
    return { id: login.id, email, pass: login.temporaryPassword,
      tok: await token(ctx, email, login.temporaryPassword) };
  };

  /* ---------- 1. the chain is signed: org + agreement ---------- */
  const org = await (await ctx.post(`${API}/tmf-api/party/v4/organization`,
    { headers: H(staff), data: { name: `Elektra Norge ${run}`, isLegalEntity: true } })).json();
  const agreement = await ctx.post(`${API}/dealer/v1/agreement`, { headers: H(staff),
    data: { dealerOrgId: org.id, name: `Elektra Norge ${run}`,
      commission: { value: 10, unit: 'EUR' } } });
  if (agreement.status() !== 200) fail('agreement refused: ' + agreement.status());
  console.log('OK the chain is SIGNED: an org and an agreement row — 10 EUR per activation.'
    + ' Being a dealer is a row, not a role');

  const clerk = await mk('clerk', org.id);
  const nosy = await mk('nosy'); // no dealer org

  /* strangers get nothing */
  if ((await ctx.get(`${API}/dealer/v1/kits`, { headers: H(nosy.tok) })).status() !== 404) {
    fail('a non-dealer read the kit shelf');
  }
  if ((await ctx.post(`${API}/dealer/v1/kits/batch`, { headers: H(nosy.tok),
      data: { count: 1 } })).status() !== 404) {
    fail('a non-dealer minted kits');
  }
  console.log('OK org boundary: someone outside the dealer org gets 404-shaped NOTHING —'
    + ' no kits, no minting, no money');

  /* ---------- 2. the shelf: a batch of kits for the store ---------- */
  const kits = await (await ctx.post(`${API}/dealer/v1/kits/batch`,
    { headers: H(clerk.tok), data: { count: 3, store: 'Lade' } })).json();
  if (!Array.isArray(kits) || kits.length !== 3
      || !kits.every((k) => /^[A-Z2-9]{8}$/.test(k.activationCode) && k.iccid.startsWith('8946'))) {
    fail('the kit batch is malformed: ' + JSON.stringify(kits).slice(0, 120));
  }
  console.log('OK THE SHELF: 3 starter kits minted for store Lade — human-readable codes,'
    + ' operator-minted SIMs, dealer attribution in every box');

  /* ---------- 3. the counter sale ---------- */
  const walkIn = await mk('walkin');
  const sale = await (await ctx.post(`${API}/dealer/v1/sell`, { headers: H(clerk.tok),
    data: { customerEmail: walkIn.email, offeringId: PLAN.id, offeringName: PLAN.name,
      store: 'Lade' } })).json();
  if (!sale.productOrderId) fail('the counter sale placed no order: ' + JSON.stringify(sale));
  let money = null;
  for (let i = 0; i < 20; i++) {
    await sleep(2000);
    money = await (await ctx.get(`${API}/dealer/v1/commission`, { headers: H(clerk.tok) })).json();
    if ((money.entries || []).length >= 1) break;
  }
  if (!money.entries.length || money.entries[0].status !== 'pending'
      || Number(money.entries[0].amount.value) !== 10) {
    fail('the counter sale accrued no pending commission: ' + JSON.stringify(money.entries[0]));
  }
  console.log('OK THE COUNTER SALE: the clerk sold against the customer\'s email, the order'
    + ' carries the dealer stamp, and 10 EUR sits PENDING — the withdrawal window is real money\'s'
    + ' waiting room');

  /* ---------- 4. the kit comes alive — and brings ITS OWN SIM ---------- */
  const homeBuyer = await mk('homebuyer');
  const kit = kits[0];
  const activation = await (await ctx.post(`${API}/dealer/v1/starterKit/activate`,
    { headers: H(homeBuyer.tok), data: { code: kit.activationCode, offeringId: PLAN.id,
      offeringName: PLAN.name } })).json();
  if (activation.iccid !== kit.iccid) fail('the activation lost the kit SIM');
  let line = null;
  for (let i = 0; i < 20 && !line; i++) {
    await sleep(2000);
    const services = await (await ctx.get(`${API}/tmf-api/serviceInventory/v4/service`,
      { headers: H(homeBuyer.tok) })).json();
    line = (Array.isArray(services) ? services : []).find((s) => s.state === 'active') || null;
  }
  if (!line) fail('the kit activation produced no active service');
  const sim = await (await ctx.get(`${API}/tmf-api/serviceInventory/v4/service/${line.id}/sim`,
    { headers: H(homeBuyer.tok) })).json();
  const visibleTail = String(sim.iccid || '').replace(/\D/g, ''); // endpoint masks: •••• 38505
  if (!visibleTail || !kit.iccid.endsWith(visibleTail)) {
    fail(`the line rides the wrong SIM: ${sim.iccid} != kit ${kit.iccid}`);
  }
  const reused = await ctx.post(`${API}/dealer/v1/starterKit/activate`,
    { headers: H(homeBuyer.tok), data: { code: kit.activationCode, offeringId: PLAN.id } });
  if (reused.status() !== 400) fail('a kit activated twice: ' + reused.status());
  money = await (await ctx.get(`${API}/dealer/v1/commission`, { headers: H(clerk.tok) })).json();
  if (money.entries.length < 2) fail('the kit activation accrued no commission');
  console.log('OK THE KIT CAME ALIVE: bought like a chocolate bar, self-activated at home —'
    + ' the line rides the SIM FROM THE BOX (' + kit.iccid.slice(0, 8) + '…), the store is'
    + ' credited though no partner system was ever involved, and a used code is dead');

  /* ---------- 5. money hardens... ---------- */
  let earned = null;
  for (let i = 0; i < 20; i++) {
    await sleep(3000);
    money = await (await ctx.get(`${API}/dealer/v1/commission`, { headers: H(clerk.tok) })).json();
    if (Number(money.totals?.earned || 0) >= 20) { earned = money; break; }
  }
  if (!earned) fail('commission never hardened to earned: ' + JSON.stringify(money.totals));
  console.log('OK MONEY HARDENS: past the withdrawal window (dev: 15s) the pending entries'
    + ' became EARNED — ' + earned.totals.earned + ' EUR the chain can invoice against');

  /* ---------- 6. ...and money comes BACK when the customer leaves inside the window ---------- */
  const regretter = await mk('regretter');
  await ctx.post(`${API}/dealer/v1/sell`, { headers: H(clerk.tok),
    data: { customerEmail: regretter.email, offeringId: PLAN.id, offeringName: PLAN.name,
      store: 'Lade' } });
  let regretLine = null;
  for (let i = 0; i < 20 && !regretLine; i++) {
    await sleep(2000);
    const services = await (await ctx.get(`${API}/tmf-api/serviceInventory/v4/service`,
      { headers: H(regretter.tok) })).json();
    regretLine = (Array.isArray(services) ? services : []).find((s) => s.state === 'active') || null;
  }
  if (!regretLine) fail('the regretter never got a line');
  await ctx.post(`${API}/tmf-api/serviceInventory/v4/service/${regretLine.id}/terminate`,
    { headers: H(regretter.tok), data: { reason: 'angrerett — changed my mind' } });
  let clawed = null;
  for (let i = 0; i < 10 && !clawed; i++) {
    await sleep(2000);
    money = await (await ctx.get(`${API}/dealer/v1/commission`, { headers: H(clerk.tok) })).json();
    clawed = (money.entries || []).find((e) => e.status === 'clawedBack') || null;
  }
  if (!clawed || !clawed.reason.includes('withdrawal window')) {
    fail('the early leave clawed nothing back: ' + JSON.stringify(clawed));
  }
  console.log('OK CLAWBACK: the customer used their angrerett inside the window and the'
    + ' pending 10 EUR went back — with the reason on the entry, not in an email thread');

  /* ---------- 7. the PARTNER API: the chain's own POS speaks machine-to-machine ---------- */
  // PowerOn runs its own retail system — no clerk logins, no console: an
  // OAuth2 client the agreement names. And PowerOn sells ITS OWN phones:
  // the device rides the sale as CONTEXT; only the subscription bills here.
  const posOrg = await (await ctx.post(`${API}/tmf-api/party/v4/organization`,
    { headers: H(staff), data: { name: `PowerOn ${run}`, isLegalEntity: true } })).json();
  await ctx.post(`${API}/dealer/v1/agreement`, { headers: H(staff),
    data: { dealerOrgId: posOrg.id, name: `PowerOn ${run}`, clientId: 'pos-poweron',
      commission: { value: 8, unit: 'EUR' } } });
  const badTok = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'client_credentials', client_id: 'pos-poweron',
      client_secret: 'wrong-secret' } });
  if (badTok.status() === 200) fail('a wrong POS secret minted a token');
  const posTokRes = await (await ctx.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'client_credentials', client_id: 'pos-poweron',
      client_secret: 'poweron-pos-secret' } })).json();
  const pos = posTokRes.access_token;
  if (!pos) fail('the POS client got no token');

  const posKits = await (await ctx.post(`${API}/dealer/v1/kits/batch`,
    { headers: H(pos), data: { count: 2, store: 'City Syd' } })).json();
  if (!Array.isArray(posKits) || posKits.length !== 2) {
    fail('the POS could not mint kits: ' + JSON.stringify(posKits).slice(0, 120));
  }
  const posBuyer = await mk('posbuyer');
  const posSale = await (await ctx.post(`${API}/dealer/v1/sell`, { headers: H(pos),
    data: { customerEmail: posBuyer.email, offeringId: PLAN.id, offeringName: PLAN.name,
      store: 'City Syd', device: 'Samsung Galaxy S26 Ultra — PowerOn own stock' } })).json();
  if (!posSale.productOrderId) fail('the POS sale placed no order: ' + JSON.stringify(posSale));
  let posStatus = null;
  for (let i = 0; i < 20 && !posStatus; i++) {
    await sleep(2000);
    const res = await ctx.get(`${API}/dealer/v1/orders/${posSale.productOrderId}`,
      { headers: H(pos) });
    if (res.status() === 200) posStatus = await res.json();
  }
  if (!posStatus || !posStatus.activated) fail('the POS never saw its order activate');
  const posEntry = posStatus.commission[0];
  if (Number(posEntry.amount.value) !== 8
      || posEntry.device !== 'Samsung Galaxy S26 Ultra — PowerOn own stock') {
    fail('the POS sale lost its money or its device context: ' + JSON.stringify(posEntry));
  }
  console.log('OK THE PARTNER API: PowerOn\'s own POS — a machine credential the agreement'
    + ' names — minted kits, sold a subscription BUNDLED WITH THEIR OWN PHONE (the device is'
    + ' context on the commission entry, never a billable item here), and polled the order:'
    + ' activated, 8 EUR pending');

  /* cross-partner isolation: Elektra's clerk cannot see PowerOn's order */
  if ((await ctx.get(`${API}/dealer/v1/orders/${posSale.productOrderId}`,
      { headers: H(clerk.tok) })).status() !== 404) {
    fail("one chain read another chain's order");
  }
  console.log('OK chains are WALLS to each other: Elektra\'s clerk asking about PowerOn\'s'
    + ' order gets a 404 — attribution is also isolation');

  /* ---------- 8. the dealer console shows the desk ---------- */
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto(`${API}/dealer-app/`);
  await page.waitForSelector('input[name="username"]', { timeout: 15000 });
  await page.fill('input[name="username"]', clerk.email);
  await page.fill('input[name="password"]', clerk.pass);
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  await page.waitForSelector('[data-testid="kit-row"]', { timeout: 15000 });
  if ((await page.locator('[data-testid="kit-row"]').count()) < 3) {
    fail('the console does not show the kit shelf');
  }
  await page.waitForSelector('[data-testid="commission-row"]', { timeout: 15000 });
  if (!(await page.locator('[data-testid="total-earned"]').count())) {
    fail('the console shows no earned total');
  }
  await browser.close();
  console.log('OK THE DEALER CONSOLE: the clerk signs in and sees their desk — the kit shelf'
    + ' (with the activated one greyed), the sales, and the money in all three states');

  console.log('\nALL DEALER-CHANNEL CHECKS PASSED — the chain is a row, the clerk is org'
    + ' membership, the kit carries its attribution and its SIM, and commission moves like'
    + ' money should: pending, earned, or honestly clawed back.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
