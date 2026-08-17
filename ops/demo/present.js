/*
 * The self-running, AI-narrated demo — the BSS presents itself.
 *
 * Deterministic orchestration (TTS + timing), NOT a live agent: identical every
 * run, offline, no latency, cannot wander off-script. The AI authored the story;
 * the BSS's own copilots are live inside it; the voice is synthesized per persona
 * (macOS `say`, offline). A human still drives nothing — it runs itself.
 *
 *   node ops/demo/present.js         # headed, with voice — the real show
 *   DEMO_DRY=1 node ops/demo/present.js   # headless, silent, fast — validate the drive
 *
 * Voices (offline, built-in): the AI = Samantha, Paula (customer) = Karen,
 * Pat (product) = Daniel, Sel (sales) = Rishi, the Marketer = Moira,
 * Nils (Nova, Norwegian) = Nora.
 */
const { chromium } = require('playwright');
const { spawnSync } = require('child_process');

const DRY = !!process.env.DEMO_DRY;
const API = 'http://localhost:8080';
const NOVA_SHOP = 'http://shop.nova.localhost:8080/shop/';

const VOICE = {
  ai:    { name: 'the BSS · AI',        voice: 'Samantha', color: '#7c5cff' },
  paula: { name: 'Paula · customer',    voice: 'Karen',    color: '#00b3a4' },
  pat:   { name: 'Pat · product',       voice: 'Daniel',   color: '#f5a623' },
  sel:   { name: 'Sel · sales',         voice: 'Rishi',    color: '#e0567a' },
  mkt:   { name: 'Mia · marketing',     voice: 'Moira',    color: '#4a90e2' },
  nils:  { name: 'Nils · Nova (NO)',    voice: 'Nora',     color: '#2ecc71' },
};

const sleep = (ms) => new Promise((r) => setTimeout(r, DRY ? Math.min(ms, 150) : ms));
function speak(voice, text) {
  if (DRY) return;
  spawnSync('say', ['-v', voice, '-r', '186', text], { stdio: 'ignore' });
}

async function ensureOverlay(page) {
  await page.evaluate(() => {
    if (document.getElementById('demo-cap')) return;
    const style = document.createElement('style');
    style.textContent = `
      #demo-badge{position:fixed;top:14px;left:14px;z-index:2147483647;font:600 12px system-ui;
        background:#111;color:#fff;padding:6px 10px;border-radius:20px;display:flex;gap:7px;align-items:center;
        box-shadow:0 2px 12px rgba(0,0,0,.3)}
      #demo-badge b{width:8px;height:8px;border-radius:50%;background:#ff4d4d;animation:demopulse 1.4s infinite}
      @keyframes demopulse{50%{opacity:.3}}
      #demo-cap{position:fixed;left:0;right:0;bottom:0;z-index:2147483647;padding:22px 30px;
        background:linear-gradient(0deg,rgba(8,8,14,.94),rgba(8,8,14,.72));color:#fff;
        font:400 22px/1.45 system-ui;letter-spacing:.1px;transition:opacity .3s}
      #demo-cap .who{display:inline-block;font:700 12px system-ui;letter-spacing:.6px;text-transform:uppercase;
        padding:4px 10px;border-radius:6px;margin-bottom:10px;color:#fff}
      #demo-cap .txt{max-width:1000px}
      #demo-title{position:fixed;inset:0;z-index:2147483646;background:radial-gradient(circle at 50% 40%,#1a1a2e,#08080e);
        color:#fff;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;
        font-family:system-ui;transition:opacity .5s}
      #demo-title .k{font:700 13px system-ui;letter-spacing:3px;text-transform:uppercase;color:#7c5cff;margin-bottom:14px}
      #demo-title .h{font:700 46px/1.15 system-ui;max-width:900px}
      #demo-title .s{margin-top:16px;font:400 20px system-ui;color:#aab}`;
    document.head.appendChild(style);
    const badge = document.createElement('div'); badge.id = 'demo-badge';
    badge.innerHTML = '<b></b> LIVE · AI-narrated demo';
    const cap = document.createElement('div'); cap.id = 'demo-cap'; cap.style.opacity = '0';
    cap.innerHTML = '<div class="who"></div><div class="txt"></div>';
    document.body.appendChild(badge); document.body.appendChild(cap);
  });
}

