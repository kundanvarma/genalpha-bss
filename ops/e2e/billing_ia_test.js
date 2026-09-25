/* Billing & Revenue: six destinations, and the roles that may see each. Suite #239.
 *
 * The desk exposed fourteen peer tabs, so an operator had to know the ledger's
 * data model before they could find a task. It reads as the revenue lifecycle
 * now — Overview · Billing · Payments · Collections · Accounting · Configuration
 * — with every old tab re-homed underneath, its path, its title and its role
 * gate untouched (#117, spec #105).
 *
 * What this proves, through the gateway, with real tokens:
 *
 *  1. the six primaries, in lifecycle order, for the operator
 *  2. the second row carries only what the destination's own screen does NOT
 *     already offer as an area — no choice printed twice, one line above itself
 *  3. every one of the fourteen tabs still opens, and lands under the primary
 *     it was re-homed to (this is the deep-link contract)
 *  4. a gate inside a destination: finance-staff holds no risk:assess, so
 *     Collections shows Cases and Dunning and no Risk — and the API agrees
 *  5. a NARROW role sees only its own area: a CSR granted risk:assess through
 *     the console's own TMF672 door gets Billing & Revenue holding Collections
 *     ALONE, with Risk the only page under it. Revoked, the department goes.
 *  6. the gate has no back door: window.consoleGoTo, the islands' one way into
 *     a page from outside the rail, used to search EVERY resource rather than
 *     the visible ones, so it would open a page the token's roles hide.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const ROLES = `${API}/tmf-api/rolesAndPermissionsManagement/v4`;
const BILLING = `${API}/tmf-api/customerBillManagement/v4`;
const RISK = `${API}/tmf-api/riskManagement/v4`;

// the six, in the order the revenue lifecycle runs
const SIX = ['Overview', 'Billing', 'Payments', 'Collections', 'Accounting', 'Configuration'];

// where each of the fourteen old tabs now lives, and what the row calls it
const HOMES = {
  Overview: 'Overview',
  Bills: 'Billing',
  Disputes: 'Billing',
  Payments: 'Payments',
  'Unapplied cash': 'Payments',
  Collections: 'Collections',
  Dunning: 'Collections',
  Risk: 'Collections',
  Journal: 'Accounting',
  'Chart of accounts': 'Accounting',
  Configuration: 'Configuration',
  'Bill formats': 'Configuration',
  Deliveries: 'Configuration',
  'Shadow billing': 'Configuration',
};

// the second row exists only where the destination is more than one screen
const SUBNAV = {
  Overview: [],
  Billing: ['Bills', 'Disputes'],
  Payments: [],
  Collections: ['Cases', 'Dunning', 'Risk'],
  Accounting: [],
  Configuration: [],
};

let undo = null;   // always runs: the granted role must not outlive this suite
const fail = async (m) => {
  console.error('FAIL: ' + m);
  if (undo) await undo().catch(() => {});
  process.exit(1);
};

async function token(request, user, pass) {
  const res = await request.post(KC, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

async function login(browser, user, pass) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  // seven sign-ins, each a Keycloak round trip: the 30s default is a laptop
  // under load away from a failure that says nothing about this desk
  ctx.setDefaultNavigationTimeout(90000);
  const page = await ctx.newPage();
  await page.goto(`${API}/console/`);
  await page.waitForSelector('input[name="username"]', { timeout: 40000 });
  await page.fill('input[name="username"]', user);
  await page.fill('input[name="password"]', pass);
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('#main:not([hidden])', { timeout: 60000 });
  await page.waitForSelector('#tabs .tab', { timeout: 30000 });
  return { ctx, page };
}

const depts = (page) => page.evaluate(() =>
  [...document.querySelectorAll('#tabs .tabgroup-label')].map((e) => e.textContent));

const row = (page) => page.evaluate(() => ({
  primaries: [...document.querySelectorAll('#pagerow .primary-tab')].map((e) => e.textContent),
  on: document.querySelector('#pagerow .primary-tab.on')?.textContent || null,
  sub: [...document.querySelectorAll('#pagerow .subnav .pagetab')].map((e) => e.textContent),
  title: document.getElementById('resource-title')?.textContent || null,
}));

/* open the department by its rail label; returns the page row */
async function openDept(page, label = 'Billing & Revenue') {
  await page.locator('#tabs .tabgroup-label', { hasText: label }).first().click();
  await page.waitForSelector('#pagerow:not([hidden])', { timeout: 30000 });
  await page.waitForTimeout(1200);
  return row(page);
}

/* click a page tab by its EXACT title — the .tab stub contract the fleet's
 * suites use, which is what "every old tab reachable" means in practice */
async function openTab(page, title) {
  const hit = await page.evaluate((t) => {
    const b = [...document.querySelectorAll('#tabs .tab')].find((x) => x.textContent === t);
    if (!b) return false;
    b.click();
    return true;
  }, title);
  if (!hit) return null;
  await page.waitForTimeout(1500);
  return row(page);
}

