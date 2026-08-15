/* Guardrails: a marketing stack must not over-message or reach opted-out people.
 *   Frequency cap  — the martech send door caps messages per party per window;
 *                    over the cap, a send is skipped (not delivered).
 *   DNC-at-export  — a suppressed (bounced/unsubscribed) address is filtered out
 *                    before an audience is pushed to an ad platform.
 */
const { request } = require('playwright');
const crypto = require('crypto');

const API = 'http://localhost:8080';
const SOCIAL = 'http://localhost:8122';
const run = Date.now();
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;
const PARTY = `${API}/tmf-api/party/v4/individual`;
const MSG = `${API}/tmf-api/communicationManagement/v4/communicationMessage`;
const INBOX = `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=100`;
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

  /* ================= frequency cap (cap=5/hour in dev) ================= */
  const email = `capcust-${run}@example.com`;
  const login = await (await ctx.post(USER, { headers: H(staff), data: { email, givenName: `Cap${run}`, familyName: `C${run}` } })).json();
  await ctx.post(PARTY, { headers: H(staff), data: { id: login.id, givenName: `Cap${run}`, familyName: `C${run}` } });
  const rp = [{ id: login.id, role: 'customer' }];
  let sent = 0; let capped = 0;
  for (let i = 1; i <= 6; i++) {
    const r = await (await ctx.post(MSG, { headers: H(staff), data: { messageType: 'inApp', subject: `cap ${run} #${i}`, content: 'hi', relatedParty: rp } })).json();
    if (r.status === 'capped') capped++; else if (r.id) sent++;
  }
  if (sent !== 5 || capped !== 1) fail(`frequency cap wrong: sent ${sent}, capped ${capped} (expected 5 + 1)`);
  // Confirm in the inbox too (best-effort; the send responses already prove it).
  try {
    const custTok = await token(ctx, 'bss-biz', email, login.temporaryPassword);
    if (custTok) {
      const res = await ctx.get(INBOX, { headers: H(custTok) });
      if (res.ok()) {
        const got = (await res.json()).filter((m) => (m.subject || '').startsWith(`cap ${run}`)).length;
        if (got !== 5) fail(`the inbox should hold 5 (cap), holds ${got}`);
      }
    }
  } catch (e) { /* inbox read is confirmation only */ }
  console.log('OK frequency cap: 6 marketing sends to one party -> 5 delivered, the 6th capped');

  /* ================= DNC-at-export (nova, real ESP suppression) ================= */
  const nova = await token(ctx, 'nova', 'demo', 'demo');
  const E = `dnc-${run}@nova.example`;       // will be suppressed
  const E2 = `keep-${run}@nova.example`;     // stays reachable
  const src = `dnc-${run}`;
  await ctx.post(IMPORT, { headers: H(nova), data: { prospects: [
    { email: E, source: src, lawfulBasis: 'opt-in' }, { email: E2, source: src, lawfulBasis: 'opt-in' } ] } });

  // the ESP reports an unsubscribe for E -> communication suppresses it
  await ctx.post(`${API}/esp/v1/event`, { headers: { 'X-Esp-Token': 'nova-esp-key', 'Content-Type': 'application/json' },
    data: [{ event: 'unsubscribe', email: E, custom_args: { tenant: 'nova', messageId: `m-${run}` } }] });
  let suppressed = false;
  for (let i = 0; i < 10; i++) {
    const s = await (await ctx.get(`${API}/esp/v1/suppression`, { headers: H(nova) })).json();
    if (Array.isArray(s) && s.some((x) => x.email === E)) { suppressed = true; break; }
    await sleep(500);
  }
  if (!suppressed) fail('E was not added to the suppression list');
  console.log('OK an unsubscribe put E on the ESP suppression list');

  const aud = await (await ctx.post(AUDIENCE, { headers: H(nova), data: { name: `DNC ${run}`, population: 'prospect', criteria: { all: [{ type: 'source', value: src }] } } })).json();
  // the EmailSuppressedEvent propagates to insight over Kafka — retry until the
  // DNC projection has landed (a job to a fresh external id each attempt).
  let users = [];
  for (let attempt = 0; attempt < 6; attempt++) {
    await sleep(2500);
    const ext = `ca-dnc-${run}-${attempt}`;
    const act = await (await ctx.post(`${AUDIENCE}/${aud.id}/activate`, { headers: H(nova), data: { externalAudienceId: ext, mode: 'seed' } })).json();
    for (let i = 0; i < 40; i++) { const j = await (await ctx.get(`${AUDIENCE}/activation/${act.jobId}`, { headers: H(nova) })).json(); if (j.status === 'done' || j.status === 'error') break; await sleep(500); }
    users = await (await ctx.get(`${SOCIAL}/v1/${ext}/users`, { headers: { Authorization: 'Bearer x' } })).json();
    if (users.includes(sha256(E2)) && !users.includes(sha256(E))) break;
  }
  if (!users.includes(sha256(E2))) fail('the reachable prospect E2 was not exported');
  if (users.includes(sha256(E))) fail('a SUPPRESSED address (E) leaked into the ad-platform export');
  console.log('OK DNC-at-export: the suppressed address was filtered out; only the reachable one reached the platform');

  console.log('\nALL GUARDRAIL CHECKS PASSED — the stack governs contact frequency (no over-messaging) and never '
    + 'exports a suppressed/opted-out address to an ad platform. The compliance layer a real operator insists on.');
})();