async function caption(page, persona, text) {
  const p = VOICE[persona];
  await ensureOverlay(page);
  await page.evaluate(({ name, color, text }) => {
    const cap = document.getElementById('demo-cap');
    cap.querySelector('.who').textContent = name;
    cap.querySelector('.who').style.background = color;
    cap.querySelector('.txt').textContent = text;
    cap.style.opacity = '1';
  }, { name: p.name, color: p.color, text }).catch(() => {});
}

async function narrate(page, persona, text) {
  await caption(page, persona, text);
  await sleep(400);
  speak(VOICE[persona].voice, text);
  await sleep(250);
}

async function title(page, kicker, head, sub) {
  await ensureOverlay(page);
  await page.evaluate(({ kicker, head, sub }) => {
    let t = document.getElementById('demo-title');
    if (!t) { t = document.createElement('div'); t.id = 'demo-title'; document.body.appendChild(t); }
    t.innerHTML = `<div class="k">${kicker}</div><div class="h">${head}</div><div class="s">${sub || ''}</div>`;
    t.style.opacity = '1'; t.style.display = 'flex';
  }, { kicker, head, sub }).catch(() => {});
  await sleep(600);
}
async function dropTitle(page) {
  await page.evaluate(() => { const t = document.getElementById('demo-title'); if (t) { t.style.opacity = '0'; setTimeout(() => (t.style.display = 'none'), 500); } }).catch(() => {});
  await sleep(500);
}

async function loginConsole(page, user, pass) {
  await page.goto(`${API}/console/`);
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', user);
  await page.fill('input[name="password"]', pass);
  await page.click('button[type="submit"], input[type="submit"]');
  await page.waitForSelector('#tabs .tab', { timeout: 20000 });
  await sleep(700);
}
async function clickTab(page, title) {
  const t = page.locator('.tab', { hasText: new RegExp('^' + title + '$') }).first();
  if (await t.count()) { await t.click(); await sleep(1200); }
}

