/* Storefront channel E2E: two customers self-register through Keycloak, one
 * orders the GenAlpha One bundle, isolation is verified at UI and API level,
 * staff completes the order, and the provisioned service appears for its
 * owner only. */
const { chromium } = require('playwright');

const SHOP = 'http://localhost:8080/shop/';
const API = 'http://localhost:8080';
const run = Date.now();

async function register(page, email, first, last) {
  await page.goto(SHOP);
  await page.click('.who >> text=Sign in'); // guests browse; identity starts here
  await page.waitForSelector('a[href*="registration"], input[name="username"]', { timeout: 20000 });
  await page.click('a[href*="registration"]');
  await page.waitForSelector('input[name="email"]');
  await page.fill('input[name="firstName"]', first);
  await page.fill('input[name="lastName"]', last);
  await page.fill('input[name="email"]', email);
  await page.fill('input[name="password"]', 'Passw0rd!');
  await page.fill('input[name="password-confirm"]', 'Passw0rd!');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.nav', { timeout: 20000 });
}

function shopToken(page) {
  return page.evaluate(() => sessionStorage.getItem('bss.shop.token'));
}

async function apiGet(page, path, token) {
  return page.evaluate(async ({ path, token }) => {
    const res = await fetch(path, { headers: { Authorization: 'Bearer ' + token } });
    return { status: res.status, body: await res.text() };
  }, { path, token });
}

