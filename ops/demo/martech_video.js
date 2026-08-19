/* martech_video.js — v2: a NARRATED screen-capture for LinkedIn — the built-in
 * martech/CDP + growth stack, end to end, with an offline macOS voiceover.
 *
 *   Cold open   the console Marketing desk.
 *   Arch intro  ~20s executive diagram (martech-arch.html, file://): five BSS
 *               domains → one Kafka bus → the CDP trait store → three outcomes.
 *   Act 1       by hand: a CAMPAIGN and a JOURNEY with plain clicks.
 *   Act 1.5     the CDP half: audience rule-tree builder + live member preview,
 *               activation to Meta (real async job, SHA-256 hashes — shown as a
 *               live-API readout: the console has no Activate button),
 *               attribution portfolio (with the no-holdout honesty rule),
 *               social listening (mood strip) and social care (DM → TMF621).
 *   Act 2       the Marketing copilot — one ask, a proposal, one human click.
 *
 * Voiceover: each narration beat renders to an .aiff via `say -v Samantha`,
 * the on-screen action holds at least that long, and after the take every clip
 * is muxed over the video at its recorded offset (adelay + amix, the same
 * idiom as ops/demo/present.js RECORD mode).
 *
 * Output: ops/demo/recordings/martech.mp4 (h264 + aac). Everything on screen is
 * the live stack doing real work; created artifacts (campaigns, journeys,
 * audiences — the copilot's included) are deleted afterwards by id-diff.
 *
 * Run:  PATH="/opt/homebrew/bin:$PATH" node ops/demo/martech_video.js
 */
const { chromium } = require('/Users/kundanverma/Documents/projects/bssproject/bss-java/ops/e2e/node_modules/playwright');
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAMPAIGN = `${API}/tmf-api/campaignManagement/v4/campaign`;
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;
const AUDIENCE = `${API}/insight/v1/audience`;
const TICKETS = `${API}/tmf-api/troubleTicket/v4/troubleTicket?limit=100`;
const SOCIAL = 'http://localhost:8122';
const HANDLE = 'genalpha-brand';
const FFMPEG = '/opt/homebrew/bin/ffmpeg';
const FFPROBE = '/opt/homebrew/bin/ffprobe';
const SAY = '/usr/bin/say';
const ARCH = 'file://' + path.join(__dirname, 'martech-arch.html');
const run = Date.now();
const dir = path.join(__dirname, 'recordings');

/* ---------- narration machinery (present.js RECORD idiom) ---------- */
const REC_LEAD = 0.3;          // small offset so voiceover aligns with the video
let recT0 = 0, clipIdx = 0;
const clips = [];              // { off (s from recording start), file, dur }
const timeline = [];           // { t, label } — segment markers for the report
const now = () => (recT0 ? (Date.now() - recT0) / 1000 : 0);
function seg(label) { timeline.push({ t: now(), label }); console.log(`— SEG ${label} @ ${now().toFixed(1)}s`); }
function renderClip(text) {
  const file = path.join(dir, `clip_${clipIdx++}.aiff`);
  execFileSync(SAY, ['-v', 'Samantha', '-r', '186', '-o', file, text]);
  const dur = parseFloat(execFileSync(FFPROBE,
    ['-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nk=1:nw=1', file]).toString()) || 2.4;
  return { file, dur };
}

/* ---------- cinema helpers (same idiom as v1 / journey_video.js) ---------- */
async function lens(page) {
  await page.addStyleTag({ content: `
    #cine-cursor{position:fixed;width:26px;height:26px;border-radius:50%;left:60%;top:60%;
      border:3px solid #ffb02e;background:rgba(255,176,46,.22);z-index:2147483647;
      pointer-events:none;transform:translate(-50%,-50%);
      transition:left .5s cubic-bezier(.45,0,.2,1),top .5s cubic-bezier(.45,0,.2,1);
      box-shadow:0 0 14px rgba(255,176,46,.6);}
    #cine-cursor.click{animation:cineclick .32s ease}
    @keyframes cineclick{45%{transform:translate(-50%,-50%) scale(.55)}}
    #cine-caption{position:fixed;left:0;right:0;bottom:0;z-index:2147483646;padding:20px 30px 24px;
      background:linear-gradient(0deg,rgba(8,8,14,.94),rgba(8,8,14,.7));color:#eef4f4;
      font:600 20px/1.4 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;text-align:center;
      opacity:0;transition:opacity .35s;pointer-events:none}
    #cine-caption .act{display:block;font:700 12px/1 -apple-system,sans-serif;letter-spacing:2.5px;
      text-transform:uppercase;color:#ffb02e;margin-bottom:7px}
    #cine-caption.on{opacity:1}
  ` }).catch(() => {});
  await page.evaluate(() => {
    if (!document.getElementById('cine-cursor')) {
      const c = document.createElement('div'); c.id = 'cine-cursor'; document.body.append(c);
      const t = document.createElement('div'); t.id = 'cine-caption';
      t.innerHTML = '<span class="act"></span><span class="txt"></span>';
      document.body.append(t);
    }
  }).catch(() => {});
}

