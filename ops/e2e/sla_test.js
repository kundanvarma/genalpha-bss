/* SLA management (TMF623: terms as agreement data, breach → credit note). Suite #74.
 *
 *  - SLA terms live on the AGREEMENT (circuit, promised minutes, pre-agreed
 *    credit, monthly cap) — data, never code
 *  - a problem on the covered circuit that resolves LATE mints a violation
 *    (assurance's ledger, where the cap is enforced) and billing compensates
 *    with the credit the contract already authorized — no human decides at
 *    breach time; the deciding happened at signature
 *  - the cap holds: the third breach in a month is RECORDED but not credited
 *  - a generous threshold never violates; the TMF623 faces list both the
 *    promises and the breaks
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const AGREE = '/tmf-api/agreementManagement/v4';
const ALARM = '/tmf-api/alarmManagement/v4';
const PROB = '/tmf-api/serviceProblemManagement/v4';
const SLA = '/tmf-api/slaManagement/v4';
const BILLS = '/tmf-api/customerBillManagement/v4';
const run = Date.now();
const circuit = `sla-circuit-${run}`;
const fail = (m) => { throw new Error(m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
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
  const staff = await token('demo', 'demo');
  const kai = await token('kai@bss.local', 'kai');
  const kaiId = sub(kai);

  /* ---------- 1. the promise, signed as data ---------- */
  const sla = await call('POST', `${AGREE}/agreement`, staff, {
    name: `Enterprise circuit SLA ${run}`, agreementType: 'commercial', status: 'active',
    engagedParty: [{ id: kaiId, role: 'customer' }],
    characteristic: { sla: { affectedObject: circuit, thresholdMinutes: 0,
      creditAmount: 2, capPerMonth: 4 } } });
  if (sla.status >= 300) fail(`sla agreement: ${sla.status} ${sla.text.slice(0, 200)}`);
  const lenient = await call('POST', `${AGREE}/agreement`, staff, {
    name: `Lenient SLA ${run}`, agreementType: 'commercial', status: 'active',
    engagedParty: [{ id: kaiId, role: 'customer' }],
    characteristic: { sla: { affectedObject: circuit, thresholdMinutes: 99999,
      creditAmount: 50, capPerMonth: 100 } } });
  if (lenient.status >= 300) fail('lenient agreement failed');
  const inForce = (await call('GET', `${SLA}/sla`, staff)).body || [];
  if (!inForce.some((s) => s.id === sla.body.id)) fail('the SLA face misses the new promise');
  console.log(`OK THE PROMISE: SLA terms signed as agreement DATA — circuit ${circuit},`
    + ' 0-minute promise, 2 EUR pre-agreed credit, 4 EUR monthly cap — and the TMF623'
    + ' face lists it in force (plus a lenient control that should never break).');

  /* ---------- 2. three breaches, sequentially (one problem per object) ---------- */
  // assurance mints ONE open problem per object — so the month's three
  // breaches happen as three cycles: alarm, let the clock run, resolve
  for (let cycle = 1; cycle <= 3; cycle++) {
    const fresh = await token('demo', 'demo'); // the waits outlive access tokens
    const alarm = await call('POST', `${ALARM}/alarm`, fresh, {
      alarmedObject: circuit, perceivedSeverity: 'critical',
      probableCause: `suite outage ${run}-${cycle}` });
    if (alarm.status >= 300) fail(`alarm ${cycle}: ${alarm.status} ${alarm.text.slice(0, 150)}`);
    await sleep(3000);
    const open = ((await call('GET', `${PROB}/serviceProblem`, fresh)).body || [])
      .find((p) => p.affectedObject === circuit && p.status === 'open');
    if (!open) fail(`cycle ${cycle}: no open problem`);
    await sleep(62000); // the 0-minute promise: any resolution over a minute breaches
    const late = await token('demo', 'demo');
    const res = await call('PATCH', `${PROB}/serviceProblem/${open.id}`, late,
      { status: 'resolved' });
    if (res.status >= 300) fail(`resolve cycle ${cycle}: ${res.status}`);
    console.log(`OK BREACH ${cycle}: outage ran ~1m against a 0m promise, then resolved.`);
  }

  /* ---------- 3. violations minted; the cap held ---------- */
  await sleep(3000);
  const staff2 = await token('demo', 'demo');
  const violations = ((await call('GET', `${SLA}/slaViolation`, staff2)).body || [])
    .filter((v) => v.affectedObject === circuit);
  if (violations.length !== 3) fail(`expected 3 violations, got ${violations.length}`);
  if (violations.some((v) => v.agreementId === lenient.body.id)) {
    fail('the lenient SLA (99999m) must never violate');
  }
  const credited = violations.filter((v) => v.credited);
  const capped = violations.filter((v) => !v.credited);
  if (credited.length !== 2 || capped.length !== 1) {
    fail(`cap arithmetic wrong: ${credited.length} credited, ${capped.length} capped`);
  }
  if (!String(capped[0].note).includes('cap')) fail('capped violation should say why');
  console.log(`OK THE LEDGER: three breaches recorded (each ${violations[0].durationMinutes}m`
    + ' over a 0m promise); TWO credited at 2 EUR, the THIRD hit the 4 EUR monthly cap —'
    + ' recorded but not credited, with the reason written down.');

  /* ---------- 4. the compensation: pre-agreed credit notes, machine-minted ---------- */
  let slaCredits = [];
  for (let i = 0; i < 20 && slaCredits.length < 2; i++) {
    await sleep(3000);
    const notes = (await call('GET', `${BILLS}/creditNote`, staff2)).body || [];
    slaCredits = notes.filter((n) => String(n.reason).includes('SLA violation')
      && String(n.reason).includes(circuit));
  }
  if (slaCredits.length !== 2) fail(`expected exactly 2 SLA credit notes, got ${slaCredits.length}`);
  for (const n of slaCredits) {
    if (Math.abs(Number(n.amount.value) - 2) > 0.001) fail('credit amount wrong: ' + n.amount.value);
    if (!(n.relatedParty || []).some((p) => p.id === kaiId)) fail('credit landed on wrong party');
  }
  console.log(`OK THE COMPENSATION: billing minted ${slaCredits[0].creditNoteNo} and`
    + ` ${slaCredits[1].creditNoteNo} — 2 EUR each, reasons citing the violation, the`
    + ' circuit and the agreement — IN-PROCESS, no admin machine grant, because nobody'
    + ' decided anything at breach time: the contract did, at signature.');

  console.log('\nALL SLA CHECKS PASSED — the promise is data, the breach is a ledger row,'
    + ' the compensation is a numbered credit note the contract pre-authorized, and the'
    + ' cap is enforced where the violations live. TMF623 lists the promises in force'
    + ' and the ones that broke.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
