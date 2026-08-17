/*
 * The self-running, AI-narrated demo — the BSS presents itself.
 *
 * Deterministic orchestration (TTS + timing), NOT a live agent: identical every
 * run, offline, no latency, cannot wander off-script. The AI authored the story;
 * the BSS's own copilots are live inside it; the voice is synthesized per persona
 * (macOS `say`, offline). Each act runs as the REAL persona in its OWN browser
 * session, so only that role's desk is shown — the RBAC is part of the story.
 *
 * LIVE CONTROLS (click the active window first, so it has focus):
 *   SPACE = pause / resume — cuts the voice instantly; on resume re-speaks the
 *           line you were on, so you pick up where you left off.
 *   Q     = quit.
 *
 *   node ops/demo/present.js              # headed, with voice — the show (keyboard control)
 *   DEMO_VOICE=1 node ops/demo/present.js # + hands-free voice: say "pause" / "resume" / "stop"
 *   DEMO_DRY=1 node ops/demo/present.js   # headless, silent, fast — validate the drive
 *
 * Voice is opt-in and offline (whisper.cpp); the keyboard stays plan B and is
 * never affected if the mic/model is unavailable. See voice-control.js.
 */
const { chromium } = require('playwright');
const { spawn } = require('child_process');
const path = require('path');
const readline = require('readline');
const fs = require('fs');

// Persona portraits captured from the launch video (ops/demo-assets/personas/*.png,
// gitignored). Inlined as data-URIs so they render inside the injected overlay with
// no file-serving / CSP issues. Missing files degrade gracefully to a text chip.
const AVATARS = {};
for (const p of ['ai', 'kai', 'pat', 'sel', 'mia', 'nils']) {
  try { AVATARS[p] = 'data:image/png;base64,' + fs.readFileSync(path.join(__dirname, '..', 'demo-assets', 'personas', p + '.png')).toString('base64'); } catch { /* no avatar */ }
}

const DRY = !!process.env.DEMO_DRY;
const API = 'http://localhost:8080';
const SHOP = `${API}/shop/`;
const NOVA_SHOP = 'http://shop.nova.localhost:8080/shop/';

const VOICE = {
  ai:   { name: 'the BSS · AI',     voice: 'Samantha', color: '#7c5cff' },
  kai:  { name: 'Kai · customer',   voice: 'Karen',    color: '#00b3a4' },
  pat:  { name: 'Pat · product',    voice: 'Daniel',   color: '#f5a623' },
  sel:  { name: 'Sel · sales',      voice: 'Rishi',    color: '#e0567a' },
  mia:  { name: 'Mia · marketing',  voice: 'Moira',    color: '#4a90e2' },
  nils: { name: 'Nils · Nova (NO)', voice: 'Nora',     color: '#2ecc71' },
};

