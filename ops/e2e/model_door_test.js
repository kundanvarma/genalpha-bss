/* The model door, ticket 1 (#283): a digital worker's model call goes through
 * the BSS, and the BSS measures it.
 *
 * Before the door, a worker held the tenant's provider key and called the
 * provider directly: its spend was whatever it chose to report (mostly
 * nothing), no budget saw it, and its prompts left unredacted. This suite
 * proves the first slice of the fix, through the gateway with a real token:
 *
 *   1. issuing a worker's door key is a governance act (ai:admin)
 *   2. a call with that key reaches the provider with the TENANT's key —
 *      the worker's key never leaves the BSS
 *   3. the ledger records the call from the PROVIDER's own token counts,
 *      priced at the model's listed rates, with the worker as its actor —
 *      no self-report involved
 *   4. an unknown key and a revoked key are refused in the provider's own
 *      error shape, so a worker's SDK reads a door refusal like a provider's
 *
 * Tenant: nova, whose worker brain defaults to the fleet's mock LLM (no key,
 * no network). The mock reports deterministic usage and the key it was sent.
 */
const API = 'http://localhost:8080';
const MOCK = 'http://localhost:8127';
const DOOR = `${API}/ai/v1/modelDoor/anthropic/v1/messages`;
const run = Date.now();
const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

async function token(realm, user, pass) {
  const r = await fetch(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }),
  });
  if (!r.ok) fail(`token for ${realm}/${user}: HTTP ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, url, tok, body, headers = {}) {
  const r = await fetch(url, {
    method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers },
    ...(body ? { body: typeof body === 'string' ? body : JSON.stringify(body) } : {}),
  });
  const text = await r.text();
  let json = null; try { json = JSON.parse(text); } catch { /* not json */ }
  return { status: r.status, json, text };
}
const door = (key, body) => call('POST', DOOR, null, body, { 'x-api-key': key, 'anthropic-version': '2023-06-01' });

(async () => {
  const admin = await token('nova', 'demo', 'demo');               // ai:admin
  const badge = `worker-care-door${run}@bss.local`;

  /* ---------- 1. the key is a governance act ---------- */
  const issued = await call('POST', `${API}/ai/v1/workforce/doorKeys`, admin,
    { badge, workerId: `door-worker-${run}`, job: 'care' });
  if (issued.status !== 201 || !issued.json?.key) fail(`issuing a door key: ${issued.status} ${issued.text.slice(0, 200)}`);
  const key = issued.json.key;
  if (!key.startsWith('bssw_')) fail(`a door key must read as a door key, got "${key.slice(0, 8)}…"`);
  const anon = await call('POST', `${API}/ai/v1/workforce/doorKeys`, null, { badge: 'nobody' });
  if (![401, 403].includes(anon.status)) fail(`an anonymous caller issued a door key: ${anon.status}`);
  console.log(`OK nova's admin issued a door key for ${badge}; an anonymous caller could not (${anon.status})`);

  /* ---------- 2. through the door, with the tenant's key ---------- */
  const body = { model: 'claude-haiku-4-5-20251001', max_tokens: 64,
    messages: [{ role: 'user', content: `Close ticket ${run}: the customer says it works again.` }] };
  const answered = await door(key, body);
  if (answered.status !== 200) fail(`a door call: ${answered.status} ${answered.text.slice(0, 200)}`);
  const usage = answered.json?.usage;
  if (!usage || !usage.input_tokens) fail(`the provider reported no usage through the door: ${answered.text.slice(0, 200)}`);
  const seen = (await (await fetch(`${MOCK}/requests`)).json()).filter((r) => r.api === 'anthropic').pop();
  if (!seen) fail('the provider never saw the call');
  if (seen.key !== 'mock-llm-takes-any-key') fail(`the provider was sent "${String(seen.key).slice(0, 12)}…", not the tenant's key`);
  if (String(seen.key).startsWith('bssw_')) fail('the WORKER\'s door key reached the provider');
  console.log(`OK the call went through the door and reached the provider with nova's key — the worker's key stayed in the BSS (${usage.input_tokens} in, ${usage.output_tokens} out)`);

  /* ---------- 3. measured, not self-reported ---------- */
  let row = null;
  for (let i = 0; i < 10 && !row; i++) {
    const audit = await call('GET', `${API}/ai/v1/audit`, admin);
    row = (audit.json || []).find((a) => a.actor === badge);
    if (!row) await new Promise((r) => setTimeout(r, 500));
  }
  if (!row) fail('no ledger row names the worker');
  const tokens = usage.input_tokens + usage.output_tokens
    + (usage.cache_creation_input_tokens || 0) + (usage.cache_read_input_tokens || 0);
  if (row.tokens !== tokens) fail(`the ledger holds ${row.tokens} tokens; the provider reported ${tokens}`);
  // claude-haiku-4-5 at its listed rates: $1 in, $5 out per million tokens = 1 and 5 micro-dollars a token
  const expected = usage.input_tokens * 1 + usage.output_tokens * 5;
  if (row.costMicros !== expected) fail(`the ledger priced the call at ${row.costMicros} µ$; the listed rates give ${expected}`);
  if (row.useCase !== 'workforce:care' || row.provider !== 'anthropic') fail(`ledger row shape: ${JSON.stringify(row).slice(0, 200)}`);
  if (/Close ticket/.test(row.prompt || '')) fail('the ledger kept the prompt content — it must not, before redaction lands');
  console.log(`OK the ledger recorded the provider's own counts (${row.tokens} tokens), priced at haiku's rates (${row.costMicros} µ$), the worker as actor — no self-report involved`);

  /* ---------- 4. refusals in the provider's own shape ---------- */
  const stranger = await door('bssw_not-a-real-key', body);
  if (stranger.status !== 401 || stranger.json?.error?.type !== 'authentication_error') {
    fail(`an unknown key: ${stranger.status} ${stranger.text.slice(0, 160)}`);
  }
  const revoked = await call('DELETE', `${API}/ai/v1/workforce/doorKeys/${issued.json.id}`, admin);
  if (revoked.status !== 204) fail(`revoking the key: ${revoked.status}`);
  const fired = await door(key, body);
  if (fired.status !== 401) fail(`a revoked key still opened the door: ${fired.status}`);
  console.log('OK an unknown key and a revoked key are refused (401, the provider\'s own error shape) — firing a worker shuts its door');

  console.log('PASS model_door_test — a worker\'s model call is measured by the BSS, not reported by the worker');
})().catch((e) => fail(e && e.stack || e));