(async () => {
  const browser = await chromium.launch();
  const api = await browser.newContext();

  /* ================== 1. the operator: six destinations ================== */
  const demo = await login(browser, 'demo', 'demo');
  let r = await openDept(demo.page);
  if (JSON.stringify(r.primaries) !== JSON.stringify(SIX)) {
    await fail(`Billing & Revenue primaries are ${JSON.stringify(r.primaries)}, expected ${JSON.stringify(SIX)}`);
  }
  console.log('OK the department reads as the revenue lifecycle: ' + SIX.join(' · '));

  /* ---- 2. the second row, and the choices it must NOT print twice ---- */
  for (const p of SIX) {
    await demo.page.evaluate((label) => {
      [...document.querySelectorAll('#pagerow .primary-tab')].find((b) => b.textContent === label)?.click();
    }, p);
    await demo.page.waitForTimeout(1600);
    const got = await row(demo.page);
    if (got.on !== p) await fail(`clicking ${p} lit ${got.on}`);
    if (JSON.stringify(got.sub) !== JSON.stringify(SUBNAV[p])) {
      await fail(`${p}'s second row is ${JSON.stringify(got.sub)}, expected ${JSON.stringify(SUBNAV[p])}`);
    }
    console.log(`  ${p} → ${got.title}${got.sub.length ? ' · ' + got.sub.join(' · ') : ' (one screen, its own areas)'}`);
  }
  // Accounting and Configuration ARE one screen each, and each carries its own
  // areas as chips — the assertion above is only worth something if the chips
  // are really there, otherwise the second row was removed and nothing replaced it
  await openTab(demo.page, 'Configuration');
  await demo.page.waitForSelector('[data-testid="configuration"]', { timeout: 30000 });
  const cfgAreas = await demo.page.evaluate(() =>
    [...document.querySelectorAll('[data-testid="configuration"] .chip')].map((c) => c.textContent));
  for (const a of ['Financial changes', 'Bill formats', 'Deliveries', 'Shadow billing']) {
    if (!cfgAreas.includes(a)) await fail(`Configuration dropped its second row but has no "${a}" area: ${cfgAreas}`);
  }
  await openTab(demo.page, 'Journal');
  await demo.page.waitForSelector('[data-testid="accounting"]', { timeout: 30000 });
  const accAreas = await demo.page.evaluate(() =>
    [...document.querySelectorAll('[data-testid="accounting"] .chip')].map((c) => c.textContent));
  for (const a of ['Journal', 'Chart of accounts']) {
    if (!accAreas.includes(a)) await fail(`Accounting dropped its second row but has no "${a}" area: ${accAreas}`);
  }
  console.log(`OK no choice is printed twice: Configuration's areas are ${cfgAreas.join(' · ')};`
    + ` Accounting's are ${accAreas.join(' · ')}`);

  /* ---- 3. every one of the fourteen still opens, under its new primary ---- */
  for (const [tab, home] of Object.entries(HOMES)) {
    const got = await openTab(demo.page, tab);
    if (!got) await fail(`the "${tab}" tab is gone — a deep link and the ⌘K palette both land on it`);
    if (got.title !== tab) await fail(`"${tab}" opened "${got.title}"`);
    if (got.on !== home) await fail(`"${tab}" opened under ${got.on}, expected ${home}`);
  }
  console.log(`OK all ${Object.keys(HOMES).length} old tabs open, each under the destination it was re-homed to`);
  await demo.ctx.close();

  /* ============ 4. finance-staff: the six, but no Risk under Collections ============ */
  const finn = await login(browser, 'finn@bss.local', 'finn');
  r = await openDept(finn.page);
  if (JSON.stringify(r.primaries) !== JSON.stringify(SIX)) {
    await fail(`finn's primaries are ${JSON.stringify(r.primaries)}, expected the six`);
  }
  await finn.page.evaluate(() => {
    [...document.querySelectorAll('#pagerow .primary-tab')].find((b) => b.textContent === 'Collections')?.click();
  });
  await finn.page.waitForTimeout(1600);
  const finnCollections = (await row(finn.page)).sub;
  if (JSON.stringify(finnCollections) !== JSON.stringify(['Cases', 'Dunning'])) {
    await fail(`finance-staff's Collections shows ${JSON.stringify(finnCollections)}, expected Cases and Dunning`
      + ' — Risk is risk:assess, which finance-staff does not hold');
  }
  // the negative pair: the hidden page's API refuses him too
  const finnTok = await token(api.request, 'finn@bss.local', 'finn');
  const finnRisk = await api.request.get(`${RISK}/partyRiskAssessment?limit=1`,
    { headers: { Authorization: 'Bearer ' + finnTok } });
  if (finnRisk.status() !== 403) {
    await fail(`Risk is hidden from finance-staff, so its API must refuse him — got ${finnRisk.status()}`);
  }
  console.log('OK FINN (finance-staff): all six destinations; Collections = Cases · Dunning,'
    + ' no Risk, and riskManagement answers him 403');

  /* ---- 6. the island door does not skip the rail's gate ---- */
  const hopped = await finn.page.evaluate(() => window.consoleGoTo('islandHealth'));
  if (hopped !== false) {
    await fail('consoleGoTo opened a page finance-staff cannot see — the islands\' door skips the rail\'s gate');
  }
  console.log('OK consoleGoTo refuses a page the token\'s roles hide (it searched every resource before)');
  await finn.ctx.close();

  /* ========= 5. a narrow role sees ONLY its area ========= */
  // departments are composite roles an IdP admin edits, not code — so this
  // grants one through the console's own TMF672 door and takes it back.
  const admin = await token(api.request, 'demo', 'demo');
  const H = { Authorization: 'Bearer ' + admin, 'Content-Type': 'application/json' };
  const users = await (await api.request.get(`${ROLES}/user?username=jo@bss.local`, { headers: H })).json();
  const jo = (users || [])[0];
  if (!jo || !jo.id) await fail('no jo@bss.local in the tenant IdP: ' + JSON.stringify(users));
  const grant = await api.request.post(`${ROLES}/permission`, { headers: H,
    data: { user: { id: jo.id }, userRole: { name: 'risk:assess' } } });
  if (!grant.ok()) await fail(`granting risk:assess to jo failed: ${grant.status()}`);
  const permId = (await grant.json()).id;
  undo = async () => api.request.delete(`${ROLES}/permission/${permId}`, { headers: H });

  const narrow = await login(browser, 'jo@bss.local', 'jo');
  const narrowDepts = await depts(narrow.page);
  if (!narrowDepts.includes('Billing & Revenue')) {
    await fail('a risk analyst sees no Billing & Revenue at all: ' + narrowDepts.join(' | '));
  }
  const nr = await openDept(narrow.page);
  if (JSON.stringify(nr.primaries) !== JSON.stringify(['Collections'])) {
    await fail(`a risk analyst sees ${JSON.stringify(nr.primaries)} — the desk should be Collections ALONE`);
  }
  if (JSON.stringify(nr.sub) !== JSON.stringify(['Risk'])) {
    await fail(`a risk analyst's Collections shows ${JSON.stringify(nr.sub)}, expected Risk alone`);
  }
  // the negative pair: the hidden destinations are walled by the API too.
  // Payments and Configuration are the honest pair — their endpoints are
  // billing:admin, which a risk analyst does not hold. (Bills is NOT: a bill
  // list is billing:read, which every customer token carries, so asserting a
  // 403 there would be asserting something that is not true.)
  const joTok = await token(api.request, 'jo@bss.local', 'jo');
  const joH = { Authorization: 'Bearer ' + joTok };
  for (const [area, url] of [['Payments', `${BILLING}/remittance/unapplied`],
    ['Configuration', `${BILLING}/billDistribution`]]) {
    const res = await api.request.get(url, { headers: joH });
    if (res.status() !== 403) {
      await fail(`${area} is hidden from a risk analyst, so its API must refuse them — got ${res.status()}`);
    }
  }
  console.log('OK NARROW (a CSR granted risk:assess): Billing & Revenue is Collections alone,'
    + ' and Risk is the only page under it — the other five destinations are not on their screen,'
    + ' and Payments and Configuration answer them 403');
  await narrow.ctx.close();

  /* ---- and the role taken back takes the destination with it ---- */
  await undo();
  undo = null;
  const after = await login(browser, 'jo@bss.local', 'jo');
  if ((await depts(after.page)).includes('Billing & Revenue')) {
    await fail('revoking risk:assess left the department standing — the gate reads the token, or it is not a gate');
  }
  console.log('OK revoked: the department goes with the role, and the fleet is as this suite found it');
  await after.ctx.close();

  /* ---- the desks that have no business here have none ---- */
  for (const [u, p] of [['pat@bss.local', 'pat'], ['gro@bss.local', 'gro'], ['omar@bss.local', 'omar']]) {
    const s = await login(browser, u, p);
    const d = await depts(s.page);
    if (d.includes('Billing & Revenue')) await fail(`${u} sees the money desk: ${d.join(' | ')}`);
    await s.ctx.close();
  }
  console.log('OK pat, gro and omar see no Billing & Revenue department at all');

  await browser.close();
  console.log('\nPASS billing_ia_test — the finance desk is six destinations on the revenue'
    + ' lifecycle, every old tab is re-homed and still opens, each destination shows only'
    + ' what its screen does not already carry, and a narrow role lands on its own area alone.');
})().catch(async (e) => {
  console.error('FAIL:', (e.message || String(e)).split('\n')[0]);
  if (undo) await undo().catch(() => {});
  process.exit(1);
});