// ---------- live control state ----------
let paused = false, quit = false, currentSay = null, killedByCtl = false;
class Quit extends Error {}
function killAudio() { if (currentSay) { killedByCtl = true; try { currentSay.kill('SIGTERM'); } catch { /* gone */ } } }
function handleKey(k) {
  if (k === ' ' || k === 'Spacebar') { paused = !paused; if (paused) killAudio(); }
  else if (k === 'q' || k === 'Q') { quit = true; paused = false; killAudio(); }
}
function setPause(b) { if (b && !paused) { paused = true; killAudio(); } else if (!b && paused) { paused = false; } }
function speakAbortable(voice, text) {
  return new Promise((res) => {
    if (DRY) return res(true);
    killedByCtl = false;
    currentSay = spawn('say', ['-v', voice, '-r', '186', text]);
    currentSay.on('exit', () => { const k = killedByCtl; currentSay = null; res(!k); });
    currentSay.on('error', () => { currentSay = null; res(true); });
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, DRY ? Math.min(ms, 120) : ms));

async function setBadge(page, mode) {
  await page.evaluate((mode) => {
    const b = document.getElementById('demo-badge'); if (!b) return;
    if (mode === 'paused') { b.style.background = '#a11'; b.innerHTML = '⏸ PAUSED · press SPACE to resume'; }
    else { b.style.background = '#111'; b.innerHTML = '<b></b> LIVE · AI-narrated · SPACE pause · Q quit'; }
  }, mode).catch(() => {});
}
async function gate(page) {
  let shown = false;
  while (paused && !quit) { if (!shown) { await setBadge(page, 'paused'); shown = true; } await new Promise((r) => setTimeout(r, 120)); }
  if (shown) await setBadge(page, 'live');
  if (quit) throw new Quit();
}
async function ensureOverlay(page) {
  await page.evaluate(() => {
    if (document.getElementById('demo-cap')) return;
    const style = document.createElement('style');
    style.textContent = `
      #demo-badge{position:fixed;top:14px;left:14px;z-index:2147483647;font:600 12px system-ui;background:#111;color:#fff;
        padding:6px 10px;border-radius:20px;display:flex;gap:7px;align-items:center;box-shadow:0 2px 12px rgba(0,0,0,.3)}
      #demo-badge b{width:8px;height:8px;border-radius:50%;background:#ff4d4d;animation:demopulse 1.4s infinite}
      @keyframes demopulse{50%{opacity:.3}}
      #demo-cap{position:fixed;left:0;right:0;bottom:0;z-index:2147483647;padding:22px 30px;
        background:linear-gradient(0deg,rgba(8,8,14,.94),rgba(8,8,14,.72));color:#fff;font:400 22px/1.45 system-ui;transition:opacity .3s}
      #demo-cap .who{display:inline-block;font:700 12px system-ui;letter-spacing:.6px;text-transform:uppercase;padding:4px 10px;border-radius:6px;margin-bottom:8px;color:#fff}
      #demo-cap .txt{max-width:1040px}
      #demo-hero{position:fixed;left:48px;top:50%;transform:translate(-44px,-50%);z-index:2147483646;
        display:flex;flex-direction:column;align-items:center;gap:16px;opacity:0;pointer-events:none;transition:opacity .45s,transform .45s}
      #demo-hero.on{opacity:1;transform:translate(0,-50%)}
      #demo-hero img{width:260px;height:260px;border-radius:50%;object-fit:cover;border:5px solid #fff;box-shadow:0 12px 60px rgba(0,0,0,.65)}
      #demo-hero .hn{font:700 23px system-ui;color:#fff;background:rgba(8,8,14,.82);padding:10px 22px;border-radius:30px}
      #demo-title{position:fixed;inset:0;z-index:2147483646;background:radial-gradient(circle at 50% 40%,#1a1a2e,#08080e);color:#fff;
        display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;font-family:system-ui;transition:opacity .5s}
      #demo-title .k{font:700 13px system-ui;letter-spacing:3px;text-transform:uppercase;color:#7c5cff;margin-bottom:14px}
      #demo-title .h{font:700 46px/1.15 system-ui;max-width:920px}
      #demo-title .s{margin-top:16px;font:400 20px system-ui;color:#aab}`;
    document.head.appendChild(style);
    const badge = document.createElement('div'); badge.id = 'demo-badge';
    badge.innerHTML = '<b></b> LIVE · AI-narrated · SPACE pause · Q quit';
    const cap = document.createElement('div'); cap.id = 'demo-cap'; cap.style.opacity = '0';
    cap.innerHTML = '<div class="who"></div><div class="txt"></div>';
    const hero = document.createElement('div'); hero.id = 'demo-hero';
    hero.innerHTML = '<img alt=""><div class="hn"></div>';
    document.body.appendChild(badge); document.body.appendChild(cap); document.body.appendChild(hero);
  }).catch(() => {});
}
async function caption(page, persona, text) {
  const p = VOICE[persona];
  await ensureOverlay(page);
  await page.evaluate(({ name, color, text }) => {
    const cap = document.getElementById('demo-cap'); if (!cap) return;
    cap.querySelector('.who').textContent = name; cap.querySelector('.who').style.background = color;
    cap.querySelector('.txt').textContent = text; cap.style.opacity = '1';
  }, { name: p.name, color: p.color, text }).catch(() => {});
}
// The persona reveal: a large portrait slides in on the LEFT while they introduce
// themselves, then it clears so the software is unobstructed.
async function showHero(page, persona) {
  const p = VOICE[persona]; const ava = AVATARS[persona];
  if (!ava) return;
  await ensureOverlay(page);
  await page.evaluate(({ name, color, ava }) => {
    const h = document.getElementById('demo-hero'); if (!h) return;
    h.querySelector('img').src = ava; h.querySelector('img').style.borderColor = color;
    h.querySelector('.hn').textContent = name; h.classList.add('on');
  }, { name: p.name, color: p.color, ava }).catch(() => {});
}
async function hideHero(page) {
  await page.evaluate(() => { const h = document.getElementById('demo-hero'); if (h) h.classList.remove('on'); }).catch(() => {});
}
async function introduce(page, persona, text) {
  await showHero(page, persona); await sleep(450);
  await narrate(page, persona, text);
  await hideHero(page); await sleep(400);
}
async function narrate(page, persona, text) {
  await caption(page, persona, text);
  for (;;) {
    await gate(page);
    await sleep(300);
    const finished = await speakAbortable(VOICE[persona].voice, text);
    if (finished) break;
  }
  await sleep(200);
}
async function title(page, kicker, head, sub) {
  await ensureOverlay(page); await gate(page);
  await page.evaluate(({ kicker, head, sub }) => {
    let t = document.getElementById('demo-title');
    if (!t) { t = document.createElement('div'); t.id = 'demo-title'; document.body.appendChild(t); }
    t.innerHTML = `<div class="k">${kicker}</div><div class="h">${head}</div><div class="s">${sub || ''}</div>`;
    t.style.opacity = '1'; t.style.display = 'flex';
  }, { kicker, head, sub }).catch(() => {});
  await sleep(700);
}
async function dropTitle(page) {
  await page.evaluate(() => { const t = document.getElementById('demo-title'); if (t) { t.style.opacity = '0'; setTimeout(() => (t.style.display = 'none'), 500); } }).catch(() => {});
  await sleep(500);
}
const tryDo = async (fn) => { try { await fn(); } catch { /* the show goes on */ } };
async function reveal(page, sel) { try { await page.locator(sel).first().scrollIntoViewIfNeeded({ timeout: 2500 }); } catch { /* not present */ } }
async function scrollDown(page, px = 350) { await page.evaluate((y) => window.scrollBy({ top: y, behavior: 'smooth' }), px).catch(() => {}); await sleep(600); }

async function loginConsole(page, u, p) {
  for (let attempt = 1; attempt <= 2; attempt++) {          // one retry — logins can be briefly slow under load
    try {
      await page.goto(`${API}/console/`, { waitUntil: 'domcontentloaded' });
      await page.waitForSelector('input[name="username"]', { timeout: 30000 });
      await page.fill('input[name="username"]', u); await page.fill('input[name="password"]', p);
      await page.click('button[type="submit"], input[type="submit"]');
      await page.waitForSelector('#tabs .tab', { timeout: 30000 }); await sleep(700);
      return;
    } catch (e) { if (attempt === 2) throw e; await sleep(1500); }
  }
}
// Make the "create a product" act repeatable: delete any prior demo-created
// "Screen Plus" (offering + spec + price) so the copilot never hits "already
// exists". Best-effort; needs catalog:write (demo).
async function resetDemoProduct() {
  if (DRY) return;
  try {
    const tr = await fetch('http://localhost:8085/realms/bss/protocol/openid-connect/token', {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' }) });
    const H = { Authorization: 'Bearer ' + (await tr.json()).access_token };
    for (const res of ['productOffering', 'productSpecification', 'productOfferingPrice']) {
      const list = await (await fetch(`${API}/tmf-api/productCatalogManagement/v4/${res}?limit=200`, { headers: H })).json();
      for (const it of (Array.isArray(list) ? list : [])) {
        if (String(it.name || '').toLowerCase().includes('screen plus')) {
          await fetch(`${API}/tmf-api/productCatalogManagement/v4/${res}/${it.id}`, { method: 'DELETE', headers: H }).catch(() => {});
        }
      }
    }
  } catch { /* best-effort — Act 2 still narrates */ }
}
async function loginShop(page, url, u, p) {
  await page.goto(url);
  await page.waitForSelector('input[name="username"], #root, #app', { timeout: 20000 }).catch(() => {});
  if (await page.locator('input[name="username"]').count()) {
    await page.fill('input[name="username"]', u); await page.fill('input[name="password"]', p);
    await page.click('button[type="submit"], input[type="submit"]');
  }
  await page.waitForLoadState('networkidle').catch(() => {}); await sleep(1200);
}
async function clickTab(page, title) {
  const t = page.locator('.tab', { hasText: new RegExp('^' + title + '$') }).first();
  if (await t.count()) {
    await t.click(); await sleep(900);
    await reveal(page, '#list, #content, #main');
    await sleep(500);
  }
}
// A REAL copilot turn: type the ask, send, wait for the AI to reply. The copilot
// only PROPOSES (nothing is persisted until a human confirms), so it is safe to
// run every time. DRY skips it to keep validation side-effect-free.
async function copilotAsk(page, prefix, text) {
  if (DRY) return true;
  const input = page.locator(`#${prefix}-input`);
  if (!(await input.count())) return false;
  await input.click(); await input.fill('');
  await input.pressSequentially(text, { delay: 28 });   // visible typing
  await sleep(400);
  const before = ((await page.locator(`#${prefix}-log`).innerText().catch(() => '')) || '').length;
  await page.locator(`#${prefix}-send`).click();
  for (let i = 0; i < 50; i++) {                          // up to ~25s for the reply
    await gate(page);
    const now = ((await page.locator(`#${prefix}-log`).innerText().catch(() => '')) || '');
    if (now.length > before + 20) { await reveal(page, `#${prefix}-log`); await sleep(600); return true; }
    await sleep(500);
  }
  return false;
}
// A REAL re-stage: drag the first card of one stage column to another (the API
// PATCHes the deal, the forecast re-totals). Reversible — the caller drags back.
async function dragCard(page, fromStage, toStage) {
  if (DRY) return null;
  const card = page.locator(`[data-testid="pl-col-${fromStage}"] .pl-card`).first();
  if (!(await card.count())) return null;
  const id = await card.getAttribute('data-id');
  await page.evaluate(({ id, toStage }) => {
    const dt = new DataTransfer();
    const el = document.querySelector(`.pl-card[data-id="${id}"]`);
    const col = document.querySelector(`[data-stage="${toStage}"]`);
    if (!el || !col) return;
    el.dispatchEvent(new DragEvent('dragstart', { dataTransfer: dt, bubbles: true }));
    col.dispatchEvent(new DragEvent('dragover', { dataTransfer: dt, bubbles: true }));
    col.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true }));
  }, { id, toStage });
  await sleep(1600);
  return id;
}
// A REAL shopping run: browse the catalog, open a product, add it to the cart, and
// land on the cart — but STOP before payment, so no order is created and it repeats
// cleanly. Best-effort throughout; the narration carries if a control moves.
async function customerBuy(page) {
  if (DRY) return;
  await tryDo(async () => { await page.goto(SHOP); await page.waitForLoadState('networkidle'); await sleep(1200); });
  await tryDo(async () => { await page.locator('a.card[href*="/offering/"], a[href*="/offering/"]').first().click(); await sleep(1600); await scrollDown(page, 220); });
  await tryDo(async () => { await page.locator('button:has-text("Add to cart"), button:has-text("Add to bag")').first().click(); await sleep(1400); });
  await tryDo(async () => { await page.locator('a[href="/cart"], a[href*="/cart"], a:has-text("Cart")').first().click(); await sleep(1600); await scrollDown(page, 150); });
}