(async () => {
  const browser = await chromium.launch();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };

  // --- Staff setup: pin Samsung availability to exactly 10 so runs repeat
  const setup = await browser.newContext();
  const tokenRes0 = await setup.request.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  const staff = (await tokenRes0.json()).access_token;
  const staffHeaders = { Authorization: 'Bearer ' + staff };
  const offeringsRes = await setup.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`, { headers: staffHeaders });
  const samsungId = (await offeringsRes.json()).find((o) => o.name === 'Samsung Galaxy S26').id;
  const stockRows = await (await setup.request.get(
    `${API}/tmf-api/productStockManagement/v4/productStock?productOfferingId=${samsungId}`,
    { headers: staffHeaders })).json();
  if (!stockRows.length) fail('no stock row seeded for Samsung Galaxy S26');
  const stockId = stockRows[0].id;
  const reservedNow = stockRows[0].reservedQuantity.amount;
  await setup.request.patch(`${API}/tmf-api/productStockManagement/v4/productStock/${stockId}`,
    { headers: staffHeaders, data: { stockedQuantity: { amount: reservedNow + 10, units: 'unit' } } });
  console.log('OK stock pinned: Samsung availability = 10');

  // --- Customer A registers and orders the bundle
  const ctxA = await browser.newContext();
  const a = await ctxA.newPage();
  await register(a, `alice-${run}@example.com`, 'Alice', 'Anders');
  console.log('OK customer A self-registered and signed in');

  const bundleCard = a.locator('.card.bundle', { hasText: 'GenAlpha One Home & Mobile' });
  await bundleCard.waitFor({ timeout: 15000 });
  const cardText = await bundleCard.textContent();
  if (!/\d+\.\d{2} EUR\/month/.test(cardText)) fail(`bundle card shows no monthly price: ${cardText}`);
  console.log('OK bundle card with monthly total visible');

  await bundleCard.click();
  await a.waitForSelector('.pricetable');
  const includes = await a.locator('.includes.big li').allTextContents();
  if (includes.length !== 3) fail(`expected 3 fixed bundle children, got ${includes.length}`);
  const options = a.locator('label.option');
  if (await options.count() !== 3) fail(`expected 3 phone options, got ${await options.count()}`);
  await a.locator('.optprice').first().waitFor({ timeout: 10000 }); // options fully resolved
  const totalBefore = (await a.locator('.pricetable .total .num').textContent()).trim();
  console.log('OK configurator: 3 fixed components, 3 phone options, default total', totalBefore);

  // Configure: Samsung, Icy Blue, 512GB — the price must follow the choice.
  await a.click('label.option:has-text("Samsung Galaxy S26")');
  await a.waitForFunction((before) =>
    document.querySelector('.pricetable .total .num')?.textContent.trim() !== before, totalBefore);
  const totalAfter = (await a.locator('.pricetable .total .num').textContent()).trim();
  const colorSelect = a.locator('.charfield', { hasText: 'color' }).locator('select');
  await colorSelect.waitFor({ timeout: 10000 });
  await colorSelect.selectOption('Icy Blue');
  await a.locator('.charfield', { hasText: 'storage' }).locator('select').selectOption('512GB');
  const stockline = await a.locator('.stockline').textContent();
  if (!stockline.includes('In stock')) fail(`expected "In stock" note, got "${stockline}"`);
  console.log('OK configured Samsung Galaxy S26 / Icy Blue / 512GB, total now', totalAfter, '|', stockline.trim());

  // Add configured bundle to cart, bump quantity to 2
  await a.click('button.primary.big');
  await a.waitForURL('**/cart');
  await a.locator('.row', { hasText: 'GenAlpha One' }).locator('button[aria-label="increase"]').click();
  await a.locator('.badge', { hasText: '2' }).waitFor({ timeout: 10000 });
  console.log('OK cart line quantity 2, badge follows');

  // Add a standalone phone as a second line
  await a.click('.nav >> text=Offers');
  await a.locator('.card:has(h2:text-is("Apple iPhone 17"))').click();
  await a.waitForSelector('.pricetable');
  await a.click('button.primary.big');
  await a.waitForURL('**/cart');
  await a.locator('.row.granded').first().waitFor({ timeout: 10000 }); // route render is async
  const cartRows = await a.locator('.row:not(.granded)').count();
  if (cartRows !== 2) fail(`expected 2 cart lines, got ${cartRows}`);
  const grand = (await a.locator('.row.granded:not(.duenow) .linetotal').textContent()).trim();
  console.log('OK 2 cart lines, total per month', grand);

  // Physical goods in the cart: checkout is blocked until an address is given
  await a.locator('.shipping').waitFor({ timeout: 10000 });
  const blocked = a.locator('.cartactions button.primary.big');
  if (!(await blocked.isDisabled())) fail('checkout not blocked without shipping address');
  if (!(await blocked.textContent()).includes('Enter shipping address')) {
    fail('checkout button should ask for the address');
  }
  await a.fill('.shipping input[name="street1"]', 'Storgatan 1');
  await a.fill('.shipping input[name="city"]', 'Stockholm');
  await a.fill('.shipping input[name="country"]', 'SE');

  // Serviceability: fiber is not available everywhere — 99999 blocks checkout
  await a.fill('.shipping input[name="postCode"]', '99999');
  await a.locator('.serviceability.error').waitFor({ timeout: 10000 });
  const reason = await a.locator('.serviceability.error').textContent();
  if (!reason.includes('not available at postcode 99999')) fail(`unexpected reason: ${reason}`);
  if (!(await a.locator('.cartactions button.primary.big').textContent()).includes('Not serviceable')) {
    fail('checkout not blocked at unserviceable address');
  }
  console.log('OK unserviceable postcode blocks checkout:', reason.trim());

  // Back in the fiber footprint: serviceable, and an install slot is required
  await a.fill('.shipping input[name="postCode"]', '11122');
  await a.locator('.serviceability.ok').waitFor({ timeout: 10000 });
  await a.locator('.slotgrid .option').first().waitFor({ timeout: 10000 });
  if (!(await a.locator('.cartactions button.primary.big').textContent()).includes('Pick an installation slot')) {
    fail('checkout should demand an install slot');
  }
  await a.locator('.slotgrid .option').first().click();
  console.log('OK serviceable at 11122; install slot picked');

  // One-time charges are due now (fiber install 49 × qty 2) and need a card
  const dueRow = a.locator('.row.duenow .linetotal');
  await dueRow.waitFor({ timeout: 10000 });
  const dueText = (await dueRow.textContent()).trim();
  if (!dueText.startsWith('98.00')) fail(`expected due now 98.00, got "${dueText}"`);
  const payBtn = a.locator('.cartactions button.primary.big');
  if (!(await payBtn.textContent()).includes('Enter card details')) {
    fail('checkout should demand card details when something is due');
  }

  // A declined card fails loudly and keeps the cart intact
  await a.fill('.payment input[name="cardNumber"]', '4000 0000 0000 0002');
  await a.fill('.payment input[name="expiry"]', '12/28');
  await a.fill('.payment input[name="cvc"]', '123');
  await payBtn.click();
  await a.locator('.error', { hasText: 'card declined' }).waitFor({ timeout: 10000 });
  console.log('OK due now', dueText, '| declined card rejected with message');

  // A good card pays and checks out the whole cart as one order
  await a.fill('.payment input[name="cardNumber"]', '4242 4242 4242 4242');
  await a.locator('.cartactions button.primary.big').click();
  await a.waitForURL('**/orders');
  const orderRow = a.locator('.row', { hasText: 'GenAlpha One Home & Mobile' });
  await orderRow.waitFor({ timeout: 15000 });
  const orderState = await orderRow.locator('.state').textContent();
  const orderText = await orderRow.textContent();
  if (!orderText.includes('×2')) fail('order description missing quantity: ' + orderText);
  console.log('OK cart checked out as one order, state:', orderState);
  await a.locator('.badge').waitFor({ state: 'detached', timeout: 5000 })
    .catch(() => fail('cart badge did not clear after checkout'));

  // Account page shows the provisioned party (wait for the fetch to land)
  await a.click('.nav >> text=Account');
  try {
    await a.locator('.rows', { hasText: 'Alice' }).waitFor({ timeout: 10000 });
  } catch {
    fail('account page missing party name: ' + await a.locator('main').textContent());
  }
  console.log('OK account page shows TMF632 party');

  // --- Customer B registers; sees no orders
  const ctxB = await browser.newContext();
  const b = await ctxB.newPage();
  await register(b, `bob-${run}@example.com`, 'Bob', 'Berg');
  await b.click('.nav >> text=My orders');
  await b.locator('main .dim', { hasText: 'No orders yet' }).waitFor({ timeout: 10000 })
    .catch(async () => fail('customer B order list not empty: ' + await b.locator('main').textContent()));
  console.log('OK customer B sees no foreign orders in UI');

  // --- API-level isolation: B fetches A's order id directly
  let tokenA = await shopToken(a);
  const tokenB = await shopToken(b);
  const listA = await apiGet(a, '/tmf-api/productOrderingManagement/v4/productOrder?limit=100', tokenA);
  const aOrder = JSON.parse(listA.body)[0];
  const aOrderId = aOrder.id;

  // One order, two lines: bundle ×2 with the configured phone nested, iPhone 17 ×1.
  const items = aOrder.productOrderItem || [];
  const bundleItem = items.find((i) => i.productOffering?.name === 'GenAlpha One Home & Mobile');
  if (!bundleItem || bundleItem.quantity !== 2) {
    fail('bundle item missing or wrong quantity: ' + JSON.stringify(bundleItem));
  }
  const phoneItem = (bundleItem.productOrderItem || []).find(
    (i) => i.productOffering?.name === 'Samsung Galaxy S26');
  if (!phoneItem) fail('bundle missing nested phone item: ' + JSON.stringify(bundleItem));
  const itemChars = Object.fromEntries(
    (phoneItem.product?.productCharacteristic || []).map((c) => [c.name, c.value]));
  if (itemChars.color !== 'Icy Blue' || itemChars.storage !== '512GB') {
    fail('order item characteristics wrong: ' + JSON.stringify(itemChars));
  }
  const standalone = items.find((i) => i.productOffering?.name === 'Apple iPhone 17');
  if (!standalone || standalone.quantity !== 1) {
    fail('standalone phone line missing: ' + JSON.stringify(items.map((i) => i.productOffering?.name)));
  }
  console.log('OK one order, quantities intact, configured chars:', JSON.stringify(itemChars));

  // Physical items carry the shipping place; the plan-only bundle line does not
  const phonePlace = (phoneItem.product?.place || [])[0];
  if (phonePlace?.city !== 'Stockholm' || phonePlace?.role !== 'shipping') {
    fail('nested phone item missing shipping place: ' + JSON.stringify(phoneItem.product));
  }
  const standalonePlace = (standalone.product?.place || [])[0];
  if (standalonePlace?.postCode !== '11122') {
    fail('standalone phone missing shipping place: ' + JSON.stringify(standalone.product));
  }
  if (bundleItem.product?.place) fail('non-physical bundle line should not carry a place');
  console.log('OK shipping place on physical items only:', phonePlace.street1 + ', ' + phonePlace.city);

  // The order references an authorized payment for the amount due
  const paymentRef = (aOrder.payment || [])[0];
  if (!paymentRef?.id) fail('order missing payment ref: ' + JSON.stringify(aOrder.payment));
  const payRes = await apiGet(a, `/tmf-api/paymentManagement/v4/payment/${paymentRef.id}`, tokenA);
  const payment = JSON.parse(payRes.body);
  if (payment.status !== 'authorized' || payment.amount.value !== 98.00
      || payment.correlatorId !== aOrderId) {
    fail('payment wrong: ' + payRes.body.slice(0, 300));
  }
  if (!payment.paymentMethod.label.includes('4242')) fail('payment method label missing last4');
  console.log('OK payment authorized:', payment.amount.value, payment.amount.unit,
    '|', payment.paymentMethod.label, '| correlated to order');

  // The install appointment exists, confirmed, linked to the order
  const appts = JSON.parse((await apiGet(a, '/tmf-api/appointment/v4/appointment?limit=10', tokenA)).body);
  const appt = appts.find((x) => (x.relatedEntity || [])[0]?.id === aOrderId);
  if (!appt || appt.status !== 'confirmed' || appt.place?.postCode !== '11122') {
    fail('appointment missing or wrong: ' + JSON.stringify(appts).slice(0, 300));
  }
  await a.click('.nav >> text=My orders');
  const installNote = await a.locator('.installnote').first().textContent();
  if (!installNote.includes('Install:')) fail('orders page missing install note');
  console.log('OK install appointment confirmed for the order:', appt.validFor.startDateTime, '|', installNote.trim());

  // The address was saved on the TMF632 party
  const partyRes = await apiGet(a, '/tmf-api/party/v4/individual?limit=10', tokenA);
  const medium = (JSON.parse(partyRes.body)[0].contactMedium || [])
    .find((m) => m.mediumType === 'postalAddress');
  if (medium?.characteristic?.city !== 'Stockholm') {
    fail('party missing saved postal address: ' + partyRes.body.slice(0, 200));
  }
  console.log('OK address saved on party contactMedium');

  // --- Stock: the order reserved 2 Samsungs (availability 10 -> 8)
  const afterReserve = await (await setup.request.get(
    `${API}/tmf-api/productStockManagement/v4/productStock/${stockId}`, { headers: staffHeaders })).json();
  if (afterReserve.availableQuantity.amount !== 8) {
    fail(`expected availability 8 after reserving 2, got ${afterReserve.availableQuantity.amount}`);
  }
  console.log('OK reservation visible in stock: available', afterReserve.availableQuantity.amount);

  // --- Over-ordering is rejected atomically with a named shortage
  const tooMany = await apiGet(a, '/tmf-api/productOrderingManagement/v4/productOrder', tokenA)
    .then(() => a.evaluate(async ({ token, offeringId }) => {
      const res = await fetch('/tmf-api/productOrderingManagement/v4/productOrder', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
        body: JSON.stringify({
          description: 'greedy order',
          productOrderItem: [{ id: '1', action: 'add', quantity: 999,
            productOffering: { id: offeringId, name: 'Samsung Galaxy S26' } }],
        }),
      });
      return { status: res.status, body: await res.text() };
    }, { token: tokenA, offeringId: samsungId }));
  if (tooMany.status !== 400 || !tooMany.body.includes('insufficient stock')) {
    fail(`over-order expected 400 insufficient, got ${tooMany.status}: ${tooMany.body.slice(0, 200)}`);
  }
  console.log('OK over-order rejected:', JSON.parse(tooMany.body).message);
  const foreign = await apiGet(b, `/tmf-api/productOrderingManagement/v4/productOrder/${aOrderId}`, tokenB);
  if (foreign.status !== 404) fail(`foreign order fetch expected 404, got ${foreign.status}`);
  console.log('OK API isolation: foreign order id returns 404');

  // --- Staff completes A's order (back office); service appears for A only
  const tokenRes = await ctxA.request.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  const staffToken = (await tokenRes.json()).access_token;
  const done = await ctxA.request.patch(
    `${API}/tmf-api/productOrderingManagement/v4/productOrder/${aOrderId}`,
    { headers: { Authorization: 'Bearer ' + staffToken }, data: { state: 'completed' } });
  if (done.status() !== 200) fail(`staff completing order expected 200, got ${done.status()}`);

  await a.click('.nav >> text=My services');
  // A Samsung row exists only on the services page (order description has no
  // Samsung) — route transitions render async, so anchor on that.
  await a.locator('.row', { hasText: 'Samsung Galaxy S26' }).first().waitFor({ timeout: 15000 });
  const serviceCount = await a.locator('.row').count();
  if (serviceCount !== 5) fail(`expected 5 provisioned products (2 bundle, 2 phone, 1 solo), got ${serviceCount}`);
  console.log('OK completion provisioned 5 per-item products into customer A inventory');

  // --- Completion consumed the reservation: shelf down 2, availability still 8
  const afterConsume = await (await setup.request.get(
    `${API}/tmf-api/productStockManagement/v4/productStock/${stockId}`, { headers: staffHeaders })).json();
  if (afterConsume.stockedQuantity.amount !== reservedNow + 8 || afterConsume.availableQuantity.amount !== 8) {
    fail(`expected stocked ${reservedNow + 8}/available 8 after consume, got `
      + `${afterConsume.stockedQuantity.amount}/${afterConsume.availableQuantity.amount}`);
  }
  console.log('OK completion consumed stock: shelf', afterConsume.stockedQuantity.amount,
    '| available', afterConsume.availableQuantity.amount);

  // Completion also captured the payment
  const capturedRes = await apiGet(a, `/tmf-api/paymentManagement/v4/payment/${paymentRef.id}`, tokenA);
  const captured = JSON.parse(capturedRes.body);
  if (captured.status !== 'captured') fail(`payment expected captured, got ${captured.status}`);
  console.log('OK completion captured the payment');

  // Access tokens live 5 minutes and this suite is long: refresh Alice's
  // session via Keycloak SSO (no password needed) before the billing chapter.
  await a.goto(SHOP);
  if (await a.locator('.who >> text=Sign in').count()) {
    await a.click('.who >> text=Sign in');
    await a.waitForSelector('.nav', { timeout: 20000 });
  }
  tokenA = await shopToken(a);

  // --- Billing: a run rates Alice's provisioned products into one bill
  const runRes = await setup.request.post(
    `${API}/tmf-api/customerBillManagement/v4/billingRun`, { headers: staffHeaders });
  if (runRes.status() !== 200) fail(`billing run failed: ${runRes.status()} ${await runRes.text()}`);
  console.log('OK billing run:', JSON.stringify(await runRes.json()));

  await a.click('.nav >> text=My bills');
  const billRow = a.locator('.row', { hasText: 'BILL-' }).first();
  await billRow.waitFor({ timeout: 15000 });
  const billTotal = (await billRow.locator('.linetotal').textContent()).trim();
  // 2× bundle (64.98 + Samsung 37.49) + iPhone 17 (33.29) = 238.23/month
  if (!billTotal.startsWith('238.23')) fail(`expected bill 238.23, got "${billTotal}"`);
  await billRow.locator('.linkish').click();
  await a.locator('.billitems .row').first().waitFor({ timeout: 10000 });
  const itemCount = await a.locator('.billitems .row').count();
  if (itemCount !== 5) fail(`expected 5 bill line items (2 bundle, 2 samsung, 1 iphone), got ${itemCount}`);
  console.log('OK bill for', billTotal, 'with', itemCount, 'recurring line items');

  // Pay the bill: card in, bill settled, payment captured by billing
  await billRow.locator('button', { hasText: 'Pay' }).click();
  await a.fill('.billpay input[name="cardNumber"]', '4242 4242 4242 4242');
  await a.fill('.billpay input[name="expiry"]', '01/29');
  await a.fill('.billpay input[name="cvc"]', '321');
  await a.locator('.billpay button.primary').click();
  await a.locator('.row', { hasText: 'BILL-' }).first().locator('.state', { hasText: 'settled' })
    .waitFor({ timeout: 15000 });
  const billsAfter = JSON.parse((await apiGet(a,
    '/tmf-api/customerBillManagement/v4/customerBill?limit=10', tokenA)).body);
  const settledBill = billsAfter.find((bl) => bl.state === 'settled');
  const billPaymentId = settledBill.payment[0].id;
  const billPayment = JSON.parse((await apiGet(a,
    `/tmf-api/paymentManagement/v4/payment/${billPaymentId}`, tokenA)).body);
  if (billPayment.status !== 'captured' || billPayment.amount.value !== 238.23) {
    fail('bill payment wrong: ' + JSON.stringify(billPayment).slice(0, 200));
  }
  console.log('OK bill settled, payment of', billPayment.amount.value, billPayment.amount.unit, 'captured');

  // --- Notifications: the event stream became customer-visible messages.
  // Outbox relays every 2s and the consumer follows; poll the inbox briefly.
  const wantSubjects = ['Order received', 'Order complete', 'Installer booked', 'Your bill is ready'];
  let inboxText = '';
  for (let attempt = 0; attempt < 15; attempt++) {
    const res = await apiGet(a, '/tmf-api/communicationManagement/v4/communicationMessage?limit=100', tokenA);
    inboxText = res.body;
    if (wantSubjects.every((subj) => inboxText.includes(subj))) break;
    await a.waitForTimeout(2000);
  }
  for (const subj of wantSubjects) {
    if (!inboxText.includes(subj)) fail(`inbox missing "${subj}": ${inboxText.slice(0, 300)}`);
  }
  console.log('OK event stream minted all 4 notifications:', wantSubjects.join(' | '));

  await a.click('.nav >> text=Inbox');
  const note = a.locator('.row', { hasText: 'Order received' }).first();
  await note.waitFor({ timeout: 15000 });
  await note.locator('button', { hasText: 'Mark read' }).click();
  await a.locator('.row.noteread', { hasText: 'Order received' }).first().waitFor({ timeout: 10000 });
  console.log('OK inbox renders; mark-read works');

  // Notifications are party-scoped like everything else.
  const bInbox = JSON.parse((await apiGet(b,
    '/tmf-api/communicationManagement/v4/communicationMessage?limit=100', tokenB)).body);
  if (bInbox.length !== 0) fail('customer B sees foreign notifications: ' + JSON.stringify(bInbox).slice(0, 200));
  console.log('OK customer B has an empty inbox');

  // Idempotency + isolation: rerun bills nobody twice; B has no bills
  const rerun = await (await setup.request.post(
    `${API}/tmf-api/customerBillManagement/v4/billingRun`, { headers: staffHeaders })).json();
  if (rerun.billsCreated !== 0) fail('second billing run cut duplicate bills: ' + JSON.stringify(rerun));
  const bBills = JSON.parse((await apiGet(b,
    '/tmf-api/customerBillManagement/v4/customerBill?limit=10', tokenB)).body);
  if (bBills.length !== 0) fail('customer B sees bills: ' + JSON.stringify(bBills).slice(0, 200));
  console.log('OK billing rerun idempotent; customer B has no bills');

  await b.click('.nav >> text=My services');
  await b.locator('main .dim', { hasText: 'Nothing active yet' }).waitFor({ timeout: 10000 })
    .catch(async () => fail('customer B service list not empty: ' + await b.locator('main').textContent()));
  console.log('OK customer B sees no foreign services');

  await browser.close();
  console.log('\nALL STOREFRONT CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
