/* The digital workforce — an AI worker hired, badged, worked, verified. Suite #65.
 *
 *  - HIRING is an operator act: staff mints a login (TMF672) and grants the
 *    digital-worker bundle — THE BADGE IS THE OPT-IN. No badge → 403.
 *  - A REAL SHIFT: the queue derives live from real backlogs (an unassigned
 *    ticket, an unapplied bank payment); the worker claims both (a second
 *    claimant is refused), does the actual work through the same TMF doors
 *    a human uses, and completes.
 *  - VERIFIED completion: completing a ticket task while the ticket is
 *    still open is refused — a worker cannot mark done what is not done.
 *  - The kill-switch stops the workforce like it stops the copilots.
 *  - FIRING works: revoke the badge → the next shift never starts.
 *  - The tenant wall: nova's queue carries none of genalpha's backlog.
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms';
const run = Date.now();

const fail = (m) => { throw new Error(m); };

async function token(realm, user, pass) {
  const res = await fetch(`${KC}/${realm}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password', client_id: 'bss-demo', username: user, password: pass,
    }),
  });
  if (!res.ok) fail(`token(${realm},${user}): ${res.status} ${await res.text()}`);
  return (await res.json()).access_token;
}

async function call(method, path, tok, body, base = API) {
  const res = await fetch(`${base}${path}`, {
    method,
    headers: {
      ...(tok ? { Authorization: `Bearer ${tok}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON */ }
  return { status: res.status, body: json, text };
}

const camt = (ref, amount) => `<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.054.001.08"><BkToCstmrDbtCdtNtfctn>
<Ntfctn><Id>WF-${run}</Id><Ntry><Amt Ccy="EUR">${amount}</Amt><CdtDbtInd>CRDT</CdtDbtInd>
<NtryDtls><TxDtls><Refs><AcctSvcrRef>bank-${run}</AcctSvcrRef></Refs>
<RmtInf><Strd><CdtrRefInf><Ref>${ref}</Ref></CdtrRefInf></Strd></RmtInf>
</TxDtls></NtryDtls></Ntry></Ntfctn></BkToCstmrDbtCdtNtfctn></Document>`;

