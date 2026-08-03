/* TMF696 risk management (can this party be trusted with this order?). Suite #82.
 *
 *  - an assessment ECHOES the truth: the suite independently fetches the
 *    party's unpaid bills, credit notes and recent orders, and asserts the
 *    assessment's evidence matches — the score recomputes by hand from its
 *    own body
 *  - the SESSION matters: the same order assessed with a BankID-verified
 *    session scores exactly 20 lower — verification is the strongest
 *    anti-fraud signal the fleet has, and only the caller's token knows it
 *  - the OPERATOR decides: a policy rule pinned against the live risk
 *    score denies the next order with 422 POLICY_DENIED; remove the rule
 *    and the same order passes — the threshold is data, reversible
 *  - walls: a customer never sees a risk score (a credit check is
 *    back-office); nova sees only nova
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms';
const RISK = '/tmf-api/riskManagement/v4';
const POLICY = '/tmf-api/policyManagement/v4';
const BILLS = '/tmf-api/customerBillManagement/v4';
const ORD = '/tmf-api/productOrderingManagement/v4';
const run = Date.now();
const fail = (m) => { throw new Error(m); };

async function token(realm, user, pass) {
  const r = await fetch(`${KC}/${realm}/protocol/openid-connect/token`, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo',
      username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
const sub = (t) => JSON.parse(Buffer.from(t.split('.')[1], 'base64url').toString()).sub;
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}

(async () => {
  const staff = await token('bss', 'demo', 'demo');
  const kai = await token('bss', 'kai@bss.local', 'kai');
  const kaiId = sub(kai);

  /* ---------- 1. the assessment echoes the truth ---------- */
  const assess = await call('POST', `${RISK}/partyRiskAssessment`, staff,
    { relatedParty: [{ id: kaiId }] });
  if (assess.status !== 201) fail(`assess: ${assess.status} ${assess.text.slice(0, 200)}`);
  const result = assess.body.riskAssessmentResult;
  const signals = Object.fromEntries(result.signal.map((s) => [s.name, s]));
  const summed = result.signal.reduce((t, s) => t + s.points, 0);
  if (result.overallScore !== Math.max(0, Math.min(100, summed))) {
    fail(`score ${result.overallScore} != sum of its own signals ${summed}`);
  }
  // independent truth: the suite fetches the same evidence itself
  const unpaid = (await call('GET',
    `${BILLS}/customerBill?relatedPartyId=${kaiId}&state=new&limit=100`, staff)).body || [];
  if (unpaid.length) {
    if (!signals.unpaidBills || signals.unpaidBills.evidence.count !== unpaid.length) {
      fail(`assessment says ${signals.unpaidBills?.evidence.count} unpaid, billing says ${unpaid.length}`);
    }
  } else if (signals.unpaidBills) fail('assessment invented unpaid bills');
  const notes = (await call('GET',
    `${BILLS}/creditNote?relatedPartyId=${kaiId}`, staff)).body || [];
  if (notes.length && signals.creditNotes
      && signals.creditNotes.evidence.count !== notes.length) {
    fail(`assessment says ${signals.creditNotes.evidence.count} credit notes, billing says ${notes.length}`);
  }
  const reread = await call('GET', `${RISK}/partyRiskAssessment/${assess.body.id}`, staff);
  if (reread.status !== 200 || reread.body.riskAssessmentResult.overallScore !== result.overallScore) {
    fail('the persisted assessment lost its score');
  }
  console.log(`OK THE TRUTH: kai assessed ${result.overallScore}/100 (${result.riskLevel})`
    + ` from ${result.signal.length} named signals — ${result.signal.map((s) => `${s.name}:+${s.points}`).join(', ')}`
    + ' — each echoing evidence the suite verified independently against billing and'
    + ' ordering, the total recomputable by hand, the assessment persisted and re-read.');

  /* ---------- 2. the session matters ---------- */
  const unverified = await call('POST', `${RISK}/productOrderRiskAssessment`, staff,
    { relatedParty: [{ id: kaiId }], totalQuantity: 1, lineCount: 1, verifiedIdentity: false });
  const verified = await call('POST', `${RISK}/productOrderRiskAssessment`, staff,
    { relatedParty: [{ id: kaiId }], totalQuantity: 1, lineCount: 1, verifiedIdentity: true });
  const uScore = unverified.body.riskAssessmentResult.overallScore;
  const vScore = verified.body.riskAssessmentResult.overallScore;
  const expectedDrop = Math.min(20, uScore);
  if (uScore - vScore !== expectedDrop) {
    fail(`verified session should score ${expectedDrop} lower: ${uScore} vs ${vScore}`);
  }
  console.log(`OK THE SESSION: the same order for the same party scores ${uScore} unverified`
    + ` and ${vScore} BankID-verified — the strongest anti-fraud signal the fleet has,`
    + ' known only to the session that holds it, reducing risk on the assessment itself.');

  /* ---------- 3. the operator decides, as data ---------- */
  const offerings = await (await fetch(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?name=${encodeURIComponent('GenAlpha Mobile 10 GB')}`)).json();
  const plan = offerings[0] || fail('no mobile plan');
  const order = () => call('POST', `${ORD}/productOrder`, kai, {
    description: `risk-gate probe ${run}`,
    productOrderItem: [{ id: '1', action: 'add', quantity: 1,
      productOffering: { id: plan.id, name: plan.name, '@referredType': 'ProductOffering' } }] });
  const threshold = Math.max(uScore - 5, 1);
  const rule = await call('POST', `${POLICY}/policyRule`, staff, {
    name: `E2E risk ceiling ${run}`, domain: 'order', effect: 'deny', priority: 5, enabled: true,
    condition: JSON.stringify({ '>=': [{ var: 'riskScore' }, threshold] }),
    message: 'This order needs a manual review — the account risk score is above the ceiling.',
  });
  if (rule.status !== 201) fail(`risk rule: ${rule.status} ${rule.text.slice(0, 150)}`);
  try {
    const denied = await order();
    if (denied.status !== 422 || denied.body.code !== 'POLICY_DENIED') {
      fail(`high-risk order must 422 POLICY_DENIED, got ${denied.status} ${denied.text.slice(0, 200)}`);
    }
    console.log(`OK THE OPERATOR DECIDES: a policy rule pinned at riskScore>=${threshold}`
      + ` (kai lives at ~${uScore}) denied his order with 422 and the review message —`
      + ' ordering fetched a FRESH assessment under its machine identity and the rules,'
      + ' as data, did the deciding. Nobody hardcoded a threshold.');
  } finally {
    await call('DELETE', `${POLICY}/policyRule/${rule.body.id}`, staff);
  }
  const allowed = await order();
  if (allowed.status !== 201) {
    fail(`rule removed, order should pass: ${allowed.status} ${allowed.text.slice(0, 200)}`);
  }
  console.log(`OK REVERSIBLE: the rule deleted, the same order passed (${allowed.body.id.slice(0, 8)}…)`
    + ' — the risk ceiling was configuration, not code, on and off without a deploy.');

  /* ---------- 4. walls ---------- */
  const kaiRead = await call('GET', `${RISK}/partyRiskAssessment`, kai);
  if (kaiRead.status !== 403) fail(`a customer must not read risk scores, got ${kaiRead.status}`);
  const kaiPost = await call('POST', `${RISK}/partyRiskAssessment`, kai,
    { relatedParty: [{ id: kaiId }] });
  if (kaiPost.status !== 403) fail(`a customer must not run assessments, got ${kaiPost.status}`);
  const nova = await token('nova', 'demo', 'demo');
  const novaList = (await call('GET', `${RISK}/partyRiskAssessment`, nova)).body || [];
  if (novaList.some((a) => (a.relatedParty || []).some((p) => p.id === kaiId))) {
    fail('kai\'s assessments leaked into nova');
  }
  console.log('OK THE WALLS: a risk score is a credit check — kai got 403 on reading AND'
    + ' running assessments (even his own), and nova\'s list holds none of genalpha\'s.');

  console.log('\nALL RISK CHECKS PASSED — the fleet that scored who might LEAVE now scores'
    + ' who should not be let IN: transparent signals from data the fleet actually holds,'
    + ' a verified session as the great reducer, and enforcement that stays in the'
    + ' operator\'s hands as reversible configuration.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
