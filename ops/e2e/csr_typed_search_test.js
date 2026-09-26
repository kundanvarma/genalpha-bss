/* Suite #243 — one search box, typed results, and a row you can actually hit.
 *
 * CSR-UX-002 (#146) and CSR-UX-003 (#147), proven in a browser through the
 * gateway with a real agent token:
 *
 *   1. the box says what it searches — "Search customers, orders, tickets,
 *      subscriptions…" — and nothing else;
 *   2. TWO CUSTOMERS WHO SHARE AN EMAIL AND A NAME can be told apart from the
 *      results alone: different customer reference, different phone, different
 *      subscription count. This is the case the review says misleads an agent,
 *      and the demo tenant really does hold such pairs;
 *   3. the whole row is ONE target: the row element is the anchor, it spans the
 *      list, a click at its far edge opens it, hover changes it visibly;
 *   4. the keyboard reaches it: ↓ from the box focuses the first result with a
 *      visible ring, ↓ again the second, Enter opens THAT one; Tab reaches rows
 *      too;
 *   5. every result is typed: a reference lands in the group that names what it
 *      is — Customers, Subscriptions, Orders, Tickets, Devices — and opening it
 *      lands on that object's own place, not on a list to search again;
 *   6. a phone number finds the line AND who holds it, both typed;
 *   7. nothing found says WHY, because free text reaches customers only.
 *
 * Seeds two parties (no Keycloak login: the party API takes a caller-supplied
 * id) and one product, and deletes all three at the end — a search suite that
 * leaks customers poisons the next agent's search results.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const CSR = `${API}/csr/`;
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const SHOTS = require('os').tmpdir();
const run = Date.now();
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const ok = (m) => console.log('  ok  ' + m);

async function token(user, pass) {
  const res = await fetch(KC, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }),
  });
  if (!res.ok) fail(`token for ${user}: ${res.status}`);
  return (await res.json()).access_token;
}

async function call(method, url, tok, body) {
  const res = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tok}`, 'X-Channel': 'care' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not json */ }
  return { status: res.status, json, text };
}

const uuid = () => 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
  const r = Math.random() * 16 | 0;
  return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
});

/* The agent console signs in as agent-anna, and a fresh context ALWAYS meets
 * Keycloak — so wait for the form unconditionally. Asking whether it is there
 * with count() races the redirect and silently skips the sign-in. */
async function agentLogin(page, username, password) {
  await page.goto(CSR);
  await page.waitForSelector('input[name="username"]', { timeout: 30000 });
  await page.fill('input[name="username"]', username);
  await page.fill('input[name="password"]', password);
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.searchbar', { timeout: 30000 });
}

/* Fill the box and wait for the rows that answer THIS query. The page carries
 * the settled query on the form (data-settled) because a spinner blinks twice
 * while the caret moves — waiting on "not Searching…" caught the results of the
 * previous query once, and a flake in a proof is worse than a red one. */
async function search(page, q) {
  const box = page.locator('[data-testid="cust-search"]');
  await box.fill('');
  await box.fill(q);
  try {
    await page.waitForSelector(`.searchbar[data-settled="${q}"]`, { timeout: 40000 });
  } catch (e) {
    const said = (await page.locator('main').innerText()).replace(/\s+/g, ' ').slice(0, 240);
    throw new Error(`the desk never settled on "${q}" — it says: ${said}`);
  }
  return box;
}

const rowsIn = (page, group) => page.locator(`[data-testid="search-results"] section:has([data-testid="group-${group}"]) [data-result-row]`);

