/* Guardrails — respecting the customer is a feature, in the model.
 *
 * ESP delivery receipts (nova):
 *  - the provider answers back: a delivered mail stamps the message, a
 *    BOUNCE puts the address on the tenant's suppression list
 *  - a suppressed address gets NO second email — but the in-app inbox
 *    still gets every message (the record is never suppressed)
 *
 * Frequency caps (genalpha):
 *  - the tenant sets a marketing-touch budget (max N per day); the third
 *    campaign to the same customer reaches NOBODY — the budget is spent
 *  - transactional notifications are not marketing: they don't count
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const ESP = 'http://localhost:8121';
const run = Date.now();

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const MSG = `${API}/tmf-api/communicationManagement/v4/communicationMessage`;
  const CAMPAIGN = `${API}/tmf-api/campaignManagement/v4/campaign`;
  const SETTINGS = `${API}/tmf-api/campaignManagement/v4/settings`;

  /* ================= ESP RECEIPTS (nova) ================= */
  const novaStaff = await token(ctx, 'nova', 'demo', 'demo');

  // a nova customer whose provider will bounce them (the mock bounces
  // any address containing 'bounce')
  const bounceEmail = `bounce-${run}@nova.example`;
  const created = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(novaStaff), data: { email: bounceEmail, givenName: 'Bo', familyName: `Unce${run}` } })).json();
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(novaStaff), data: {
    id: created.id, givenName: 'Bo', familyName: `Unce${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: bounceEmail } }] } });

  const bounceSubject = `Bounces ${run}`;
  const sent = await (await ctx.post(MSG, { headers: H(novaStaff), data: {
    subject: bounceSubject, content: 'This address will bounce.',
    relatedParty: [{ id: created.id, role: 'customer' }] } })).json();

  /* the receipt comes home: message stamped, address suppressed */
  let receipt = null;
  for (let i = 0; i < 15 && !receipt; i++) {
    await sleep(1000);
    const msg = await (await ctx.get(`${MSG}/${sent.id}`, { headers: H(novaStaff) })).json();
    if (msg.deliveryStatus) receipt = msg.deliveryStatus;
  }
  if (receipt !== 'bounce') fail('the bounce receipt never stamped the message: ' + receipt);
  const suppression = await (await ctx.get('http://localhost:8095/esp/v1/suppression',
    { headers: H(novaStaff) })).json();
  if (!suppression.some((s) => s.email === bounceEmail && s.reason === 'bounce')) {
    fail('the bounced address is not on the suppression list');
  }
  console.log('OK the provider ANSWERED BACK: the message is stamped "bounce" and the address'
    + ' sits on nova\'s suppression list');

  /* a suppressed address gets no second email — the inbox still does */
  const secondSubject = `Never emailed ${run}`;
  await ctx.post(MSG, { headers: H(novaStaff), data: {
    subject: secondSubject, content: 'In-app only now.',
    relatedParty: [{ id: created.id, role: 'customer' }] } });
  await sleep(4000);
  const mails = await (await ctx.get(`${ESP}/mails?to=${bounceEmail}`)).json();
  if (mails.length !== 1) fail(`the ESP got ${mails.length} mails for a suppressed address (must stay 1)`);
  if (mails[0].subject !== bounceSubject) fail('the wrong mail reached the ESP');
  const boToken = await token(ctx, 'nova', bounceEmail, created.temporaryPassword);
  const inbox = await (await ctx.get(`${MSG}?limit=100`, { headers: H(boToken) })).json();
  if (!inbox.some((m) => m.subject === secondSubject)) {
    fail('suppression must never touch the in-app inbox');
  }
  console.log('OK SUPPRESSED: no second email left the building — and the in-app inbox still'
    + ' has every message (the record is never suppressed)');

  /* delivered path: a clean address gets stamped "delivered" */
  const goodEmail = `good-${run}@nova.example`;
  const good = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(novaStaff), data: { email: goodEmail, givenName: 'Gro', familyName: `Od${run}` } })).json();
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(novaStaff), data: {
    id: good.id, givenName: 'Gro', familyName: `Od${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: goodEmail } }] } });
  const goodSent = await (await ctx.post(MSG, { headers: H(novaStaff), data: {
    subject: `Lands fine ${run}`, content: 'x',
    relatedParty: [{ id: good.id, role: 'customer' }] } })).json();
  let delivered = null;
  for (let i = 0; i < 15 && !delivered; i++) {
    await sleep(1000);
    const msg = await (await ctx.get(`${MSG}/${goodSent.id}`, { headers: H(novaStaff) })).json();
    if (msg.deliveryStatus) delivered = msg.deliveryStatus;
  }
  if (delivered !== 'delivered') fail('a clean send never stamped "delivered": ' + delivered);
  console.log('OK a clean address reads "delivered" — the seam reports truth in both directions');

  /* ================= FREQUENCY CAPS (genalpha) ================= */
  const staff = await token(ctx, 'bss', 'demo', 'demo');
  try {
    const setting = await (await ctx.post(SETTINGS, { headers: H(staff),
      data: { maxMarketingMessages: 2, perDays: 1 } })).json();
    if (!setting.capActive) fail('the cap did not activate: ' + JSON.stringify(setting));
    console.log('OK the tenant set a marketing budget: 2 messages per customer per day');

    /* one consented, stitched customer in a run-unique segment */
    const email = `capped-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'Cap', familyName: `Ped${run}` } })).json();
    const vid = `cap-vis-${run}`;
    const SEG = `CapCat${run}`;
    await ctx.post(`${API}/insight/v1/consent`, { headers: { 'Content-Type': 'application/json' },
      data: { visitorId: vid, analytics: true, personalization: true } });
    await ctx.post(`${API}/insight/v1/event`, { headers: { 'Content-Type': 'application/json' },
      data: { visitorId: vid, type: 'view', category: SEG } });
    const custTok = await token(ctx, 'bss', email, login.temporaryPassword);
    await ctx.post(`${API}/insight/v1/stitch`, { headers: H(custTok), data: { visitorId: vid } });

    /* three campaigns, same customer: 1, 2, then THE BUDGET IS SPENT */
    const reachedCounts = [];
    for (let i = 1; i <= 3; i++) {
      const c = await (await ctx.post(CAMPAIGN, { headers: H(staff), data: {
        name: `Cap blast ${i} ${run}`, segmentName: SEG,
        message: { subject: `Cap pitch ${i} ${run}`, content: 'One more thing…' } } })).json();
      const blast = await (await ctx.post(`${CAMPAIGN}/${c.id}/execute`,
        { headers: H(staff), data: {} })).json();
      reachedCounts.push(blast.reached);
    }
    if (reachedCounts.join(',') !== '1,1,0') {
      fail(`the budget did not hold: reached ${reachedCounts.join(',')} (wanted 1,1,0)`);
    }
    const inboxSubjects = (await (await ctx.get(`${MSG}?limit=100`, { headers: H(custTok) })).json())
      .map((m) => m.subject);
    if (!inboxSubjects.includes(`Cap pitch 1 ${run}`) || !inboxSubjects.includes(`Cap pitch 2 ${run}`)
        || inboxSubjects.includes(`Cap pitch 3 ${run}`)) {
      fail('the inbox disagrees with the cap: ' + inboxSubjects.filter((s) => s.includes('Cap')).join('|'));
    }
    console.log('OK the CAP HELD: blasts reached 1, 1, then 0 — the third campaign found the'
      + ' budget spent, and the inbox has exactly two pitches');
  } finally {
    // the cap is tenant-wide: ALWAYS switch it off so other suites keep blasting
    await ctx.post(SETTINGS, { headers: H(staff), data: { maxMarketingMessages: 0 } });
  }
  const off = await (await ctx.get(SETTINGS, { headers: H(staff) })).json();
  if (off[0].capActive) fail('the cap did not switch off after the test');
  console.log('OK the cap is data: switched off as easily as it was set');

  /* ================= QUIET HOURS (genalpha) ================= */
  try {
    // a window that covers RIGHT NOW, tenant-local = UTC for determinism
    const hhmm = (d) => `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}`;
    const quiet = await (await ctx.post(SETTINGS, { headers: H(staff), data: {
      maxMarketingMessages: 0,
      quietStart: hhmm(new Date(Date.now() - 3600e3)),
      quietEnd: hhmm(new Date(Date.now() + 3600e3)),
      timeZone: 'UTC' } })).json();
    if (!quiet.quietActive) fail('quiet hours did not activate: ' + JSON.stringify(quiet));
    console.log(`OK quiet hours set: ${quiet.quietStart}–${quiet.quietEnd} UTC — the tenant is asleep`);

    /* a fresh segment customer; the blast finds NOBODY awake */
    const qEmail = `quiet-${run}@example.com`;
    const qLogin = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email: qEmail, givenName: 'Qui', familyName: `Et${run}` } })).json();
    const qVid = `quiet-vis-${run}`;
    const QSEG = `QuietCat${run}`;
    await ctx.post(`${API}/insight/v1/consent`, { headers: { 'Content-Type': 'application/json' },
      data: { visitorId: qVid, analytics: true, personalization: true } });
    await ctx.post(`${API}/insight/v1/event`, { headers: { 'Content-Type': 'application/json' },
      data: { visitorId: qVid, type: 'view', category: QSEG } });
    const qTok = await token(ctx, 'bss', qEmail, qLogin.temporaryPassword);
    await ctx.post(`${API}/insight/v1/stitch`, { headers: H(qTok), data: { visitorId: qVid } });

    const qc = await (await ctx.post(CAMPAIGN, { headers: H(staff), data: {
      name: `Quiet blast ${run}`, segmentName: QSEG,
      message: { subject: `Night pitch ${run}`, content: 'zzz' } } })).json();
    const silent = await (await ctx.post(`${CAMPAIGN}/${qc.id}/execute`,
      { headers: H(staff), data: {} })).json();
    if (silent.reached !== 0) fail('a campaign spoke during quiet hours: ' + JSON.stringify(silent));
    console.log('OK SILENT: the blast reached nobody inside the window — quiet hours are a wall,'
      + ' not a suggestion');

    /* morning comes (settings save is full-replace: no quiet fields = cleared) */
    await ctx.post(SETTINGS, { headers: H(staff), data: { maxMarketingMessages: 0 } });
    const morning = await (await ctx.post(`${CAMPAIGN}/${qc.id}/execute`,
      { headers: H(staff), data: {} })).json();
    if (morning.reached !== 1) fail('the skipped customer was not reachable after quiet hours: '
      + JSON.stringify(morning));
    console.log('OK MORNING: the same execute reaches them once the window lifts — skipped, never lost');
  } finally {
    await ctx.post(SETTINGS, { headers: H(staff), data: { maxMarketingMessages: 0 } });
  }

  console.log('\nALL GUARDRAIL CHECKS PASSED — the provider\'s receipts stamp messages and feed'
    + ' the suppression list; a suppressed address never gets another email; the frequency cap'
    + ' makes "leave the customer alone" a budget, not an etiquette; and quiet hours mean the'
    + ' brand sleeps when the customer does.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
