/* #241 csr_wayfinding_test — the two dead ends the UX review found on the agent
 * console (#144), proven in a browser through the gateway with a real token.
 *
 *   1. CSR-UX-001 (#145) the brand is the way home. It was an image and two
 *      spans wired to nothing. Now: one link over the whole brand, to the
 *      desk's default workspace; an accessible name built from the TENANT's
 *      brand name (proven twice, the second time against a config the suite
 *      itself rewrites, so no constant can pass); first stop on Tab, with an
 *      outline a keyboard user can actually see; Enter goes home and so does a
 *      click on the logo image itself; and the logo keeps its onError fallback,
 *      proven by breaking the image on purpose.
 *   2. CSR-UX-007 (#151, the immediate half only) help never opens into a dead
 *      end. "No help written for this page yet." is gone; with no article the
 *      drawer offers the next step — search, ask where the agent holds ai:use,
 *      and suggestions that belong to the screen the agent is standing on
 *      (proven by comparing two screens). Picking one arms the search and Ask.
 *
 * NOT proven here, deliberately: the contextual knowledge assistant that is the
 * later half of #151. This suite would fail if that landed badly, not pass it.
 */
const os = require('os');
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const CSR = `${API}/csr/`;
const KC = 'http://localhost:8085';
const SHOTS = os.tmpdir();
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

