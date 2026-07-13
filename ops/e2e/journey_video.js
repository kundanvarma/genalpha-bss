/* The journey film: one customer's story shot across the REAL screens —
 * storefront → back office → CSR → back to the customer → Live Flow.
 * A visible cursor glides and clicks, captions narrate, typing is human-paced.
 * Playwright records it all; the output lands in ops/e2e/recordings/journey.webm.
 * Everything on screen is a live system doing real work. */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const run = Date.now();
const EMAIL = `mia-${run}@example.com`;
const FAMILY = `Journey${run}`;

/* ---------- cinema helpers ---------- */
async function lens(page) {
  // Fake cursor + caption bar, injected into whatever app we're filming.
  await page.addStyleTag({ content: `
    #cine-cursor{position:fixed;width:26px;height:26px;border-radius:50%;left:60%;top:60%;
      border:3px solid #ffb02e;background:rgba(255,176,46,.22);z-index:2147483647;
      pointer-events:none;transform:translate(-50%,-50%);
      transition:left .45s cubic-bezier(.45,0,.2,1),top .45s cubic-bezier(.45,0,.2,1);
      box-shadow:0 0 14px rgba(255,176,46,.6);}
    #cine-cursor.click{animation:cineclick .32s ease}
    @keyframes cineclick{45%{transform:translate(-50%,-50%) scale(.55)}}
    #cine-caption{position:fixed;left:50%;bottom:30px;transform:translateX(-50%);
      background:rgba(10,18,22,.93);color:#eef4f4;padding:13px 26px;border-radius:14px;
      font:600 17px/1.45 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
      z-index:2147483646;border:1px solid rgba(69,175,172,.45);max-width:74%;text-align:center;
      box-shadow:0 14px 44px rgba(0,0,0,.5);opacity:0;transition:opacity .35s}
    #cine-caption.on{opacity:1}
  ` }).catch(() => {});
  await page.evaluate(() => {
    if (!document.getElementById('cine-cursor')) {
      const c = document.createElement('div'); c.id = 'cine-cursor'; document.body.append(c);
      const t = document.createElement('div'); t.id = 'cine-caption'; document.body.append(t);
    }
  }).catch(() => {});
}

async function caption(page, text, holdMs = 2600) {
  await lens(page);
  await page.evaluate((t) => {
    const el = document.getElementById('cine-caption');
    if (el) { el.textContent = t; el.classList.add('on'); }
  }, text);
  await page.waitForTimeout(holdMs);
}
async function captionOff(page) {
  await page.evaluate(() => document.getElementById('cine-caption')?.classList.remove('on')).catch(() => {});
}

/** Glide the visible cursor onto a locator, then click it. */
async function glideClick(page, locator, opts = {}) {
  await lens(page);
  const box = await locator.boundingBox();
  if (box) {
    const x = box.x + box.width / 2, y = box.y + box.height / 2;
    await page.evaluate(([px, py]) => {
      const c = document.getElementById('cine-cursor');
      if (c) { c.style.left = px + 'px'; c.style.top = py + 'px'; }
    }, [x, y]);
    await page.waitForTimeout(520);
    await page.evaluate(() => {
      const c = document.getElementById('cine-cursor');
      if (c) { c.classList.remove('click'); void c.offsetWidth; c.classList.add('click'); }
    });
  }
  await locator.click(opts);
  await page.waitForTimeout(320);
}

/** Human typing into a locator (cursor glides there first). */
async function glideType(page, locator, text) {
  await lens(page);
  const box = await locator.boundingBox();
  if (box) {
    await page.evaluate(([px, py]) => {
      const c = document.getElementById('cine-cursor');
      if (c) { c.style.left = px + 'px'; c.style.top = py + 'px'; }
    }, [box.x + box.width / 2, box.y + box.height / 2]);
    await page.waitForTimeout(380);
  }
  await locator.click();
  await locator.type(text, { delay: 42 });
}


