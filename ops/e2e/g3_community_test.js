/* G3 — the Community Grid + Klubbdugnad.
 *  - a street goal (target 3) fills as joiners redeem with their area code;
 *    the progress score is a percentage, never a person
 *  - a referrer ties their code to their local CLUB; every conversion counts
 *    for the club's season tally
 */
const { request } = require('playwright');
const API = 'http://localhost:8080';
const run = Date.now();
const REF = `${API}/tmf-api/campaignManagement/v4/referral`;

async function token(ctx, client, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const staff = await token(ctx, 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const mk = async (tag) => {
    const email = `g3-${tag}-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'G3', familyName: `${tag}${run}` } })).json();
    return { id: login.id, tok: await token(ctx, 'bss-biz', email, login.temporaryPassword) };
  };

  const area = `0${String(run).slice(-3)}G`;
  const goal = await (await ctx.post(`${REF}/community`, { headers: H(staff),
    data: { name: `Fiber til ${area}`, areaCode: area, target: 3 } })).json();
  if (goal.joined !== 0 || goal.unlocked) fail('a fresh goal must start empty');
  console.log(`OK the street's goal exists: "${goal.name}" 0/3`);

  // the referrer ties their code to the local club
  const club = await (await ctx.post(`${API}/tmf-api/party/v4/organization`, { headers: H(staff),
    data: { name: `IL Fjellkameratene ${run}`, isLegalEntity: true } })).json();
  const referrer = await mk('ref');
  const code = (await (await ctx.get(`${REF}/myCode`, { headers: H(referrer.tok) })).json()).code;
  const linked = await (await ctx.post(`${REF}/myClub`, { headers: H(referrer.tok),
    data: { clubOrgId: club.id } })).json();
  if (linked.clubOrgId !== club.id) fail('club link failed');
  console.log(`OK Klubbdugnad: ${code} now plays for "IL Fjellkameratene" — every signup counts for the club`);

  // three neighbors join with the street's area code
  for (const tag of ['a', 'b', 'c']) {
    const joiner = await mk(tag);
    const r = await (await ctx.post(`${REF}/redeem`, { headers: H(joiner.tok),
      data: { code, areaCode: area } })).json();
    if (r.status !== 'pending') fail(`neighbor ${tag} redeem failed: ` + JSON.stringify(r));
  }
  const progress = await (await ctx.get(`${REF}/community/${goal.id}/progress`,
    { headers: H(staff) })).json();
  if (progress.joined !== 3 || progress.percent !== 100 || !progress.unlocked) {
    fail('the street did not unlock: ' + JSON.stringify(progress));
  }
  console.log(`OK COMMUNITY GRID: 3/3 neighbors — "${progress.name}" UNLOCKED at ${progress.percent}%`);

  const clubs = await (await ctx.get(`${REF}/clubs`, { headers: H(staff) })).json();
  const row = (clubs || []).find((c) => c.clubOrgId === club.id);
  if (!row || Number(row.joined) !== 3) fail('the club tally is wrong: ' + JSON.stringify(clubs).slice(0, 200));
  console.log(`OK the club's season tally: ${row.joined} joined through the dugnad`);

  console.log('\nALL G3 CHECKS PASSED — the street unlocks together, the club earns together, '
    + 'and the public score is a percentage, never a person.');
})();