(async () => {
  const browser = await chromium.launch({ headless: DRY, slowMo: DRY ? 0 : 110 });
  const vp = { viewport: { width: 1560, height: 900 } };
  const keyInit = () => window.addEventListener('keydown', (e) => {
    if ([' ', 'Spacebar', 'q', 'Q'].includes(e.key)) { e.preventDefault(); if (window.__demoKey) window.__demoKey(e.key); }
  }, true);

  // A fresh context per persona = independent SSO session (no wrong-persona guard),
  // so each role shows ONLY its own desk.
  async function newCtx() {
    const ctx = await browser.newContext(vp);
    await ctx.exposeBinding('__demoKey', (_s, k) => handleKey(k));
    await ctx.addInitScript(keyInit);
    return ctx;
  }
  const desks = {};
  async function desk(persona, u, p) {           // logged-in console page for a staff persona
    if (!desks[persona]) { const page = await (await newCtx()).newPage(); await loginConsole(page, u, p); desks[persona] = page; }
    await desks[persona].bringToFront();
    return desks[persona];
  }

  // customer + Nova shop sessions
  const sPage = await (await newCtx()).newPage();

  // Plan A: voice control (opt-in). Keyboard (plan B) is always live and untouched.
  let voiceProc = null;
  if (process.env.DEMO_VOICE && !DRY) {
    voiceProc = spawn('node', [path.join(__dirname, 'voice-control.js')], { stdio: ['ignore', 'pipe', 'pipe'] });
    readline.createInterface({ input: voiceProc.stdout }).on('line', (l) => {
      const c = l.trim();
      if (c === 'pause') setPause(true);
      else if (c === 'resume') setPause(false);
      else if (c === 'quit') { quit = true; paused = false; killAudio(); }
    });
    voiceProc.stderr.on('data', (d) => process.stderr.write(String(d)));
  }

  try {
    await resetDemoProduct();   // clear any prior "Screen Plus" so Act 2's create is fresh
    // ---------- COLD OPEN ----------
    await loginShop(sPage, SHOP, 'kai@bss.local', 'kai'); await sPage.bringToFront();
    await title(sPage, 'genalpha-bss', 'The demo gives itself.',
      'Every other BSS demo is a human reading slides. This one is the AI, running its own.');
    await introduce(sPage, 'ai',
      "Every BSS demo you have seen was a person reading slides. This one is different. I am the AI inside this BSS, and I am going to give you the demo myself. No slides. Real software.");
    await dropTitle(sPage);

    // ---------- ACT 1 — the customer ----------
    await title(sPage, 'Act one', 'A customer shops — and it just works.', 'The storefront · signed in as a customer');
    await dropTitle(sPage);
    await introduce(sPage, 'kai', "I am Kai. This is my storefront — my plans, my devices, all in one place.");
    await narrate(sPage, 'kai', "Watch me shop. I browse the plans, open one, and add it to my cart.");
    await customerBuy(sPage);
    await narrate(sPage, 'kai', "There — it is in my cart, ready to check out. And when I buy, the digital SIM activates itself in seconds. No human, no wait.");
    await narrate(sPage, 'ai',
      "Every part fulfils on its own clock — the SIM instant, the phone shipped, the fibre booked for an engineer. One order, many timelines, one bill.");

    // ---------- ACT 2 — Pat's product desk (only his tabs) ----------
    const patPage = await desk('pat', 'pat@bss.local', 'pat');
    await title(patPage, 'Act two', 'Products are data — and bundles stay soft.', 'Signed in as Pat — only the product desk shows');
    await dropTitle(patPage);
    await introduce(patPage, 'pat',
      "I am Pat, in product. Notice my console — I only see the catalog. I do not file a ticket to launch a product; I just talk to my copilot.");
    await clickTab(patPage, 'Product copilot');
    await narrate(patPage, 'pat', "Watch. I want a new streaming add-on — I just tell it, in words.");
    await tryDo(() => copilotAsk(patPage, 'copilot', 'Create a streaming TV add-on called Screen Plus for 9.99 per month'));
    await narrate(patPage, 'ai',
      "It proposed the standards-based payloads — a specification, a price, an offering, the category. Pat did not write a line of code. He just approves.");
    await tryDo(async () => { await patPage.locator('[data-testid="copilot-create"]').first().click(); await sleep(2800); await reveal(patPage, '[data-testid="copilot-log"]'); });
    await narrate(patPage, 'ai',
      "Created — and live in the storefront this instant. No deploy, no JSON. A sentence became a sellable product.");
    await clickTab(patPage, 'Product Offerings');
    await narrate(patPage, 'pat',
      "And my bundles are soft. A customer upgrades the internet and downgrades the TV in place — no re-contract. The bundle decomposes into parts that each fulfil, and change, on their own.");
    await narrate(patPage, 'ai',
      "Here is the line operators lean in for. When Pat changes a price, he changes ONE row — not a migration project that forklifts every customer off the old plan. The customers on it simply see the new price. No mass migration, ever.");

    // ---------- ACT 3 — the AI, kept honest (operator / whole-floor view) ----------
    const opPage = await desk('demo', 'demo', 'demo');
    await title(opPage, 'Act three', 'AI you can audit.', 'The operator view — the AI works across the whole floor');
    await dropTitle(opPage);
    await introduce(opPage, 'ai', "This is my view — the whole operation. Digital workers pull the real backlog: open tickets, unapplied cash.");
    await clickTab(opPage, 'AI Workforce'); await scrollDown(opPage, 320);
    await narrate(opPage, 'ai', "But I cannot mark done what is not done. I escalate when I am unsure. And a human holds every approval key.");
    await clickTab(opPage, 'Runbooks'); await scrollDown(opPage, 320);
    await narrate(opPage, 'ai', "My learning is not a black box. Three confirmed diagnoses become a runbook a human approves — and can revoke. AI you can read, and switch off.");

    // ---------- ACT 4 — Sel's sales desk (only sales) ----------
    const selPage = await desk('sel', 'sel@bss.local', 'sel');
    await title(selPage, 'Act four', 'A pipeline that forecasts itself.', 'Signed in as Sel — the sales desk');
    await dropTitle(selPage);
    await introduce(selPage, 'sel', "I am Sel. My desk is sales, nothing else. Every deal staged, valued, forecast. I drag a card across the stages and the weighted forecast re-totals, live.");
    await clickTab(selPage, 'Pipeline board');
    await reveal(selPage, '[data-testid="pipeline-board"]'); await sleep(400);
    await narrate(selPage, 'sel', "Watch me move a live deal — from Proposal across to Negotiation.");
    const movedId = await dragCard(selPage, 'proposal', 'negotiation');
    await narrate(selPage, 'ai', "The stage moved, the win-probability rode up, and the weighted forecast re-totalled — live, and the rest of the BSS agrees. When Sel wins, that quote becomes an order and a signed contract in one act.");
    if (movedId) await tryDo(() => dragCard(selPage, 'negotiation', 'proposal'));   // revert — keep the board repeatable

    // ---------- ACT 5 — Mia's marketing desk: AUTHOR it, then the CUSTOMER RECEIVES it ----------
    const mktPage = await desk('mkt', 'mkt@bss.local', 'mkt');
    await title(mktPage, 'Act five', 'Marketing: authored here, received there.', 'Signed in as Mia — the marketing desk');
    await dropTitle(mktPage);
    await introduce(mktPage, 'mia', "I am Mia, in marketing — again, just my desk. I do not hand-build a campaign; I describe it.");
    await clickTab(mktPage, 'Marketing copilot');
    await tryDo(() => copilotAsk(mktPage, 'growth-copilot', 'Welcome new customers when they sign up, and include a 10 percent code'));
    await narrate(mktPage, 'mia', "It proposes a whole journey — the steps, the timing, the copy, even a holdout group to prove the lift — and creates it only when I confirm. It proposes; I decide.");
    await narrate(mktPage, 'mia', "Here is a journey, drawn as a canvas: a message, then a wait, then a branch.");
    await clickTab(mktPage, 'Journeys');
    await tryDo(async () => { await mktPage.locator('#list tbody tr, .row').first().click(); await sleep(1300); });
    await tryDo(async () => { await mktPage.waitForSelector('[data-testid="journey-canvas"]', { timeout: 4000 }); await reveal(mktPage, '[data-testid="journey-canvas"]'); });
    await narrate(mktPage, 'ai', "Same journey object the API serves, drawn as a graph — with how many customers each step has reached. Data, not code.");

    // ---- the payoff: what the CUSTOMER sees ----
    await sPage.bringToFront();
    await title(sPage, 'Act five', 'And here is what the customer sees.', "Kai's app · notifications");
    await dropTitle(sPage);
    await tryDo(async () => { await sPage.locator('a:has-text("Notifications"), [href*="notification"]').first().click(); await sleep(1400); await scrollDown(sPage, 200); });
    await narrate(sPage, 'kai',
      "And on my side — here they are. The messages land right in my app. A welcome, an offer with my name on it, a nudge when my data runs low. That is the campaign Mia just built, reaching me.");
    await narrate(sPage, 'ai',
      "Authored in the back office, delivered on the event bus, received in the customer's own app — the whole loop, one platform.");

    // ---------- ACT 6 — any operator ----------
    const novaPage = await (await newCtx()).newPage(); await novaPage.bringToFront();
    await title(novaPage, 'Act six', 'One build. Any operator.', 'The multi-tenant punchline');
    await dropTitle(novaPage);
    await tryDo(async () => { await loginShop(novaPage, NOVA_SHOP, 'nils@nova.local', 'nils'); });
    await introduce(novaPage, 'nils', "Hei. Velkommen til Nova. A second operator — Norwegian, priced in kroner, its own catalog and customers.");
    await narrate(novaPage, 'ai', "Same binary. Same deployment. Walled off by row-level security. Onboarding a new operator is a form, not a project.");

    // ---------- CLOSE ----------
    await title(novaPage, 'genalpha-bss', 'AI-native, top to bottom.', 'One codebase. Every operator. A demo that ran itself.');
    await narrate(novaPage, 'ai',
      "One codebase. Every operator. Catalogue to cash, marketing to sales — all standards-based. And a demo that ran itself, because this BSS is AI-native, top to bottom. Any questions? Ask my copilots. They are me.");
    await sleep(1000);
    console.log(DRY ? 'DRY RUN complete — the drive is clean (per-persona sessions, no window, no audio).' : 'PRESENTATION complete.');
  } catch (e) {
    if (e instanceof Quit) console.log('Stopped (Q).');
    else throw e;
  } finally {
    if (voiceProc) { try { voiceProc.kill(); } catch { /* gone */ } }
    await browser.close();
  }
})().catch((e) => { console.error('PRESENTER ERROR:', e.message.split('\n')[0]); process.exit(1); });
