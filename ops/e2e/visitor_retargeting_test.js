/* Anonymous-visitor retargeting: browsers who never signed in are a population
 * too. A visitor consents + browses a category; a visitor audience resolves them
 * by interest, keyed by visitorId (cookie/device) — for on-site personalization
 * and web retargeting lists (no account, no email).
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const CONSENT = `${API}/insight/v1/consent`;
const EVENT = `${API}/insight/v1/event`;
const AUDIENCE = `${API}/insight/v1/audience`;

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'bss', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const J = { 'Content-Type': 'application/json' };

  const visitor = `vis-${run}`;              // interested browser
  const other = `vis-other-${run}`;          // consented but different interest
  const category = `Handsets${run}`;

  /* ---------- an anonymous visitor consents and browses a category ---------- */
  await ctx.post(CONSENT, { headers: J, data: { visitorId: visitor, analytics: true, personalization: true } });
  await ctx.post(EVENT, { headers: J, data: { visitorId: visitor, type: 'view', category } });
  await ctx.post(EVENT, { headers: J, data: { visitorId: visitor, type: 'view', category } });
  await ctx.post(CONSENT, { headers: J, data: { visitorId: other, analytics: true, personalization: true } });
  await ctx.post(EVENT, { headers: J, data: { visitorId: other, type: 'view', category: `Plans${run}` } });
  console.log('OK an anonymous visitor consented and browsed a category (no account)');

  /* ---------- a visitor audience resolves them by interest ---------- */
  const aud = await (await ctx.post(AUDIENCE, { headers: H(staff), data: {
    name: `Retarget ${run}`, population: 'visitor', criteria: { all: [{ type: 'interest', value: category }] } } })).json();
  if (aud.population !== 'visitor') fail('the audience was not saved as a visitor population');
  let members = [];
  for (let i = 0; i < 20; i++) {
    members = await (await ctx.get(`${AUDIENCE}/${aud.id}/members`, { headers: H(staff) })).json();
    if (members.some((m) => m.visitorId === visitor)) break;
    await sleep(1000);
  }
  const ids = members.map((m) => m.visitorId);
  if (!ids.includes(visitor)) fail('the interested visitor is not in the retargeting audience: ' + JSON.stringify(ids).slice(0, 200));
  if (ids.includes(other)) fail('a visitor with a different interest leaked in');
  console.log(`OK a visitor audience {interest=${category}} resolved the browser by visitorId — retargeting-ready`);

  console.log('\nALL VISITOR CHECKS PASSED — anonymous browsers are a first-class population: consented visitors '
    + 'are resolved by interest (keyed by visitorId) for on-site personalization + web retargeting, no account needed.');
})();
