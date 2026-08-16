/* C2a — CPQ configuration rules + discount approvals.
 * Rules: a requires-rule + a maxQty-rule; the validate endpoint (agent-callable,
 * no mutation) reports violations, and the quote BUILDER enforces them.
 * Approvals: a discount over threshold is pending until a manager approves it,
 * and the quote can't be approved while pending (the human gate on a — possibly
 * agent-proposed — discount). genalpha (bss realm); demo/demo staff.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const SALES = `${API}/tmf-api/salesManagement/v4`;
const QBASE = `${API}/tmf-api/quoteManagement/v4`;
const run = Date.now();
const IP = `IP-${run}`, LINE = `Line-${run}`, CAP = `Cap-${run}`;

async function token(ctx) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const tok = await token(ctx);
  const H = { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' };
  const num = (v) => Number(v);

  /* ---------- author configuration rules ---------- */
  await ctx.post(`${QBASE}/quote/configRule`, { headers: H, data: {
    ruleType: 'requires', subjectOffering: IP, objectOffering: LINE,
    message: `${IP} requires ${LINE}` } });
  await ctx.post(`${QBASE}/quote/configRule`, { headers: H, data: {
    ruleType: 'maxQty', subjectOffering: CAP, qty: 2 } });
  console.log('OK authored config rules: requires + maxQty');

  /* ---------- the validate decision endpoint (agent-callable) ---------- */
  let v = await (await ctx.post(`${QBASE}/quote/validate`, { headers: H, data: {
    items: [{ offeringName: IP, quantity: 1 }] } })).json();
  if (v.valid !== false || !v.violations.some((x) => x.subject === IP)) {
    fail('validate should flag IP-without-Line: ' + JSON.stringify(v));
  }
  v = await (await ctx.post(`${QBASE}/quote/validate`, { headers: H, data: {
    items: [{ offeringName: CAP, quantity: 3 }] } })).json();
  if (v.valid !== false || !v.violations.some((x) => x.ruleType === 'maxQty')) {
    fail('validate should flag maxQty breach: ' + JSON.stringify(v));
  }
  v = await (await ctx.post(`${QBASE}/quote/validate`, { headers: H, data: {
    items: [{ offeringName: IP, quantity: 1 }, { offeringName: LINE, quantity: 1 }] } })).json();
  if (v.valid !== true) fail('a valid configuration was rejected: ' + JSON.stringify(v));
  console.log('OK the validate endpoint returns violations (no mutation) — an agent can pre-check a config');

  /* ---------- the quote builder ENFORCES the rules ---------- */
  const mkOpp = async () => {
    const lead = await (await ctx.post(`${SALES}/salesLead`, { headers: H, data: { name: `CPQ ${run} ${Math.random()}`, source: 'campaign' } })).json();
    const q = await (await ctx.patch(`${SALES}/salesLead/${lead.id}`, { headers: H, data: { state: 'qualified' } })).json();
    return q.salesOpportunity.id;
  };
  const opp = await mkOpp();
  await ctx.post(`${SALES}/salesOpportunity/${opp}/item`, { headers: H, data: { offeringName: IP, quantity: 1, unitPrice: 100 } });
  const blocked = await ctx.post(`${SALES}/salesOpportunity/${opp}/quote`, { headers: H, data: {} });
  if (blocked.status() < 400) fail('quote build should be blocked by the requires-rule, got ' + blocked.status());
  console.log('OK the quote builder refuses a configuration that violates a rule (IP without Line)');

  await ctx.post(`${SALES}/salesOpportunity/${opp}/item`, { headers: H, data: { offeringName: LINE, quantity: 1, unitPrice: 200 } });
  const built = await (await ctx.post(`${SALES}/salesOpportunity/${opp}/quote`, { headers: H, data: {} })).json();
  if (!built.quote || !built.quote.id) fail('adding the required Line should let the quote build: ' + JSON.stringify(built));
  const qhref = built.quote.href;
  console.log('OK once the required item is added, the quote builds');

  /* ---------- discount approval gate ---------- */
  // a big discount -> pending, and the quote can't be approved while pending
  let quote = await (await ctx.patch(`${API}${qhref}`, { headers: H, data: { discountPercent: 30 } })).json();
  if (quote.approvalStatus !== 'pending') fail('a 30% discount should be pending approval: ' + JSON.stringify(quote.approvalStatus));
  if (num(quote.netMonthlyTotal) !== num(quote.quoteTotalPrice.value) * 0.7) fail('net total not discounted: ' + JSON.stringify(quote));
  const tryApprove = await ctx.patch(`${API}${qhref}`, { headers: H, data: { state: 'approved' } });
  if (tryApprove.status() < 400) fail('the quote must NOT be approvable while the discount is pending, got ' + tryApprove.status());
  console.log('OK a 30% discount is pending — the quote cannot be approved until a manager signs off');

  // manager approves the discount -> now the quote can be approved
  quote = await (await ctx.post(`${API}${qhref}/approveDiscount`, { headers: H })).json();
  if (quote.approvalStatus !== 'approved') fail('approveDiscount did not approve: ' + JSON.stringify(quote.approvalStatus));
  const nowApprove = await ctx.patch(`${API}${qhref}`, { headers: H, data: { state: 'approved' } });
  if (nowApprove.status() !== 200) fail('after approval the quote should move to approved, got ' + nowApprove.status());
  console.log('OK once the manager approves the discount, the quote advances — the human gate held');

  console.log('\nALL CPQ RULES + APPROVALS CHECKS PASSED — configuration is validated (agent-callable) and '
    + 'enforced at build; an over-threshold discount is gated on a human approval before the quote can advance.');
})();
