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
    people.push({ id: login.id, email, tok, vid });
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

  /* REVENUE ATTRIBUTION: the conversion carries the plan's real catalog price */
  const offering = await (await ctx.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering/${PLAN_ID}`)).json();
  let expectedMonthly = 0;
  for (const ref of offering.productOfferingPrice || []) {
    const price = await (await ctx.get(
      `${API}/tmf-api/productCatalogManagement/v4/productOfferingPrice/${ref.id}`)).json();
    if (price.priceType === 'recurring' && price.recurringChargePeriodType === 'month') {
      expectedMonthly += price.price.value;
    }
  }
  if (!(expectedMonthly > 0)) fail('the test plan has no monthly price to attribute');
  if (!stats.revenue || Math.abs(stats.revenue.treated - expectedMonthly) > 0.01) {
    fail(`attributed revenue is not the catalog's number: wanted ${expectedMonthly}, `
      + `got ${JSON.stringify(stats.revenue)}`);
  }
  if (Number(stats.revenue.holdout) !== 0) {
    fail('the holdout earned revenue it was never pitched: ' + JSON.stringify(stats.revenue));
  }
  if (!(stats.revenue.liftPerCustomer > 0)) {
    fail('revenue lift per customer missing: ' + JSON.stringify(stats.revenue));
  }
  console.log(`OK REVENUE: the conversion is worth ${stats.revenue.treated}/month — the`
    + ` catalog's own number — and the lift reads ${stats.revenue.liftPerCustomer} per`
    + ` customer/month, not just points`);

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

  /* ================= A/B ARMS: two messages, one honest readout ================= */
  const subjA = `Pitch A ${run}`;
  const subjB = `Pitch B ${run}`;
  // arm assignment hashes the campaign id — re-create until the six split
  // across BOTH arms (a same-arm run is legal but proves nothing)
  let ab = null, abStats = null;
  for (let attempt = 0; attempt < 6; attempt++) {
    ab = await (await ctx.post(CAMPAIGN, { headers: H(staff), data: {
      name: `AB test ${run}.${attempt}`, segmentName: SEGMENT,
      messageVariants: [
        { name: 'A', subject: subjA, content: 'The direct pitch.' },
        { name: 'B', subject: subjB, content: 'The playful pitch.' },
      ] } })).json();
    if (!ab.id) fail('A/B campaign create failed: ' + JSON.stringify(ab).slice(0, 200));
    await ctx.post(`${CAMPAIGN}/${ab.id}/execute`, { headers: H(staff), data: {} });
    abStats = await (await ctx.get(`${CAMPAIGN}/${ab.id}/stats`, { headers: H(staff) })).json();
    const sent = abStats.arms.arms.map((a) => a.sent);
    if (sent[0] > 0 && sent[1] > 0) break;
    abStats = null;
  }
  if (!abStats) fail('six attempts never split across both arms — the hash is not splitting');
  const sentA = abStats.arms.arms.find((a) => a.name === 'A').sent;
  const sentB = abStats.arms.arms.find((a) => a.name === 'B').sent;
  if (sentA + sentB !== 6) fail('the arms do not cover the segment: ' + JSON.stringify(abStats.arms));
  console.log(`OK the A/B split is deterministic and total: ${sentA} heard pitch A, ${sentB} heard pitch B`);

  /* every inbox has exactly ONE of the two pitches, and the tallies agree */
  let inboxA = 0, inboxB = 0;
  for (const p of people) {
    let subjects = [];
    for (let i = 0; i < 10; i++) {
      subjects = await inboxOf(p);
      if (subjects.includes(subjA) || subjects.includes(subjB)) break;
      await sleep(1000);
    }
    const hasA = subjects.includes(subjA), hasB = subjects.includes(subjB);
    if (hasA === hasB) fail(`${p.email} heard ${hasA ? 'BOTH pitches' : 'neither pitch'}`);
    if (hasA) { inboxA++; p.arm = 'A'; } else { inboxB++; p.arm = 'B'; }
  }
  if (inboxA !== sentA || inboxB !== sentB) {
    fail(`inboxes disagree with the arm ledger: heard ${inboxA}/${inboxB}, ledger says ${sentA}/${sentB}`);
  }
  console.log('OK every customer heard exactly one pitch — and the inboxes match the ledger');

  /* a fresh buyer converts — the conversion lands on THEIR arm only */
  const abBuyer = people.find((p) => p.id !== converter.id && p.id !== exiter.id);
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(abBuyer.tok), data: {
      productOrderItem: [{ action: 'add', productOffering: { id: PLAN_ID, name: 'GenAlpha Mobile 10 GB' } }] } });
  let armRead = null;
  for (let i = 0; i < 30 && !armRead; i++) {
    await sleep(2000);
    abStats = await (await ctx.get(`${CAMPAIGN}/${ab.id}/stats`, { headers: H(staff) })).json();
    const mine = abStats.arms.arms.find((a) => a.name === abBuyer.arm);
    if (mine.conversions === 1) armRead = mine;
  }
  if (!armRead) fail('the conversion never landed on the buyer\'s arm: ' + JSON.stringify(abStats.arms));
  const other = abStats.arms.arms.find((a) => a.name !== abBuyer.arm);
  if (other.conversions !== 0) fail('a conversion leaked onto the arm that never spoke: ' + JSON.stringify(abStats.arms));
  if (abStats.arms.leader !== abBuyer.arm) fail('the leader is not the converting arm: ' + JSON.stringify(abStats.arms));
  if (!abStats.arms.verdict || !abStats.arms.verdict.includes('anecdote')) {
    fail('six people crowned a winner — the verdict must call small samples an anecdote: '
      + JSON.stringify(abStats.arms.verdict));
  }
  console.log(`OK arm ${abBuyer.arm} leads (${armRead.conversions}/${armRead.sent}) and the verdict`
    + ` stays honest: "${abStats.arms.verdict}"`);

  /* ================= BRANCHES: the journey reads before it speaks ================= */
  const BRANCH_SEG = `BranchCat${run}`;
  const gadgetLover = people[4];
  await ctx.post(`${API}/insight/v1/event`, { headers: { 'Content-Type': 'application/json' },
    data: { visitorId: gadgetLover.vid, type: 'view', category: BRANCH_SEG } });
  const brThen = `For the gadget lover ${run}`;
  const brElse = `For everyone else ${run}`;
  const branchy = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Branch journey ${run}`, segmentName: SEGMENT, holdoutPercent: 0,
    steps: [{ type: 'branch', inSegment: BRANCH_SEG,
      then: { subject: brThen, content: 'That accessory shelf you kept visiting…' },
      else: { subject: brElse, content: 'A plan for normal humans.' } }] } })).json();
  if (!branchy.id) fail('branch journey create failed: ' + JSON.stringify(branchy).slice(0, 200));
  const brEnrolled = await (await ctx.post(`${JOURNEY}/${branchy.id}/enroll`,
    { headers: H(staff), data: {} })).json();
  if (brEnrolled.enrolled !== 6) fail('branch journey missed the segment: ' + JSON.stringify(brEnrolled));

  /* the gadget lover hears the THEN pitch; a plain member hears the ELSE */
  const plain = people.find((p) => p.id !== gadgetLover.id);
  let loverSubjects = [], plainSubjects = [];
  for (let i = 0; i < 20; i++) {
    [loverSubjects, plainSubjects] = [await inboxOf(gadgetLover), await inboxOf(plain)];
    if (loverSubjects.includes(brThen) && plainSubjects.includes(brElse)) break;
    await sleep(1500);
  }
  if (!loverSubjects.includes(brThen)) fail('the segment member never heard the THEN pitch');
  if (loverSubjects.includes(brElse)) fail('the segment member heard BOTH pitches');
  if (!plainSubjects.includes(brElse)) fail('a plain member never heard the ELSE pitch');
  if (plainSubjects.includes(brThen)) fail('a plain member heard the gadget pitch');
  console.log('OK BRANCHES: the journey read each customer before speaking — the gadget lover'
    + ' heard the gadget pitch, everyone else the generic one, nobody heard both');

  console.log('\nALL GROWTH CHECKS PASSED — M1: holdouts are real control groups, conversions'
    + ' count inside the window, lift is a number. M2: journeys walk, wait, follow up —'
    + ' and the conversion event exits people from ANY step. A/B: arms split deterministically,'
    + ' convert separately, and never crown a winner on six people. Branches: the journey reads'
    + ' the customer before it speaks.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
