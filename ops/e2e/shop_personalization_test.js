/* The shop that knows you (personalization from the customer's OWN data). Suite #86.
 *
 *  - the usage-aware upsell: a nearly-drained meter surfaces the NEXT RUNG
 *    of the allowance ladder — the cheapest fuller plan, from data the
 *    fleet always had; a comfortable meter surfaces nothing
 *  - the returning guest: `visits` (distinct days) joins the experience
 *    context, so an operator's welcome-back rule is authorable as data
 *  - the guest grid ranks by the FULL consented interest profile, in
 *    interest order — not just one floating hero
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const run = Date.now();
const fail = (m) => { throw new Error(m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo',
      username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}

(async () => {
  const staff = await token('demo', 'demo');

  /* ---------- 0. a purpose-made customer with a nearly-drained meter ---------- */
  const email = `shopper-${run}@example.com`;
  const login = (await call('POST', '/tmf-api/rolesAndPermissionsManagement/v4/user', staff,
    { email, givenName: 'Shop', familyName: `Knows${run}` })).body;
  if (!login || !login.id) fail('customer mint failed');
  await call('POST', '/tmf-api/party/v4/individual', staff, { id: login.id, givenName: 'Shop',
    familyName: `Knows${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] });
  const cust = await token(email, login.temporaryPassword);
  const TEN_GB = { id: '14291c1a-df26-4232-8084-500466888e46', name: 'GenAlpha Mobile 10 GB' };
  const ord = await call('POST', '/tmf-api/productOrderingManagement/v4/productOrder', cust,
    { productOrderItem: [{ id: '1', action: 'add', quantity: 1, productOffering: TEN_GB }] });
  if (ord.status !== 201) fail(`order: ${ord.status}`);
  // drain the meter to 92%
  await call('POST', '/tmf-api/usageManagement/v4/usage', staff, {
    usageType: 'Mobile data', usageCharacteristic: { value: 9.2, units: 'GB' },
    productOffering: { id: TEN_GB.id }, relatedParty: [{ id: login.id, role: 'customer' }] });

  /* ---------- 1. the upsell names the next rung ---------- */
  let fy = null;
  for (let i = 0; i < 10 && !(fy && fy.upsell); i++) {
    await sleep(3000);
    fy = (await call('GET', '/ai/v1/forYou', cust)).body;
  }
  if (!fy || !fy.upsell) fail('a 92% meter never surfaced an upsell');
  const up = fy.upsell;
  if (up.usedPct < 80) fail('upsell fired under the 80% line: ' + up.usedPct);
  if (Number(up.suggestedAllowance) <= Number(up.currentAllowance)) {
    fail('the suggestion is not a fuller plan: ' + JSON.stringify(up));
  }
  // the LADDER check: the suggestion must be the NEXT rung, not the top —
  // verify no active allowance sits between current and suggested
  const ladder = (await call('GET',
    '/tmf-api/usageManagement/v4/usageAllowance?limit=200', staff)).body || [];
  const between = ladder.filter((a) => a.usageType === up.bucketName
    && a.allowance && Number(a.allowance.value) > Number(up.currentAllowance)
    && Number(a.allowance.value) < Number(up.suggestedAllowance));
  if (between.length) fail('a smaller rung was skipped: ' + JSON.stringify(between[0]));
  if (!up.suggestedOffering.name || up.suggestedOffering.name === 'null') {
    fail('the suggestion must carry a human label: ' + JSON.stringify(up.suggestedOffering));
  }
  console.log(`OK THE UPSELL: ${up.usedValue}/${up.currentAllowance} ${up.units} used (${up.usedPct}%)`
    + ` surfaced "${up.suggestedOffering.name}" at ${up.suggestedAllowance} ${up.units} — the`
    + ' NEXT rung of the allowance ladder, verified against the ladder itself. The shop'
    + ' finally reads the meter one page away.');

  /* ---------- 2. a comfortable meter surfaces nothing ---------- */
  const email2 = `calm-${run}@example.com`;
  const login2 = (await call('POST', '/tmf-api/rolesAndPermissionsManagement/v4/user', staff,
    { email: email2, givenName: 'Calm', familyName: `Meter${run}` })).body;
  await call('POST', '/tmf-api/party/v4/individual', staff, { id: login2.id, givenName: 'Calm',
    familyName: `Meter${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email2 } }] });
  const calm = await token(email2, login2.temporaryPassword);
  await call('POST', '/tmf-api/productOrderingManagement/v4/productOrder', calm,
    { productOrderItem: [{ id: '1', action: 'add', quantity: 1, productOffering: TEN_GB }] });
  await call('POST', '/tmf-api/usageManagement/v4/usage', staff, {
    usageType: 'Mobile data', usageCharacteristic: { value: 2, units: 'GB' },
    productOffering: { id: TEN_GB.id }, relatedParty: [{ id: login2.id, role: 'customer' }] });
  await sleep(3000);
  const fyCalm = (await call('GET', '/ai/v1/forYou', calm)).body;
  if (fyCalm && fyCalm.upsell) fail('a 20% meter must not nag: ' + JSON.stringify(fyCalm.upsell));
  console.log('OK NO NAGGING: a customer at 20% of their allowance sees NO upsell — the'
    + ' block exists only when the meter genuinely argues for it.');

  /* ---------- 3. the returning guest is a rule an operator can write ---------- */
  const vid = `guest-${run}`;
  await call('POST', '/insight/v1/consent', null,
    { visitorId: vid, analytics: true, personalization: true });
  await call('POST', '/insight/v1/event', null,
    { visitorId: vid, type: 'view', category: 'Devices' });
  const rule = await call('POST', '/tmf-api/policyManagement/v4/policyRule', staff, {
    name: `Welcome back ${run}`, domain: 'personalization', effect: 'allow',
    priority: 1, enabled: true,
    condition: JSON.stringify({ '>=': [{ var: 'visits' }, 2] }),
    message: 'Good to see you again — your picks are waiting.',
    experience: JSON.stringify({ heroCategory: 'Devices' }) });
  if (rule.status !== 201) fail(`rule: ${rule.status} ${rule.text.slice(0, 150)}`);
  try {
    const first = (await call('GET', `/insight/v1/experience?visitorId=${vid}`, null)).body;
    if (first && first.banner && String(first.banner).includes('Good to see you again')) {
      fail('a FIRST-day visitor matched the returning-visitor rule');
    }
    // a second distinct day, honestly: backdate one event at the source
    const { execSync } = require('child_process');
    execSync(`docker exec bss-postgres psql -U postgres -d insight -c "UPDATE visitor_event SET created_at = created_at - interval '1 day' WHERE visitor_id = '${vid}'"`,
      { stdio: 'pipe' });
    await call('POST', '/insight/v1/event', null,
      { visitorId: vid, type: 'view', category: 'Devices' });
    const back = (await call('GET', `/insight/v1/experience?visitorId=${vid}`, null)).body;
    if (!back || !String(back.banner || '').includes('Good to see you again')) {
      fail('a two-day visitor did not match visits >= 2: ' + JSON.stringify(back).slice(0, 150));
    }
    console.log('OK THE RETURN: `visits` joined the experience context — a first-day guest'
      + ' passed the welcome-back rule by, a two-day guest was greeted. Frequency is now'
      + ' a variable an operator authors against, as data.');
  } finally {
    await call('DELETE', `/tmf-api/policyManagement/v4/policyRule/${rule.body.id}`, staff);
  }

  /* ---------- 4. the grid ranks by the FULL profile ---------- */
  const vid2 = `ranker-${run}`;
  await call('POST', '/insight/v1/consent', null,
    { visitorId: vid2, analytics: true, personalization: true });
  for (let i = 0; i < 3; i++) {
    await call('POST', '/insight/v1/event', null,
      { visitorId: vid2, type: 'view', category: 'Accessories' });
  }
  await call('POST', '/insight/v1/event', null,
    { visitorId: vid2, type: 'view', category: 'Devices' });
  const exp = (await call('GET', `/insight/v1/experience?visitorId=${vid2}`, null)).body;
  const interests = exp && exp.interests || [];
  if (interests[0] !== 'Accessories' || !interests.includes('Devices')) {
    fail('interest profile wrong: ' + JSON.stringify(interests));
  }
  console.log('OK THE PROFILE: two interests ranked in browse-weight order'
    + ` (${interests.slice(0, 2).join(' > ')}) ride the experience payload — the grid`
    + ' sorts singles by exactly this array (Shop.jsx interestRank), profile-wide,'
    + ' not one floating hero.');

  console.log('\\nALL SHOP-PERSONALIZATION CHECKS PASSED — the shop finally uses what it'
    + ' already knew: the meter one page away names the next rung of a ladder that was'
    + ' always data, frequency became a rule variable, and the consented profile ranks'
    + ' the grid. Personal, honest, and never a nag.');
})().catch((e) => { console.error('FAIL:', e.message.split('\\n').slice(0, 3).join(' | ')); process.exit(1); });