let ACT = '';
async function captionOn(page, text) {
  await lens(page);
  await page.evaluate(({ act, text }) => {
    const el = document.getElementById('cine-caption');
    if (el) {
      el.querySelector('.act').textContent = act;
      el.querySelector('.txt').textContent = text;
      el.classList.add('on');
    }
  }, { act: ACT, text });
}
async function captionOff(page) {
  await page.evaluate(() => document.getElementById('cine-caption')?.classList.remove('on')).catch(() => {});
  await page.waitForTimeout(250);
}

// The heart of v2: speak a line (offline TTS), show it as the caption, and hold
// the shot at least as long as the clip. The caption tracks the spoken words;
// capText overrides the printed form where TTS needs a phonetic spelling
// (say "S H A two fifty-six", print "SHA-256").
async function narrate(page, text, tailMs = 350, capText) {
  const clip = renderClip(text);
  await captionOn(page, capText || text);
  clips.push({ off: now(), file: clip.file });
  await page.waitForTimeout(clip.dur * 1000 + tailMs);
}

async function glideTo(page, locator) {
  await lens(page);
  await locator.scrollIntoViewIfNeeded().catch(() => {});
  const box = await locator.boundingBox();
  if (box) {
    await page.evaluate(([x, y]) => {
      const c = document.getElementById('cine-cursor');
      if (c) { c.style.left = x + 'px'; c.style.top = y + 'px'; }
    }, [box.x + box.width / 2, box.y + box.height / 2]);
    await page.waitForTimeout(420);
  }
}
async function glideClick(page, locator, opts = {}) {
  await glideTo(page, locator);
  await page.evaluate(() => {
    const c = document.getElementById('cine-cursor');
    if (c) { c.classList.remove('click'); void c.offsetWidth; c.classList.add('click'); }
  }).catch(() => {});
  await locator.click(opts);
  await page.waitForTimeout(380);
}
async function glideType(page, locator, text) {
  await glideTo(page, locator);
  await locator.click();
  await locator.type(text, { delay: 20 });
  await page.waitForTimeout(220);
}
async function glideSelect(page, locator, value) {
  await glideClick(page, locator);
  await locator.selectOption(value);
  await page.waitForTimeout(380);
}

// A terminal-style inset showing a LIVE API exchange (used where the console
// has no UI for the flow — activation, the opened trouble ticket). Every line
// shown is a real request/response from this take.
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
async function apiCard(page, title, lines) {
  await page.evaluate(({ title, lines }) => {
    let el = document.getElementById('cine-api');
    if (!el) {
      el = document.createElement('div'); el.id = 'cine-api';
      el.style.cssText = 'position:fixed;right:44px;top:110px;width:590px;max-height:560px;overflow:hidden;'
        + 'z-index:2147483645;background:rgba(10,18,17,.96);border:1.5px solid #45AFAC;border-radius:12px;'
        + 'padding:16px 20px;font:12.5px/1.55 ui-monospace,Menlo,monospace;color:#cfe8e6;'
        + 'box-shadow:0 12px 44px rgba(0,0,0,.55)';
      document.body.append(el);
    }
    el.innerHTML = '<div style="color:#45AFAC;font-weight:700;font-size:13px;margin-bottom:8px">' + title + '</div>'
      + lines.map((l) => '<div style="white-space:pre">' + l + '</div>').join('');
  }, { title, lines });
}
async function apiCardOff(page) {
  await page.evaluate(() => document.getElementById('cine-api')?.remove()).catch(() => {});
}

