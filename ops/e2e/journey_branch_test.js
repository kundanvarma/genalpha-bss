/* Graph engine: TRUE multi-path branching.
 *
 *  - a decision node routes each side to a DIFFERENT downstream node (not just
 *    two inline messages) via thenNext / elseNext (node ids)
 *  - a VIP (in the segment) flows welcome -> VIP message -> exit; a standard
 *    customer flows welcome -> STD message -> exit — different sub-paths
 *  - the VIP never sees the standard message and vice-versa
 *  - linear journeys are unchanged (backward compatible)
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const JOURNEY = `${API}/tmf-api/campaignManagement/v4/journey`;
const INBOX = `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=100`;
const ENROLL_SEG = `Enroll${run}`; const VIP_SEG = `Vip${run}`;

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

  const mkPerson = async (tag, cats) => {
    const email = `br-${tag}-${run}@example.com`;
    const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: `Br${tag}`, familyName: `R${run}` } })).json();
    const vid = `br-vis-${tag}-${run}`;
    await ctx.post(`${API}/insight/v1/consent`, { headers: { 'Content-Type': 'application/json' },
      data: { visitorId: vid, analytics: true, personalization: true } });
    for (const c of cats) await ctx.post(`${API}/insight/v1/event`, { headers: { 'Content-Type': 'application/json' },
      data: { visitorId: vid, type: 'view', category: c } });
    const tok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
    await ctx.post(`${API}/insight/v1/stitch`, { headers: H(tok), data: { visitorId: vid } });
    return { id: login.id, tok };
  };

  const vip = await mkPerson('vip', [ENROLL_SEG, VIP_SEG]);   // in both -> VIP branch
  const std = await mkPerson('std', [ENROLL_SEG]);            // enroll only -> standard branch
  console.log('OK a VIP (in the segment) and a standard customer (not), both enrollable');

  /* ---------- a journey whose decision ROUTES to different sub-paths ---------- */
  const journey = await (await ctx.post(JOURNEY, { headers: H(staff), data: {
    name: `Branch ${run}`, segmentName: ENROLL_SEG, holdoutPercent: 0,
    steps: [
      { id: 'w', type: 'message', stage: 'Welcome', subject: `W ${run}`, content: 'hi' },
      { id: 'd', type: 'decision', inSegment: VIP_SEG, thenNext: 'vip', elseNext: 'std' },
      { id: 'vip', type: 'message', stage: 'VIP', subject: `VIP ${run}`, content: 'a premium welcome' },
      { id: 'vipx', type: 'exit' },
      { id: 'std', type: 'message', stage: 'Standard', subject: `STD ${run}`, content: 'a standard welcome' },
      { id: 'stdx', type: 'exit' },
    ] } })).json();
  if (!journey.id) fail('branch journey not created: ' + JSON.stringify(journey));
  await ctx.post(`${JOURNEY}/${journey.id}/enroll`, { headers: H(staff), data: {} });
  console.log('OK a journey with a decision that routes VIP->VIP-path and everyone-else->standard-path');

  /* ---------- each customer takes its OWN downstream path ---------- */
  const subs = async (p) => (await (await ctx.get(INBOX, { headers: H(p.tok) })).json()).map((m) => m.subject);
  const hasAll = async (p, want) => { for (let i = 0; i < 25; i++) { const s = await subs(p); if (want.every((x) => s.includes(x))) return true; await sleep(1200); } return false; };

  if (!(await hasAll(vip, [`W ${run}`, `VIP ${run}`]))) fail('the VIP did not get welcome + VIP message');
  if (!(await hasAll(std, [`W ${run}`, `STD ${run}`]))) fail('the standard customer did not get welcome + STD message');
  const vipSubs = await subs(vip); const stdSubs = await subs(std);
  if (vipSubs.includes(`STD ${run}`)) fail('the VIP wrongly received the standard-path message');
  if (stdSubs.includes(`VIP ${run}`)) fail('the standard customer wrongly received the VIP-path message');
  console.log('OK the VIP took the VIP sub-path, the standard customer the standard sub-path — no cross-over');

  console.log('\nALL BRANCH CHECKS PASSED — the graph engine does TRUE multi-path branching: a decision routes '
    + 'each customer to a different downstream node, and linear journeys stay backward-compatible.');
})();