(async () => {
  const staff = await token('bss', 'demo', 'demo');
  const H = (t) => t;

  /* ---------- 1. hiring: mint the badge (TMF672) ---------- */
  const workerEmail = `worker-hermes-${run}@bss.local`;
  const minted = await call('POST', '/tmf-api/rolesAndPermissionsManagement/v4/user', staff,
    { email: workerEmail, givenName: 'Hermes', familyName: 'Worker' });
  if (minted.status >= 300) fail(`mint worker login: ${minted.status} ${minted.text.slice(0, 200)}`);
  const workerId = minted.body.id;
  const workerPass = minted.body.temporaryPassword;

  // before the badge: the login exists but the workforce door is shut
  const unbadged = await token('bss', workerEmail, workerPass);
  const shut = await call('GET', '/ai/v1/workforce/tasks', unbadged);
  if (shut.status !== 403) fail(`no badge should mean 403: ${shut.status}`);

  // hiring = one grant; the service itself sheds the walk-in customer
  // defaults (a digital worker is STAFF, or party-scoping would confine it
  // to an empty customer world of its own)
  const granted = await call('POST', '/tmf-api/rolesAndPermissionsManagement/v4/permission', staff,
    { user: { id: workerId }, userRole: { name: 'digital-worker' } });
  if (granted.status >= 300) fail(`grant digital-worker: ${granted.status} ${granted.text.slice(0, 200)}`);
  let worker = await token('bss', workerEmail, workerPass); // fresh token wears the badge
  const claims = JSON.parse(Buffer.from(worker.split('.')[1], 'base64url').toString());
  if (claims.realm_access.roles.includes('customer')) {
    fail('the hired worker still carries the customer persona');
  }
  const openDoor = await call('GET', '/ai/v1/workforce/tasks', worker);
  if (openDoor.status !== 200) fail(`badged worker refused: ${openDoor.status} ${openDoor.text.slice(0, 200)}`);
  console.log('OK HIRED: staff minted a login and granted the digital-worker bundle — before the'
    + ' badge the workforce door said 403, after it the queue answered. The badge IS the opt-in.');

  /* ---------- 2. seed a real backlog ---------- */
  const ticketName = `Router blinking red ${run}`;
  const ticket = await call('POST', '/tmf-api/troubleTicket/v4/troubleTicket', staff, {
    name: ticketName, description: 'Customer reports the router LED is red after the storm.',
    severity: 'major',
  });
  if (ticket.status >= 300) fail(`seed ticket: ${ticket.status} ${ticket.text.slice(0, 200)}`);
  const ticketId = ticket.body.id;

  const bank = await fetch(`${API}/bank/v1/remittance`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/xml', 'X-Bank-Token': 'genalpha-bank-token' },
    body: camt(`WFREF${run}`, '77.77'), // no bill carries this reference → parks as unapplied
  });
  if (!bank.ok) fail(`bank ingest: ${bank.status}`);

  const queue = (await call('GET', '/ai/v1/workforce/tasks', worker)).body;
  const tTask = queue.find((t) => t.kind === 'ticket' && t.subjectRef === ticketId)
    || fail('the seeded ticket never reached the queue');
  const unappliedRows = (await call('GET',
    '/tmf-api/customerBillManagement/v4/remittance/unapplied', staff)).body;
  const cashRow = unappliedRows.find((r) => r.reference === `WFREF${run}`)
    || fail('the parked payment never reached the worklist');
  const cTask = queue.find((t) => t.kind === 'unapplied-cash' && t.subjectRef === cashRow.id)
    || fail('the parked payment never reached the queue');
  console.log(`OK QUEUE: the workforce queue derived ${queue.length} open task(s) live from the`
    + ' REAL backlogs — the seeded ticket and the parked bank payment are both on it, no copies');

  /* ---------- 3. the shift: claim, verify-refusal, work, complete ---------- */
  const claimed = await call('POST', `/ai/v1/workforce/tasks/${encodeURIComponent(tTask.id)}/claim`, worker);
  if (claimed.status !== 200) fail(`claim: ${claimed.status} ${claimed.text.slice(0, 200)}`);
  const rival = await call('POST', `/ai/v1/workforce/tasks/${encodeURIComponent(tTask.id)}/claim`, staff);
  if (rival.status !== 403 && rival.status !== 409) {
    // staff lacks workforce:use → 403; a second WORKER would get 409 — both refusals hold
    fail(`a rival claim should be refused: ${rival.status}`);
  }

  const early = await call('POST',
    `/ai/v1/workforce/tasks/${encodeURIComponent(tTask.id)}/complete`, worker,
    { outcome: 'done (it was not)' });
  if (early.status !== 409) fail(`completing an unresolved ticket must 409: ${early.status}`);

  const resolved = await call('PATCH', `/tmf-api/troubleTicket/v4/troubleTicket/${ticketId}`,
    worker, { status: 'resolved', note: [{ text: 'Power-cycled the ONT remotely; link restored.' }] });
  if (resolved.status >= 300) fail(`worker resolving ticket: ${resolved.status} ${resolved.text.slice(0, 200)}`);
  const done = await call('POST',
    `/ai/v1/workforce/tasks/${encodeURIComponent(tTask.id)}/complete`, worker,
    { outcome: 'Resolved: remote ONT power-cycle restored the link.',
      selfReported: { tokens: 1420, costMicros: 2840, model: 'hermes-4-70b' } });
  if (done.status !== 200) fail(`complete after real work: ${done.status} ${done.text.slice(0, 200)}`);
  console.log('OK THE SHIFT: the worker claimed the ticket (a rival claim was refused), tried to'
    + ' complete WITHOUT doing the work — refused with 409 — then resolved the ticket through the'
    + ' same TMF621 door a human uses and completed, self-reporting its model usage');

  /* ---------- 4. back-office leg: escalate the cash honestly ---------- */
  const cClaim = await call('POST', `/ai/v1/workforce/tasks/${encodeURIComponent(cTask.id)}/claim`, worker);
  if (cClaim.status !== 200) fail(`claim cash task: ${cClaim.status}`);
  const cEarly = await call('POST',
    `/ai/v1/workforce/tasks/${encodeURIComponent(cTask.id)}/complete`, worker,
    { outcome: 'matched (it was not)' });
  if (cEarly.status !== 409) fail(`completing unapplied cash still parked must 409: ${cEarly.status}`);
  const esc = await call('POST',
    `/ai/v1/workforce/tasks/${encodeURIComponent(cTask.id)}/escalate`, worker,
    { reason: 'No open bill matches reference or amount — needs a human AR decision.' });
  if (esc.status !== 200) fail(`escalate: ${esc.status} ${esc.text.slice(0, 200)}`);
  const ledger = (await call('GET', '/ai/v1/workforce/ledger', worker)).body;
  const lDone = ledger.find((t) => t.id === tTask.id);
  const lEsc = ledger.find((t) => t.id === cTask.id);
  if (!lDone || lDone.status !== 'completed') fail('the completed task is not on the ledger');
  if (!lEsc || lEsc.status !== 'escalated') fail('the escalated task is not on the ledger');
  if (!lDone.selfReported || lDone.selfReported.model !== 'hermes-4-70b') {
    fail('the self-reported model usage did not reach the ledger');
  }
  console.log('OK HONEST ESCALATION: the cash with no matching bill could not be force-completed'
    + ' (409) — the worker escalated with a reason, and the shift ledger now shows one completed'
    + ' (with self-reported cost, labeled) and one escalated. Counted, not hidden.');

  /* ---------- 5. the kill-switch ---------- */
  const off = await call('POST', '/ai/v1/governance/budget', staff, { enabled: false });
  if (off.status >= 300) fail(`kill-switch off: ${off.status}`);
  const dark = await call('GET', '/ai/v1/workforce/tasks', worker);
  await call('POST', '/ai/v1/governance/budget', staff, { enabled: true }); // restore before asserting
  if (dark.status !== 403) fail(`kill-switch should stop the workforce: ${dark.status}`);
  console.log('OK KILL-SWITCH: the tenant\'s one AI lever stopped the workforce mid-shift — the'
    + ' same switch that stops the copilots, restored after the proof');

  /* ---------- 5b. the T3 gate: propose, never perform ---------- */
  const mkPayment = async (label) => {
    const p = await call('POST', '/tmf-api/paymentManagement/v4/payment', staff, {
      description: `WF refund subject ${label} ${run}`,
      amount: { unit: 'EUR', value: 9.99 },
      paymentMethod: { '@type': 'bankCard', token: `spt-wf-${label}-${run}`, lastFourDigits: '4242' },
    });
    if (p.status >= 300) fail(`payment ${label}: ${p.status} ${p.text.slice(0, 200)}`);
    const cap = await call('PATCH', `/tmf-api/paymentManagement/v4/payment/${p.body.id}`, staff,
      { status: 'captured' });
    if (cap.status >= 300) fail(`capture ${label}: ${cap.status}`);
    return p.body.id;
  };
  const paymentStatus = async (id) => (await call('GET',
    `/tmf-api/paymentManagement/v4/payment/${id}`, staff)).body.status;

  const p1 = await mkPayment('p1');
  const fileRefund = (paymentId, why) => call('POST', '/ai/v1/workforce/approvals', worker, {
    action: 'payment.refund', method: 'POST',
    path: `/tmf-api/paymentManagement/v4/payment/${paymentId}/refund`,
    body: { amount: { unit: 'EUR', value: 9.99 }, reason: why },
    reason: why,
  });
  const a1 = await fileRefund(p1, 'Customer was double-charged after the outage — goodwill refund.');
  if (a1.status >= 300) fail(`file approval: ${a1.status} ${a1.text.slice(0, 200)}`);
  if (await paymentStatus(p1) !== 'captured') fail('filing an approval MOVED MONEY — it must not');

  const selfApprove = await call('POST',
    `/ai/v1/workforce/approvals/${a1.body.id}/approve`, worker);
  if (selfApprove.status !== 403) fail(`a worker approving its own ask must 403: ${selfApprove.status}`);

  const pendings = (await call('GET', '/ai/v1/workforce/approvals?status=pending', staff)).body;
  if (!pendings.some((a) => a.id === a1.body.id)) fail('the filed approval is not in the pending queue');
  const approved = await call('POST',
    `/ai/v1/workforce/approvals/${a1.body.id}/approve`, staff);
  if (approved.status !== 200) fail(`approve: ${approved.status} ${approved.text.slice(0, 300)}`);
  if (await paymentStatus(p1) !== 'refunded') fail('approval approved but the refund never happened');

  const p2 = await mkPayment('p2');
  const a2 = await fileRefund(p2, 'Second proposed refund — to be refused.');
  const refused = await call('POST', `/ai/v1/workforce/approvals/${a2.body.id}/refuse`, staff,
    { note: 'Not warranted: the charge is correct.' });
  if (refused.status !== 200) fail(`refuse: ${refused.status}`);
  if (await paymentStatus(p2) !== 'captured') fail('a REFUSED approval moved money');
  console.log('OK T3 GATE: the worker FILED a refund — no money moved; it could not approve its'
    + ' own ask (403); a human approved and the refund executed under the HUMAN\'S token; a second'
    + ' ask was refused with a note and the money stayed put. Proposals, never performances.');

  /* ---------- 5c. the scoreboard ---------- */
  const kpis = (await call('GET', '/ai/v1/workforce/kpis', staff)).body;
  if (!kpis || kpis.completed < 1) fail('KPIs show no completed work');
  if (kpis.escalated < 1) fail('KPIs show no escalation');
  if (kpis.approvals.approved < 1 || kpis.approvals.refused < 1) {
    fail('KPIs miss the approval outcomes: ' + JSON.stringify(kpis.approvals));
  }
  if (kpis.humanMinutesSaved.estimate !== true) {
    fail('human-minutes-saved must be LABELED an estimate');
  }
  if (!kpis.reopen || kpis.reopen.definition == null) fail('the reopen (honesty) metric is missing');
  const wRow = kpis.workers.find((w) => w.completed >= 1)
    || fail('no worker row on the scoreboard');
  console.log(`OK SCOREBOARD: ${kpis.completed} completed / ${kpis.escalated} escalated`
    + ` (deflection ${Math.round((kpis.deflectionRate || 0) * 100)}%), reopen rate`
    + ` ${Math.round(kpis.reopen.rate * 100)}% of ${kpis.reopen.checked} checked,`
    + ` ~${kpis.humanMinutesSaved.minutes} human-minutes saved (LABELED estimate, baselines`
    + ` ${JSON.stringify(kpis.humanMinutesSaved.baselineMinutes)}), worker ${wRow.worker.slice(0, 8)}…`
    + ' on the board — every number from the ledger, every estimate labeled');

  // one more pending approval for the DASHBOARD to decide
  const p3 = await mkPayment('p3');
  const a3 = await fileRefund(p3, 'Third proposed refund — for the dashboard click.');
  if (a3.status >= 300) fail(`file a3: ${a3.status}`);

  /* ---------- 6. firing: revoke the badge ---------- */
  const permissionId = Buffer.from(`${workerId}~digital-worker`).toString('base64url');
  const fired = await call('DELETE',
    `/tmf-api/rolesAndPermissionsManagement/v4/permission/${permissionId}`, staff);
  if (fired.status >= 300) fail(`revoke badge: ${fired.status} ${fired.text.slice(0, 200)}`);
  worker = await token('bss', workerEmail, workerPass); // fresh token, no badge
  const after = await call('GET', '/ai/v1/workforce/tasks', worker);
  if (after.status !== 403) fail(`a fired worker should get 403: ${after.status}`);
  console.log('OK FIRED: the badge was revoked on the same TMF672 surface that granted it — the'
    + ' next shift never started. Employment is a grant the operator holds, not a property of the AI.');

  /* ---------- 7. the tenant wall ---------- */
  const novaStaff = await token('nova', 'demo', 'demo');
  const novaMint = await call('POST', '/tmf-api/rolesAndPermissionsManagement/v4/user', novaStaff,
    { email: `worker-nova-${run}@nova.local`, givenName: 'Nova', familyName: 'Worker' });
  if (novaMint.status >= 300) fail(`nova mint: ${novaMint.status} ${novaMint.text.slice(0, 200)}`);
  await call('POST', '/tmf-api/rolesAndPermissionsManagement/v4/permission', novaStaff,
    { user: { id: novaMint.body.id }, userRole: { name: 'digital-worker' } });
  const novaWorker = await token('nova', `worker-nova-${run}@nova.local`, novaMint.body.temporaryPassword);
  const novaQueue = (await call('GET', '/ai/v1/workforce/tasks', novaWorker)).body;
  if (novaQueue.some((t) => t.subjectRef === ticketId || t.subjectRef === cashRow.id)) {
    fail('nova\'s queue carries genalpha\'s backlog');
  }
  console.log('OK TENANT WALL: nova hired its own worker on its own realm — its queue carries'
    + ' none of genalpha\'s tickets or cash. Every operator\'s workforce works only its own floor.');

  /* ---------- 8. the dashboard: see the shift, click the decision ---------- */
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    await page.goto(`${API}/console/`);
    await page.waitForSelector('#username, input[name="username"]', { timeout: 15000 });
    if (await page.locator('input[name="username"]').count()) {
      await page.fill('input[name="username"]', 'demo');
      await page.fill('input[name="password"]', 'demo');
      await page.click('input[type="submit"], button[type="submit"]');
    }
    await page.waitForSelector('#main:not([hidden])', { timeout: 15000 });
    await page.locator('.tab', { hasText: 'Workforce' }).click();
    await page.waitForSelector('[data-testid=wf-kpi-completed]', { timeout: 15000 });
    const completedCard = await page.locator('[data-testid=wf-kpi-completed] b').textContent();
    if (Number(completedCard) < 1) fail('the dashboard shows no completed work');
    await page.waitForSelector('[data-testid=wf-approval-row]', { timeout: 10000 });
    const rows = await page.locator('[data-testid=wf-approval-row]').count();
    await page.locator('[data-testid=wf-approve]').first().click();
    // the click IS the write: the refund executes under the signed-in human
    for (let i = 0; i < 20 && await paymentStatus(p3) !== 'refunded'; i++) {
      await new Promise((r) => setTimeout(r, 1000));
    }
    if (await paymentStatus(p3) !== 'refunded') fail('the dashboard Approve click did not refund');
    if (!(await page.locator('[data-testid=wf-ledger-row]').count())) {
      fail('the shift ledger is empty on the dashboard');
    }
    console.log(`OK DASHBOARD: the console's Workforce tab showed ${completedCard} completed task(s),`
      + ` ${rows} pending approval(s) and the shift ledger; one Approve CLICK refunded the payment`
      + ' under the signed-in human\'s own token — the scoreboard is also the control room.');
  } finally {
    await browser.close();
  }

  console.log('\nALL WORKFORCE CHECKS PASSED — an AI worker was hired with a revocable badge,'
    + ' worked a real shift through the same doors humans use, could not fake a completion,'
    + ' escalated honestly, proposed the refund it was never allowed to perform, obeyed the'
    + ' kill-switch, and was fired cleanly — while the operator watched all of it, with KPIs'
    + ' whose estimates say they are estimates, on the Workforce dashboard.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
