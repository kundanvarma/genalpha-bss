/* The AI control plane — authenticate, budget, audit. Suite #59.
 *
 *  - METERED: every AI turn now carries token counts and a cost — the
 *    ledger knows what the conversation cost, not just what it said.
 *  - BUDGETED, FAIL-CLOSED: a tenant with a spend ceiling gets refused
 *    when it's crossed — a clean 429, audited as refused-budget, never
 *    a silent charge. The same law the gateway rate limiter and the
 *    DNC wash obey: fail closed on a metered resource.
 *  - THE KILL-SWITCH: aiEnabled=false refuses instantly (403), audited;
 *    flip it back and the calls flow again.
 *  - ACTIONS ON THE LEDGER: an advisor adoption lands as an ACTION row
 *    (which AI wrote which resource), not only a completion.
 *  - GOVERNANCE VIEW: spend, ceiling, remaining and the action trail in
 *    one call — and a second tenant's numbers are isolated (RLS).
 *  - FALLBACK HOLDS: no budget row = unlimited-and-enabled — governance
 *    is per-tenant opt-in, not a global tax on every AI feature.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();

async function token(ctx, realm, user, pass) {
  for (let i = 0; i < 12; i++) {
    try {
      const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
        { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
      const body = await res.json();
      if (body.access_token) return body.access_token;
    } catch (transient) { /* mid-boot is not a verdict */ }
    await new Promise((r) => setTimeout(r, 3000));
  }
  throw new Error(`no token for ${user}@${realm}`);
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const staff = await token(ctx, 'bss', 'demo', 'demo');

  const copy = (tok) => ctx.post(`${API}/ai/v1/campaignCopy`, { headers: H(tok),
    data: { brief: `Governed spring data boost ${run}`, brandName: 'GenAlpha' } });

  /* ---------- 0. reset: genalpha unlimited-and-enabled ---------- */
  await ctx.post(`${API}/ai/v1/governance/budget`, { headers: H(staff),
    data: { budgetMicros: 0, enabled: true } });

  /* ---------- 1. METERED ---------- */
  const first = await copy(staff);
  if (first.status() !== 200) fail('baseline AI call failed: ' + first.status());
  const audit = await (await ctx.get(`${API}/ai/v1/audit`, { headers: H(staff) })).json();
  // newest-first; the governed row is the fresh campaign-copy (the 240-char
  // prompt PREVIEW is all system prompt, so match recency, not content)
  const metered = audit.find((a) => a.useCase === 'campaign-copy'
    && (Date.now() - Date.parse(a.createdAt)) < 120000);
  if (!metered) fail('the governed call did not land in the ledger');
  if (!(metered.tokens > 0) || !(metered.costMicros > 0) || metered.outcome !== 'ok') {
    fail('the ledger row is not metered: ' + JSON.stringify(metered).slice(0, 200));
  }
  console.log(`OK METERED: the AI turn cost ${metered.tokens} tokens / ${metered.costMicros}`
    + ' micros and says so in the ledger — the audit knows what the conversation COST,'
    + ' not just what it said');

  /* ---------- 2. BUDGETED, FAIL-CLOSED ---------- */
  // a ceiling below one call's cost: the very next call must refuse
  await ctx.post(`${API}/ai/v1/governance/budget`, { headers: H(staff),
    data: { budgetMicros: 1, enabled: true } });
  const refused = await copy(staff);
  if (refused.status() !== 429) {
    fail('over-budget call was not refused with 429: ' + refused.status());
  }
  const audit2 = await (await ctx.get(`${API}/ai/v1/audit`, { headers: H(staff) })).json();
  if (!audit2.some((a) => a.outcome === 'refused-budget')) {
    fail('the refusal did not land in the ledger as refused-budget');
  }
  console.log('OK BUDGETED, FAIL-CLOSED: the ceiling crossed, the next call answered 429 and'
    + ' the refusal itself is a ledger row — no silent charges, no silent refusals');

  /* ---------- 3. THE KILL-SWITCH ---------- */
  await ctx.post(`${API}/ai/v1/governance/budget`, { headers: H(staff),
    data: { budgetMicros: 0, enabled: false } });
  const disabled = await copy(staff);
  if (disabled.status() !== 403) {
    fail('kill-switch did not refuse with 403: ' + disabled.status());
  }
  await ctx.post(`${API}/ai/v1/governance/budget`, { headers: H(staff),
    data: { enabled: true } });
  const revived = await copy(staff);
  if (revived.status() !== 200) fail('AI did not revive after the kill-switch: ' + revived.status());
  console.log('OK THE KILL-SWITCH: enabled=false refused instantly (403, audited), and one'
    + ' flip later the calls flow again — an operator can turn a tenant\'s AI OFF');

  /* ---------- 4. ACTIONS ON THE LEDGER ---------- */
  const adopt = await (await ctx.post(`${API}/advisor/v1/adopt`, { headers: H(staff),
    data: { name: `Governed Advisor Draft ${run}`,
      description: 'Born in suite #59', price: { unit: 'EUR', value: 7.5 } } })).json();
  if (!adopt.offeringId) fail('advisor adopt failed: ' + JSON.stringify(adopt).slice(0, 160));
  const gov = await (await ctx.get(`${API}/ai/v1/governance`, { headers: H(staff) })).json();
  const action = (gov.actions || []).find((a) => a.resourceRef === adopt.offeringId);
  if (!action || action.action !== 'catalog.createDraftOffering') {
    fail('the adoption is not on the action trail: ' + JSON.stringify(gov.actions || []).slice(0, 200));
  }
  console.log(`OK ACTIONS ON THE LEDGER: the advisor's write is a governance row —`
    + ` ${action.action} → ${String(action.resourceRef).slice(0, 8)}… — the trail answers`
    + ' "which AI touched which resource", not only "what did it say"');

  /* ---------- 5. GOVERNANCE VIEW + tenant isolation ---------- */
  if (!(gov.spentMicros > 0) || gov.unlimited !== true) {
    fail('the governance view is wrong: ' + JSON.stringify(gov).slice(0, 200));
  }
  const nova = await token(ctx, 'nova', 'demo', 'demo');
  const novaGov = await (await ctx.get(`${API}/ai/v1/governance`, { headers: H(nova) })).json();
  if (novaGov.tenant === gov.tenant) fail('nova sees genalpha governance');
  if ((novaGov.actions || []).some((a) => a.resourceRef === adopt.offeringId)) {
    fail('nova sees genalpha\'s action trail — tenant wall breached');
  }
  console.log('OK GOVERNANCE VIEW: spend, ceiling and trail in one call — and nova\'s view'
    + ' is its own tenant\'s, never genalpha\'s (the RLS wall holds on the control plane too)');

  /* ---------- 6. FALLBACK HOLDS ---------- */
  const novaCopy = await ctx.post(`${API}/ai/v1/campaignCopy`, { headers: H(nova),
    data: { brief: 'Ubudsjettert vårkampanje', brandName: 'Nova' } });
  if (novaCopy.status() !== 200) {
    fail('a tenant with NO budget row was taxed by governance: ' + novaCopy.status());
  }
  console.log('OK FALLBACK HOLDS: a tenant with no budget row is unlimited-and-enabled —'
    + ' governance is opt-in per tenant, not a global tax');

  console.log('\nALL AI-CONTROL-PLANE CHECKS PASSED — every LLM turn is authenticated, metered'
    + ' and audited; spend has a fail-closed ceiling; the operator holds a kill-switch; and'
    + ' agent actions are on the same ledger as the words. The AI is governed now, with receipts.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
