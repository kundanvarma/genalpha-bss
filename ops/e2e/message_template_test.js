/* GJ2 — message templates + channel.
 *
 *  - a reusable, per-channel, localized template with personalization tokens
 *    ({{party.firstName}}, {{promotion.code}}) is authored ONCE
 *  - /render proves the token substitution, locale selection and locale
 *    fallback (a missing locale falls back to English)
 *  - a journey step references the template (not inline copy); a new customer
 *    registers and the DELIVERED message is personalized with their real name
 *    and carries the template's channel — copy authored once, sent rendered
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const TEMPLATE = `${API}/tmf-api/communicationManagement/v4/messageTemplate`;
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;
const INBOX = `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=100`;

async function token(ctx, client, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  /* ---------- author one template: email channel, en + nb, tokens ---------- */
  const tpl = await (await ctx.post(TEMPLATE, { headers: H(staff), data: {
    name: `Welcome template ${run}`, channel: 'email', promotionRef: 'WELCOME10',
    locales: {
      en: { subject: `Welcome {{party.firstName}} ${run}`,
            body: 'Hi {{party.firstName}}, here is {{promotion.code}} for your first order.' },
      nb: { subject: `Velkommen {{party.firstName}} ${run}`,
            body: 'Hei {{party.firstName}}, her er {{promotion.code}}.' },
    } } })).json();
  if (!tpl.id) fail('template not created: ' + JSON.stringify(tpl));
  console.log('OK authored one email template with en + nb copy and personalization tokens');

  /* ---------- render: tokens substitute, locale selects, fallback works ---------- */
  const renderEn = await (await ctx.post(`${TEMPLATE}/${tpl.id}/render`, { headers: H(staff),
    data: { locale: 'en', context: { 'party.firstName': 'Ada', 'promotion.code': 'XYZ99' } } })).json();
  if (!renderEn.subject.includes('Ada') || !renderEn.body.includes('XYZ99')) {
    fail('en render did not substitute tokens: ' + JSON.stringify(renderEn));
  }
  const renderNb = await (await ctx.post(`${TEMPLATE}/${tpl.id}/render`, { headers: H(staff),
    data: { locale: 'nb', context: { 'party.firstName': 'Ada' } } })).json();
  if (!renderNb.subject.startsWith('Velkommen') || renderNb.locale !== 'nb') {
    fail('nb locale not selected: ' + JSON.stringify(renderNb));
  }
  const renderFallback = await (await ctx.post(`${TEMPLATE}/${tpl.id}/render`, { headers: H(staff),
    data: { locale: 'de', context: { 'party.firstName': 'Ada' } } })).json();
  if (!renderFallback.subject.startsWith('Welcome') || renderFallback.locale !== 'en') {
    fail('missing locale did not fall back to en: ' + JSON.stringify(renderFallback));
  }
  console.log('OK render substitutes tokens, selects the locale (nb), and falls back to en when a locale is missing');

  /* ---------- a journey step references the template, not inline copy ---------- */
  const journey = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Templated onboarding ${run}`, triggerEventType: 'IndividualCreateEvent', holdoutPercent: 0,
    steps: [
      { type: 'message', stage: 'Welcome', templateRef: tpl.id, channel: 'email' },
      { type: 'wait', stage: 'Activate', days: 3 },
    ] } })).json();
  if (!journey.id) fail('templated journey not created: ' + JSON.stringify(journey));
  console.log('OK a journey message step references the template (no inline subject/content)');

  /* ---------- a new customer registers; the DELIVERED message is personalized ---------- */
  const firstName = `Ada${run}`;
  const email = `tpl-${run}@example.com`;
  const login = await (await ctx.post(USER, { headers: H(staff),
    data: { email, givenName: firstName, familyName: `T${run}` } })).json();
  await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: firstName,
    familyName: `T${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });

  const custTok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
  let msg = null;
  for (let i = 0; i < 30; i++) {
    const inbox = await (await ctx.get(INBOX, { headers: H(custTok) })).json();
    msg = inbox.find((m) => m.subject && m.subject.includes(firstName));
    if (msg) break;
    await sleep(1500);
  }
  if (!msg) fail('the templated welcome never arrived personalized with the customer name');
  if (!msg.subject.includes(firstName) || !msg.content.includes(firstName)) {
    fail('the delivered copy was not personalized: ' + JSON.stringify(msg));
  }
  if (msg.messageType !== 'email') {
    fail('the template channel did not carry to the delivered message: ' + JSON.stringify(msg.messageType));
  }
  console.log(`OK the delivered message was rendered from the template — personalized ("${msg.subject}") and channel "email"`);

  console.log('\nALL GJ2 CHECKS PASSED — copy is authored once as a reusable, localized, tokenized template; '
    + 'journeys reference it; and the delivered message is rendered per-customer with the template channel.');
})();
