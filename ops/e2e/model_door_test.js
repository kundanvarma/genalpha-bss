/* The model door (#283): a digital worker's model call goes through
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
 *   4. the guardrails (#307): the workers' own spend ceiling and the
 *      kill-switch refuse with 402 and billing words (the SDK's hard wall, so a
 *      shift ends instead of retrying); only the tenant's worker models are
 *      served; streaming is refused in words the SDK falls back on
 *   5. redaction (#308): customer data in a tool result reaches the provider
 *      as placeholders, never as the customer's address or number
 *   6. an unknown key and a revoked key are refused in the provider's own
 *      error shape, so a worker's SDK reads a door refusal like a provider's
 *   7. the Workforce page (#311): "AI cost — measured" shows what the door
 *      recorded, and the worker spend ceiling is set from the page
 *
 * Tenant: nova, whose worker brain defaults to the fleet's mock LLM (no key,
 * no network). The mock reports deterministic usage and the key it was sent.
 */
const { chromium } = require('playwright');
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

  /* ---------- 4. the guardrails ---------- */
  const budget = (b) => call('POST', `${API}/ai/v1/governance/budget`, admin, b);
  const spent = async () => (await call('GET', `${API}/ai/v1/governance`, admin)).json?.workerSpentMicros ?? 0;
  try {
    // a ceiling one micro-dollar above what workers have spent: one more call fits, then the wall
    const set = await budget({ workerBudgetMicros: (await spent()) + 1 });
    if (set.status !== 200) fail(`setting the workers' ceiling: ${set.status} ${set.text.slice(0, 160)}`);
    const fits = await door(key, body);
    if (fits.status !== 200) fail(`the call under the ceiling: ${fits.status} ${fits.text.slice(0, 160)}`);
    const wall = await fetch(DOOR, { method: 'POST', body: JSON.stringify(body),
      headers: { 'x-api-key': key, 'Content-Type': 'application/json' } });
    const wallBody = await wall.json().catch(() => ({}));
    if (wall.status !== 402) fail(`past the workers' ceiling the door answered ${wall.status}, not 402`);
    if (!/billing hard limit/.test(wallBody.error?.message || '')) fail(`the ceiling's words: "${wallBody.error?.message}"`);
    if (wall.headers.get('retry-after')) fail('a ceiling refusal carried Retry-After — the SDK would retry into the wall');
    const gov = (await call('GET', `${API}/ai/v1/governance`, admin)).json;
    console.log(`OK past the workers' ceiling the door answered 402 "billing hard limit", no Retry-After — the shift ends (${gov.workerSpentMicros} of ${gov.workerBudgetMicros} µ$)`);
    await budget({ workerBudgetMicros: 0, enabled: false });
    const killed = await door(key, body);
    if (killed.status !== 402 || !/kill-switch/.test(killed.json?.error?.message || '')) {
      fail(`with the kill-switch off: ${killed.status} ${killed.text.slice(0, 160)}`);
    }
    console.log('OK the tenant\'s kill-switch stops its workers too (402)');
  } finally {
    await budget({ workerBudgetMicros: 0, enabled: true });
  }
  const pricey = await door(key, { ...body, model: 'claude-opus-not-this-tenants' });
  if (pricey.status !== 400 || pricey.json?.error?.type !== 'invalid_request_error') {
    fail(`a model that is not the tenant's worker model: ${pricey.status} ${pricey.text.slice(0, 160)}`);
  }
  const streamed = await door(key, { ...body, stream: true });
  const why = streamed.json?.error?.message || '';
  if (streamed.status !== 400 || !/stream/.test(why) || !/not supported/.test(why)) {
    fail(`a streaming request: ${streamed.status} "${why}"`);
  }
  const models = await call('GET', `${API}/ai/v1/modelDoor/anthropic/v1/models`);
  if (models.status !== 404) fail(`the model list answered ${models.status}`);
  console.log('OK only the tenant\'s worker models are served (400); streaming is refused as "not supported" so the SDK falls back; the model list is not served (404)');

  /* ---------- 5. redacted before it leaves ---------- */
  const withCustomer = { model: 'claude-haiku-4-5-20251001', max_tokens: 64, messages: [
    { role: 'user', content: [{ type: 'text', text: `Work ticket ${run}.` }] },
    { role: 'assistant', content: [{ type: 'tool_use', id: 'toolu_1', name: 'care_ticket_get', input: { id: `t-${run}` } }] },
    { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'toolu_1',
      content: `Customer paula${run}@family.example reports a red router light; call her on +47 912 34 567.` }] },
  ] };
  const redacted = await door(key, withCustomer);
  if (redacted.status !== 200) fail(`a call carrying customer data: ${redacted.status} ${redacted.text.slice(0, 160)}`);
  const sent = (await (await fetch(`${MOCK}/requests`)).json()).filter((r) => r.api === 'anthropic').pop();
  if (!sent?.said) fail('the provider recorded nothing it was sent');
  if (sent.said.includes(`paula${run}@family.example`) || sent.said.includes('912 34 567')) {
    fail(`the provider received customer data in clear: "${sent.said.slice(0, 200)}"`);
  }
  if (!/<email#\d+>/.test(sent.said)) fail(`the provider did not receive a placeholder: "${sent.said.slice(0, 200)}"`);
  const rrow = ((await call('GET', `${API}/ai/v1/audit`, admin)).json || [])
    .filter((a) => a.actor === badge).find((a) => a.redactedFields > 0);
  if (!rrow || rrow.rawExposure) fail('the ledger does not record a redacted call for the worker');
  console.log(`OK customer data left as placeholders ("${sent.said.match(/<email#\d+>/)[0]}"), never in clear — the ledger counts ${rrow.redactedFields} redacted value(s)`);

  /* ---------- 6. refusals in the provider's own shape ---------- */
  const stranger = await door('bssw_not-a-real-key', body);
  if (stranger.status !== 401 || stranger.json?.error?.type !== 'authentication_error') {
    fail(`an unknown key: ${stranger.status} ${stranger.text.slice(0, 160)}`);
  }
  const revoked = await call('DELETE', `${API}/ai/v1/workforce/doorKeys/${issued.json.id}`, admin);
  if (revoked.status !== 204) fail(`revoking the key: ${revoked.status}`);
  const fired = await door(key, body);
  if (fired.status !== 401) fail(`a revoked key still opened the door: ${fired.status}`);
  console.log('OK an unknown key and a revoked key are refused (401, the provider\'s own error shape) — firing a worker shuts its door');

  /* ---------- 7. the Workforce page shows it, and sets the ceiling ---------- */
  const kpis = (await call('GET', `${API}/ai/v1/workforce/kpis`, admin)).json;
  if (!(kpis?.measuredCostMicros > 0)) fail(`the workforce KPIs carry no measured cost: ${JSON.stringify(kpis).slice(0, 160)}`);
  const browser = await chromium.launch();
  try {
    const page = await (await browser.newContext({ viewport: { width: 1500, height: 950 } })).newPage();
    await page.goto('http://console.nova.localhost:8080/console/');
    await page.waitForSelector('input[name="username"]', { timeout: 20000 });
    await page.fill('input[name="username"]', 'demo');
    await page.fill('input[name="password"]', 'demo');
    await page.click('input[type="submit"], button[type="submit"]');
    await page.waitForSelector('#main:not([hidden])', { timeout: 25000 });
    await page.getByText('AI & AUTOMATION', { exact: false }).first().click();
    await page.getByText('AI Workforce', { exact: true }).first().click();
    const tile = page.locator('[data-testid=wf-kpi-measured]');
    await tile.waitFor({ timeout: 20000 });
    const shown = (await tile.innerText()).replace(/\s+/g, ' ');
    const expected = '$' + (kpis.measuredCostMicros / 1e6).toFixed(4);
    if (!shown.includes(expected)) fail(`the measured tile reads "${shown}", the door recorded ${expected}`);
    if (!(await page.locator('[data-testid=wf-kpi-self-reported]').count())) fail('the self-reported figure is gone from beside the measured one');
    await page.fill('[data-testid=wf-spend-input]', '0.25');
    await page.click('[data-testid=wf-spend-save]');
    let saved = 0;
    for (let i = 0; i < 10 && saved !== 250000; i++) {
      await new Promise((r) => setTimeout(r, 500));
      saved = (await call('GET', `${API}/ai/v1/governance`, admin)).json?.workerBudgetMicros;
    }
    if (saved !== 250000) fail(`the page's worker ceiling did not reach governance (${saved})`);
    console.log(`OK the Workforce page shows "AI cost — measured" ${expected} with the self-reported figure beside it, and its worker spend ceiling ($0.25) reached governance`);
  } finally {
    await browser.close();
    await call('POST', `${API}/ai/v1/governance/budget`, admin, { workerBudgetMicros: 0 });
  }

  console.log('PASS model_door_test — a worker\'s model call is measured by the BSS, not reported by the worker');
})().catch((e) => fail(e && e.stack || e));
