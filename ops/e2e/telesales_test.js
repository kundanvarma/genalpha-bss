/* The telesales channel — outbound selling shaped by the law, not around it.
 *
 *  - the DNC wash runs FAIL-CLOSED before any offer exists: a reserved
 *    number refuses, and an UNREACHABLE register refuses too — "we
 *    couldn't check" is not consent
 *  - the call's output is an OFFER, never an order: under angrerettloven
 *    a phone agreement binds only when the customer confirms IN WRITING.
 *    The confirmation letter lands in the inbox; the signed-in customer's
 *    token IS the writing; only then is the order (and the call center's
 *    commission) born
 *  - unconfirmed offers expire; expired tokens refuse; a re-click never
 *    orders twice; a stranger's confirm sees nothing
 *  - the dialer's calls land on the TMF683 record like every channel
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
  const mk = async (tag) => {
    const email = `${tag}-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'Te', familyName: `Le${tag}${run}` } })).json();
    await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
      id: login.id, givenName: 'Te', familyName: `Le${tag}${run}`,
      contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
    return { id: login.id, email, tok: await token(ctx, email, login.temporaryPassword) };
  };

  /* ---------- 0. the call center is a dealer with a phone ---------- */
  const org = await (await ctx.post(`${API}/tmf-api/party/v4/organization`,
    { headers: H(staff), data: { name: `CallCo Nord ${run}`, isLegalEntity: true } })).json();
  await ctx.post(`${API}/dealer/v1/agreement`, { headers: H(staff),
    data: { dealerOrgId: org.id, name: `CallCo Nord ${run}`,
      commission: { value: 12, unit: 'EUR' } } });
  const agentEmail = `agent-${run}@example.com`;
  const agentLogin = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email: agentEmail, givenName: 'Ag', familyName: `Ent${run}` } })).json();
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
    id: agentLogin.id, givenName: 'Ag', familyName: `Ent${run}`,
    organization: { id: org.id },
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: agentEmail } }] } });
  const agent = await token(ctx, agentEmail, agentLogin.temporaryPassword);
  console.log('OK the call center is SIGNED like any dealer: an org, an agreement, 12 EUR per'
    + ' confirmed sale — the agent is org membership, same as a clerk');

  /* ---------- 1. the wash refuses a reserved citizen ---------- */
  const target = await mk('warmbase');
  const reserved = await ctx.post(`${API}/dealer/v1/telesales/offer`, { headers: H(agent),
    data: { customerEmail: target.email, phone: '+47 999 99 999', offeringId: PLAN.id,
      offeringName: PLAN.name, campaign: 'Winter upsell' } });
  if (reserved.status() !== 400) fail('a RESERVED number was sold to: ' + reserved.status());
  const reservedBody = await reserved.json();
  if (!JSON.stringify(reservedBody).includes('reservation register')) {
    fail('the refusal does not name the register: ' + JSON.stringify(reservedBody));
  }
  console.log('OK THE WASH: a number on the reservation register may not be sold to — refused'
    + ' with the reason, before any offer existed');

  /* ---------- 2. the wash is FAIL-CLOSED ---------- */
  await ctx.post('http://localhost:8125/outage', { data: { count: 1 } });
  const unwashed = await ctx.post(`${API}/dealer/v1/telesales/offer`, { headers: H(agent),
    data: { customerEmail: target.email, phone: '+47 400 00 001', offeringId: PLAN.id,
      offeringName: PLAN.name } });
  if (unwashed.status() !== 400) fail('an UNWASHED sale went through: ' + unwashed.status());
  console.log('OK FAIL-CLOSED: the register was unreachable and the sale refused — "we could'
    + ' not check" is not consent');

  /* ---------- 3. the offer is not an order ---------- */
  const offered = await (await ctx.post(`${API}/dealer/v1/telesales/offer`, { headers: H(agent),
    data: { customerEmail: target.email, phone: '+47 400 00 001', offeringId: PLAN.id,
      offeringName: PLAN.name, campaign: 'Winter upsell' } })).json();
  if (offered.status && offered.status !== 'offered') fail('no offer: ' + JSON.stringify(offered));
  await sleep(4000);
  const ordersBefore = await (await ctx.get(
    `${API}/tmf-api/productOrderingManagement/v4/productOrder?limit=20`,
    { headers: H(target.tok) })).json();
  if (ordersBefore.length) fail('an order exists BEFORE the written confirmation — unlawful');
  console.log('OK NOT BINDING YET: the call produced an offer and NOTHING else — no order, no'
    + ' service, no commission. A phone agreement is not an agreement');

  /* ---------- 4. the written yes, from the customer's own inbox ---------- */
  let letter = null;
  for (let i = 0; i < 15 && !letter; i++) {
    await sleep(2000);
    const inbox = await (await ctx.get(
      `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=50`,
      { headers: H(target.tok) })).json();
    letter = inbox.find((m) => (m.subject || '').includes('Confirm your new plan')) || null;
  }
  if (!letter) fail('the confirmation letter never reached the inbox');
  const code = (letter.content.match(/confirm with code ([A-Z2-9]{10})/) || [])[1];
  if (!code) fail('the letter carries no code: ' + letter.content.slice(0, 120));
  if (!letter.content.includes('Nothing is ordered yet')) {
    fail('the letter does not say the honest thing');
  }
  /* a stranger with the code still sees nothing — the yes must come from
   * the customer it belongs to, signed in */
  const stranger = await mk('stranger');
  const strangerYes = await ctx.post(`${API}/telesales/v1/confirm`,
    { headers: H(stranger.tok), data: { token: code } });
  if (strangerYes.status() !== 404) fail("a stranger confirmed someone else's offer");
  const confirmed = await (await ctx.post(`${API}/telesales/v1/confirm`,
    { headers: H(target.tok), data: { token: code } })).json();
  if (confirmed.status !== 'confirmed' || !confirmed.productOrderId) {
    fail('the confirmation did not order: ' + JSON.stringify(confirmed));
  }
  const again = await (await ctx.post(`${API}/telesales/v1/confirm`,
    { headers: H(target.tok), data: { token: code } })).json();
  if (again.productOrderId !== confirmed.productOrderId) fail('a re-click ordered twice');
  let line = null;
  for (let i = 0; i < 25 && !line; i++) {
    await sleep(2000);
    const services = await (await ctx.get(`${API}/tmf-api/serviceInventory/v4/service`,
      { headers: H(target.tok) })).json();
    line = (Array.isArray(services) ? services : []).find((s) => s.state === 'active') || null;
  }
  if (!line) fail('the confirmed order never activated');
  console.log('OK THE WRITTEN YES: the letter said "nothing is ordered yet", the customer'
    + ' confirmed with the code FROM THEIR OWN INBOX, signed in — and only then the order was'
    + ' born and activated. A stranger with the same code saw a 404; a re-click ordered nothing'
    + ' twice');

  /* ---------- 5. commission starts where the agreement starts ---------- */
  let money = null;
  for (let i = 0; i < 15; i++) {
    await sleep(2000);
    money = await (await ctx.get(`${API}/dealer/v1/commission`, { headers: H(agent) })).json();
    if ((money.entries || []).length) break;
  }
  if (!money.entries.length || money.entries[0].status !== 'pending'
      || Number(money.entries[0].amount.value) !== 12
      || money.entries[0].store !== 'Winter upsell') {
    fail('commission is wrong: ' + JSON.stringify(money.entries[0]));
  }
  console.log('OK COMMISSION AT CONFIRMATION: 12 EUR pending to CallCo Nord, attributed to the'
    + ' "Winter upsell" campaign — born with the agreement, not with the phone call');

  /* ---------- 6. unconfirmed expires ---------- */
  const drifter = await mk('drifter');
  await ctx.post(`${API}/dealer/v1/telesales/offer`, { headers: H(agent),
    data: { customerEmail: drifter.email, phone: '+47 400 00 002', offeringId: PLAN.id,
      offeringName: PLAN.name } });
  let code2 = null;
  for (let i = 0; i < 15 && !code2; i++) {
    await sleep(2000);
    const inbox = await (await ctx.get(
      `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=50`,
      { headers: H(drifter.tok) })).json();
    const msg = inbox.find((m) => (m.subject || '').includes('Confirm your new plan'));
    code2 = msg ? (msg.content.match(/confirm with code ([A-Z2-9]{10})/) || [])[1] : null;
  }
  if (!code2) fail('the second letter never arrived');
  await sleep(22000); // past the dev expiry (15s) and its tick
  const tooLate = await ctx.post(`${API}/telesales/v1/confirm`,
    { headers: H(drifter.tok), data: { token: code2 } });
  if (tooLate.status() !== 400) fail('an EXPIRED offer confirmed: ' + tooLate.status());
  const pipeline = await (await ctx.get(`${API}/dealer/v1/telesales/offers`,
    { headers: H(agent) })).json();
  if (!pipeline.find((o) => o.status === 'expired')) {
    fail('the pipeline shows no expired offer');
  }
  console.log('OK UNCONFIRMED IS UNBINDING: the drifter never answered, the offer expired on'
    + ' the clock, the late click refused — and the pipeline shows the truth');

  /* ---------- 7. the dialer's calls land on the record ---------- */
  // the dialer integration writes with its own granted credential
  // (interaction:write) — the staff token stands in for it here
  const call = await ctx.post(`${API}/tmf-api/partyInteraction/v4/partyInteraction`,
    { headers: H(staff), data: {
      description: `Outbound call: pitched ${PLAN.name} (Winter upsell) — customer will think about it`,
      channel: 'phone', direction: 'outbound', sourceSystem: 'callco-dialer',
      relatedParty: [{ id: target.id, role: 'customer', '@referredType': 'Individual' }] } });
  if (call.status() >= 400) fail('the dialer could not log its call: ' + call.status());
  const timeline = await (await ctx.get(
    `${API}/tmf-api/partyInteraction/v4/partyInteraction?relatedPartyId=${target.id}&limit=50`,
    { headers: H(staff) })).json();
  if (!timeline.find((i) => (i.sourceSystem || '') === 'callco-dialer')) {
    fail("the dialer's call is not on the timeline");
  }
  console.log('OK ON THE RECORD: the dialer logged its call through the TMF683 open door and'
    + ' the CSR 360 reads it — the outbound channel shares the one memory like every other');

  /* ---------- 8. THE DIAL LIST: consent at the source, washed numbers only ---------- */
  // three consented browsers of a run-unique category = the segment; one
  // carries a RESERVED number and must be excluded, never listed
  const SEG = `winter-cat-${run}`;
  const dialFolk = [];
  const folkSpecs = [['lista', '+47 400 11 001'], ['listb', '+47 400 11 002'],
    ['listc', '+47 999 99 999']];
  for (const [tag, phone] of folkSpecs) {
    const email = `${tag}-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'Di', familyName: `Al${tag}${run}` } })).json();
    await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
      id: login.id, givenName: 'Di', familyName: `Al${tag}${run}`,
      contactMedium: [
        { mediumType: 'email', characteristic: { emailAddress: email } },
        { mediumType: 'phone', characteristic: { phoneNumber: phone } }] } });
    const vid = `vid-${tag}-${run}`;
    await ctx.post(`${API}/insight/v1/consent`, { headers: { 'Content-Type': 'application/json' },
      data: { visitorId: vid, analytics: true, personalization: true } });
    await ctx.post(`${API}/insight/v1/event`, { headers: { 'Content-Type': 'application/json' },
      data: { visitorId: vid, type: 'view', category: SEG } });
    const tok = await token(ctx, email, login.temporaryPassword);
    await ctx.post(`${API}/insight/v1/stitch`, { headers: H(tok), data: { visitorId: vid } });
    dialFolk.push({ id: login.id, phone });
  }
  const list = await (await ctx.get(
    `${API}/dealer/v1/telesales/dialList?segment=${encodeURIComponent(SEG)}`,
    { headers: H(agent) })).json();
  if (list.entries.length !== 2 || list.reservedExcluded !== 1) {
    fail('the dial list is wrong: ' + JSON.stringify({ entries: list.entries.length,
      reserved: list.reservedExcluded }));
  }
  if (list.entries.some((e) => e.phone.replace(/\D/g, '').endsWith('9999'))) {
    fail('a RESERVED number is ON the list');
  }
  if (!list.entries.every((e) => e.phone && e.name && e.consent.includes('washed'))) {
    fail('list entries are missing their facts: ' + JSON.stringify(list.entries[0]));
  }
  console.log('OK THE DIAL LIST: the dialer pulled the same segment campaigns use — consent'
    + ' filtered at the source, every number washed, and the reserved citizen EXCLUDED and'
    + ' counted (' + list.reservedExcluded + '), never listed');

  /* ---------- 9. THE COLD PROSPECT: identity arrives with the confirmation ---------- */
  const coldEmail = `coldcall-${run}@example.com`;
  const coldOffer = await (await ctx.post(`${API}/dealer/v1/telesales/offer`, { headers: H(agent),
    data: { customerEmail: coldEmail, prospectName: 'Kald Kunde', phone: '+47 400 22 001',
      offeringId: PLAN.id, offeringName: PLAN.name, campaign: 'Cold winter' } })).json();
  if (!coldOffer.prospect || !coldOffer.confirmToken) {
    fail('the cold offer returned no code for the partner SMS: ' + JSON.stringify(coldOffer));
  }
  // the prospect REGISTERS with the offered email — that is the identity
  // proof — then signs in and confirms with the code from the SMS
  const coldLogin = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email: coldEmail, givenName: 'Kald', familyName: `Kunde${run}` } })).json();
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
    id: coldLogin.id, givenName: 'Kald', familyName: `Kunde${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: coldEmail } }] } });
  const cold = await token(ctx, coldEmail, coldLogin.temporaryPassword);
  // a different registered person with the stolen code still sees nothing
  const thief = await ctx.post(`${API}/telesales/v1/confirm`,
    { headers: H(target.tok), data: { token: coldOffer.confirmToken } });
  if (thief.status() !== 404) fail("someone else confirmed the cold prospect's offer");
  const coldYes = await (await ctx.post(`${API}/telesales/v1/confirm`,
    { headers: H(cold), data: { token: coldOffer.confirmToken } })).json();
  if (coldYes.status !== 'confirmed' || !coldYes.productOrderId) {
    fail('the cold confirmation did not order: ' + JSON.stringify(coldYes));
  }
  let coldLine = null;
  for (let i = 0; i < 25 && !coldLine; i++) {
    await sleep(2000);
    const services = await (await ctx.get(`${API}/tmf-api/serviceInventory/v4/service`,
      { headers: H(cold) })).json();
    coldLine = (Array.isArray(services) ? services : []).find((s) => s.state === 'active') || null;
  }
  if (!coldLine) fail('the cold prospect never activated');
  console.log('OK THE COLD PROSPECT: not a customer when the phone rang — the offer held their'
    + ' contact, the partner\'s own SMS carried the code, and REGISTERING with the offered email'
    + ' was the identity proof: signed in, confirmed, ordered, activated. A registered stranger'
    + ' with the stolen code saw a 404');

  /* ---------- 10. the telesales desk in the dealer console ---------- */
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto(`${API}/dealer-app/`);
  await page.waitForSelector('input[name="username"]', { timeout: 15000 });
  await page.fill('input[name="username"]', agentEmail);
  await page.fill('input[name="password"]', agentLogin.temporaryPassword);
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
  // the pipeline shows this run's offers with their honest states
  await page.waitForSelector('[data-testid="ts-offer-row"]', { timeout: 15000 });
  const states = await page.locator('[data-testid="ts-offer-row"] .state').allTextContents();
  if (!states.includes('confirmed') || !states.includes('expired')) {
    fail('the pipeline does not show the run\'s states: ' + states.join(','));
  }
  // the dial list pulls from the UI: two callable, the reserved one absent
  await page.fill('[data-testid="dial-segment"]', SEG);
  await page.click('[data-testid="dial-go"]');
  await page.waitForSelector('[data-testid="dial-row"]', { timeout: 15000 });
  if ((await page.locator('[data-testid="dial-row"]').count()) !== 2) {
    fail('the console dial list is not the washed two');
  }
  const summary = await page.locator('#dial-summary').textContent();
  if (!summary.includes('1 reserved excluded')) {
    fail('the summary hides the exclusion: ' + summary);
  }
  await browser.close();
  console.log('OK THE DESK: the agent\'s console shows the pipeline (confirmed AND expired,'
    + ' honestly), and pulls the washed dial list — two callable, "1 reserved excluded" said'
    + ' out loud');

  console.log('\nALL TELESALES CHECKS PASSED — the wash refuses the reserved and the unwashed,'
    + ' the call makes an offer and the CUSTOMER makes it an order, commission is born with the'
    + ' agreement, silence expires, and every call is on the record. Outbound, shaped by the'
    + ' law instead of around it.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
