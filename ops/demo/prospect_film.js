/* prospect_film.js — the personalized-film generator.
 *
 * Give it a prospect config (name, brand color, PUBLIC price list, assumed
 * mix) and it: mints a walled sandbox operator via prospectSimulation (their
 * shelf, a synthetic base, a billed quarter on the real engines), brands it,
 * then shoots a ~60-second Norwegian film of THEIR company running on this
 * platform — storefront in their colors, their tariffs, and the real bills
 * of the simulated quarter in the back office. Output: <id>-film.mp4.
 *
 *   node ops/demo/prospect_film.js path/to/prospect.json
 *
 * Config: { "id": "nordlys", "name": "Nordlys Bredbånd", "color": "#1B4F72",
 *   "currency": "NOK", "locale": "no",
 *   "priceList": [{ "offeringName": "Nordlys Fiber 500", "monthly": 449 }, …],
 *   "baseMix":  [{ "offeringName": "Nordlys Fiber 500", "subscribers": 6 }, …] }
 * Keep prospect configs OUT of the repository.
 */
const path = require('path');
const fs = require('fs');
const { chromium, request } = require(path.join(__dirname, '..', 'e2e', 'node_modules', 'playwright'));

const cfg = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const API = process.env.BSS_API || 'http://localhost:8080';
const KC = process.env.BSS_KC_HOST || 'http://localhost:8085';
const OUT = process.env.FILM_OUT || path.join(process.env.HOME, 'Downloads');

async function lens(page) {
  await page.addStyleTag({ content: `
    #cine-cursor{position:fixed;width:26px;height:26px;border-radius:50%;left:60%;top:60%;
      border:3px solid #ffb02e;background:rgba(255,176,46,.22);z-index:2147483647;pointer-events:none;
      transform:translate(-50%,-50%);transition:left .45s cubic-bezier(.45,0,.2,1),top .45s cubic-bezier(.45,0,.2,1);}
    #cine-caption{position:fixed;left:50%;bottom:30px;transform:translateX(-50%);
      background:rgba(12,18,24,.93);color:#eef2f6;padding:13px 26px;border-radius:14px;
      font:600 17px/1.45 -apple-system,sans-serif;z-index:2147483646;border:1px solid rgba(120,160,200,.45);
      max-width:74%;text-align:center;opacity:0;transition:opacity .35s;pointer-events:none}
    #cine-caption.on{opacity:1}` }).catch(() => {});
  await page.evaluate(() => {
    if (!document.getElementById('cine-cursor')) {
      const c = document.createElement('div'); c.id = 'cine-cursor'; document.body.append(c);
      const t = document.createElement('div'); t.id = 'cine-caption'; document.body.append(t);
    }
  }).catch(() => {});
}
async function cap(page, text, hold = 3600) {
  await lens(page);
  await page.evaluate((t) => { const el = document.getElementById('cine-caption'); if (el) { el.textContent = t; el.classList.add('on'); } }, text);
  await page.waitForTimeout(hold);
  await page.evaluate(() => document.getElementById('cine-caption')?.classList.remove('on')).catch(() => {});
}
async function click(page, loc) {
  await lens(page);
  const b = await loc.boundingBox().catch(() => null);
  if (b) {
    await page.evaluate(([x, y]) => { const c = document.getElementById('cine-cursor'); if (c) { c.style.left = x + 'px'; c.style.top = y + 'px'; } }, [b.x + b.width / 2, b.y + b.height / 2]);
    await page.waitForTimeout(500);
  }
  await loc.click(); await page.waitForTimeout(400);
}