/** KC without id_token_hint asks "do you want to log out?" — confirm it. */
async function confirmLogout(page) {
  await page.waitForTimeout(1200);
  const btn = page.locator('form[action*="logout"] input[type="submit"], form[action*="logout"] button, #kc-logout');
  if (await btn.count()) { await btn.first().click(); await page.waitForTimeout(1200); }
}

/* ---------- the film ---------- */
(async () => {
  const dir = path.join(__dirname, 'recordings');
  fs.mkdirSync(dir, { recursive: true });
  const browser = await chromium.launch({ headless: !process.env.HEADED, slowMo: process.env.HEADED ? 30 : 0 });
  const ctx = await browser.newContext({
    viewport: { width: 1600, height: 900 },
    recordVideo: { dir, size: { width: 1600, height: 900 } },
  });
  const page = await ctx.newPage();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

  // Pre-flight (off camera): top up the device shelf so the film is always
  // re-runnable — every take genuinely consumes stock.
  {
    const apiCtx = await browser.newContext();
    const tok = (await (await apiCtx.request.post(
      'http://localhost:8085/realms/bss/protocol/openid-connect/token',
      { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json()).access_token;
    const H = { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' };
    const stocks = await (await apiCtx.request.get(
      'http://localhost:8080/tmf-api/productStockManagement/v4/productStock?limit=100',
      { headers: H })).json();
    for (const st of stocks.filter((x) => /Samsung|iPhone/.test(x.name || ''))) {
      await apiCtx.request.patch(
        'http://localhost:8080/tmf-api/productStockManagement/v4/productStock/' + st.id,
        { headers: H, data: { stockedQuantity: { amount: 40, units: 'unit' } } });
    }
    // retire any pricing rules left behind by interrupted takes
    const oldRules = await (await apiCtx.request.get(
      'http://localhost:8080/tmf-api/policyManagement/v4/policyRule?limit=100',
      { headers: H })).json();
    for (const r of oldRules.filter((x) => (x.name || '').startsWith('Launch 15% off'))) {
      await apiCtx.request.delete(
        'http://localhost:8080/tmf-api/policyManagement/v4/policyRule/' + r.id, { headers: H });
    }
    await apiCtx.close();
    console.log('· preflight: shelf topped up');
  }

  /* ============ SCENE 1 — the customer shops ============ */
  await page.goto('http://localhost:8080/shop/');
  await page.waitForSelector('.card', { timeout: 20000 });
  console.log('· SCENE1-shop');
  await caption(page, '🛍  A customer lands on the shop — no account, just browsing.');
  await captionOff(page);
  await glideClick(page, page.locator('text=GenAlpha One Home & Mobile').first());
  await page.waitForSelector('.choice', { timeout: 20000 });
  await page.locator('.optprice').first().waitFor({ timeout: 15000 });
  await caption(page, '⚙️  They configure the bundle: pick a phone…');
  await captionOff(page);
  await glideClick(page, page.locator('label.option:has-text("Samsung Galaxy S26")'));
  await page.waitForTimeout(700);
  const colorSel = page.locator('.charfield', { hasText: 'color' }).locator('select');
  await colorSel.waitFor({ timeout: 10000 });
  await glideClick(page, colorSel);
  await colorSel.selectOption('Icy Blue');
  await page.locator('.charfield', { hasText: 'storage' }).locator('select').selectOption('512GB');
  await caption(page, '…Icy Blue, 512 GB — the price follows every choice.', 2200);
  await captionOff(page);
  const addon = page.locator('[data-testid^="extra-"]').first();
  if (await addon.count()) { await glideClick(page, addon); }
  await glideClick(page, page.locator('button.primary.big'));
  await page.waitForURL('**/cart', { timeout: 15000 });
  console.log('· SCENE1-cart');
  await caption(page, '🧺  Into the cart. Time to become a customer — it takes seconds.');
  await captionOff(page);

  // register mid-flow (the guest cart follows them in)
  await glideClick(page, page.locator('.who >> text=Sign in'));
  await page.waitForSelector('a[href*="registration"], input[name="username"]', { timeout: 20000 });
  await lens(page);
  await glideClick(page, page.locator('a[href*="registration"]'));
  await page.waitForSelector('input[name="email"]');
  await lens(page);
  await glideType(page, page.locator('input[name="firstName"]'), 'Mia');
  await glideType(page, page.locator('input[name="lastName"]'), FAMILY);
  await glideType(page, page.locator('input[name="email"]'), EMAIL);
  await page.fill('input[name="password"]', 'Passw0rd!');
  await page.fill('input[name="password-confirm"]', 'Passw0rd!');
  await glideClick(page, page.locator('input[type="submit"], button[type="submit"]').first());
  await page.waitForSelector('.nav', { timeout: 20000 });
  console.log('· SCENE1-registered');
  await page.goto('http://localhost:8080/shop/cart');
  await page.waitForSelector('.shipping', { timeout: 20000 });
  await caption(page, '📦  Address, install slot, card — checkout knows this bundle needs a technician.');
  await captionOff(page);
  await glideType(page, page.locator('.shipping input[name="street1"]'), 'Storgatan 1');
  await page.fill('.shipping input[name="postCode"]', '11122');
  await page.fill('.shipping input[name="city"]', 'Stockholm');
  await page.fill('.shipping input[name="country"]', 'SE');
  await page.locator('.serviceability.ok').waitFor({ timeout: 15000 });
  await page.locator('.slotgrid .option').first().waitFor({ timeout: 15000 });
  await glideClick(page, page.locator('.slotgrid .option').first());
  await glideType(page, page.locator('.payment input[name="cardNumber"]'), '4242 4242 4242 4242');
  await page.fill('.payment input[name="expiry"]', '12/28');
  await page.fill('.payment input[name="cvc"]', '123');
  await caption(page, '💳  One tap. From here on, the machine does the work.', 2200);
  await captionOff(page);
  await glideClick(page, page.locator('.cartactions button.primary.big'));
  await page.waitForURL('**/orders', { timeout: 20000 });
  await page.locator('.row', { hasText: 'GenAlpha One' }).waitFor({ timeout: 15000 });
  console.log('· SCENE1-ordered');
  await caption(page, '🕐  Order placed. Physical goods wait for fulfilment — let\'s visit the operator\'s side.');
  await captionOff(page);
  await glideClick(page, page.locator('button', { hasText: 'Sign out' }).first());
  await confirmLogout(page);

  /* ============ SCENE 2 — the back office ships a price rule ============ */
  await page.goto('http://localhost:8080/console/');
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await lens(page);
  await glideType(page, page.locator('input[name="username"]'), 'demo');
  await page.fill('input[name="password"]', 'demo');
  await glideClick(page, page.locator('input[type="submit"], button[type="submit"]').first());
  await page.waitForSelector('.tab', { timeout: 20000 });
  console.log('· SCENE2-console');

  // the catalog first: a complicated bundle is DATA a product owner clicks together
  await caption(page, '🏢  Meanwhile in the back office. First stop: the catalog.');
  await captionOff(page);
  let fmRow = page.locator('#listing-body tr', { hasText: 'GenAlpha Family Max' });
  if (!(await fmRow.count())) { await page.click('#next'); await page.waitForTimeout(800); }
  await fmRow.waitFor({ timeout: 15000 });
  await glideClick(page, fmRow.locator('button', { hasText: 'Edit' }));
  await page.waitForFunction(() => document.querySelectorAll('[data-composer-row]').length >= 5);
  await page.locator('[data-composer-row="choice"]').first().scrollIntoViewIfNeeded();
  await page.waitForTimeout(600);
  await caption(page, '🧩  Family Max: fixed fiber and TV, "pick 1–2 family lines", a phone with a default, optional extras, a 12-month term — assembled by clicking, no JSON.', 4200);
  await captionOff(page);
  await glideClick(page, page.locator('#save'));
  await page.waitForSelector('#editor-title:has-text("New")', { timeout: 10000 });
  console.log('· SCENE2-composer');
  await caption(page, '💾  Saved — losslessly. The storefront is already selling this exact structure.', 2600);
  await captionOff(page);

  await caption(page, '📣  Next: marketing wants a 15% launch discount.');
  await captionOff(page);
  await glideClick(page, page.locator('.tab', { hasText: 'Rules' }));
  await page.waitForSelector('select[name="ruleKind"]', { timeout: 10000 });
  await glideClick(page, page.locator('select[name="ruleKind"]'));
  await page.selectOption('select[name="ruleKind"]', 'price-always');
  await page.waitForTimeout(600);
  await glideType(page, page.locator('input[name="name"]'), `Launch 15% off ${run}`);
  await page.selectOption('select[name="adjustmentType"]', 'percent');
  await page.fill('input[name="adjustmentValue"]', '-15');
  await glideType(page, page.locator('input[name="message"]'), 'Launch offer (15% off)');
  await page.locator('input[name="enabled"]').check();
  await caption(page, '📜  A business rule, written as data. No release train, no deployment.', 2400);
  await captionOff(page);
  await glideClick(page, page.locator('#save'));
  await page.locator('#listing-body tr', { hasText: 'Launch 15% off' }).first().waitFor({ timeout: 10000 });
  await caption(page, '✅  Live. Every cart in the shop reprices from this second.', 2400);
  await captionOff(page);
  await glideClick(page, page.locator('#logout'));
  await confirmLogout(page);

  /* ============ SCENE 3 — the CSR fulfils the order ============ */
  await page.goto('http://localhost:8080/csr/');
  await page.waitForSelector('input[name="username"]', { timeout: 20000 }).catch(() => {});
  if (await page.locator('input[name="username"]').count()) {
    await lens(page);
    await glideType(page, page.locator('input[name="username"]'), 'agent-anna');
    await page.fill('input[name="password"]', 'agent');
    await glideClick(page, page.locator('input[type="submit"], button[type="submit"]').first());
  }
  await page.waitForSelector('.searchbar', { timeout: 20000 });
  console.log('· SCENE3-csr');
  await caption(page, '🎧  The CSR console. An agent picks up Mia\'s order for fulfilment.');
  await captionOff(page);
  await glideType(page, page.locator('.searchbar input'), FAMILY);
  await glideClick(page, page.locator('.searchbar button'));
  const hit = page.locator('.rowlink', { hasText: FAMILY });
  await hit.waitFor({ timeout: 15000 });
  await glideClick(page, hit);
  await page.locator('h1', { hasText: 'Mia' }).waitFor({ timeout: 15000 });
  await caption(page, '👤  The full 360: the order, the live cart, suggestions — and an AI copilot.');
  await captionOff(page);
  const completeBtn = page.locator('.row', { hasText: 'GenAlpha One' }).locator('button', { hasText: 'Complete' }).first();
  await completeBtn.waitFor({ timeout: 15000 });
  await glideClick(page, completeBtn);
  // Verify the order REALLY flipped to completed; retry the click if a
  // transient hiccup swallowed it (this is a film, but the work is real).
  let fulfilled = false;
  for (let i = 0; i < 6 && !fulfilled; i++) {
    await page.waitForTimeout(2500);
    await page.reload();
    await page.waitForSelector('h1', { timeout: 15000 });
    const st = await page.locator('.row', { hasText: 'GenAlpha One' }).locator('.state')
      .first().textContent().catch(() => '');
    if ((st || '').includes('completed')) { fulfilled = true; break; }
    const err = await page.locator('.error').first().textContent().catch(() => '');
    if (err) console.log('· CSR error:', err.slice(0, 140));
    const again = page.locator('.row', { hasText: 'GenAlpha One' }).locator('button', { hasText: 'Complete' }).first();
    if (await again.count()) await again.click().catch(() => {});
  }
  if (!fulfilled) throw new Error('order never completed in CSR');
  console.log('· SCENE3-order-completed');
  await lens(page);
  await caption(page, '🚀  Fulfilment is one click — activation is autonomous from here.', 2600);
  await captionOff(page);
  await glideClick(page, page.locator('button', { hasText: 'Sign out' }).first());
  await confirmLogout(page);

  /* ============ SCENE 4 — back to the customer ============ */
  await page.goto('http://localhost:8080/shop/');
  await page.waitForSelector('.card', { timeout: 20000 });
  await caption(page, '🌙  That evening, Mia checks back in…', 2000);
  await captionOff(page);
  await glideClick(page, page.locator('.who >> text=Sign in'));
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await lens(page);
  await glideType(page, page.locator('input[name="username"]'), EMAIL);
  await page.fill('input[name="password"]', 'Passw0rd!');
  await glideClick(page, page.locator('input[type="submit"], button[type="submit"]').first());
  await page.waitForSelector('.nav', { timeout: 20000 });
  console.log('· SCENE4-back-signed-in');
  await page.goto('http://localhost:8080/shop/services');
  let numberShown = false;
  for (let i = 0; i < 50 && !numberShown; i++) {
    await page.reload();
    // wait for the page's own fetch to land before judging
    // .first(): a multi-line customer renders one my-number PER LINE now
    numberShown = await page.locator('[data-testid="my-number"]').first().waitFor({ timeout: 4000 })
      .then(() => true).catch(() => false);
  }
  if (!numberShown) fail('service/number did not appear for the customer');
  await caption(page, '📱  Seconds later, on Mia\'s side: a live service — and her new number.');
  await captionOff(page);
  await caption(page, '🪪  Her page recomposes around what she owns — plan, lines, SIM, one place.', 2800);
  await captionOff(page);
  // SIM self-care: the PUK, revealed on request — no call to anyone
  await glideClick(page, page.locator('[data-testid=show-puk]').first());
  await page.locator('[data-testid=sim-puk]').first().waitFor({ timeout: 10000 });
  await caption(page, '🔐  Her SIM\'s PUK — self-served in one tap. At rest it\'s AES-256-GCM ciphertext, bound to her card.', 3200);
  await captionOff(page);
  // one-tap data top-up
  const topupBtn = page.locator('[data-testid^=topup-]').first();
  if (await topupBtn.count()) {
    await glideClick(page, topupBtn);
    await page.locator('[data-testid=topup-done]').waitFor({ timeout: 15000 });
    await caption(page, '➕  Need more data this month? One tap, 5 GB, a one-time charge.', 2600);
    await captionOff(page);
  }

  // VAS: a PARTNER service — sold like any offering, fulfilled differently
  await caption(page, '🎬  One more thing for movie night: Netflix, straight onto her GenAlpha bill.', 2600);
  await captionOff(page);
  await page.goto('http://localhost:8080/shop/');
  await page.waitForSelector('.card', { timeout: 20000 });
  await glideClick(page, page.locator('.card', { hasText: 'Netflix Standard' }).first());
  await page.locator('button.primary.big').waitFor({ timeout: 15000 });
  await glideClick(page, page.locator('button.primary.big'));
  await page.waitForURL('**/cart', { timeout: 15000 });
  await glideClick(page, page.locator('.cartactions button.primary.big'));
  await page.waitForURL('**/orders', { timeout: 20000 });
  console.log('· SCENE4-netflix-ordered');
  await page.goto('http://localhost:8080/shop/services');
  let vasShown = false;
  for (let i = 0; i < 20 && !vasShown; i++) {
    await page.reload();
    vasShown = await page.locator('[data-testid=activation-code]').first().waitFor({ timeout: 4000 })
      .then(() => true).catch(() => false);
  }
  if (!vasShown) fail('Netflix entitlement never reached My page');
  await page.locator('[data-testid=vas-card]').scrollIntoViewIfNeeded();
  await page.waitForTimeout(600);
  await caption(page, '🤝  No phone number for this one: the SOM activated an ENTITLEMENT with the partner — her code is ready.', 3600);
  await captionOff(page);
  await page.goto('http://localhost:8080/shop/notifications');
  await page.locator('.row', { hasText: 'Order' }).first().waitFor({ timeout: 20000 });
  await caption(page, '📨  Every step became a message in her inbox — order received, completed, installer booked.');
  await captionOff(page);
  // the pricing rule from Scene 2, visible in HER cart
  await page.goto('http://localhost:8080/shop/');
  await glideClick(page, page.locator('.card:has(h2:text-is("Apple iPhone 17"))').first());
  await page.waitForSelector('.pricetable', { timeout: 15000 });
  await glideClick(page, page.locator('button.primary.big'));
  await page.waitForURL('**/cart', { timeout: 15000 });
  await page.locator('[data-testid="price-adjustment"]').first().waitFor({ timeout: 15000 });
  await caption(page, '💚  And marketing\'s rule is already here: 15% off, previewed before checkout.');
  await captionOff(page);

  /* ============ SCENE 5 — same build, a Norwegian operator ============ */
  await glideClick(page, page.locator('button', { hasText: 'Sign out' }).first());
  await confirmLogout(page);
  await page.goto('http://shop.nova.localhost:8080/shop/');
  await page.waitForSelector('.card', { timeout: 20000 });
  console.log('· SCENE5-nova');
  await caption(page, '🇳🇴  One more thing. Same build, different operator: Nova Telecom of Norway.', 3000);
  await captionOff(page);
  await glideClick(page, page.locator('.who >> text=Logg inn'));
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await lens(page);
  await glideType(page, page.locator('input[name="username"]'), 'nils@nova.local');
  await page.fill('input[name="password"]', 'nils');
  await glideClick(page, page.locator('input[type="submit"], button[type="submit"]').first());
  await page.waitForSelector('.nav', { timeout: 20000 });
  await glideClick(page, page.locator('.nav >> text=Min side'));
  await page.waitForSelector('[data-testid=mobile-card]', { timeout: 15000 });
  await page.waitForTimeout(1500);
  await caption(page, '💠  Min side: kroner, Norwegian SIM care, a Norwegian bill — the tenant manifest did this, not a fork.', 3800);
  await captionOff(page);
  // and the plan change, in Norwegian: same line, same number, new plan
  await glideClick(page, page.locator('[data-testid^=change-plan-]').first());
  await page.waitForSelector('[data-testid=change-plan-form] select', { timeout: 10000 });
  await glideClick(page, page.locator('[data-testid=change-plan-form] select'));
  const alt = await page.locator('[data-testid=change-plan-form] option:not([value=""])')
    .first().getAttribute('value');
  await page.selectOption('[data-testid=change-plan-form] select', alt);
  await glideClick(page, page.locator('[data-testid=change-plan-form] button.primary'));
  await page.waitForSelector('[data-testid=plan-changed]', { timeout: 20000 });
  console.log('· SCENE5-plan-changed-norsk');
  await caption(page, '🔁  Bytt abonnement: a real TMF622 modify — Nils keeps his number, the price is in kroner.', 3200);
  await captionOff(page);
  await glideClick(page, page.locator('button', { hasText: 'Logg ut' }).first());
  await confirmLogout(page);

  // B2B, same tenant: Fjellheim's admin runs her company from the business
  // console — people, lines, and ONE consolidated invoice, all in Norwegian.
  await page.goto('http://biz.nova.localhost:8080/biz/');
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await lens(page);
  await glideType(page, page.locator('input[name="username"]'), 'birgit@fjellheim.no');
  await page.fill('input[name="password"]', 'birgit');
  await glideClick(page, page.locator('input[type="submit"], button[type="submit"]').first());
  await page.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  await page.waitForTimeout(2000);
  console.log('· SCENE5-b2b-norsk');
  await caption(page, '🏔  And B2B: Birgit runs Fjellheim AS — her people, their lines, one consolidated invoice in kroner.', 3800);
  await captionOff(page);
  await page.locator('.billrow', { hasText: 'BILL-' }).first().waitFor({ timeout: 15000 });
  await caption(page, '🧾  The company pays; employees see their own work line — nothing billed to them personally.', 3200);
  await captionOff(page);
  await glideClick(page, page.locator('#logout'));
  await confirmLogout(page);

  /* ============ SCENE 6 — the machine tells its own story ============ */
  await page.goto('http://localhost:8080/flow/');
  await page.waitForTimeout(3500);
  await caption(page, '🛰  Under the hood, every click you saw was an event — the system narrates itself.', 4000);
  await caption(page, '🔮  And under THAT: Java 25 with post-quantum crypto in the runtime, a hybrid ML-KEM edge, encrypted card secrets — PQC-ready, receipts in the repo.', 4200);
  await caption(page, 'genalpha-bss · 31 ODA components · 11 CTKs at zero · any language, any currency · quantum-ready · everything you watched was real.', 4500);

  await ctx.close();
  const video = await page.video().path();
  const out = path.join(dir, 'journey.webm');
  fs.copyFileSync(video, out); fs.rmSync(video);
  console.log('VIDEO:', out);

  // teardown: retire the pricing rule so the stack stays clean
  const tokenRes = await (await browser.newContext()).request.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  const token = (await tokenRes.json()).access_token;
  const cleanupCtx = await browser.newContext();
  const rules = await (await cleanupCtx.request.get(
    'http://localhost:8080/tmf-api/policyManagement/v4/policyRule?limit=100',
    { headers: { Authorization: 'Bearer ' + token } })).json();
  for (const r of rules.filter((x) => (x.name || '').startsWith('Launch 15% off'))) {
    await cleanupCtx.request.delete(
      'http://localhost:8080/tmf-api/policyManagement/v4/policyRule/' + r.id,
      { headers: { Authorization: 'Bearer ' + token } });
  }
  // restore Nils to his usual plan (the film swaps it every take)
  try {
    const nilsTok = (await (await cleanupCtx.request.post(
      'http://localhost:8085/realms/nova/protocol/openid-connect/token',
      { form: { grant_type: 'password', client_id: 'bss-csr', username: 'nils@nova.local', password: 'nils' } })).json()).access_token;
    const NH = { Authorization: 'Bearer ' + nilsTok, 'Content-Type': 'application/json' };
    const offers = await (await cleanupCtx.request.get(
      'http://localhost:8080/tmf-api/productCatalogManagement/v4/productOffering?limit=100',
      { headers: { ...NH, Host: 'shop.nova.localhost' } })).json();
    const unlimited = offers.find((o) => o.name === 'Nova Unlimited 5G');
    const products = await (await cleanupCtx.request.get(
      'http://localhost:8080/tmf-api/productInventory/v4/product?status=active', { headers: NH })).json();
    const current = products.find((p) => p.productOffering?.id !== unlimited.id
      && (p.name || '').startsWith('Nova '));
    if (current && unlimited) {
      await cleanupCtx.request.post('http://localhost:8080/tmf-api/productOrderingManagement/v4/productOrder',
        { headers: NH, data: { productOrderItem: [{ action: 'modify',
          product: { id: current.id }, productOffering: { id: unlimited.id, name: unlimited.name } }] } });
      console.log('· Nils restored to Nova Unlimited 5G');
    }
  } catch (e) { console.log('· Nils restore skipped:', e.message.split('\n')[0]); }
  console.log('cleanup done');
  await browser.close();
})().catch((e) => { console.error('FAIL:', e.message.split('\n')[0]); console.error(e.stack.split('\n').slice(1,4).join('\n')); process.exit(1); });
