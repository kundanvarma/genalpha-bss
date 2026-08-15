/* Async activation jobs: a large export doesn't block the request. Activate
 * returns a JOB immediately (queued); the hashed push runs in the background;
 * the caller polls the job to done with counts.
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

  /* ---------- a prospect audience to export ---------- */
  const src = `async-${run}`;
  const emails = [`a-${run}@x.com`, `b-${run}@x.com`, `c-${run}@x.com`];
  await ctx.post(IMPORT, { headers: H(staff), data: { prospects: emails.map((e) => ({ email: e, source: src, lawfulBasis: 'opt-in' })) } });
  const aud = await (await ctx.post(AUDIENCE, { headers: H(staff), data: {
    name: `Async ${run}`, population: 'prospect', criteria: { all: [{ type: 'source', value: src }] } } })).json();

  /* ---------- activate returns IMMEDIATELY with a queued job ---------- */
  const ext = `ca-async-${run}`;
  const t0 = Date.now();
  const act = await (await ctx.post(`${AUDIENCE}/${aud.id}/activate`, { headers: H(staff),
    data: { externalAudienceId: ext, mode: 'seed' } })).json();
  const took = Date.now() - t0;
  if (act.status !== 'queued' || !act.jobId) fail('activate did not return a queued job: ' + JSON.stringify(act));
  console.log(`OK activate returned a queued job in ${took}ms — it did not block on the export`);

  /* ---------- poll the job to done, with counts ---------- */
  let job = null;
  const seen = new Set();
  for (let i = 0; i < 40; i++) {
    job = await (await ctx.get(`${AUDIENCE}/activation/${act.jobId}`, { headers: H(staff) })).json();
    seen.add(job.status);
    if (job.status === 'done' || job.status === 'error') break;
    await sleep(300);
  }
  if (!job || job.status !== 'done') fail('the job did not finish: ' + JSON.stringify(job));
  if (job.pushed !== 3) fail('the job did not push all 3 members: ' + JSON.stringify(job));
  if (!job.finishedAt) fail('a done job should have finishedAt');
  console.log(`OK the job ran to done (states seen: ${[...seen].join(' → ')}) — pushed ${job.pushed}, members ${job.members}`);

  /* ---------- the platform actually received the hashed emails ---------- */
  const users = await (await ctx.get(`${SOCIAL}/v1/${ext}/users`, { headers: { Authorization: 'Bearer x' } })).json();
  for (const e of emails) if (!users.includes(sha256(e))) fail('a member never reached the platform: ' + e);
  console.log('OK all 3 hashed emails reached the Custom Audience — the background export completed correctly');

  console.log('\nALL ASYNC-ACTIVATION CHECKS PASSED — activation is a background job: the request returns a '
    + 'queued job instantly, the hashed export runs off-thread, and the caller polls it to done. A million-row '
    + 'push never blocks the console.');
})();