(async () => {
  const anna = await token('agent-anna', 'agent');
  const staff = await token('demo', 'demo');       // seeds, and the device desk role

  /* ---------- 1. the pair that misleads: one email, one name, two people ---- */
  const email = `tvilling-${run}@example.com`;
  const family = `Tvilling${run}`;
  const twins = [
    { id: uuid(), phone: '+4790000001', products: 1 },
    { id: uuid(), phone: '+4790000002', products: 0 },
  ];
  const seededProducts = [];
  /* Defined BEFORE the seeding loop on purpose: a seed that dies half-way used
   * to leave a customer behind, and four suites were fixed for exactly that. */
  const cleanup = async () => {
    for (const id of seededProducts) await call('DELETE', `${API}/tmf-api/productInventory/v4/product/${id}`, staff);
    for (const t of twins) await call('DELETE', `${API}/tmf-api/party/v4/individual/${t.id}`, staff);
  };
  const die = async (m) => { await cleanup(); fail(m); };

  for (const t of twins) {
    const made = await call('POST', `${API}/tmf-api/party/v4/individual`, staff, {
      id: t.id, givenName: 'Kari', familyName: family,
      contactMedium: [
        { mediumType: 'email', characteristic: { emailAddress: email } },
        { mediumType: 'phone', characteristic: { phoneNumber: t.phone } },
      ],
    });
    if (made.status !== 201) await die(`seeding a twin: ${made.status} ${made.text}`);
    for (let i = 0; i < t.products; i += 1) {
      const p = await call('POST', `${API}/tmf-api/productInventory/v4/product`, staff, {
        name: `Suite Fiber ${run}`, status: 'active',
        relatedParty: [{ id: t.id, role: 'customer' }],
      });
      if (p.status !== 201) await die(`seeding a subscription: ${p.status} ${p.text}`);
      seededProducts.push(p.json.id);
    }
  }
  ok(`seeded two customers named Kari ${family} on one email ${email}`);

  /* ---------- 2. references the suite will paste in, discovered live -------- */
  const firstWithOwner = async (path) => {
    const list = (await call('GET', `${API}${path}`, anna)).json || [];
    for (const o of list) {
      const owner = (o.relatedParty || []).find((p) => p.role === 'customer')?.id
        || (o.relatedParty || [])[0]?.id;
      if (!owner || owner.startsWith('op-')) continue;
      if ((await call('GET', `${API}/tmf-api/party/v4/individual/${owner}`, anna)).status === 200) return { obj: o, owner };
    }
    return null;
  };
  const anOrder = await firstWithOwner('/tmf-api/productOrderingManagement/v4/productOrder?limit=20');
  const aTicket = await firstWithOwner('/tmf-api/troubleTicket/v4/troubleTicket?limit=20');
  if (!anOrder) await die('the tenant holds no order with a customer this desk can open');
  if (!aTicket) await die('the tenant holds no ticket with a customer this desk can open');

  const services = (await call('GET', `${API}/tmf-api/serviceInventory/v4/service?limit=40`, anna)).json || [];
  const withNumber = services.find((s) => (s.supportingResource || []).some((r) => r.value)
    && ((s.relatedParty || []).some((p) => p.id && !p.id.startsWith('op-'))));
  if (!withNumber) await die('no running service carries a number — the number lookup cannot be proven');
  const msisdn = (withNumber.supportingResource || []).map((r) => r.value).find(Boolean);

  const agreements = (await call('GET', `${API}/tmf-api/deviceCommerce/v1/deviceAgreement`, staff)).json || [];
  const anAgreement = agreements.find((a) => (a.relatedParty || []).length);
  if (!anAgreement) await die('the tenant holds no device agreement — the device result cannot be proven');
  ok(`references in hand: order ${anOrder.obj.id.slice(0, 8)}…, ticket ${aTicket.obj.id.slice(0, 8)}…, `
    + `subscription ${seededProducts[0].slice(0, 8)}…, number ${msisdn}, device ${anAgreement.id.slice(0, 8)}…`);

  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1360, height: 980 } });
  const a = await ctx.newPage();
  a.on('pageerror', (e) => console.error('  console error: ' + e.message));
  const stop = async (m) => { await cleanup(); await browser.close(); fail(m); };

  try {
    await agentLogin(a, 'agent-anna', 'agent');
    ok('agent-anna is on the desk');

    /* ---------- 3. the box says what it searches ---------------------------- */
    const prompt = await a.locator('[data-testid="cust-search"]').getAttribute('placeholder');
    if (prompt !== 'Search customers, orders, tickets, subscriptions…') {
      await stop(`the prompt should be the simple one, not "${prompt}"`);
    }
    ok(`one box, one prompt: "${prompt}"`);

    /* ---------- 4. the pair, told apart from the results alone -------------- */
    await search(a, email);
    const people = rowsIn(a, 'customer');
    await people.first().waitFor({ timeout: 20000 });
    if (await people.count() !== 2) await stop(`one email, two customers — got ${await people.count()} rows`);
    const shown = await people.evaluateAll((els) => els.map((el) => ({
      text: el.innerText.replace(/\s+/g, ' '),
      href: el.getAttribute('href'),
    })));
    for (const t of twins) {
      const mine = shown.find((s) => s.href === `/csr/customer/${t.id}`);
      if (!mine) await stop(`no row lands on twin ${t.id}`);
      if (!mine.text.includes(t.phone)) await stop(`the row for ${t.id} does not carry its phone ${t.phone}`);
      if (!mine.text.includes(t.id.replace(/-/g, '').slice(0, 8))) {
        await stop(`the row for ${t.id} does not carry its customer reference`);
      }
      const wanted = t.products === 1 ? '1 active subscription' : 'no subscriptions yet';
      if (!mine.text.includes(wanted)) await stop(`the row for ${t.id} should say "${wanted}": ${mine.text}`);
    }
    if (shown[0].text === shown[1].text) await stop('the two rows read identically — an agent cannot choose');
    ok('two customers, one email, one name: reference, phone and subscription count tell them apart');
    if (!shown.every((s) => /^customer /i.test(s.text))) await stop('a customer row does not say it is a customer');
    ok('each row says what it IS before anything else');

    /* ---------- 5. the whole row is one target ----------------------------- */
    const geometry = await a.evaluate(() => {
      const row = document.querySelector('[data-result-row]');
      const list = row.closest('.rows');
      return {
        tag: row.tagName, width: row.getBoundingClientRect().width,
        listWidth: list.getBoundingClientRect().width, height: row.getBoundingClientRect().height,
      };
    });
    if (geometry.tag !== 'A') await stop(`the row itself should be the link, not a word inside it (got <${geometry.tag}>)`);
    if (geometry.width < geometry.listWidth * 0.98) await stop('the link does not span the row');
    if (geometry.height < 40) await stop(`the target is ${geometry.height}px tall — too small to aim at`);
    ok(`the row IS the link: <a> ${Math.round(geometry.width)}×${Math.round(geometry.height)}px, the full width of the list`);

    const paint = async () => a.evaluate(() => getComputedStyle(document.querySelector('[data-result-row]')).backgroundColor);
    const resting = await paint();
    await a.locator('[data-result-row]').first().hover();
    const hovered = await paint();
    if (resting === hovered) await stop(`hover changes nothing (${resting})`);
    ok(`hover is visible: ${resting} → ${hovered}`);

    /* ---------- 6. the keyboard, with a visible ring ------------------------ */
    await a.locator('[data-testid="cust-search"]').focus();
    await a.keyboard.press('ArrowDown');
    const first = await a.evaluate(() => {
      const el = document.activeElement;
      const s = getComputedStyle(el);
      return { isRow: el.hasAttribute('data-result-row'), href: el.getAttribute('href'),
        outline: s.outlineWidth, style: s.outlineStyle, matches: el.matches(':focus-visible') };
    });
    if (!first.isRow) await stop('↓ from the search box does not reach the first result');
    if (parseFloat(first.outline) < 2 || first.style === 'none') {
      await stop(`the focused row has no visible ring (outline ${first.style} ${first.outline})`);
    }
    if (!first.matches) await stop('the focused row does not match :focus-visible — the ring may not be drawn for a keyboard user');
    ok(`↓ focuses the first result with a ${first.outline} ${first.style} ring, :focus-visible and all`);

    await a.keyboard.press('ArrowDown');
    const second = await a.evaluate(() => document.activeElement.getAttribute('href'));
    if (!second || second === first.href) await stop('↓ again does not move to the next result');
    await a.keyboard.press('Enter');
    await a.waitForURL((u) => u.pathname === second, { timeout: 20000 });
    ok(`↓ ↓ Enter opened the second result — ${second}`);

    await a.goBack();
    await search(a, email);
    await rowsIn(a, 'customer').first().waitFor({ timeout: 20000 });
    await a.locator('[data-testid="cust-search"]').focus();
    await a.keyboard.press('Tab');                 // past the Search button
    await a.keyboard.press('Tab');
    if (!await a.evaluate(() => document.activeElement.hasAttribute('data-result-row'))) {
      await stop('Tab from the box does not reach the results');
    }
    ok('Tab reaches the results too');

    /* ---------- 7. a click at the far edge of the row opens it -------------- */
    const target = twins[0];
    const row = a.locator(`[data-result-row][href="/csr/customer/${target.id}"]`);
    const box = await row.boundingBox();
    await a.mouse.click(box.x + box.width - 8, box.y + box.height / 2);
    await a.waitForURL(`**/customer/${target.id}`, { timeout: 20000 });
    ok('a click 8px from the right edge of the row opens that customer — the whole row is the target');

    /* ---------- 8. every reference lands in the group that names it --------- */
    const typed = async (ref, group, wantHref, what) => {
      await a.goto(CSR);
      await a.waitForSelector('.searchbar', { timeout: 20000 });
      await search(a, ref);
      const grp = a.locator(`[data-testid="group-${group}"]`);
      await grp.waitFor({ timeout: 20000 });
      const hit = rowsIn(a, group).first();
      const href = await hit.getAttribute('href');
      if (href !== wantHref) await stop(`${what} should open ${wantHref}, not ${href}`);
      const text = (await hit.innerText()).replace(/\s+/g, ' ');
      if (!new RegExp(`^${group.toUpperCase()} `, 'i').test(text)) await stop(`${what} is not labelled ${group}: ${text}`);
      if (!text.includes(ref.replace(/-/g, '').slice(0, 8))) await stop(`${what} does not show the reference it matched: ${text}`);
      ok(`${what} → group "${group}", opens ${wantHref}`);
      return text;
    };

    await typed(anOrder.obj.id, 'order', `/csr/customer/${anOrder.owner}#activity`, 'an order reference');
    if (!await a.locator('[data-testid="group-customer"]').count()) {
      await stop('an order reference does not also offer the customer who owns it');
    }
    ok('…and the customer behind it gets a row of their own');

    await typed(aTicket.obj.id, 'ticket', `/csr/customer/${aTicket.owner}#activity`, 'a ticket reference');
    await typed(seededProducts[0], 'subscription', `/csr/customer/${twins[0].id}#services`, 'a subscription reference');

    /* ---------- 9. a number finds the line and the holder ------------------- */
    await a.goto(CSR);
    await a.waitForSelector('.searchbar', { timeout: 20000 });
    await search(a, msisdn);
    await a.locator('[data-testid="group-subscription"]').waitFor({ timeout: 20000 });
    const line = (await rowsIn(a, 'subscription').first().innerText()).replace(/\s+/g, ' ');
    if (!line.includes(msisdn)) await stop(`the line found by ${msisdn} does not show the number: ${line}`);
    if (!await a.locator('[data-testid="group-customer"]').count()) {
      await stop('a number finds the line but not who holds it');
    }
    ok(`${msisdn} → the line it runs on, and the customer who holds it, both typed`);

    /* ---------- 10. nothing found says why ---------------------------------
     * Letters only, and none of the run's digits: party search falls back to a
     * pg_trgm typo net when strict finds nothing, and `nobody-<run>` shares
     * enough trigrams with `tvilling-<run>@example.com` to come back as a
     * near miss. A query that must find nothing has to share nothing. */
    await search(a, 'zzzqqqxwwv');
    const none = a.locator('[data-testid="search-nothing"]');
    if (!await none.count()) {
      await stop('an empty result should say so; the page says: '
        + (await a.locator('main').innerText()).replace(/\s+/g, ' ').slice(0, 300));
    }
    const nothing = await none.innerText();
    if (!/reference/i.test(nothing)) await stop(`an empty result should say how the other types are found: ${nothing}`);
    ok('nothing found says why, and how to find an order or a ticket instead');

    /* ---------- 11. a device result opens that agreement ------------------- */
    await search(a, email);                        // a shot of the pair for the record
    await rowsIn(a, 'customer').first().waitFor({ timeout: 20000 });
    await a.screenshot({ path: `${SHOTS}/typed-search-twins-${run}.png`, fullPage: false });
    console.log('  shot ' + `${SHOTS}/typed-search-twins-${run}.png`);

    // a FRESH context: anna's single-sign-on session is context-wide, so a new
    // tab beside her lands on her desk and never meets the sign-in form
    const d = await (await browser.newContext({ viewport: { width: 1360, height: 980 } })).newPage();
    await agentLogin(d, 'demo', 'demo');           // the device desk needs device:read
    await search(d, anAgreement.id);
    await d.locator('[data-testid="group-device"]').waitFor({ timeout: 20000 });
    const dev = d.locator('[data-testid="result-device"]').first();
    const devHref = await dev.getAttribute('href');
    if (devHref !== `/csr/devices?agreement=${anAgreement.id}`) {
      await stop(`a device result should open its own agreement, not the desk list: ${devHref}`);
    }
    console.log('  shot ' + `${SHOTS}/typed-search-device-${run}.png`);
    await d.screenshot({ path: `${SHOTS}/typed-search-device-${run}.png`, fullPage: false });
    await dev.click();
    await d.locator('[data-testid="agreement-focus"]').waitFor({ timeout: 20000 });
    // the desk fetches its agreements after it renders: wait for the row, not
    // for the note, or the count is taken while the list is still empty
    await d.locator('[data-testid="agreement-row"]').first().waitFor({ timeout: 20000 });
    const shownRows = await d.locator('[data-testid="agreement-row"]').count();
    if (shownRows !== 1) await stop(`the device desk should show the one agreement, not ${shownRows}`);
    ok('a device reference opens THAT agreement on the device desk, not the whole list');
    await d.locator('[data-testid="agreement-focus-clear"]').click();
    await d.waitForFunction(() => document.querySelectorAll('[data-testid="agreement-row"]').length > 1,
      null, { timeout: 20000 }).catch(() => null);
    if (await d.locator('[data-testid="agreement-row"]').count() < 2) {
      await stop('"Show every agreement" does not bring the list back');
    }
    ok('and "Show every agreement" is the way back');
  } catch (e) {
    await stop(`unexpected: ${e.message}`);
  }

  await cleanup();
  const left = (await call('GET', `${API}/tmf-api/party/v4/individual?q=${family}`, staff)).json || [];
  if (left.length) fail(`the suite leaked ${left.length} customer(s) named ${family}`);
  ok('the seeded pair and its subscription are gone');
  await browser.close();
  console.log('PASS: one box, typed results, and a row an agent can hit with a mouse or a keyboard');
})().catch((e) => fail(e.stack || e.message));
