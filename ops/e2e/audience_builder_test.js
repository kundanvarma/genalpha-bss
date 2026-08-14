/* GJ4 — saved audiences + rule tree.
 *
 *  - four consented, stitched customers with run-unique browsing interests
 *  - a SAVED audience defined by a criteria TREE, not a segment string:
 *      all: [ interest "Device", NOT interest "Tv" ]
 *  - GET /audience/{id}/members resolves EXACTLY the parties the tree admits
 *  - a campaign references the audience by id and blasts only its members
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const AUDIENCE = `${API}/insight/v1/audience`;
const CAMPAIGN = `${API}/tmf-api/campaignManagement/v4/campaign`;
const INBOX = `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=100`;
const DEVICE = `Device${run}`; const TV = `Tv${run}`; const SPORT = `Sport${run}`;

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

  // a consented, stitched customer who browsed the given interest categories
  const mkPerson = async (tag, interests) => {
    const email = `aud-${tag}-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: `Aud${tag}`, familyName: `R${run}` } })).json();
    const vid = `aud-vis-${tag}-${run}`;
    await ctx.post(`${API}/insight/v1/consent`, { headers: { 'Content-Type': 'application/json' },
      data: { visitorId: vid, analytics: true, personalization: true } });
    for (const cat of interests) {
      await ctx.post(`${API}/insight/v1/event`, { headers: { 'Content-Type': 'application/json' },
        data: { visitorId: vid, type: 'view', category: cat } });
    }
    const tok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
    await ctx.post(`${API}/insight/v1/stitch`, { headers: H(tok), data: { visitorId: vid } });
    return { id: login.id, email, tok };
  };

  const A = await mkPerson('A', [DEVICE]);            // Device only        -> IN
  const B = await mkPerson('B', [DEVICE, TV]);        // Device + Tv        -> OUT (Tv)
  const C = await mkPerson('C', [TV]);                // Tv only            -> OUT (no Device)
  const D = await mkPerson('D', [DEVICE, SPORT]);     // Device + Sport     -> IN
  console.log('OK four consented, stitched customers with run-unique interests');

  /* ---------- a saved audience with a criteria TREE ---------- */
  const audience = await (await ctx.post(AUDIENCE, { headers: H(staff), data: {
    name: `Device-not-Tv ${run}`,
    criteria: { all: [ { type: 'interest', value: DEVICE }, { not: { type: 'interest', value: TV } } ] },
  } })).json();
  if (!audience.id) fail('audience not created: ' + JSON.stringify(audience));
  console.log('OK saved an audience: all[ interest Device, NOT interest Tv ]');

  /* ---------- the tree resolves EXACTLY the right members ---------- */
  let ids = [];
  for (let i = 0; i < 10; i++) {
    const members = await (await ctx.get(`${AUDIENCE}/${audience.id}/members`, { headers: H(staff) })).json();
    ids = members.map((m) => m.partyId);
    if (ids.includes(A.id) && ids.includes(D.id)) break;
    await sleep(1000);
  }
  const has = (p) => ids.includes(p.id);
  if (!has(A) || !has(D)) fail('the tree missed a qualifying member: ' + JSON.stringify(ids));
  if (has(B)) fail('the tree admitted a Tv-interested customer (NOT clause failed): ' + JSON.stringify(ids));
  if (has(C)) fail('the tree admitted a non-Device customer (all clause failed): ' + JSON.stringify(ids));
  console.log(`OK the rule tree resolved EXACTLY {A, D}: A ✓ D ✓ B ✗(Tv) C ✗(no Device)`);

  /* ---------- a campaign targets the audience by id and blasts its members ---------- */
  const subject = `Audience blast ${run}`;
  const campaign = await (await ctx.post(CAMPAIGN, { headers: H(staff), data: {
    name: `Aud campaign ${run}`, audienceRef: audience.id, holdoutPercent: 0,
    message: { subject, content: 'For device shoppers.' } } })).json();
  const blast = await (await ctx.post(`${CAMPAIGN}/${campaign.id}/execute`,
    { headers: H(staff), data: {} })).json();
  if (blast.reached !== 2) fail('the audience blast did not reach exactly the 2 members: ' + JSON.stringify(blast));

  const inboxHas = async (p) => {
    for (let i = 0; i < 10; i++) {
      const subs = (await (await ctx.get(INBOX, { headers: H(p.tok) })).json()).map((m) => m.subject);
      if (subs.includes(subject)) return true;
      await sleep(1000);
    }
    return false;
  };
  if (!(await inboxHas(A))) fail('member A did not receive the audience blast');
  if (await inboxHas(C)) fail('non-member C received the audience blast');
  console.log('OK a campaign blasted the saved audience: member A heard it, non-member C did not');

  console.log('\nALL GJ4 CHECKS PASSED — audiences are saved, named and defined by a criteria TREE '
    + '(all/any/not over interests), resolved consent-aware, and campaigns target them by id.');
})();
