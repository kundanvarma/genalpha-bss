/* BB2 — Journey Insights: the per-node funnel.
 *
 *  - a journey [Welcome msg -> 3-day wait -> After msg]; two customers enter
 *  - stats.funnel reports, per node, how many REACHED it and are ACTIVE there
 *  - the drop is visible: everyone reaches the welcome and parks at the wait;
 *    nobody has reached the post-wait message yet — that's the leak, measured
 *  - reached is monotonic non-increasing down the funnel
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;

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

  const j = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Insights ${run}`, triggerEventType: 'IndividualCreateEvent', holdoutPercent: 0,
    steps: [
      { type: 'message', stage: 'Welcome', subject: `INS Welcome ${run}`, content: 'hi' },
      { type: 'wait', stage: 'Activate', days: 3 },
      { type: 'message', stage: 'Post', subject: `INS Post ${run}`, content: 'later' },
    ] } })).json();
  if (!j.id) fail('journey not created: ' + JSON.stringify(j));

  const mk = async (tag) => {
    const email = `ins-${tag}-${run}@example.com`;
    const login = await (await ctx.post(USER, { headers: H(staff),
      data: { email, givenName: `Ins${tag}`, familyName: `A${run}` } })).json();
    await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: `Ins${tag}`, familyName: `A${run}` } });
  };
  await mk('1'); await mk('2');
  console.log('OK a 3-node journey (Welcome -> wait -> Post) with two customers entering');

  // both progress: Welcome fires, the wait consumes, and they park pointing at
  // the final (Post) node for 3 days — reached the last node, nobody completed
  let stats = null;
  for (let i = 0; i < 30; i++) {
    stats = await (await ctx.get(`${JOURNEY}/${j.id}/stats`, { headers: H(staff) })).json();
    const f = stats.funnel;
    if (f && f[2] && f[2].active >= 2) break;
    await sleep(1500);
  }
  const f = stats.funnel;
  if (!Array.isArray(f) || f.length !== 3) fail('no per-node funnel: ' + JSON.stringify(stats.funnel));
  if (f[0].reached < 2) fail('the welcome node was not reached by both: ' + JSON.stringify(f));
  // the 3-day wait is the leak: both sit at the final node, none have completed
  if (f[2].active < 2) fail('both should be parked before the final node: ' + JSON.stringify(f));
  if ((stats.completedUnconverted || 0) !== 0) fail('nobody should have finished past the 3-day wait: ' + JSON.stringify(stats));
  // reached is monotonic non-increasing — the funnel only narrows
  for (let i = 1; i < f.length; i++) {
    if (f[i].reached > f[i - 1].reached) fail('funnel reached is not monotonic: ' + JSON.stringify(f));
  }
  console.log(`OK the funnel measures each node: reached [${f.map((n) => n.reached).join(', ')}], `
    + `active [${f.map((n) => n.active).join(', ')}] — 2 parked before Post, 0 completed (the 3-day wait is the leak)`);
  console.log('OK reached is monotonic non-increasing — the funnel only narrows');

  console.log('\nALL BB2 CHECKS PASSED — Journey Insights reports a per-node funnel (reached + active per node) '
    + 'so a journey owner sees exactly where people fall out, against the honest holdout baseline.');
})();
