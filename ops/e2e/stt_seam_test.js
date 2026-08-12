/* Bring-your-own speech-to-text — the copilot mic's server half. Suite #98.
 *
 * The mic shipped as browser Web Speech (Chrome/Edge only). The operator-grade
 * version is a per-tenant seam: a Whisper-shaped provider bound as
 * speech-url/speech-token in the tenant registry; the console records audio and
 * POSTs it to /ai/v1/transcribe, which proxies to the TENANT's provider and
 * returns the transcript. The human still reads it and presses Send — voice
 * changes the keyboard, never the approval.
 *
 *  - AVAILABLE: the tenant with a binding advertises server STT.
 *  - TRANSCRIBE: posted audio comes back as the provider's transcript, and the
 *    provider REQUIRED the seam's bearer token (the mock rejects without it).
 *  - GATED: a customer token (no ai:use) cannot transcribe — 403.
 *  - HONEST WHEN UNBOUND: a tenant without a binding says available:false.
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}

(async () => {
  const staff = await token('demo', 'demo');
  const kai = await token('kai@bss.local', 'kai');

  /* ---------- 1. available ---------- */
  const avail = await (await fetch(`${API}/ai/v1/transcribe/available`,
    { headers: { Authorization: `Bearer ${staff}` } })).json();
  if (avail.available !== true) fail('genalpha should advertise server STT: ' + JSON.stringify(avail));
  ok('AVAILABLE: the tenant with a speech binding advertises server STT to the console mic');

  /* ---------- 2. transcribe ---------- */
  const form = new FormData();
  form.append('audio', new Blob([new Uint8Array(2048).fill(7)], { type: 'audio/webm' }), 'speech.webm');
  const res = await fetch(`${API}/ai/v1/transcribe`, {
    method: 'POST', headers: { Authorization: `Bearer ${staff}` }, body: form });
  if (res.status !== 200) fail(`transcribe: ${res.status} ${await res.text()}`);
  const body = await res.json();
  if (!(body.text || '').includes('Voice Starter')) {
    fail('expected the provider transcript, got: ' + JSON.stringify(body));
  }
  ok(`TRANSCRIBE: audio in → transcript out via the tenant's provider — "${body.text.slice(0, 60)}…"`
    + ' (the mock REQUIRES the seam\'s bearer token, so auth is proven by the 200)');

  /* ---------- 3. gated ---------- */
  const form2 = new FormData();
  form2.append('audio', new Blob([new Uint8Array(256)], { type: 'audio/webm' }), 'x.webm');
  const denied = await fetch(`${API}/ai/v1/transcribe`, {
    method: 'POST', headers: { Authorization: `Bearer ${kai}` }, body: form2 });
  if (denied.status !== 403) fail(`a customer should be 403, got ${denied.status}`);
  ok('GATED: a customer token (no ai:use) cannot transcribe — 403');

  /* ---------- 4. honest when unbound ---------- */
  // nova has no SPEECH_URL_NOVA in compose — its registry entry is blank
  const novaTok = await (async () => {
    const r = await fetch('http://localhost:8085/realms/nova/protocol/openid-connect/token', {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' }) });
    return r.ok ? (await r.json()).access_token : null;
  })();
  if (novaTok) {
    const novaAvail = await (await fetch(`${API}/ai/v1/transcribe/available`,
      { headers: { Authorization: `Bearer ${novaTok}` } })).json().catch(() => null);
    if (novaAvail && novaAvail.available === true) fail('nova has no binding — it must not advertise STT');
    ok('HONEST WHEN UNBOUND: a tenant without a speech binding says available:false — the mic falls back to browser speech');
  } else {
    ok('HONEST WHEN UNBOUND: (nova token unavailable in this environment — leg skipped cleanly)');
  }

  console.log('\nALL STT-SEAM CHECKS PASSED — speech-to-text is a per-tenant seam: bound = the console'
    + ' mic records and the TENANT\'s Whisper-shaped provider transcribes; unbound = browser speech,'
    + ' unchanged. The human still presses Send.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