async function token(user, pass) {
  const res = await fetch(`${KC}/realms/bss/protocol/openid-connect/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }),
  });
  if (!res.ok) fail(`token for ${user}: ${res.status}`);
  return (await res.json()).access_token;
}

/* The agent console signs in as an AGENT (agent-anna), never demo/demo, and a
 * fresh context ALWAYS meets Keycloak — so wait for the form unconditionally.
 * Counting the form first races the redirect and silently skips sign-in. */
async function agentLogin(page, username = 'agent-anna', password = 'agent') {
  await page.goto(CSR);
  await page.waitForSelector('input[name="username"]', { timeout: 30000 });
  await page.fill('input[name="username"]', username);
  await page.fill('input[name="password"]', password);
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.searchbar', { timeout: 30000 });
}

const shelf = async (tok, tag) => {
  const res = await fetch(`${API}/tmf-api/knowledgeManagement/v4/article?tag=${encodeURIComponent(tag)}`,
    { headers: { Authorization: `Bearer ${tok}`, 'X-Channel': 'care' } });
  if (!res.ok) fail(`knowledge shelf ${tag}: ${res.status} (is the knowledge service up?)`);
  return await res.json();
};

/** Every suggestion the drawer is offering, in order. */
const suggestions = (page) => page.$$eval('[data-testid="help-suggestion"]', (n) => n.map((b) => b.textContent.trim()));

async function openHelp(page) {
  await page.click('[data-testid="help-button"]');
  await page.waitForSelector('[data-testid="help-drawer"]', { timeout: 10000 });
}

/** Type a term nobody wrote an article about, and wait for the drawer to settle. */
async function searchNothing(page, term) {
  await page.fill('[data-testid="help-search"]', term);
  await page.waitForFunction(() => {
    const d = document.querySelector('[data-testid="help-drawer"]');
    return d && d.querySelector('[data-testid="help-empty"]') && !d.querySelector('[data-testid="help-article"]');
  }, null, { timeout: 15000 });
}

(async () => {
  const anna = await token('agent-anna', 'agent');

  /* a screen nobody has written help for — picked from the LIVE shelves, so the
   * proof does not depend on remembering which seed wrote what */
  const routes = { '/tickets': 'csr:tickets', '/chats': 'csr:chats', '/collections': 'csr:collections',
    '/devices': 'csr:devices', '/migrations': 'csr:migrations', '/registry': 'csr:registry' };
  let bare = null;
  for (const [route, tag] of Object.entries(routes)) {
    if (!(await shelf(anna, tag)).length) { bare = { route, tag }; break; }
  }
  if (!bare) fail('every CSR shelf has articles — the unauthored case cannot be reached; add a screen to `routes`');
  console.log(`  no help is authored for ${bare.tag} — that is the screen the dead end used to appear on`);

  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1360, height: 900 } });
  const page = await ctx.newPage();
  await agentLogin(page);

  /* ---------- 1. the brand is one accessible link home (#145) -------------- */
  const cfgBrand = await page.evaluate(() => ((window.BSS_CSR_CONFIG || {}).brandName || '').trim());
  if (!cfgBrand) fail('the gateway served no brandName for this host — a tenant-derived name cannot be proven');
  const link = page.locator('[data-testid="brand-home"]');
  if (await link.count() !== 1) fail(`the brand should be exactly one link, found ${await link.count()}`);
  if (await link.evaluate((a) => a.tagName) !== 'A') fail('the brand is not an <a> — a div with a click handler is not a link');
  const label = await link.getAttribute('aria-label');
  if (label !== `${cfgBrand} CSR home`) fail(`accessible name is "${label}", expected "${cfgBrand} CSR home"`);
  const href = await link.evaluate((a) => a.getAttribute('href'));
  if (!/\/csr\/?$/.test(href)) fail(`the brand links to "${href}", not the default workspace`);
  const inside = await link.evaluate((a) => ({
    logo: !!a.querySelector('img.brandlogo'), area: !!a.querySelector('.area'),
    stray: document.querySelectorAll('.top img.brandlogo, .top .area').length,
  }));
  if (!inside.logo || !inside.area) fail(`the logo and the wordmark must be inside the link (${JSON.stringify(inside)})`);
  if (inside.stray !== 2) fail(`${inside.stray} brand pieces in the header — a piece outside the link is not clickable`);
  console.log(`  brand: one <a> to ${href}, named "${label}" from the tenant's own config, logo + wordmark inside it`);

  /* the name is CONFIG, not a constant: rewrite the tenant config and reload */
  const realConfig = await (await fetch(`${CSR}tenant-config.js`)).text();
  const probe = 'Probe Brand';
  await page.route(`${CSR}tenant-config.js`, (route) => route.fulfill({
    status: 200, contentType: 'application/javascript',
    body: realConfig.replace(/brandName: '[^']*'/, `brandName: '${probe}'`),
  }));
  await page.reload();
  await page.waitForSelector('.searchbar', { timeout: 30000 });
  const probed = await page.locator('[data-testid="brand-home"]').getAttribute('aria-label');
  if (probed !== `${probe} CSR home`) fail(`with brandName "${probe}" the name is "${probed}" — it is not read from config`);
  await page.unroute(`${CSR}tenant-config.js`);
  await page.reload();
  await page.waitForSelector('.searchbar', { timeout: 30000 });
  console.log(`  brand: a rewritten brandName renames the link to "${probed}" — no tenant name is compiled in`);

  /* Tab reaches it first, the focus is VISIBLE, and Enter goes home */
  await page.goto(`${CSR}tickets`);
  await page.waitForSelector('[data-testid="brand-home"]', { timeout: 30000 });
  const quiet = await page.locator('[data-testid="brand-home"]').evaluate((a) => {
    const s = getComputedStyle(a); return { w: s.outlineWidth, style: s.outlineStyle };
  });
  await page.keyboard.press('Tab');
  const focused = await page.evaluate(() => {
    const a = document.activeElement;
    const s = getComputedStyle(a);
    return { testid: a.getAttribute('data-testid'), visible: a.matches(':focus-visible'),
      w: s.outlineWidth, style: s.outlineStyle, colour: s.outlineColor };
  });
  if (focused.testid !== 'brand-home') fail(`the first Tab lands on "${focused.testid}", not the brand`);
  if (!focused.visible) fail('the brand takes focus but does not match :focus-visible');
  // idle draws NO outline (a width alone means nothing while the style is none);
  // keyboard focus draws a real one at least 2px wide
  const grew = quiet.style === 'none' && focused.style !== 'none' && parseFloat(focused.w) >= 2;
  if (!grew) fail(`focus is not visible: outline ${quiet.style} ${quiet.w} → ${focused.style} ${focused.w}`);
  await page.screenshot({ path: `${SHOTS}/csr-brand-focus.png`, clip: { x: 0, y: 0, width: 700, height: 120 } });
  await page.keyboard.press('Enter');
  await page.waitForSelector('.searchbar', { timeout: 20000 });
  if (!/\/csr\/?$/.test(new URL(page.url()).pathname)) fail(`Enter on the brand landed on ${page.url()}`);
  console.log(`  brand: first stop on Tab, outline ${quiet.style} → ${focused.style} ${focused.w} ${focused.colour}, Enter lands on Customers`);

  /* and a mouse click on the LOGO IMAGE — not only on the text — goes home */
  await page.goto(`${CSR}tickets`);
  await page.waitForSelector('[data-testid="brand-home"] img.brandlogo', { timeout: 30000 });
  await page.click('[data-testid="brand-home"] img.brandlogo');
  await page.waitForSelector('.searchbar', { timeout: 20000 });
  if (!/\/csr\/?$/.test(new URL(page.url()).pathname)) fail(`clicking the logo landed on ${page.url()}`);
  console.log('  brand: a click on the logo image itself returns to the default workspace');

  /* ---------- 2. help never opens into a dead end (#151) ------------------- */
  await openHelp(page);
  const deadEnd = async () => (await page.locator('[data-testid="help-drawer"]').innerText()).includes('No help written for this page yet');

  /* the landing screen's own shelf resolves: the drawer used to ask the knowledge
   * base for `csr:customerss` and get nothing, so the FIRST screen an agent opens
   * showed the dead end while its article sat there unread */
  const authored = (await shelf(anna, 'csr:customers')).length;
  if (!authored) fail('no article is tagged csr:customers — re-run ops/seed/seed_knowledge*.py; the shelf proof needs one');
  await page.waitForSelector('[data-testid="help-article"]', { timeout: 15000 });
  const shown = await page.locator('[data-testid="help-article"]').count();
  if (shown < 1) fail(`csr:customers has ${authored} article(s) and the drawer shows ${shown} — the screen tag does not match the shelf`);
  if (await deadEnd()) fail('the landing screen still opens into the dead end');
  console.log(`  help on the landing screen: ${shown} of ${authored} authored article(s) on the shelf — no dead end where help exists`);
  const term = `zorblax tariff ${Date.now()}`;
  await searchNothing(page, term);
  if (await deadEnd()) fail('the old dead-end sentence is still rendered');
  const nextStep = (await page.locator('[data-testid="help-next-step"]').innerText()).trim();
  if (!/search/i.test(nextStep)) fail(`the fallback offers no next step: "${nextStep}"`);
  const onCustomers = await suggestions(page);
  if (onCustomers.length < 2) fail(`only ${onCustomers.length} suggestion(s) offered on Customers`);
  const canAsk = await page.locator('[data-testid="help-ask"]').count() > 0;
  console.log(`  help, nothing found: "${nextStep}" · ${onCustomers.length} suggestions · Ask ${canAsk ? 'offered' : 'withheld (no ai:use)'}`);

  /* a suggestion is a next step, not decoration: it arms search and Ask */
  await page.click(`[data-testid="help-suggestion"] >> nth=0`);
  const typed = await page.inputValue('[data-testid="help-search"]');
  if (typed !== onCustomers[0]) fail(`picking a suggestion put "${typed}" in the search box`);
  if (canAsk) {
    await page.waitForFunction(() => {
      const b = document.querySelector('[data-testid="help-ask"]');
      return b && !b.disabled;
    }, null, { timeout: 10000 });
  }
  console.log(`  help: picking "${typed.slice(0, 40)}…" searches it${canAsk ? ' and arms Ask' : ''}`);
  await page.screenshot({ path: `${SHOTS}/csr-help-no-dead-end.png`, fullPage: false });

  /* the suggestions belong to the SCREEN: another desk offers other questions */
  await page.goto(`${CSR}tickets`);
  await page.waitForSelector('[data-testid="help-button"]', { timeout: 30000 });
  await openHelp(page);
  await searchNothing(page, term);
  const onTickets = await suggestions(page);
  if (!onTickets.length) fail('no suggestions on the Tickets screen');
  if (JSON.stringify(onTickets) === JSON.stringify(onCustomers)) fail('the same suggestions on every screen — they are not contextual');
  console.log(`  help: Customers offers "${onCustomers[0]}", Tickets offers "${onTickets[0]}" — the set follows the screen`);

  /* the case the ticket was filed about: a screen NOBODY wrote help for */
  await page.goto(`${CSR}${bare.route.replace(/^\//, '')}`);
  await page.waitForSelector('[data-testid="help-button"]', { timeout: 30000 });
  await openHelp(page);
  await page.waitForSelector('[data-testid="help-empty"]', { timeout: 15000 });
  if (await deadEnd()) fail(`${bare.tag} still opens into the dead end`);
  const bareLead = (await page.locator('.help-none-lead').innerText()).trim();
  const bareIdeas = await suggestions(page);
  if (!/how can i help/i.test(bareLead)) fail(`the unauthored screen leads with "${bareLead}"`);
  if (!bareIdeas.length) fail(`${bare.tag} offers no next step`);
  await page.screenshot({ path: `${SHOTS}/csr-help-unauthored.png`, fullPage: false });
  console.log(`  help on ${bare.tag} (no article at all): "${bareLead}" + ${bareIdeas.length} suggestions, never an empty panel`);

  /* ---------- the logo's onError fallback is still wired ------------------- */
  await page.goto(CSR);
  await page.waitForSelector('.searchbar', { timeout: 30000 });
  await page.evaluate(() => { document.querySelector('[data-testid="brand-home"] img.brandlogo').src = '/csr/no-such-logo.png'; });
  await page.waitForFunction(() => document.querySelector('[data-testid="brand-home"] img.brandlogo').style.display === 'none',
    null, { timeout: 10000 });
  if (await page.locator('[data-testid="brand-home"]').count() !== 1) fail('the brand link vanished with the logo');
  console.log('  brand: a broken logo still hides itself (onError kept) and the link home survives it');

  await browser.close();
  console.log(`  screenshots: ${SHOTS}/csr-brand-focus.png, ${SHOTS}/csr-help-no-dead-end.png, ${SHOTS}/csr-help-unauthored.png`);
  console.log('PASS csr_wayfinding_test');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
