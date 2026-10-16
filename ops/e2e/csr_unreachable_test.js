/* EMPTY IS NOT UNAVAILABLE (#289).
 *
 * bss-recommendation was stopped for five days and the CSR 360 said
 * "Nothing to suggest." the whole time: the read caught its own 500, returned
 * `[]`, and `[]` rendered as a fact about the customer. An agent on a retention
 * call was told there was nothing to offer. The agreements card had the same
 * shape — "no agreements" while the agreement service was simply down.
 *
 * So this suite takes the two components away on purpose and asserts the desk
 * says it could not reach them, and that it does NOT say "none". Then it brings
 * them back and asserts the warning clears — a banner that never clears would
 * pass the first half and be as useless as the silence it replaced.
 *
 * It stops real containers. They are restarted in `finally`, whatever happens,
 * and the suite waits for them to be healthy before it exits.
 */
const { chromium } = require('playwright');
const { execSync } = require('child_process');

const CSR = 'http://localhost:8080/csr/';
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const TAKEN = ['bss-recommendation', 'bss-agreement'];

const sh = (cmd) => execSync(cmd, { encoding: 'utf8' }).trim();
const healthy = (c) => sh(`docker inspect -f '{{.State.Health.Status}}' ${c}`) === 'healthy';

async function waitHealthy(names) {
  for (let i = 0; i < 60; i++) {
    if (names.every(healthy)) return;
    await new Promise((r) => setTimeout(r, 5000));
  }
  throw new Error(`${names.join(', ')} did not come back healthy within 5 minutes`);
}

async function paula() {
  const res = await fetch(KC, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: 'agent-anna', password: 'agent' }),
  });
  if (!res.ok) throw new Error(`token for agent-anna: HTTP ${res.status}`);
  const { access_token: token } = await res.json();
  const found = await (await fetch(`${API}/tmf-api/party/v4/individual?givenName=Paula&familyName=Family&limit=5`, {
    headers: { Authorization: `Bearer ${token}`, 'X-Channel': 'csr' },
  })).json();
  // "Paula Family" is also a substring of seeded payers; match the whole name
  const her = found.find((p) => p.givenName === 'Paula' && p.familyName === 'Family');
  if (!her) throw new Error('the demo customer Paula Family is not in the party register');
  return her.id;
}

(async () => {
  const fail = (msg) => { throw new Error(msg); };
  for (const c of TAKEN) if (!healthy(c)) fail(`${c} is not healthy before the suite starts — nothing to prove`);
  const id = await paula();

  const browser = await chromium.launch();
  const page = await (await browser.newContext()).newPage();
  try {
    await page.goto(CSR);
    await page.waitForSelector('input[name="username"]', { timeout: 20000 });
    await page.fill('input[name="username"]', 'agent-anna');
    await page.fill('input[name="password"]', 'agent');
    await page.click('input[type="submit"], button[type="submit"]');
    await page.waitForSelector('.searchbar', { timeout: 20000 });

    sh(`docker stop ${TAKEN.join(' ')}`);
    console.log(`OK stopped ${TAKEN.join(' and ')}`);

    await page.goto(`${CSR}customer/${id}`);
    await page.locator('h1', { hasText: 'Paula' }).waitFor({ timeout: 20000 });

    const suggest = page.locator('[data-testid="suggest-card"]');
    await suggest.locator('[data-testid="unreachable"]').waitFor({ timeout: 20000 });
    const said = (await suggest.innerText()).replace(/\s+/g, ' ');
    if (/Nothing to suggest/.test(said)) fail(`the engine is down and the card still says "Nothing to suggest.": "${said}"`);
    if (!/recommendation engine/.test(said)) fail(`the card does not name what it could not reach: "${said}"`);
    console.log(`OK Suggest next names the outage instead of an empty answer: "${said.trim()}"`);

    const strip = (await page.locator('[data-testid="now-account"] [data-testid="agreements-card"]').innerText()).trim();
    if (strip === 'no agreements') fail('the agreement service is down and the account strip says "no agreements"');
    if (!/unreachable/.test(strip)) fail(`the account strip does not say the agreements are unreachable: "${strip}"`);
    console.log(`OK the account strip says "${strip}", not "no agreements"`);

    await page.goto(`${CSR}customer/${id}#billing`);
    const heading = page.locator('h2', { hasText: 'Agreements' });
    await heading.locator('[data-testid="unreachable"]').waitFor({ timeout: 20000 });
    const h = (await heading.innerText()).replace(/\s+/g, ' ');
    if (/— none/.test(h)) fail(`the Agreements card is marked "none" while its service is down: "${h}"`);
    console.log(`OK the Agreements card says "${h.trim()}"`);

    // the rest of the page still works: one dead component does not blank the 360
    if (!(await page.locator('h2', { hasText: 'Bills' }).count())) fail('the billing area lost its Bills card when two other components went down');
    console.log('OK the rest of the billing area still renders');

    sh(`docker start ${TAKEN.join(' ')}`);
    await waitHealthy(TAKEN);
    console.log(`OK ${TAKEN.join(' and ')} are healthy again`);

    await page.goto(`${CSR}customer/${id}`);
    await page.locator('h1', { hasText: 'Paula' }).waitFor({ timeout: 20000 });
    // the answer is either rows or a real "Nothing to suggest." — what matters is that the outage notice is gone
    await suggest.locator('.row, p.dim').first().waitFor({ timeout: 20000 });
    if (await page.locator('[data-testid="unreachable"]').count()) {
      fail('the components are back and the 360 still says it could not reach them — a warning that never clears is noise');
    }
    console.log('OK with both components back, the 360 shows their answers and no outage notice');
  } finally {
    await browser.close();
    try { sh(`docker start ${TAKEN.join(' ')}`); } catch (e) { console.error(`could not restart ${TAKEN.join(' ')}: ${e.message}`); }
    await waitHealthy(TAKEN).catch((e) => console.error(e.message));
  }
  console.log('PASS csr_unreachable_test — a dead component reads as "could not reach", never as "none"');
})().catch((e) => { console.error('FAIL: ' + (e && e.message || e)); process.exit(1); });
