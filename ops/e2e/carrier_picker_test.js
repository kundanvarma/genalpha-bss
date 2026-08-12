/* Carrier picker — the SHOPPER's carrier choice at checkout (C-P3, storefront). Suite #92.
 *
 * The operator half (per-tenant carrier menu) is suite #90. This proves the
 * customer-facing half: the cart renders every configured carrier as a delivery
 * option, the shopper PICKS one for home delivery, and that choice rides the
 * order's delivery place all the way to the fulfilment booking — the parcel is
 * booked with the carrier the shopper chose, not the tenant default.
 *
 *  - MENU: the cart shows a home option per carrier (Helthjem, Posten/Bring,
 *    PostNord) plus pickup points — the whole operator menu, not a single fallback.
 *  - PICK → BOOK: choosing "PostNord" home books the parcel via PostNord (a
 *    'PN…' consignment, place.carrier=postnord), overriding the Bring default.
 *
 * Ensures the demo carrier menu is present (idempotent PUT) and leaves it — the
 * standing demo default (ops/seed/seed_carriers_psp.py).
 */
const { chromium, request } = require('playwright');
const API = 'http://localhost:8080';
const SHOP = API + '/shop/';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const F = '/tmf-api/shippingOrderManagement/v4';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(rc, user, pass) {
  const r = await rc.post(KC, { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  if (!r.ok()) fail(`token(${user}): ${r.status()}`);
  return (await r.json()).access_token;
}

const MENU = [
  { carrier: 'helthjem', displayName: 'Helthjem', baseUrl: 'http://mock-logistics:8080', secretRef: 'HELTHJEM_API_KEY', methods: ['home'], isDefault: false },
  { carrier: 'bring', displayName: 'Posten/Bring', baseUrl: 'http://mock-bring:8080', secretRef: 'BRING_API_KEY', methods: ['home', 'pickupPoint'], isDefault: true },
  { carrier: 'postnord', displayName: 'PostNord', baseUrl: 'http://mock-postnord:8080', secretRef: 'POSTNORD_API_KEY', methods: ['home', 'pickupPoint'], isDefault: false },
];

(async () => {
  const rc = await request.newContext();
  const staff = await token(rc, 'demo', 'demo');
  const kaiTok = await token(rc, 'kai@bss.local', 'kai');
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };
  const KH = { Authorization: 'Bearer ' + kaiTok };
  // Ensure the demo carrier menu is present (idempotent; the standing demo default).
  for (const c of MENU) await rc.put(`${API}${F}/carrier`, { headers: H, data: c });

  const browser = await chromium.launch();
  const page = await browser.newPage();
  try {
    // sign in
    await page.goto(SHOP);
    await page.click('.who >> text=Sign in');
    await page.waitForSelector('input[name="username"]', { timeout: 20000 });
    await page.fill('input[name="username"]', 'kai@bss.local');
    await page.fill('input[name="password"]', 'kai');
    await page.click('input[type="submit"], button[type="submit"]');
    await page.waitForSelector('.nav', { timeout: 20000 });

    // a device ships (physical) with no serviceability gate → clean carrier-picker path
    await page.click('.nav >> text=Offers');
    await page.locator('.shoptab', { hasText: 'Devices' }).first().click();
    await page.locator('.card:has(h2:text-is("Apple iPhone 17"))').first().click();
    await page.waitForSelector('.pricetable');
    await page.click('button.primary.big');
    await page.waitForURL('**/cart');

    await page.locator('.shipping').waitFor({ timeout: 10000 });
    await page.fill('.shipping input[name="street1"]', 'Storgata 1');
    await page.fill('.shipping input[name="city"]', 'Oslo');
    await page.fill('.shipping input[name="country"]', 'NO');
    await page.fill('.shipping input[name="postCode"]', '0150');
    await sleep(1500);

    const opts = page.locator('[data-testid="delivery-opt"]');
    const labels = [];
    const n = await opts.count();
    for (let i = 0; i < n; i++) labels.push((await opts.nth(i).locator('.simopt-t').textContent()).trim());
    for (const name of ['Helthjem', 'Posten/Bring', 'PostNord']) {
      if (!labels.some((l) => l.includes(name))) fail(`carrier menu missing ${name}: ${labels.join(' | ')}`);
    }
    ok(`MENU: the cart shows every configured carrier — ${labels.join('  ·  ')}`);

    // pick PostNord home — override the Bring default
    await opts.filter({ hasText: 'PostNord' }).first().click();
    const cn = page.locator('input[name="cardNumber"]');
    if (await cn.count()) { await page.fill('input[name="cardNumber"]', '4242 4242 4242 4242'); await page.fill('input[name="expiry"]', '12/29'); await page.fill('input[name="cvc"]', '123'); }
    await page.locator('.cartactions button.primary.big').click();
    await page.waitForURL('**/orders', { timeout: 40000 });
    ok('PICK: the shopper chose PostNord and the order was placed');

    // the newest order kai placed — match its shipping booking precisely
    const myOrders = await (await rc.get(`${API}/tmf-api/productOrderingManagement/v4/productOrder?limit=1`, { headers: KH })).json();
    const orderId = (Array.isArray(myOrders) && myOrders[0] || {}).id;
    if (!orderId) fail('could not read the just-placed order');

    // the shipping order for THIS order booked via PostNord (the pick, not the Bring default)
    let so = null;
    for (let i = 0; i < 20 && !so; i++) {
      await sleep(1000);
      const list = await (await rc.get(`${API}${F}/shippingOrder?limit=20`, { headers: H })).json();
      so = (Array.isArray(list) ? list : []).find((s) => s.productOrderId === orderId);
    }
    if (!so) fail(`no shipping order appeared for order ${orderId}`);
    if (!(so.carrier || '').includes('PostNord') || !(so.trackingRef || '').startsWith('PN')) {
      fail(`order ${orderId} booked with ${so.carrier}/${so.trackingRef}, expected PostNord/PN… (the shopper's pick)`);
    }
    const place = Array.isArray(so.place) ? so.place[0] : so.place;
    if ((place || {}).carrier !== 'postnord') fail('delivery place did not carry the chosen carrier: ' + JSON.stringify(place));
    ok(`PICK → BOOK: the parcel booked via PostNord (${so.trackingRef}), place.carrier=postnord — the shopper's`
      + ' carrier choice overrode the Bring default, all the way to the booking');

    /* ---------- eSIM vs shipping: delivery only when something SHIPS, and it says what ---------- */
    const offs3 = await (await rc.get(`${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`, { headers: H })).json();
    const planOff = (offs3 || []).find((o) => o.name === 'GenAlpha Mobile 50 GB');
    const bundleOff2 = (offs3 || []).find((o) => o.name === 'GenAlpha One Home & Mobile');
    if (!planOff || !bundleOff2) fail('catalog fixtures missing (50 GB plan / Home & Mobile bundle)');
    // a plain plan with an eSIM ships nothing → NO delivery UI at all
    await page.goto(`${API}/shop/offering/${planOff.id}`);
    await page.waitForSelector('.pricetable');
    await page.click('button.primary.big');
    await page.waitForURL('**/cart');
    await sleep(2500);
    if (await page.locator('.delivery-method, [data-testid=delivery-by]').count()) {
      fail('a plan with an eSIM ships nothing — no delivery UI should render');
    }
    ok('ESIM: a plain plan with the default eSIM shows NO delivery block — nothing ships');

    // flip to a physical SIM → delivery appears and NAMES the SIM as the parcel
    await page.locator('.simopt', { hasText: 'Physical SIM' }).click();
    await sleep(1500);
    const shipsSim = page.locator('[data-testid=ships-to-you], [data-testid=delivery-by]');
    if (!(await page.locator('.delivery-method, [data-testid=delivery-by]').count())) {
      fail('a physical SIM ships — the delivery UI should appear');
    }
    const simTxt = (await shipsSim.count()) ? await shipsSim.first().textContent() : '';
    if (!simTxt.includes('SIM card')) fail('the delivery block should say the SIM card ships: ' + simTxt);
    ok(`PHYSICAL SIM: the delivery block appears and names the parcel — "${simTxt.trim().slice(0, 60)}"`);

    // a bundle whose phone choice pre-selects a handset: eSIM chosen, the phone
    // still ships — and the delivery block SAYS the parcel is the phone, so
    // "eSIM (nothing to ship)" never reads as a contradiction
    await page.locator('.simopt', { hasText: 'eSIM' }).first().click();
    await page.goto(`${API}/shop/offering/${bundleOff2.id}`);
    await page.waitForSelector('.pricetable');
    await page.locator('.optprice').first().waitFor({ timeout: 10000 });
    await page.click('button.primary.big');
    await page.waitForURL('**/cart');
    await sleep(2500);
    const shipsLine = page.locator('[data-testid=ships-to-you]');
    if (!(await shipsLine.count())) fail('the bundle carries a pre-picked phone — the delivery block should name it');
    const shipsTxt = await shipsLine.textContent();
    if (!/iPhone|Samsung|Galaxy/.test(shipsTxt)) fail('ships-to-you should name the handset: ' + shipsTxt);
    ok(`ESIM + PHONE: with eSIM chosen the delivery block explains itself — "${shipsTxt.trim().slice(0, 70)}"`);

    console.log('\nALL CARRIER-PICKER CHECKS PASSED — the storefront shows the operator\'s whole carrier menu,'
      + ' the shopper picks a carrier for home delivery, that pick rides the order to the fulfilment booking,'
      + ' and the delivery block renders only when something ships — naming exactly what.');
  } finally {
    await browser.close();
  }
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
