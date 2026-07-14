/* Family phase 3: age is data, and the guardian's window has meters —
 * for children only.
 *
 *  - a payer-created dependent with an ADULT birth date joins as a plain
 *    MEMBER; an under-18 birth date (or none — the safe default) makes a
 *    CHILD account
 *  - the payer/admin sees a CHILD's usage meters on the family hub; an
 *    adult member's usage stays their own even when the family pays —
 *    paying is not watching
 *  - a plain member cannot read anyone's meters through the family window
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const PLAN_ID = '14291c1a-df26-4232-8084-500466888e46'; // GenAlpha Mobile 10 GB

async function token(request, client, user, pass) {
  const res = await request.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const staff = await token(ctx.request, 'bss-demo', 'demo', 'demo');
  const PARTY = `${API}/tmf-api/party/v4/individual`;

  const mkLogin = async (given, family, email) => (await (await ctx.request.post(
    `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`, {
      headers: H(staff), data: { email, givenName: given, familyName: family } })).json());
  const mkPerson = async (given, family, email) => {
    const login = await mkLogin(given, family, email);
    await ctx.request.post(PARTY, { headers: H(staff),
      data: { id: login.id, givenName: given, familyName: family,
        contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
    return { id: login.id, email, password: login.temporaryPassword };
  };
  const heads = async (p) => H(await token(ctx.request, 'bss-biz', p.email, p.password));

  /* ---------- age decides the role at creation ---------- */
  const pia = await mkPerson('Pia', `Owner${run}`, `pia-${run}@family.example`);
  const PH = await heads(pia);
  const mkDependent = async (given, birthDate) => {
    const login = await mkLogin(given, `Dep${run}`, `${given.toLowerCase()}-${run}@family.example`);
    const created = await (await ctx.request.post(`${PARTY}/${pia.id}/dependents`,
      { headers: PH, data: { id: login.id, givenName: given, familyName: `Dep${run}`,
        ...(birthDate ? { birthDate } : {}),
        contactMedium: [{ mediumType: 'email',
          characteristic: { emailAddress: `${given.toLowerCase()}-${run}@family.example` } }] } })).json();
    return { id: login.id, email: `${given.toLowerCase()}-${run}@family.example`,
      password: login.temporaryPassword, role: created.householdPayer?.role };
  };
  const grownup = await mkDependent('Gro', '1990-05-01');
  const kid = await mkDependent('Kim', '2016-09-15');
  const undated = await mkDependent('Uma', null);
  if (grownup.role !== 'member') fail('an adult birth date should join as member: ' + grownup.role);
  if (kid.role !== 'child') fail('an under-18 birth date should join as child: ' + kid.role);
  if (undated.role !== 'child') fail('no birth date should default to child: ' + undated.role);
  console.log('OK age is data: 1990 joins as MEMBER, 2016 as CHILD, undated stays CHILD (safe default)');

  /* ---------- meters: the child's show, the adult's never ---------- */
  const meter = (party, value) => ctx.request.post(`${API}/tmf-api/usageManagement/v4/usage`, {
    headers: H(staff), data: { usageType: 'Mobile data', usageCharacteristic: { value, units: 'GB' },
      productOffering: { id: PLAN_ID }, relatedParty: [{ id: party, role: 'customer' }] } });
  await meter(kid.id, 3.5);
  await meter(grownup.id, 2);
  const usageAs = async (hdrs, party) => ctx.request.get(
    `${API}/tmf-api/usageConsumption/v4/queryUsageConsumption?relatedPartyId=${party}`, { headers: hdrs });
  const kidReport = await usageAs(PH, kid.id);
  if (kidReport.status() !== 200) fail('the payer cannot read the child\'s meters: ' + kidReport.status());
  const kidBucket = ((await kidReport.json()).bucket || []).find((b) => b.name === 'Mobile data');
  if (!kidBucket || Number(kidBucket.usedValue) !== 3.5) fail('child meter wrong: ' + JSON.stringify(kidBucket));
  if ((await usageAs(PH, grownup.id)).status() === 200) {
    fail('an ADULT member\'s usage leaked to the payer — paying is not watching');
  }
  const GH = await heads(grownup);
  if ((await usageAs(GH, kid.id)).status() === 200) {
    fail('a plain member read the child\'s meters — that is the payer/admin\'s window');
  }
  console.log('OK meters: the payer reads the CHILD\'s usage; the adult member\'s stays'
    + ' their own; a plain member gets no window at all');

  /* ---------- the hub shows the child's meter ---------- */
  const page = await (await browser.newContext()).newPage();
  await page.goto(`${API}/shop/`);
  await page.locator('.who >> text=Sign in').click();
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', pia.email);
  await page.fill('input[name="password"]', pia.password);
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.nav', { timeout: 20000 });
  await page.locator('.nav >> text=Family').click();
  const kidCard = page.locator(`[data-testid=fam-member-${kid.id}]`);
  await kidCard.waitFor({ timeout: 20000 });
  await kidCard.locator('[data-testid=fam-usage]').waitFor({ timeout: 15000 });
  const meterText = await kidCard.locator('[data-testid=fam-usage]').textContent();
  if (!meterText.includes('3.5')) fail('the hub meter shows wrong usage: ' + meterText);
  if (await page.locator(`[data-testid=fam-member-${grownup.id}] [data-testid=fam-usage]`).count()) {
    fail('an adult member\'s card grew a usage meter');
  }
  console.log('OK the hub: Kim\'s card carries a live usage bar (3.5 GB), Gro\'s card does not');

  /* ---------- the storefront add-family form takes a birth date ---------- */
  await page.locator('summary', { hasText: 'Add a family member' }).click();
  await page.fill('[data-testid=hh-add-given]', 'Nils');
  await page.fill('[data-testid=hh-add-family]', `Teen${run}`);
  await page.fill('[data-testid=hh-add-email]', `nils-teen-${run}@family.example`);
  await page.fill('[data-testid=hh-add-born]', '2012-03-03');
  await page.click('[data-testid=hh-add]');
  await page.locator('[data-testid=hh-credentials]').waitFor({ timeout: 20000 });
  await page.locator('[data-testid=role-chip]', { hasText: 'child' }).first().waitFor({ timeout: 15000 });
  console.log('OK the add-family form carries the birth date — the 2012 kid lands as a CHILD');

  await browser.close();
  console.log('\nALL FAMILY PHASE-3 CHECKS PASSED — birth dates decide roles, the guardian\'s'
    + ' window has meters for children only, and adults\' usage stays theirs.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
