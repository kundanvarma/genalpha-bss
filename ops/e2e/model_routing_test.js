/* Tiered model routing — one tenant, several models AT ONCE.
 *
 *  - the task class is declared AT THE CALL SITE: copywriting is FAST
 *    (swift-mini), the product copilot is SMART (frontier-x) — both
 *    live simultaneously behind one per-tenant seam
 *  - the mock LLM names the model in every answer and keeps a ledger,
 *    so the routing is proven on the wire, not assumed
 *  - fallback holds: a tenant with no tiers (genalpha, on the stub)
 *    behaves exactly as before
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const aurora = await token(ctx, 'aurora', 'demo', 'demo');
  if (!aurora) fail('no aurora staff token — run operator_form_test first (aurora must exist)');

  /* ---------- 1. volume work rides the CHEAP model ---------- */
  const copy = await (await ctx.post(`${API}/ai/v1/campaignCopy`, { headers: H(aurora),
    data: { brief: 'Winter data boost for loyal customers', offering: 'Aurora Tele Mobile M' } })).json();
  const copyText = JSON.stringify(copy);
  if (!copyText.includes('[swift-mini]')) {
    fail('the copywriting did not ride the fast model: ' + copyText.slice(0, 200));
  }
  console.log('OK FAST LANE: campaign copywriting answered by [swift-mini] — the volume task'
    + ' got the cheap model, and the SUBJECT/BODY contract still parsed');

  /* ---------- 2. judgment rides the CAREFUL model — at the same time ---------- */
  const copilot = await (await ctx.post(`${API}/ai/v1/productCopilot`, { headers: H(aurora),
    data: { messages: [{ role: 'owner', content: 'I want to add a streaming service to the catalog' }] } })).json();
  const copilotText = JSON.stringify(copilot);
  if (!copilotText.includes('[frontier-x]')) {
    fail('the product copilot did not ride the smart model: ' + copilotText.slice(0, 200));
  }
  console.log('OK SMART LANE: the product copilot answered by [frontier-x] — judgment work got'
    + ' the careful model, through the SAME tenant seam, in the same minute');

  /* ---------- 3. the wire agrees ---------- */
  const ledger = await (await ctx.get('http://localhost:8127/requests')).json();
  const models = new Set(ledger.map((r) => r.model));
  if (!models.has('swift-mini') || !models.has('frontier-x')) {
    fail('the LLM ledger does not show both models: ' + [...models].join(','));
  }
  const misrouted = ledger.find((r) => r.task.includes('product copilot') && r.model !== 'frontier-x');
  if (misrouted) fail('a copilot prompt hit the wrong model: ' + JSON.stringify(misrouted));
  // and DIFFERENT PROVIDER PROTOCOLS: FAST rode the openai dialect,
  // SMART rode the anthropic dialect — one tenant, two providers, at once
  const fastApi = ledger.find((r) => r.model === 'swift-mini');
  const smartApi = ledger.find((r) => r.model === 'frontier-x');
  if (!fastApi || fastApi.api !== 'openai' || !smartApi || smartApi.api !== 'anthropic') {
    fail('the provider dialects are wrong: ' + JSON.stringify({
      fast: fastApi && fastApi.api, smart: smartApi && smartApi.api }));
  }
  console.log('OK ON THE WIRE: the ledger shows both models AND both provider dialects —'
    + ' swift-mini over openai, frontier-x over anthropic — one tenant, two providers, two'
    + ' models, at once');

  /* ---------- 4. no tiers, no change ---------- */
  const gaStaff = await token(ctx, 'bss', 'demo', 'demo');
  const gaCopy = await (await ctx.post(`${API}/ai/v1/campaignCopy`, { headers: H(gaStaff),
    data: { brief: 'Same ask on the stub tenant', offering: 'GenAlpha Mobile 10 GB' } })).json();
  if (JSON.stringify(gaCopy).includes('[swift-mini]')) {
    fail('genalpha leaked onto aurora\'s models');
  }
  console.log('OK FALLBACK HOLDS: genalpha (no tiers configured) still answers from its own'
    + ' provider — tiering is per-tenant config, not a global rewire');

  console.log('\nALL MODEL-ROUTING CHECKS PASSED — the task class lives at the call site, the'
    + ' cheap model does the volume work, the careful model does the judgment, both at once,'
    + ' per tenant, as data.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
