/* Passwords follow NIST SP 800-63B rev 4 (#320, first leg: #324).
 *
 * Every tenant's sign-in enforces the length rules, and says so in plain words, on
 * Keycloak's own pages — the ones a customer actually sees:
 *
 *   1. every realm reports the policy the security settings declare, read back live
 *      (not what a file says), and the plain-language words for it in en and no
 *   2. a FRESHLY ONBOARDED tenant carries it from its first minute
 *   3. registering with a 14-character password is refused in plain words; the
 *      email address as the password is refused; a 15+ phrase with spaces and
 *      Norwegian letters is accepted — and then signs in
 *   4. existing logins still sign in (a policy is checked when a password is set)
 *
 * Tenant: genalpha (realm bss) for the registration, through the shop.
 */
const { chromium, request } = require('playwright');
const fs = require('fs');
const path = require('path');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085';
const SHOP = `${API}/shop/`;
const SECURITY = path.join(__dirname, '..', '..', 'infra', 'keycloak', 'security');
const POLICY = fs.readFileSync(path.join(SECURITY, 'password-policy'), 'utf8').trim();
const MESSAGES = Object.fromEntries(fs.readdirSync(SECURITY).filter((f) => /^messages-[a-z]+\.json$/.test(f))
  .map((f) => [f.replace(/^messages-|\.json$/g, ''), JSON.parse(fs.readFileSync(path.join(SECURITY, f), 'utf8'))]));
const run = Date.now();
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

