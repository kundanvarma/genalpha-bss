/* Growth M1 — measurement: the difference between a marketing tool and a
 * message cannon.
 *
 *  - a segment campaign with a HOLDOUT: a deterministic slice of the
 *    audience lands in the control group — same ledger, NO message
 *  - a treated customer's completed order INSIDE the conversion window
 *    marks their execution converted; the stats read reached / held out /
 *    conversion rates per variant / LIFT
 *  - the segment is run-unique (a synthetic interest), so the numbers are
 *    exact, not statistical
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const PLAN_ID = '14291c1a-df26-4232-8084-500466888e46'; // GenAlpha Mobile 10 GB
const SEGMENT = `GrowthCat${run}`; // a category only this run browses

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

  /* ---------- six consented, stitched customers with a run-unique interest ---------- */
  const people = [];
  for (let i = 0; i < 6; i++) {
    const email = `growth-${i}-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: `Grow${i}`, familyName: `Th${run}` } })).json();
    const vid = `growth-vis-${i}-${run}`;
    await ctx.post(`${API}/insight/v1/consent`, { headers: { 'Content-Type': 'application/json' },
      data: { visitorId: vid, analytics: true, personalization: true } });
    await ctx.post(`${API}/insight/v1/event`, { headers: { 'Content-Type': 'application/json' },
      data: { visitorId: vid, type: 'view', category: SEGMENT } });
    const tok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
    await ctx.post(`${API}/insight/v1/stitch`, { headers: H(tok), data: { visitorId: vid } });
    people.push({ id: login.id, email, tok });
  }
  console.log('OK six consented customers browsed the run-unique category — a segment of exactly six');

  /* ---------- the campaign: 50% holdout, 7-day window ---------- */
  const CAMPAIGN = `${API}/tmf-api/campaignManagement/v4/campaign`;
  const subject = `Growth pitch ${run}`;
  const campaign = await (await ctx.post(CAMPAIGN, { headers: H(staff), data: {
    name: `Growth campaign ${run}`, segmentName: SEGMENT,
    holdoutPercent: 50, conversionWindowDays: 7,
    message: { subject, content: 'Something for your shelf.' } } })).json();
  const blast = await (await ctx.post(`${CAMPAIGN}/${campaign.id}/execute`,
    { headers: H(staff), data: {} })).json();
  let stats = await (await ctx.get(`${CAMPAIGN}/${campaign.id}/stats`, { headers: H(staff) })).json();
  if (stats.reached + stats.heldOut !== 6) {
    fail('the ledger does not cover the segment: ' + JSON.stringify(stats));
  }
  if (stats.reached < 1 || stats.heldOut < 1) {
    fail('a 50% holdout produced an empty variant: ' + JSON.stringify(stats));
  }
  console.log(`OK the blast ledgered all six: ${stats.reached} treated, ${stats.heldOut} held out`);

  /* ---------- the holdout got NOTHING; the treated got the message ---------- */
  const executions = await (await ctx.get(`${CAMPAIGN}/${campaign.id}/execution`,
    { headers: H(staff) })).json();
  const inboxOf = async (p) => (await (await ctx.get(
    `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=100`,
    { headers: H(p.tok) })).json()).map((m) => m.subject);
  // stats variant is not on the execution list — infer via inbox, verify against stats
  let treatedSeen = 0, holdoutSeen = 0;
  for (const p of people) {
    for (let i = 0; i < 8; i++) {
      const has = (await inboxOf(p)).includes(subject);
      if (has) { p.variant = 'treated'; break; }
      await sleep(1000);
      p.variant = 'holdout';
    }
    if (p.variant === 'treated') treatedSeen++; else holdoutSeen++;
  }
  if (treatedSeen !== stats.reached || holdoutSeen !== stats.heldOut) {
    fail(`inboxes disagree with the ledger: saw ${treatedSeen}/${holdoutSeen}, ledger says `
      + `${stats.reached}/${stats.heldOut}`);
  }
  console.log('OK the control group is real: every treated inbox has the message, every'
    + ' holdout inbox has silence — and the ledger agrees');

  /* ---------- a treated customer converts inside the window ---------- */
  const converter = people.find((p) => p.variant === 'treated');
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(converter.tok), data: {
      productOrderItem: [{ action: 'add', productOffering: { id: PLAN_ID, name: 'GenAlpha Mobile 10 GB' } }] } });
  let converted = 0;
  for (let i = 0; i < 30 && !converted; i++) {
    await sleep(2500);
    stats = await (await ctx.get(`${CAMPAIGN}/${campaign.id}/stats`, { headers: H(staff) })).json();
    converted = stats.conversions.treated;
  }
  if (converted !== 1 || stats.conversions.holdout !== 0) {
    fail('conversion tracking wrong: ' + JSON.stringify(stats));
  }
  if (stats.liftPoints == null || stats.liftPoints <= 0) {
    fail('lift missing or non-positive: ' + JSON.stringify(stats));
  }
  console.log(`OK MEASURED: treated ${stats.treatedRate}% vs holdout ${stats.holdoutRate}% — `
    + `${stats.liftPoints} points of lift, honestly labelled `
    + `("${stats.note || stats.conversionWindowDays + '-day window'}")`);

  /* ================= M2 — JOURNEYS: sequences with an exit rule ================= */
  const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;
  const step1 = `Welcome aboard ${run}`;
  const step2 = `Still thinking? ${run}`;
  const journey = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Welcome journey ${run}`, segmentName: SEGMENT, holdoutPercent: 0,
    steps: [
      { type: 'message', subject: step1, content: 'Good to have you looking around.' },
      { type: 'wait', seconds: 25 },
      { type: 'message', subject: step2, content: 'That plan is still worth a look.' },
    ] } })).json();
  if (!journey.id) fail('journey create failed: ' + JSON.stringify(journey).slice(0, 200));
  const enrolled = await (await ctx.post(`${JOURNEY}/${journey.id}/enroll`,
    { headers: H(staff), data: {} })).json();
  if (enrolled.enrolled !== 6) fail('enrollment missed the segment: ' + JSON.stringify(enrolled));
  console.log('OK the welcome journey enrolled the whole segment of six');

  /* step 1 lands for everyone (no holdout on this journey) */
  for (const p of people) {
    let got = false;
    for (let i = 0; i < 15 && !got; i++) {
      got = (await inboxOf(p)).includes(step1);
      if (!got) await sleep(1500);
    }
    if (!got) fail('step 1 never reached ' + p.email);
  }
  console.log('OK step 1 reached all six — the tick walks the ledger');

  /* the EXIT RULE: one enrollee converts during the wait — no follow-up for them */
  const exiter = people[1].id === converter.id ? people[2] : people[1];
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(exiter.tok), data: {
      productOrderItem: [{ action: 'add', productOffering: { id: PLAN_ID, name: 'GenAlpha Mobile 10 GB' } }] } });
  let jstats = null;
  for (let i = 0; i < 30; i++) {
    await sleep(2000);
    jstats = await (await ctx.get(`${JOURNEY}/${journey.id}/stats`, { headers: H(staff) })).json();
    if (jstats.conversions.treated >= 1) break;
  }
  if (jstats.conversions.treated < 1) fail('the journey conversion never registered: ' + JSON.stringify(jstats));
  console.log('OK the exit rule fired: the buyer CONVERTED OUT during the wait');

  /* after the wait: follow-ups land for the patient, NEVER for the converted */
  const stayer = people.find((p) => p.id !== exiter.id && p.id !== converter.id);
  let followedUp = false;
  for (let i = 0; i < 30 && !followedUp; i++) {
    await sleep(2000);
    followedUp = (await inboxOf(stayer)).includes(step2);
  }
  if (!followedUp) fail('the follow-up never reached a non-converter');
  await sleep(3000);
  if ((await inboxOf(exiter)).includes(step2)) {
    fail('a CONVERTER received the follow-up — the exit rule is the whole point');
  }
  jstats = await (await ctx.get(`${JOURNEY}/${journey.id}/stats`, { headers: H(staff) })).json();
  if (!jstats.completedUnconverted || jstats.completedUnconverted < 1) {
    fail('the funnel did not complete: ' + JSON.stringify(jstats));
  }
  console.log(`OK the funnel reads honestly: entered ${jstats.entered}, converted out `
    + `${jstats.conversions.treated}, completed unconverted ${jstats.completedUnconverted} — `
    + 'and nobody got "10% off!" the day after they paid full price');

  console.log('\nALL GROWTH CHECKS PASSED — M1: holdouts are real control groups, conversions'
    + ' count inside the window, lift is a number. M2: journeys walk, wait, follow up —'
    + ' and the conversion event exits people from ANY step.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
