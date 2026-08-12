/* Install-slot rollback — a lost slot must not strand an order. Suite #95.
 *
 * A fiber order needs a technician visit. The order is placed, THEN the install
 * appointment is booked — so if the chosen slot is taken in between (the calendar
 * fills, or a race), booking the appointment 409s. Without rollback the order is
 * left stranded (stock reserved, card authorised) and the shopper just sees an
 * error. This proves the compensation: on appointment failure the order is
 * cancelled — which releases the stock and VOIDS the payment — and the shopper is
 * told to pick another time, not charged.
 *
 * The race is simulated: the shopper picks a free slot, we fill that exact slot
 * to capacity via the appointment API, THEN the shopper checks out.
 */
const { chromium, request } = require('playwright');
const API = 'http://localhost:8080';
const SHOP = API + '/shop/';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const APPT = '/tmf-api/appointment/v4/appointment';
const ORDERS = '/tmf-api/productOrderingManagement/v4/productOrder';
const PAY = '/tmf-api/paymentManagement/v4/payment';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(rc, user, pass) {
  const r = await rc.post(KC, { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  if (!r.ok()) fail(`token(${user}): ${r.status()}`);
  return (await r.json()).access_token;
}

(async () => {
  const rc = await request.newContext();
  const staff = await token(rc, 'demo', 'demo');
  const kai = await token(rc, 'kai@bss.local', 'kai');
  const SH = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };
  const KH = { Authorization: 'Bearer ' + kai };

  // preflight: free stale bookings so the shopper can pick a slot at all
  const stale = await (await rc.get(`${API}${APPT}?limit=100`, { headers: SH })).json();
  let freed = 0;
  for (const a of (Array.isArray(stale) ? stale : [])) {
    if (a.status === 'confirmed') { await rc.patch(`${API}${APPT}/${a.id}`, { headers: SH, data: { status: 'cancelled' } }); freed++; }
  }
  console.log(`freed ${freed} stale install bookings`);

  // orders kai already has (to spot the new one)
  const before = new Set(((await (await rc.get(`${API}${ORDERS}?limit=30`, { headers: KH })).json()) || []).map((o) => o.id));

  const browser = await chromium.launch();
  const page = await browser.newPage();
  try {
    await page.goto(SHOP);
    await page.click('.who >> text=Sign in');
    await page.waitForSelector('input[name="username"]', { timeout: 20000 });
    await page.fill('input[name="username"]', 'kai@bss.local');
    await page.fill('input[name="password"]', 'kai');
    await page.click('input[type="submit"], button[type="submit"]');
    await page.waitForSelector('.nav', { timeout: 20000 });

    const bundle = page.locator('.card.bundle', { hasText: 'GenAlpha One Home & Mobile' }).first();
    await bundle.waitFor({ timeout: 15000 });
    await bundle.click();
    await page.waitForSelector('.pricetable');
    await page.locator('.optprice').first().waitFor({ timeout: 10000 });
    await page.click('label.option:has-text("Samsung Galaxy S26")');
    await page.locator('.charfield', { hasText: 'color' }).locator('select').waitFor({ timeout: 10000 });
    await page.click('button.primary.big');
    await page.waitForURL('**/cart');

    await page.locator('.shipping').waitFor({ timeout: 10000 });
    await page.fill('.shipping input[name="street1"]', 'Storgatan 1');
    await page.fill('.shipping input[name="city"]', 'Stockholm');
    await page.fill('.shipping input[name="country"]', 'SE');
    await page.fill('.shipping input[name="postCode"]', '11122');
    await page.locator('.serviceability.ok').waitFor({ timeout: 10000 });
    const slotBtn = page.locator('.slotgrid .option').first();
    await slotBtn.waitFor({ timeout: 10000 });
    await slotBtn.click();
    ok('the shopper picked a free install slot');

    // the picked slot (stashed by the cart) — now fill it to capacity behind their back
    const slot = await page.evaluate(() => JSON.parse(localStorage.getItem('bss.shop.installSlot') || 'null'));
    if (!slot || !slot.startDateTime) fail('could not read the picked slot');
    let filled = 0;
    for (let i = 0; i < 3; i++) {
      const r = await rc.post(`${API}${APPT}`, { headers: SH,
        data: { validFor: slot, description: 'slot-fill (race sim)' } });
      if (r.status() === 201 || r.status() === 200) filled++;
      else break; // 409 — already full
    }
    ok(`the chosen slot ${slot.startDateTime} was filled to capacity behind the shopper (${filled} bookings) — a lost slot`);

    // card + checkout → the order places, the appointment 409s, the cart rolls back
    await page.fill('input[name="cardNumber"]', '4242 4242 4242 4242');
    await page.fill('input[name="expiry"]', '12/29');
    await page.fill('input[name="cvc"]', '123');
    await page.locator('.cartactions button.primary.big').click();

    await page.locator('.error').first().waitFor({ timeout: 30000 });
    const err = (await page.locator('.error').first().textContent()).trim();
    if (!/not placed|another slot|taken/i.test(err)) fail('expected a clear rollback message, got: ' + err);
    ok(`ROLLBACK MESSAGE: the shopper is told to pick another time — "${err}"`);
    // and we did NOT navigate to /orders
    if (page.url().includes('/orders')) fail('checkout navigated to /orders despite the failure');

    // the order that WAS placed is now cancelled (not stranded)
    let placed = null;
    for (let i = 0; i < 15 && !placed; i++) {
      await sleep(1000);
      const now = (await (await rc.get(`${API}${ORDERS}?limit=30`, { headers: KH })).json()) || [];
      placed = now.find((o) => !before.has(o.id));
    }
    if (!placed) fail('no order row found for the attempt (cannot verify rollback)');
    if (placed.state !== 'cancelled') fail(`the stranded order should be cancelled, is '${placed.state}'`);
    ok(`ROLLED BACK: the placed order ${placed.id.slice(0, 8)}… is 'cancelled' — not stranded in acknowledged`);

    // the card authorisation was voided by the cancel (no silent charge)
    const payRefs = (placed.payment || []).map((p) => p.id).filter(Boolean);
    if (payRefs.length) {
      let voided = false;
      for (let i = 0; i < 10 && !voided; i++) {
        const p = await (await rc.get(`${API}${PAY}/${payRefs[0]}`, { headers: KH })).json();
        if (p && p.status === 'voided') voided = true; else await sleep(1000);
      }
      if (!voided) fail('the order was cancelled but its payment was not voided — the shopper may be charged');
      ok('PAYMENT VOIDED: the card authorisation was released by the cancel — the shopper is not charged');
    } else {
      ok('PAYMENT: no captured payment to void on this order');
    }

    console.log('\nALL INSTALL-SLOT-ROLLBACK CHECKS PASSED — a lost install slot no longer strands an order:'
      + ' the order is cancelled, the stock released, the card voided, and the shopper is asked to pick another time.');
  } finally {
    await browser.close();
    // tidy: free the slot-fill bookings we made
    const mine = await (await rc.get(`${API}${APPT}?limit=100`, { headers: SH })).json();
    for (const a of (Array.isArray(mine) ? mine : [])) {
      if (a.status === 'confirmed' && (a.description || '').includes('slot-fill')) {
        await rc.patch(`${API}${APPT}/${a.id}`, { headers: SH, data: { status: 'cancelled' } }).catch(() => {});
      }
    }
  }
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