async function admin(ctx) {
  const r = await ctx.post(`${KC}/realms/master/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: process.env.KEYCLOAK_ADMIN_PASSWORD || 'admin' } });
  if (!r.ok()) fail(`master admin token: ${r.status()}`);
  return (await r.json()).access_token;
}
async function token(ctx, realm, user, pass) {
  const r = await ctx.post(`${KC}/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return r.ok() ? (await r.json()).access_token : null;
}
/** a realm's sign-in rules as Keycloak serves them right now */
async function rulesOf(ctx, adm, realm) {
  const r = await (await ctx.get(`${KC}/admin/realms/${realm}`, { headers: { Authorization: `Bearer ${adm}` } })).json();
  const words = {};
  for (const locale of Object.keys(MESSAGES)) {
    words[locale] = await (await ctx.get(`${KC}/admin/realms/${realm}/localization/${locale}`,
      { headers: { Authorization: `Bearer ${adm}` } })).json();
  }
  return { policy: r.passwordPolicy || '', words };
}
function sameRules(rules, realm) {
  if (rules.policy !== POLICY) fail(`realm ${realm} enforces "${rules.policy}", the settings declare "${POLICY}"`);
  for (const [locale, keys] of Object.entries(MESSAGES)) {
    for (const [key, text] of Object.entries(keys)) {
      if ((rules.words[locale] || {})[key] !== text) fail(`realm ${realm} (${locale}) does not say "${text.slice(0, 40)}…" for ${key}`);
    }
  }
}

(async () => {
  const ctx = await request.newContext();
  const adm = await admin(ctx);

  /* ---------- 1. every realm, read back live ---------- */
  const realms = (await (await ctx.get(`${KC}/admin/realms`, { headers: { Authorization: `Bearer ${adm}` } })).json())
    .map((r) => r.realm).filter((r) => r !== 'master');
  if (!realms.includes('bss')) fail(`unexpected realm list: ${realms.join(', ')}`);
  for (const realm of realms) sameRules(await rulesOf(ctx, adm, realm), realm);
  console.log(`OK EVERY REALM: ${realms.length} realm(s) enforce "${POLICY}" with its words in ${Object.keys(MESSAGES).join(' and ')} — read from Keycloak, not from a file`);

  /* ---------- 2. a freshly onboarded tenant, from its first minute ---------- */
  const staff = await token(ctx, 'bss', 'demo', 'demo');
  if (!staff) fail('demo/demo no longer signs in to genalpha — an existing login must keep working');
  const OP = `pw${String(run).slice(-6)}`;
  const made = await ctx.post(`${API}/onboarding/v1/operator`, {
    headers: { Authorization: `Bearer ${staff}`, 'Content-Type': 'application/json' },
    data: { id: OP, name: `Policy Probe ${OP}`, locale: 'en', currency: 'EUR', color: '#335577' }, timeout: 180000 });
  if (made.status() !== 201 && made.status() !== 200) fail(`onboarding ${OP}: ${made.status()} ${(await made.text()).slice(0, 200)}`);
  const receipt = await made.json();
  sameRules(await rulesOf(ctx, adm, OP), OP);
  if (!receipt.staffPassword || receipt.staffPassword.length < 15) fail('the new operator\'s staff login was handed over without a password that meets the policy');
  if (!(await token(ctx, OP, receipt.staffUsername, receipt.staffPassword))) fail('the handed-over staff login does not sign in to the new tenant');
  console.log(`OK NEW TENANT: ${OP} was born with the rules, and its first staff login (handed over once) meets them and signs in`);

  /* ---------- 3. the customer's own page ---------- */
  const browser = await chromium.launch();
  const page = await (await browser.newContext()).newPage();
  const email = `pw-${run}@family.example`;
  await page.goto(SHOP);
  await page.click('.who >> text=Sign in');
  await page.waitForSelector('a[href*="registration"], input[name="username"]', { timeout: 20000 });
  await page.click('a[href*="registration"]');
  await page.waitForSelector('input[name="email"]');
  const attempt = async (pw) => {
    await page.fill('input[name="firstName"]', 'Pia');
    await page.fill('input[name="lastName"]', `Policy${run}`);
    await page.fill('input[name="email"]', email);
    await page.fill('input[name="password"]', pw);
    await page.fill('input[name="password-confirm"]', pw);
    await page.click('input[type="submit"], button[type="submit"]');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForTimeout(1200);
  };
  const shown = async () => (await page.locator('body').innerText()).replace(/\s+/g, ' ');

  await attempt('fourteen chars');                           // 14 characters
  const tooShort = MESSAGES.en.invalidPasswordMinLengthMessage.replace('{0}', '15');
  if (!(await shown()).includes(tooShort)) fail(`a 14-character password was not refused in plain words: "${(await shown()).slice(0, 300)}"`);
  if (/invalidPassword|Invalid password: minimum/.test(await shown())) fail('the page shows Keycloak\'s raw error, not the plain words');
  console.log(`OK TOO SHORT: a 14-character password is refused — "${tooShort}"`);

  await attempt(email);                                      // the address itself, 27+ characters
  // the shop's realms use the email AS the username, so either rule may be the one that answers
  const notMe = [MESSAGES.en.invalidPasswordNotUsernameMessage, MESSAGES.en.invalidPasswordNotEmailMessage];
  const page3 = await shown();
  const answer = notMe.find((m) => page3.includes(m));
  if (!answer) fail(`the email address was not refused in plain words: "${page3.slice(0, 300)}"`);
  console.log(`OK NOT THE EMAIL: the email address is refused as a password — "${answer}"`);

  const phrase = 'blåbær på fjellet i sol';                  // 23 characters: spaces, å and æ, no digits, no symbols
  await attempt(phrase);
  await page.waitForSelector('.nav', { timeout: 20000 }).catch(async () => fail(`a 15+ phrase was refused: "${(await shown()).slice(0, 300)}"`));
  if (!(await token(ctx, 'bss', email, phrase))) fail('the account made with the phrase does not sign in with it');
  console.log(`OK A PHRASE: "${phrase}" — ordinary words, spaces and Norwegian letters, no symbols — made the account and signs in`);
  await browser.close();

  /* ---------- 4. existing logins, untouched ---------- */
  for (const [realm, user, pass] of [['bss', 'paula@family.example', 'paula'], ['nova', 'demo', 'demo'], ['taranga', 'mira@taranga.example', 'mira']]) {
    if (!(await token(ctx, realm, user, pass))) fail(`${user} in ${realm} no longer signs in — the policy must not touch existing passwords`);
  }
  console.log('OK EXISTING LOGINS: demo, paula and mira still sign in — the policy binds a password when it is SET');

  await ctx.delete(`${KC}/admin/realms/${OP}`, { headers: { Authorization: `Bearer ${adm}` } });
  console.log('PASS password_policy_test — every tenant\'s sign-in follows the length rules of NIST SP 800-63B, in plain words');
})().catch((e) => fail(e.message));