(async () => {
  const ctx = await request.newContext();
  const host = (await (await ctx.post(`${KC}/realms/bss/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json()).access_token;
  const H = { Authorization: 'Bearer ' + host, 'Content-Type': 'application/json' };

  console.log(`· minting '${cfg.name}' as a sandbox (shelf + twins + a billed quarter — takes a few minutes)…`);
  const sim = await (await ctx.post(`${API}/onboarding/v1/prospectSimulation`,
    { headers: H, data: cfg, timeout: 600000 })).json();
  if (!sim.sandboxId) { console.error('simulation failed:', JSON.stringify(sim).slice(0, 300)); process.exit(1); }
  const ID = sim.sandboxId;
  console.log(`· sandbox '${ID}': shelf ${sim.shelf}, twins ${sim.twinBase}`);
  if (cfg.color) {
    await ctx.patch(`${API}/onboarding/v1/operator/${ID}`, { headers: H, data: { color: cfg.color } });
  }

  // wait for storefront routing + brand
  for (let i = 0; i < 30; i++) {
    const m = await (await ctx.get(`${API}/app/tenant-config.json?_=${Date.now()}`,
      { headers: { Host: `shop.${ID}.localhost` } })).json().catch(() => ({}));
    if (m.tenantId === ID && (!cfg.color || (m.brandColor || '').toLowerCase() === cfg.color.toLowerCase())) break;
    await new Promise((r) => setTimeout(r, 3000));
  }

  const b = await chromium.launch();
  const recDir = fs.mkdtempSync('/tmp/prospect-film-');
  const bctx = await b.newContext({ viewport: { width: 1600, height: 900 }, recordVideo: { dir: recDir, size: { width: 1600, height: 900 } } });
  const page = await bctx.newPage();

  /* 1 — their storefront */
  // a NEWBORN tenant's storefront needs the fleet to adopt it — reload-poll
  await page.goto(`http://shop.${ID}.localhost:8080/shop/`);
  let up = false;
  for (let i = 0; i < 15 && !up; i++) {
    up = await page.waitForSelector('.card', { timeout: 6000 }).then(() => true).catch(() => false);
    if (!up) { await page.waitForTimeout(4000); await page.reload(); }
  }
  if (!up) throw new Error('storefront never served the newborn tenant');
  const nei = page.locator('button', { hasText: /Nei takk|No thanks/ }).first();
  if (await nei.count()) await nei.click().catch(() => {});
  await cap(page, `🇳🇴  Dette er ${cfg.name} — som den kunne sett ut. Deres priser, deres farger. Bygget på 2 minutter, ingen mockup.`, 4600);
  await cap(page, '📶  Prislisten er deres egen — offentlige tariffer, lastet inn som katalogdata.', 3600);
  const firstCard = page.locator('.card').first();
  await click(page, firstCard);
  await page.waitForTimeout(1800);
  await cap(page, '🧾  Hvert produkt: pris, bestilling, Min side, familie, Vipps og EHF — alt følger med. Ingen frontend-prosjekt.', 4400);

  /* 2 — the back office: the REAL bills of the simulated quarter */
  await page.goto(`http://console.${ID}.localhost:8080/console/`);
  await page.waitForSelector('input[name="username"]', { timeout: 30000 });
  await lens(page);
  await page.fill('input[name="username"]', 'demo');
  await page.fill('input[name="password"]', 'demo');
  await click(page, page.locator('input[type="submit"], button[type="submit"]').first());
  await page.waitForSelector('.tab', { timeout: 40000 });
  await cap(page, '🏢  Og bak: hele driftskontoret. Vi kjørte allerede et KVARTAL for dere — på ekte motorer.', 3800);
  await click(page, page.locator('.tab', { hasText: 'Customer Bills' }).first());
  await page.waitForFunction(() => {
    const el = document.querySelector('#listing-body');
    return el && el.children.length > 0;
  }, { timeout: 15000 }).catch(() => {});
  await page.evaluate(() => {
    const el = document.querySelector('#listing-body');
    if (el) window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - 140, behavior: 'smooth' });
  });
  await page.waitForTimeout(1400);
  await cap(page, `💶  Ekte fakturaer fra det simulerte kvartalet — ${cfg.name} sine tariffer, fakturert av de samme motorene som kjører i produksjon.`, 5200);
  await cap(page, '✅  Vil dere se det med EKTE data? En migreringsprøve er gratis: avvikene listes ved navn FØR noe flyttes. Ingen selger ringer.', 5200);

  await bctx.close();
  const video = await page.video().path();
  const webm = path.join(recDir, 'film.webm');
  fs.copyFileSync(video, webm);
  await b.close();
  const { execFileSync } = require('child_process');
  const mp4 = path.join(OUT, `${ID}-film.mp4`);
  execFileSync('/opt/homebrew/bin/ffmpeg', ['-y', '-i', webm, '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '22', '-movflags', '+faststart', mp4], { stdio: 'ignore' });
  console.log('FILM:', mp4);
  console.log(`· sandbox '${ID}' left alive for the follow-up demo — delete its realm when done`);
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); process.exit(1); });