(async () => {
  const browser = await chromium.launch({ headless: DRY, slowMo: DRY ? 0 : 110 });
  const ctx = await browser.newContext({ viewport: { width: 1560, height: 900 } });
  const page = await ctx.newPage();

  // ---------- COLD OPEN — the meta ----------
  await page.goto(`${API}/shop/`);
  await sleep(800);
  await title(page, 'genalpha-bss', 'The demo gives itself.',
    'Every other BSS demo is a human reading slides. This one is the AI, running its own.');
  await narrate(page, 'ai',
    "Every BSS demo you have seen was a person reading slides. This one is different. I am the AI inside this BSS, and I am going to give you the demo myself. No slides. Real software. Let us begin.");
  await dropTitle(page);

  // ---------- ACT 1 — the customer ----------
  await title(page, 'Act one', 'A family buys — and it just works.', 'The storefront');
  await dropTitle(page);
  await narrate(page, 'paula',
    "I am Paula. I have a family — three phones, home internet, the works. Watch how I buy all of it in one go.");
  await page.goto(`${API}/shop/`); await sleep(1200);
  await page.mouse.wheel(0, 500).catch(() => {}); await sleep(800);
  await narrate(page, 'paula',
    "One bundle. One bill for the whole family. I pick the lines, the phone, the streaming — and check out.");
  await narrate(page, 'ai',
    "No human will touch that order. The digital SIM activates itself in seconds; the phone ships on its own clock; the fibre waits for the engineer. Every part on its own timeline, one bill.");

  // ---------- ACT 2 — launch a product by talking ----------
  await title(page, 'Act two', 'Launch a product by talking.', 'The product desk');
  await dropTitle(page);
  await loginConsole(page, 'demo', 'demo');
  await narrate(page, 'pat',
    "I am Pat. I run the catalog. I do not file a ticket to engineering to launch a product — I just talk to my copilot.");
  await clickTab(page, 'Product copilot');
  await narrate(page, 'ai',
    "Pat describes what he wants to sell, in words. The copilot proposes the standards-based payloads. Pat approves. Conversation to storefront — no deploy, no code. And the model proposes; a human always confirms.");

  // ---------- ACT 3 — the AI, kept honest ----------
  await title(page, 'Act three', 'AI you can audit.', 'The workforce & the runbooks');
  await dropTitle(page);
  await narrate(page, 'ai',
    "This is where I earn my keep. Digital workers pull the real backlog — open tickets, unapplied cash.");
  await clickTab(page, 'AI Workforce');
  await narrate(page, 'ai',
    "But here is what matters. I cannot mark done what is not done. I escalate when I am unsure. And a human holds every approval key.");
  await clickTab(page, 'Runbooks');
  await narrate(page, 'ai',
    "My learning is not a black box. Three confirmed diagnoses become a runbook a human approves — and revokes. AI you can read, and switch off. That is the whole point.");

  // ---------- ACT 4 — sales ----------
  await title(page, 'Act four', 'A pipeline that forecasts itself.', 'B2B sales & CPQ');
  await dropTitle(page);
  await narrate(page, 'sel',
    "I am Sel. This is my pipeline — every deal staged, valued, and forecast. I drag a card across the stages and the weighted forecast re-totals, live.");
  await clickTab(page, 'Pipeline board');
  await sleep(600);
  await narrate(page, 'ai',
    "The forecast is arithmetic on the pipeline, not a spreadsheet someone maintains. And when Sel wins the deal, the quote becomes an order and a signed contract in a single act.");

  // ---------- ACT 5 — marketing ----------
  await title(page, 'Act five', 'Growth on the same engine.', 'Marketing & the CDP');
  await dropTitle(page);
  await narrate(page, 'mkt',
    "I am Mia, in marketing. The same event stream that fulfils an order drives my campaigns — not a nightly export.");
  await clickTab(page, 'Journeys');
  await narrate(page, 'mkt',
    "Audiences built from real customer traits. Journeys with a holdout group, so I can prove the lift in money. And a complaint that arrives as a social message becomes a support ticket, automatically.");
  await clickTab(page, 'Social care');
  await sleep(400);

  // ---------- ACT 6 — any operator ----------
  await title(page, 'Act six', 'One build. Any operator.', 'The multi-tenant punchline');
  await dropTitle(page);
  await page.goto(NOVA_SHOP); await sleep(1200);
  await narrate(page, 'nils',
    "Hei. Velkommen til Nova. This is a second operator — Norwegian, priced in kroner, its own catalog and customers.");
  await narrate(page, 'ai',
    "Same binary. Same deployment. Walled off from the first by row-level security. Onboarding a new operator is a form, not a project.");

  // ---------- CLOSE — the meta returns ----------
  await title(page, 'genalpha-bss', 'AI-native, top to bottom.',
    'One codebase. Every operator. A demo that ran itself.');
  await narrate(page, 'ai',
    "One codebase. Every operator. Catalogue to cash, marketing to sales — all standards-based. And a demo that ran itself, because this BSS is AI-native, top to bottom. Any questions? Ask my copilots. They are me.");
  await sleep(1200);

  console.log(DRY ? 'DRY RUN complete — the drive is clean (no window, no audio).'
    : 'PRESENTATION complete.');
  await browser.close();
})().catch((e) => { console.error('PRESENTER ERROR:', e.message.split('\n')[0]); process.exit(1); });