// The RECORDED console session is the MARKETING persona (mkt@bss.local,
// marketing-staff — console_workspaces_test 3b): the RBAC is part of the pitch,
// a marketer's console shows ONLY the Marketing desk. API driving/cleanup stays
// on the demo operator token, exactly like the suites.
async function openConsole(page) {
  await page.goto(`${API}/console/`);
  await Promise.race([
    page.waitForSelector('input[name="username"]', { timeout: 20000 }).catch(() => null),
    page.waitForSelector('#main:not([hidden])', { timeout: 20000 }).catch(() => null),
  ]);
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', 'mkt@bss.local');
    await page.fill('input[name="password"]', 'mkt');
    await page.click('input[type="submit"], button[type="submit"]');
  }
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  await page.waitForSelector('#tabs .tab', { timeout: 10000 });
}

/* ---------- the film ---------- */
(async () => {
  fs.mkdirSync(dir, { recursive: true });
  for (const f of fs.readdirSync(dir)) {
    if (f.startsWith('clip_') || f.endsWith('.webm')) { try { fs.unlinkSync(path.join(dir, f)); } catch { /* */ } }
  }
  const browser = await chromium.launch({ headless: true });

  // Pre-flight (off camera): auth, id snapshots for cleanup, and the social
  // seeds the on-camera Syncs will pull in (run-unique, same idiom as the suites).
  const apiCtx = await browser.newContext();
  const tok = (await (await apiCtx.request.post(KC,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json()).access_token;
  const H = { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' };
  const ids = async (url) => new Set((await (await apiCtx.request.get(url, { headers: H })).json()).map((x) => x.id));
  const beforeCampaigns = await ids(CAMPAIGN);
  const beforeJourneys = await ids(JOURNEY);
  const beforeAudiences = await ids(AUDIENCE);
  console.log(`· preflight: ${beforeCampaigns.size} campaigns, ${beforeJourneys.size} journeys, ${beforeAudiences.size} audiences before the take`);

  // Brand mentions for Social listening (love→positive, outage→negative, plain→neutral)…
  const J = { 'Content-Type': 'application/json' };
  const mention = (m) => apiCtx.request.post(`${SOCIAL}/v1/${HANDLE}/mentions`, { headers: J, data: m });
  await mention({ text: `love the new fibre speed — five hundred megabits, zero drops`, platform: 'x', author: `ada${run}` });
  await mention({ text: `great coverage on the new plan`, platform: 'instagram', author: `sam${run}` });
  await mention({ text: `outage in my street again this morning`, platform: 'x', author: `leo${run}` });
  await mention({ text: `just switched my plan over today`, platform: 'x', author: `mia${run}` });
  // …and DMs for Social care (down→negative→MAJOR ticket; love→positive→no ticket).
  const dm = async (author, text) => {
    const r = await apiCtx.request.post(`${SOCIAL}/v1/${HANDLE}/dms`,
      { headers: J, data: { author, handle: '@' + author, text } });
    return `social-dm:x:${(await r.json()).id}`;
  };
  const angryDm = await dm(`pat${run}`, 'my internet has been down all day and nobody answers');
  await dm(`hana${run}`, 'love the new plan, thank you so much!');
  console.log('· preflight: 4 mentions + 2 DMs seeded on the brand handle');

  // Three foreign-BSS customers via the bridge (bss_bridge_test/audience_sql_test
  // idiom): CUSTOMER_CREATED gives them an email trait, LOYALTY_TIER gold +
  // CHURN_SCORED low puts them in the on-camera audience — so the activation
  // shot has real, hashable addresses to export.
  const BRIDGE = 'http://localhost:8140';
  // acme's shape nests the person INSIDE account (bss_bridge_test line 40) —
  // top-level firstName/mail is silently dropped by the mapping
  const bev = (account, kind, extra) => apiCtx.request.post(`${BRIDGE}/bridge/v1/acme-bss/event`,
    { headers: J, data: { kind, account, ...extra } });
  const filmMails = [];
  for (const tag of ['a', 'b', 'c']) {
    const ref = `film-${tag}-${run}`; const mail = `film-${tag}-${run}@genalpha.example`;
    filmMails.push(mail);
    await bev({ ref, firstName: 'Film', mail }, 'CUSTOMER_CREATED', {});
    await bev({ ref }, 'LOYALTY_TIER', { tier: 'gold' });
    await bev({ ref }, 'CHURN_SCORED', { band: 'low' });
  }
  let traitsLanded = false;
  for (let i = 0; i < 40 && !traitsLanded; i++) {
    const facets = await (await apiCtx.request.get(`${AUDIENCE}/facets`, { headers: H })).json();
    traitsLanded = filmMails.every((m) => facets.some((f) => f.key === 'email' && f.value === m));
    if (!traitsLanded) await new Promise((r) => setTimeout(r, 1500));
  }
  if (!traitsLanded) throw new Error('bridged film customers never landed in the trait store');
  console.log('· preflight: 3 bridged gold/low-churn customers with email traits landed');

  const ctx = await browser.newContext({
    viewport: { width: 1560, height: 900 },
    recordVideo: { dir, size: { width: 1560, height: 900 } },
  });
  const page = await ctx.newPage();
  recT0 = Date.now();                       // Playwright video starts with the page

  /* ============ COLD OPEN — the Marketing desk ============ */
  await openConsole(page);
  const tabList = await page.evaluate(() => {
    const out = {};
    for (const g of document.querySelectorAll('#tabs .tabgroup')) {
      const label = g.querySelector('.tabgroup-label');
      out[label ? label.textContent : '(unlabeled)'] = [...g.querySelectorAll('.tab')].map((b) => b.textContent);
    }
    return out;
  });
  console.log('· console open as mkt (marketing-staff); desks:', JSON.stringify(tabList));
  seg('cold open');
  ACT = 'genalpha-bss · marketing desk';
  await narrate(page, 'A live telco BSS, logged in as a marketer — one desk: marketing. Campaigns, '
    + 'journeys, audiences, activation, attribution — the whole martech stack lives inside the BSS.');
  await captionOff(page);

  /* ============ EXECUTIVE ARCHITECTURE INTRO (~20s) ============ */
  seg('architecture intro');
  ACT = 'the architecture';
  await page.goto(ARCH);
  await page.waitForTimeout(900);           // let the first dots start moving
  await narrate(page, 'Every BSS already produces the data marketing pays to reconstruct. Orders, bills, '
    + 'loyalty, usage — every event lands on the bus, and the CDP fills itself. No export. No reverse ETL. '
    + 'The BSS is the CDP.', 2600);
  await captionOff(page);
  await openConsole(page);

  /* ============ ACT 1a — a campaign, by hand ============ */
  seg('campaign by hand');
  ACT = 'Act 1 · by hand';
  await glideClick(page, page.locator('.tab', { hasText: 'Campaigns' }));
  await page.waitForSelector('input[name="name"]', { timeout: 15000 });
  await narrate(page, 'First, by hand. A campaign is one form: name it, pick the trigger — every first '
    + 'order — and write the message.');
  await captionOff(page);

  const campName = `Welcome aboard ${run}`;
  await glideType(page, page.locator('input[name="name"]'), campName);
  await glideSelect(page, page.locator('select[name="triggerEventType"]'), 'ProductOrderCreateEvent');
  await glideType(page, page.locator('input[name="messageSubject"]'), 'Welcome to GenAlpha!');
  await glideType(page, page.locator('textarea[name="messageContent"]'),
    'Thanks for your first order — great to have you on board.');
  await glideClick(page, page.locator('#save'));
  const campRow = page.locator('#listing-body tr', { hasText: campName });
  await campRow.waitFor({ timeout: 20000 });
  await glideTo(page, campRow);
  console.log('· ACT1 campaign saved');
  await narrate(page, 'Saved, and active from this second. About thirty seconds of work.');
  await captionOff(page);

  /* ============ ACT 1b — a journey, by hand ============ */
  seg('journey by hand');
  await glideClick(page, page.locator('.tab', { hasText: 'Journeys' }));
  await page.waitForSelector('[data-testid="step-card"]', { timeout: 15000 });
  await narrate(page, 'A journey is the same idea, staged: a welcome, a two-day wait, a check-in. '
    + 'Cards, not code.');
  await captionOff(page);

  const jrnName = `Onboarding ${run}`;
  await glideType(page, page.locator('input[name="name"]'), jrnName);
  await glideType(page, page.locator('input[name="triggerEventType"]'), 'IndividualCreateEvent');
  const card1 = page.locator('[data-testid="step-card"]').nth(0);
  await glideType(page, card1.getByPlaceholder('e.g. Welcome'), 'Welcome');
  await glideType(page, card1.getByPlaceholder('Subject line — type {{ for a name'), 'Welcome to GenAlpha');
  await glideType(page, card1.getByPlaceholder('Message body — {{ inserts a name, {code} a promo code'),
    'Great to have you on board.');
  await glideClick(page, page.locator('[data-testid="add-step"]'));
  const card2 = page.locator('[data-testid="step-card"]').nth(1);
  await glideSelect(page, card2.locator('select.steptype'), 'wait');
  await glideType(page, card2.getByPlaceholder('Days'), '2');
  await glideClick(page, page.locator('[data-testid="add-step"]'));
  const card3 = page.locator('[data-testid="step-card"]').nth(2);
  await glideType(page, card3.getByPlaceholder('e.g. Welcome'), 'Check-in');
  await glideType(page, card3.getByPlaceholder('Subject line — type {{ for a name'), 'How is everything?');
  await glideType(page, card3.getByPlaceholder('Message body — {{ inserts a name, {code} a promo code'),
    'Two days in — need a hand?');
  await glideClick(page, page.locator('#save'));
  const jrnRow = page.locator('#listing-body tr', { hasText: jrnName });
  await jrnRow.waitFor({ timeout: 20000 });
  await glideTo(page, jrnRow);
  console.log('· ACT1 journey saved');
  await narrate(page, 'Live. Every new sign-up enrolls from now on.');
  await captionOff(page);

  /* ============ AUDIENCE BUILDER — a rule tree over BSS traits ============ */
  seg('audience builder');
  ACT = 'the CDP · audiences';
  await glideClick(page, page.locator('.tab', { hasText: 'Audience builder' }));
  await page.waitForSelector('[data-testid="audience-builder"]', { timeout: 15000 });
  await narrate(page, 'Now audiences: a rule tree over data the BSS already owns. Customers, loyalty '
    + 'tier gold, churn risk low. No CSV upload, no reverse ETL.');
  await captionOff(page);

  const audName = `Gold low-churn ${run}`;
  await glideType(page, page.locator('#audience-name'), audName);
  await glideSelect(page, page.locator('#audience-population'), 'customer');
  const cond1 = page.locator('[data-testid="aud-cond"]').nth(0);
  await glideSelect(page, cond1.locator('[data-testid="aud-cond-type"]'), 'trait');
  await glideSelect(page, cond1.locator('select[data-testid="aud-cond-key"]'), 'loyaltyTier');
  await glideSelect(page, cond1.locator('select[data-testid="aud-cond-value"]'), 'gold');
  await glideClick(page, page.locator('[data-testid="aud-add-cond"]'));
  const cond2 = page.locator('[data-testid="aud-cond"]').nth(1);
  await glideSelect(page, cond2.locator('[data-testid="aud-cond-type"]'), 'trait');
  await glideSelect(page, cond2.locator('select[data-testid="aud-cond-key"]'), 'churnRisk');
  await glideSelect(page, cond2.locator('select[data-testid="aud-cond-value"]'), 'low');
  await glideClick(page, page.locator('[data-testid="aud-save"]'));
  await page.waitForFunction((nm) => [...document.querySelectorAll('[data-testid="aud-row-name"]')]
    .some((e) => e.textContent === nm), audName, { timeout: 15000 });
  console.log('· audience saved:', audName);

  // Saved audiences: resolve the members LIVE (the count appears next to the row).
  const audRow = page.locator('[data-testid="aud-row"]', { hasText: audName });
  await glideTo(page, audRow);
  await glideClick(page, audRow.locator('[data-testid="aud-preview"]'));
  await page.waitForFunction((nm) => {
    const r = [...document.querySelectorAll('[data-testid="aud-row"]')]
      .find((x) => x.querySelector('[data-testid="aud-row-name"]')?.textContent === nm);
    return r && /member/.test(r.textContent);
  }, audName, { timeout: 15000 });
  const memberTxt = (await audRow.textContent()).match(/\d+ members?/)?.[0] || '';
  console.log('· live member preview:', memberTxt);
  await narrate(page, 'Saved — and resolved live against the trait store: '
    + (memberTxt || 'the members') + ', counted the moment I ask.');
  await captionOff(page);

  /* ============ ACTIVATION — hashed export to the ad platform ============ */
  // The console has no Activate button (activation is an API), so this shot is
  // a live-API readout: the real request, the real async job, the real hashes
  // the mock ad platform received — nothing staged.
  seg('activation (live API)');
  ACT = 'the CDP · activation';
  const audJson = (await (await apiCtx.request.get(AUDIENCE, { headers: H })).json()).find((a) => a.name === audName);
  const ext = `film-${run}`;
  await narrate(page, 'Activation pushes that audience out to the ad platforms — here, Meta, through '
    + 'the API. Every email is S H A two fifty-six hashed before it leaves; anyone without a contactable '
    + 'address, or on the do-not-contact ledger, is skipped; and the export runs as an async job.', 200,
  'Activation pushes that audience out to the ad platforms — here, Meta, through the API. Every email '
    + 'is SHA-256 hashed before it leaves; anyone without a contactable address, or on the '
    + 'do-not-contact ledger, is skipped; and the export runs as an async job.');
  const act = await (await apiCtx.request.post(`${AUDIENCE}/${audJson.id}/activate`,
    { headers: H, data: { externalAudienceId: ext, mode: 'seed', destination: 'meta' } })).json();
  await apiCard(page, `POST /insight/v1/audience/{id}/activate · "${audName}"`,
    JSON.stringify(act, null, 2).split('\n').map(esc));
  console.log('· activation queued:', act.jobId, act.status);
  await page.waitForTimeout(1600);

  let job = act;
  for (let i = 0; i < 40 && job.status !== 'done' && job.status !== 'error'; i++) {
    await page.waitForTimeout(500);
    job = await (await apiCtx.request.get(`${AUDIENCE}/activation/${act.jobId}`, { headers: H })).json();
  }
  const hashes = job.status === 'done'
    ? await (await apiCtx.request.get(`${SOCIAL}/v1/${ext}/users`, { headers: { Authorization: 'Bearer x' } })).json()
    : [];
  await apiCard(page, `GET /insight/v1/audience/activation/${act.jobId}`,
    JSON.stringify(job, null, 2).split('\n').map(esc)
      .concat(['', esc(`— the platform received (SHA-256, first ${Math.min(2, hashes.length)} of ${hashes.length}):`)])
      .concat(hashes.slice(0, 2).map((h) => esc('  ' + h.slice(0, 28) + '…'))));
  console.log(`· activation job ${job.status}: members ${job.members}, pushed ${job.pushed}, skipped ${job.skipped}; platform holds ${hashes.length} hash(es)`);
  await narrate(page, 'Queued, pushed, done. The platform only ever sees hashes — never a raw address.');
  await apiCardOff(page);
  await captionOff(page);

  /* ============ ATTRIBUTION — the honest portfolio ============ */
  seg('attribution');
  ACT = 'the CDP · attribution';
  await glideClick(page, page.locator('.tab', { hasText: 'Attribution' }));
  await page.waitForSelector('[data-testid="attribution"]', { timeout: 15000 });
  await page.waitForSelector('[data-testid="kpi-incremental"]', { timeout: 15000 });
  await page.waitForSelector('[data-testid="attribution-row"]', { timeout: 15000 });
  await glideTo(page, page.locator('[data-testid="kpi-incremental"]'));
  await narrate(page, 'Attribution is one honest readout: reach, lift, and incremental revenue — '
    + 'holdout-adjusted.');
  const noHold = page.locator('[data-testid="attribution-row"]', { hasText: 'no holdout' }).first();
  if (await noHold.count()) {
    await glideTo(page, noHold);
    await narrate(page, 'And where a program ran without a control group, the report refuses to invent a '
      + 'number. You cannot measure lift you never left room to see.');
  }
  await captionOff(page);

  /* ============ SOCIAL LISTENING — the mood strip ============ */
  seg('social listening');
  ACT = 'the CDP · social';
  await glideClick(page, page.locator('.tab', { hasText: 'Social listening' }));
  await page.waitForSelector('[data-testid="social-listening"]', { timeout: 15000 });
  await glideClick(page, page.locator('[data-testid="listening-sync"]'));
  await page.waitForFunction(() =>
    (document.querySelector('[data-testid="listening-summary"]')?.textContent || '').includes('Mentions:'),
  null, { timeout: 15000 });
  await page.waitForSelector('[data-testid="mention-row"]', { timeout: 15000 });
  await glideTo(page, page.locator('[data-testid="listening-summary"]'));
  await narrate(page, 'The stack listens, too. One sync pulls brand mentions in from social and scores '
    + 'the mood — positive, neutral, negative.');
  await captionOff(page);

  /* ============ SOCIAL CARE — a negative DM becomes a TMF621 ticket ============ */
  seg('social care');
  await glideClick(page, page.locator('.tab', { hasText: 'Social care' }));
  await page.waitForSelector('[data-testid="social-care"]', { timeout: 15000 });
  await glideClick(page, page.locator('[data-testid="care-sync"]'));
  await page.waitForFunction(() =>
    (document.querySelector('[data-testid="care-summary"]')?.textContent || '').includes('needs care'),
  null, { timeout: 15000 });
  await page.waitForSelector('[data-testid="care-row"]', { timeout: 15000 });
  // the ticket is opened over the bus — poll for it off camera while narrating
  const ticketPoll = (async () => {
    for (let i = 0; i < 30; i++) {
      const list = await (await apiCtx.request.get(TICKETS, { headers: H })).json();
      const arr = Array.isArray(list) ? list : (list.content || []);
      const hit = arr.find((t) => Array.isArray(t.relatedEntity) && t.relatedEntity.some((e) => e.id === angryDm));
      if (hit) return hit;
      await new Promise((r) => setTimeout(r, 800));
    }
    return null;
  })();
  const angryRow = page.locator('[data-testid="care-row"]', { hasText: 'down all day' }).first();
  if (await angryRow.count()) await glideTo(page, angryRow);
  await narrate(page, 'Direct messages get triaged the same way — and a negative DM does not rot in a '
    + 'feed. It becomes a real TMF six twenty-one trouble ticket, opened over the event bus.', 350,
  'Direct messages get triaged the same way — and a negative DM does not rot in a feed. It becomes a '
    + 'real TMF621 trouble ticket, opened over the event bus.');
  const ticket = await ticketPoll;
  if (ticket) {
    await apiCard(page, 'GET /tmf-api/troubleTicket/v4/troubleTicket', [
      esc(`id:         ${ticket.id}`),
      esc(`ticketType: ${ticket.ticketType}`),
      esc(`severity:   ${ticket.severity}`),
      esc(`name:       ${ticket.name || ''}`),
    ]);
    console.log('· TMF621 ticket opened:', ticket.id, ticket.severity);
    await narrate(page, 'There is the case — social care, severity major.');
    await apiCardOff(page);
  } else {
    console.log('· WARN: ticket not observed within 24s (bus lag) — narration beat skipped');
  }
  await captionOff(page);

  /* ============ ACT 2 — or just ask ============ */
  seg('marketing copilot');
  ACT = 'Act 2 · or just ask';
  await glideClick(page, page.locator('.tab', { hasText: 'Marketing copilot' }));
  await page.waitForSelector('#growth-copilot-input', { timeout: 15000 });
  await narrate(page, 'Or just ask. The marketing copilot is a real model on the live stack — and it '
    + 'can only propose. It never writes.');
  await captionOff(page);

  const ask = 'Create a winback campaign for gold-tier fibre customers in Oslo with a 10% offer';
  await glideType(page, page.locator('#growth-copilot-input'), ask);
  await glideClick(page, page.locator('#growth-copilot-send'));
  await narrate(page, 'It is thinking against real catalog and customer data — governed, and audited.');
  await captionOff(page);

  const answers = [
    'Yes — one message, a 10% discount on their next bill, warm tone.',
    'That is everything — go ahead and propose it.',
  ];
  const lastBubbleDone = () => page.waitForFunction(() => {
    const b = [...document.querySelectorAll('#growth-copilot-log .copilot-ai')].pop();
    return b && b.textContent && b.textContent !== '…' && b.textContent.length > 5;
  }, null, { timeout: 90000 });
  await lastBubbleDone();
  let clarified = false;
  for (const answer of answers) {
    if (await page.locator('[data-testid="growth-proposal"]').count()) break;
    if (!clarified) { clarified = true; await narrate(page, 'It asks before it assumes — so I give it the details.'); await captionOff(page); }
    else await page.waitForTimeout(1500);
    await glideType(page, page.locator('#growth-copilot-input'), answer);
    await glideClick(page, page.locator('#growth-copilot-send'));
    await lastBubbleDone();
  }
  const proposal = page.locator('[data-testid="growth-proposal"]').last();
  await proposal.waitFor({ timeout: 15000 });
  await proposal.scrollIntoViewIfNeeded();
  console.log('· ACT2 proposal:', (await proposal.textContent()).slice(0, 160).replace(/\s+/g, ' '));
  await narrate(page, 'A reviewable proposal: name, targeting, message. The human decides.');
  await captionOff(page);

  await glideClick(page, proposal.locator('[data-testid="growth-create"]'));
  await page.waitForFunction(() => {
    const b = [...document.querySelectorAll('[data-testid="growth-create"]')].pop();
    return b && /Created/.test(b.textContent);
  }, null, { timeout: 30000 });
  console.log('· ACT2 created');
  await narrate(page, 'One click — applied with the marketer\'s own permissions, logged in the AI '
    + 'audit ledger.');
  await captionOff(page);

  /* ============ CLOSING CARD ============ */
  seg('closing card');
  ACT = '';
  await page.evaluate(() => {
    const d = document.createElement('div'); d.id = 'cine-close';
    d.style.cssText = 'position:fixed;inset:0;z-index:2147483644;display:flex;flex-direction:column;'
      + 'align-items:center;justify-content:center;text-align:center;'
      + 'background:radial-gradient(circle at 50% 42%,#152220,#0F1514);'
      + 'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif';
    d.innerHTML = '<div style="font:700 15px sans-serif;letter-spacing:5px;color:#45AFAC;'
      + 'text-transform:uppercase;margin-bottom:22px">genalpha-bss</div>'
      + '<div style="font:700 44px/1.35 -apple-system,sans-serif;color:#eef7f6;max-width:1160px">'
      + 'Campaigns · journeys · audiences ·<br>activation · attribution</div>'
      + '<div style="font:500 25px -apple-system,sans-serif;color:#9fc4c1;margin-top:20px">'
      + 'one stack, native TM Forum APIs</div>';
    document.body.append(d);
  });
  await narrate(page, 'Campaigns. Journeys. Audiences. Activation. Attribution. One stack, native '
    + 'T M Forum APIs. The BSS is the CDP.', 1800,
  'Campaigns. Journeys. Audiences. Activation. Attribution. One stack, native TM Forum APIs. '
    + 'The BSS is the CDP.');

  /* ============ render (mux narration over the take) + cleanup ============ */
  await ctx.close();
  const webm = await page.video().path();
  const out = path.join(dir, 'martech.mp4');
  const inputs = []; const filters = [];
  clips.forEach((c, i) => {
    inputs.push('-i', c.file);
    const ms = Math.max(0, Math.round((REC_LEAD + c.off) * 1000));
    filters.push(`[${i + 1}]adelay=${ms}|${ms}[a${i}]`);
  });
  const fc = filters.join(';') + ';' + clips.map((_, i) => `[a${i}]`).join('')
    + `amix=inputs=${clips.length}:normalize=0:dropout_transition=0[aout]`;
  execFileSync(FFMPEG, ['-hide_banner', '-loglevel', 'error', '-y', '-i', webm, ...inputs,
    '-filter_complex', fc, '-map', '0:v', '-map', '[aout]',
    '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '22', '-pix_fmt', 'yuv420p',
    '-movflags', '+faststart', '-c:a', 'aac', '-shortest', out]);
  fs.rmSync(webm);
  for (const c of clips) { try { fs.unlinkSync(c.file); } catch { /* */ } }
  console.log('VIDEO:', out, `${(fs.statSync(out).size / 1e6).toFixed(1)} MB`);
  console.log('TIMELINE:'); for (const t of timeline) console.log(`  ${t.t.toFixed(1).padStart(6)}s  ${t.label}`);

  // Everything the take created gets retired (the copilot's campaign included).
  const sweep = async (url, before, label) => {
    const after = await (await apiCtx.request.get(url, { headers: H })).json();
    for (const x of after.filter((i) => !before.has(i.id))) {
      await apiCtx.request.delete(`${url}/${x.id}`, { headers: H });
      console.log(`· cleaned ${label}:`, x.name || x.id);
    }
  };
  await sweep(CAMPAIGN, beforeCampaigns, 'campaign');
  await sweep(JOURNEY, beforeJourneys, 'journey');
  await sweep(AUDIENCE, beforeAudiences, 'audience');
  await browser.close();
  console.log('DONE');
})().catch((e) => {
  console.error('FAIL:', e.message.split('\n')[0]);
  console.error(e.stack.split('\n').slice(1, 5).join('\n'));
  process.exit(1);
});
