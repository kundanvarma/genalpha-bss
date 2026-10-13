/* The incident learning loop was STARVED, not broken (#285).
 *
 * The agent diagnosed 280 incidents. 234 verdicts were never given, 0 runbooks
 * were ever approved, and the cause was not a bug in the loop: NO UI ANYWHERE
 * CALLED `POST /ai/v1/incident/{id}/verdict`. The endpoint, the promotion
 * threshold and the human gate all worked, and nothing could reach them. A
 * queue nobody knows exists is not a review backlog anyone chose.
 *
 * So this suite guards the CALLER, which is the part that was missing: the
 * verdict control on the ticket the agent diagnosed, where the human is
 * already reading the diagnosis note.
 *
 * WHY IT VOTES "NOT USEFUL". A `useful` verdict is evidence, and three of them
 * on one signature draft a runbook (`maybePromote`, PROMOTION_THRESHOLD). A
 * suite that voted `useful` would manufacture exactly the evidence the gate
 * exists to require — and a nightly run would do it three times and promote a
 * runbook nobody judged. `maybePromote` is called only when useful is true, so
 * voting `not useful` exercises the whole path (button → POST → store → UI)
 * and cannot invent a promotion. The note says the suite did it, so no one
 * reading the row later mistakes it for a human's judgement.
 */
const { chromium } = require('playwright');

const CSR = 'http://localhost:8080/csr/';
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';

async function agentToken() {
  const res = await fetch(KC, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password', client_id: 'bss-demo',
      username: 'agent-anna', password: 'agent',
    }),
  });
  if (!res.ok) throw new Error(`token for agent-anna: HTTP ${res.status}`);
  const body = await res.json();
  if (!body.access_token) throw new Error('token response carried no access_token');
  return body.access_token;
}

async function traces(token) {
  const res = await fetch(`${API}/ai/v1/incident`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new Error(`GET /ai/v1/incident: HTTP ${res.status}`);
  return res.json();
}

(async () => {
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };
  const token = await agentToken();

  // The loop needs something to review. If nothing is pending there is nothing
  // this suite can prove, and saying so is the honest outcome — not a pass.
  const before = await traces(token);
  const pending = before.filter((t) => t.verdict === 'pending' && t.ticketId);
  console.log(`OK ${before.length} diagnoses on file, ${pending.length} pending with a ticket`);
  if (!pending.length) {
    fail('no pending diagnosis is attached to a ticket, so the verdict control '
       + 'has nothing to render — this suite cannot prove the loop is reachable');
  }

  const browser = await chromium.launch();
  const page = await (await browser.newContext()).newPage();

  await page.goto(CSR);
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', 'agent-anna');
  await page.fill('input[name="password"]', 'agent');
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForSelector('.searchbar', { timeout: 20000 });
  await page.click('.nav >> text=Tickets');
  await page.waitForSelector('[data-testid="queue-row"]', { timeout: 20000 });

  // Walk the queue for a ticket the agent diagnosed. The desk sorts by its own
  // rules, so which row carries a diagnosis is not ours to assume.
  const rows = await page.locator('[data-testid="queue-row"]').count();
  let judged = null;
  for (let i = 0; i < Math.min(rows, 25); i++) {
    await page.locator('[data-testid="queue-row"]').nth(i).click();
    await page.waitForTimeout(900);
    const control = page.locator('[data-testid="diagnosis-verdict"]');
    if (!(await control.count())) continue;
    if (!(await page.locator('[data-testid="diagnosis-not-useful"]').count())) continue; // already judged
    console.log(`OK the verdict control is on the ticket the agent diagnosed (queue row ${i + 1} of ${rows})`);
    const asked = (await control.first().innerText()).replace(/\s+/g, ' ');
    if (!/diagnosis/i.test(asked)) {
      fail(`the control renders but does not ask about the diagnosis: "${asked}"`);
    }
    await page.locator('[data-testid="diagnosis-not-useful"]').click();
    await page.waitForTimeout(1500);
    judged = (await control.first().innerText()).replace(/\s+/g, ' ');
    break;
  }
  if (!judged) {
    fail(`walked ${Math.min(rows, 25)} tickets and none offered an unjudged diagnosis — `
       + `the API says ${pending.length} are pending with a ticket id, so the desk is not `
       + 'showing the human what the agent already wrote');
  }

  if (await page.locator('[data-testid="diagnosis-not-useful"]').count()) {
    fail('the verdict was clicked and the control still offers the buttons — '
       + 'the desk gives no sign the judgement landed, so a reviewer would click twice');
  }
  console.log(`OK the control acknowledged the judgement: "${judged}"`);

  // The DOM flipping proves nothing on its own; a verdict that never reached
  // the store is the whole defect this suite exists to catch.
  const after = await traces(token);
  const nowPending = after.filter((t) => t.verdict === 'pending' && t.ticketId).length;
  if (nowPending !== pending.length - 1) {
    fail(`the click did not reach the store: ${pending.length} diagnoses were pending `
       + `with a ticket before and ${nowPending} after (expected ${pending.length - 1})`);
  }
  const was = new Set(before.filter((t) => t.verdict === 'not-useful').map((t) => t.id));
  const flipped = after.filter((t) => t.verdict === 'not-useful' && !was.has(t.id));
  if (flipped.length !== 1) {
    fail(`expected exactly one diagnosis to become not-useful, ${flipped.length} did`);
  }
  console.log('OK the judgement is in the store, not just on the screen — '
            + `pending ${pending.length} → ${nowPending}`);

  // THE COST OF THIS GATE, stated rather than hidden. The suite spends one real
  // pending diagnosis per run, and `not-useful` is a judgement no human made —
  // it discards evidence instead of inventing it, which is the safe direction,
  // but it is not free. There is no endpoint to return a trace to `pending`, so
  // the row is named here and can be re-judged by hand.
  console.log(`NOTE this run spent trace ${flipped[0].id} (${flipped[0].signature}); `
            + 'no human judged it — re-judge it if that signature matters');

  await browser.close();
  console.log('PASS csr_diagnosis_verdict_test — the learning loop has a caller: a human '
            + 'reading the agent\'s diagnosis can judge it without leaving the ticket');
})().catch((e) => { console.error('FAIL: ' + (e && e.stack || e)); process.exit(1); });
