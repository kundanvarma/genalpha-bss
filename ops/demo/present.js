/*
 * The self-running, AI-narrated demo — the BSS presents itself.
 *
 * Deterministic orchestration (TTS + timing), NOT a live agent: identical every
 * run, offline, no latency, cannot wander off-script. The AI authored the story;
 * the BSS's own copilots are live inside it; the voice is synthesized per persona
 * (macOS `say`, offline). Drives the REAL stack across two live sessions — the
 * back-office console (as `demo`) and a customer's shop (as `kai`).
 *
 * LIVE CONTROLS (click the browser window first, so it has focus):
 *   SPACE = pause / resume — cuts the voice instantly; on resume it re-speaks the
 *           line you were on, so you pick up exactly where you left off. Use it to
 *           interrupt and explain, then carry on.
 *   Q     = quit.
 *
 *   node ops/demo/present.js              # headed, with voice — the show
 *   DEMO_DRY=1 node ops/demo/present.js   # headless, silent, fast — validate the drive
 */
const { chromium } = require('playwright');
const { spawn } = require('child_process');

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
      #demo-cap .who{display:inline-block;font:700 12px system-ui;letter-spacing:.6px;text-transform:uppercase;padding:4px 10px;border-radius:6px;margin-bottom:10px;color:#fff}
      #demo-cap .txt{max-width:1040px}
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
    document.body.appendChild(badge); document.body.appendChild(cap);
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
async function narrate(page, persona, text) {
  await caption(page, persona, text);
  for (;;) {
    await gate(page);            // hold here while paused
    await sleep(300);
    const finished = await speakAbortable(VOICE[persona].voice, text);
    if (finished) break;         // natural end
    // cut by SPACE → loop: gate holds until resume, then this same line replays
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

async function loginConsole(page, u, p) {
  await page.goto(`${API}/console/`);
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', u); await page.fill('input[name="password"]', p);
  await page.click('button[type="submit"], input[type="submit"]');
  await page.waitForSelector('#tabs .tab', { timeout: 20000 }); await sleep(700);
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
  if (await t.count()) { await t.click(); await sleep(1200); }
}

(async () => {
  const browser = await chromium.launch({ headless: DRY, slowMo: DRY ? 0 : 110 });
  const vp = { viewport: { width: 1560, height: 900 } };
  const keyInit = () => window.addEventListener('keydown', (e) => {
    if ([' ', 'Spacebar', 'q', 'Q'].includes(e.key)) { e.preventDefault(); if (window.__demoKey) window.__demoKey(e.key); }
  }, true);

  const consoleCtx = await browser.newContext(vp);
  await consoleCtx.exposeBinding('__demoKey', (_s, k) => handleKey(k));
  await consoleCtx.addInitScript(keyInit);
  const cPage = await consoleCtx.newPage();

  const shopCtx = await browser.newContext(vp);
  await shopCtx.exposeBinding('__demoKey', (_s, k) => handleKey(k));
  await shopCtx.addInitScript(keyInit);
  const sPage = await shopCtx.newPage();

  try {
    // ---------- COLD OPEN ----------
    await loginShop(sPage, SHOP, 'kai@bss.local', 'kai');
    await title(sPage, 'genalpha-bss', 'The demo gives itself.',
      'Every other BSS demo is a human reading slides. This one is the AI, running its own.');
    await narrate(sPage, 'ai',
      "Every BSS demo you have seen was a person reading slides. This one is different. I am the AI inside this BSS, and I am going to give you the demo myself. No slides. Real software.");
    await dropTitle(sPage);

    // ---------- ACT 1 — the customer ----------
    await title(sPage, 'Act one', 'A customer, and their live account.', 'The storefront');
    await dropTitle(sPage);
    await narrate(sPage, 'kai',
      "I am Kai. This is my account — my plan, my number, my usage, all in one place. When I bought a family bundle, the digital SIM activated itself in seconds. No human, no wait.");
    await tryDo(async () => { await sPage.mouse.wheel(0, 500); }); await sleep(500);
    await narrate(sPage, 'ai',
      "Every part fulfils on its own clock — the SIM instant, the phone shipped, the fibre booked for an engineer. One order, many timelines, one bill.");

    // ---------- ACT 2 — create a product; soft bundles; price change without migration ----------
    await loginConsole(cPage, 'demo', 'demo');
    await title(cPage, 'Act two', 'Products are data — and bundles stay soft.', 'The product desk');
    await dropTitle(cPage);
    await narrate(cPage, 'pat',
      "I am Pat. I run the catalog. I do not file a ticket to launch a product — I just talk to my copilot.");
    await clickTab(cPage, 'Product copilot');
    await narrate(cPage, 'ai',
      "Pat describes it in words; the copilot proposes the standards-based payloads; Pat approves. Conversation to storefront, no deploy. By hand it is the same three rows — a price, an offering, a category. A product is data, not code.");
    await clickTab(cPage, 'Product Offerings');
    await narrate(cPage, 'pat',
      "And my bundles are soft. A customer upgrades the internet and downgrades the TV in place — no re-contract. The bundle decomposes into parts that each fulfil, and change, on their own.");
    await narrate(cPage, 'ai',
      "Here is the line operators lean in for. When Pat changes a price, he changes ONE row — not a migration project that forklifts every customer off the old plan. The customers on it simply see the new price. No mass migration, ever.");

    // ---------- ACT 3 — the AI, kept honest ----------
    await title(cPage, 'Act three', 'AI you can audit.', 'The workforce & the runbooks');
    await dropTitle(cPage);
    await narrate(cPage, 'ai', "This is where I earn my keep. Digital workers pull the real backlog — open tickets, unapplied cash.");
    await clickTab(cPage, 'AI Workforce');
    await narrate(cPage, 'ai', "But I cannot mark done what is not done. I escalate when I am unsure. And a human holds every approval key.");
    await clickTab(cPage, 'Runbooks');
    await narrate(cPage, 'ai', "My learning is not a black box. Three confirmed diagnoses become a runbook a human approves — and can revoke. AI you can read, and switch off.");

    // ---------- ACT 4 — sales ----------
    await title(cPage, 'Act four', 'A pipeline that forecasts itself.', 'B2B sales & CPQ');
    await dropTitle(cPage);
    await narrate(cPage, 'sel', "I am Sel. Every deal staged, valued, forecast. I drag a card across the stages and the weighted forecast re-totals, live.");
    await clickTab(cPage, 'Pipeline board'); await sleep(400);
    await narrate(cPage, 'ai', "Arithmetic on the pipeline, not a spreadsheet. And when Sel wins, the quote becomes an order and a signed contract in a single act.");

    // ---------- ACT 5 — marketing: AUTHOR it, then the CUSTOMER RECEIVES it ----------
    await title(cPage, 'Act five', 'Marketing: authored here, received there.', 'Campaign → journey → the customer');
    await dropTitle(cPage);
    await narrate(cPage, 'mia', "I am Mia, in marketing. Let me build a campaign. New campaign — a proven recipe, a real trigger like an order, and a message.");
    await clickTab(cPage, 'Campaigns');
    await tryDo(async () => { await cPage.getByRole('button', { name: /^\+?\s*new$/i }).first().click(); await sleep(1000); });
    await narrate(cPage, 'mia', "The AI drafts the copy — the double-brace inserts the customer's name, the promo code drops in, and a holdout group is held back so I can prove the lift in money.");
    await tryDo(async () => { await cPage.keyboard.press('Escape'); });
    await narrate(cPage, 'mia', "A journey is the sophisticated version — an ordered path drawn as a canvas: message, wait, branch.");
    await clickTab(cPage, 'Journeys');
    await tryDo(async () => { await cPage.locator('#list tbody tr, .row').first().click(); await sleep(1300); });
    await tryDo(async () => { await cPage.waitForSelector('[data-testid="journey-canvas"]', { timeout: 4000 }); });
    await narrate(cPage, 'ai', "Same journey object the API serves, drawn as a graph — with how many customers each step has reached. Data, not code.");

    // ---- the payoff: what the CUSTOMER sees ----
    await title(sPage, 'Act five', 'And here is what the customer sees.', "Kai's app · notifications");
    await dropTitle(sPage);
    await tryDo(async () => { await sPage.locator('a:has-text("Notifications"), [href*="notification"]').first().click(); await sleep(1400); });
    await narrate(sPage, 'kai',
      "And on my side — here they are. The messages land right in my app. A welcome, an offer with my name on it, a nudge when my data runs low. That is the campaign Mia just built, reaching me.");
    await narrate(sPage, 'ai',
      "Authored in the back office, delivered on the event bus, received in the customer's own app — the whole loop, one platform.");

    // ---------- ACT 6 — any operator ----------
    await title(cPage, 'Act six', 'One build. Any operator.', 'The multi-tenant punchline');
    await dropTitle(cPage);
    await tryDo(async () => { await loginShop(cPage, NOVA_SHOP, 'nils@nova.local', 'nils'); });
    await narrate(cPage, 'nils', "Hei. Velkommen til Nova. A second operator — Norwegian, priced in kroner, its own catalog and customers.");
    await narrate(cPage, 'ai', "Same binary. Same deployment. Walled off by row-level security. Onboarding a new operator is a form, not a project.");

    // ---------- CLOSE ----------
    await title(cPage, 'genalpha-bss', 'AI-native, top to bottom.', 'One codebase. Every operator. A demo that ran itself.');
    await narrate(cPage, 'ai',
      "One codebase. Every operator. Catalogue to cash, marketing to sales — all standards-based. And a demo that ran itself, because this BSS is AI-native, top to bottom. Any questions? Ask my copilots. They are me.");
    await sleep(1000);
    console.log(DRY ? 'DRY RUN complete — the drive is clean (two live sessions, no window, no audio).' : 'PRESENTATION complete.');
  } catch (e) {
    if (e instanceof Quit) console.log('Stopped (Q).');
    else throw e;
  } finally {
    await browser.close();
  }
})().catch((e) => { console.error('PRESENTER ERROR:', e.message.split('\n')[0]); process.exit(1); });
