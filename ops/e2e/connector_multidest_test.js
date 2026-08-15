/* Multi-destination connector: the SAME audience activates to more than one ad
 * platform through one pluggable connector — Meta Custom Audiences and Google
 * Customer Match, each with its own wire shape, same SHA-256 hashes. Production
 * swaps the mock URL + a real OAuth token; the flow is unchanged.
 */
const { request } = require('playwright');
const crypto = require('crypto');

const API = 'http://localhost:8080';
const SOCIAL = 'http://localhost:8122';
const run = Date.now();
const IMPORT = `${API}/insight/v1/prospect/import`;
const AUDIENCE = `${API}/insight/v1/audience`;
const sha256 = (s) => crypto.createHash('sha256').update(s.trim().toLowerCase()).digest('hex');

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

  /* ---------- both destinations are configured ---------- */
  const dests = await (await ctx.get(`${AUDIENCE}/destinations`, { headers: H(staff) })).json();
  if (!('meta' in dests) || !('google' in dests)) fail('expected meta + google destinations: ' + JSON.stringify(dests));
  console.log(`OK destinations available: ${Object.keys(dests).join(', ')}`);

  /* ---------- one audience, two platforms ---------- */
  const src = `md-${run}`;
  const emails = [`m1-${run}@x.com`, `m2-${run}@x.com`];
  await ctx.post(IMPORT, { headers: H(staff), data: { prospects: emails.map((e) => ({ email: e, source: src, lawfulBasis: 'opt-in' })) } });
  const aud = await (await ctx.post(AUDIENCE, { headers: H(staff), data: { name: `Multi ${run}`, population: 'prospect', criteria: { all: [{ type: 'source', value: src }] } } })).json();

  const activateTo = async (destination, ext, verifyUrl) => {
    const act = await (await ctx.post(`${AUDIENCE}/${aud.id}/activate`, { headers: H(staff), data: { externalAudienceId: ext, mode: 'seed', destination } })).json();
    if (act.destination !== destination || !act.jobId) fail(`activate to ${destination} not queued: ` + JSON.stringify(act));
    let job = null;
    for (let i = 0; i < 40; i++) { job = await (await ctx.get(`${AUDIENCE}/activation/${act.jobId}`, { headers: H(staff) })).json(); if (job.status === 'done' || job.status === 'error') break; await sleep(400); }
    if (job.status !== 'done' || job.pushed !== 2) fail(`${destination} job did not push 2: ` + JSON.stringify(job));
    const got = await (await ctx.get(verifyUrl, { headers: { Authorization: 'Bearer x' } })).json();
    for (const e of emails) if (!got.includes(sha256(e))) fail(`${destination}: ${e} did not reach the platform`);
    return job;
  };

  const extM = `md-meta-${run}`;
  await activateTo('meta', extM, `${SOCIAL}/v1/${extM}/users`);
  console.log('OK the audience activated to META (schema[EMAIL_SHA256]/data shape) — 2 hashed emails landed');

  const extG = `md-google-${run}`;
  await activateTo('google', extG, `${SOCIAL}/google/v1/${extG}/members`);
  console.log('OK the SAME audience activated to GOOGLE (operations/hashed_email shape) — 2 hashed emails landed');

  console.log('\nALL MULTI-DESTINATION CHECKS PASSED — one audience, one connector, two ad platforms with '
    + 'different wire shapes and the same hashes. Adding a platform is a new AdDestination, not a new flow; '
    + 'production swaps the mock URL for a real OAuth token.');
})();
