/* "My internet is slow" — triage before ticket.
 *
 *  - the diagnosis checks the three usual suspects in a support agent's
 *    order: is the line paused? is there a KNOWN outage? are they simply
 *    OUT OF DATA (the classic)?
 *  - out of data reads as THROTTLED with a top-up hint; a top-up restores
 *    the all-clear immediately
 *  - open incidents in the area surface as a caution even when the line's
 *    own path is clean
 *  - only a true all-clear earns "raise a ticket and we will dig"
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const OCS = 'http://localhost:8115';
const run = Date.now();
const PLAN_ID = '14fd6b3d-a144-4989-b05a-2c3f2778f1b0'; // GenAlpha Mobile 60 GB 5G (OCS-charged)

async function token(ctx, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  /* ---------- a customer with a live 10 GB line ---------- */
  const email = `slow-${run}@example.com`;
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Slow', familyName: `Net${run}` } })).json();
  const cust = await token(ctx, email, login.temporaryPassword);
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(cust), data: {
      productOrderItem: [{ action: 'add', productOffering: { id: PLAN_ID, name: 'GenAlpha Mobile 60 GB 5G' } }] } });
  let svc = null;
  for (let i = 0; i < 30 && !svc; i++) {
    await sleep(2000);
    const svcs = await (await ctx.get(`${API}/tmf-api/serviceInventory/v4/service`,
      { headers: H(cust) })).json();
    svc = (svcs || []).find((s) => s.state === 'active') || null;
  }
  if (!svc) fail('the line never activated');
  const diagnose = async (tok) => (await (await ctx.post(
    `${API}/tmf-api/serviceInventory/v4/service/${svc.id}/diagnose`,
    { headers: H(tok), data: {} })).json());

  /* ---------- act 1: all clear, honestly ---------- */
  const clear = await diagnose(cust);
  if (clear.verdict !== 'allClear'
      || !clear.findings.some((f) => f.code === 'allClear' || f.code === 'areaIncidents')) {
    fail('a healthy line should read all-clear-ish: ' + JSON.stringify(clear));
  }
  console.log('OK a healthy line reads ALL CLEAR — "if it still feels slow, raise a ticket"');

  /* ---------- act 2: the classic — they are out of data ---------- */
  const subs = await (await ctx.get(`${OCS}/subscribers?tenantId=genalpha`)).json();
  const sub = subs.find((s) => s.serviceId === svc.id);
  if (!sub) fail('no OCS subscriber for the line');
  await ctx.post(`${OCS}/subscribers/${sub.id}/usage`,
    { headers: { 'Content-Type': 'application/json' }, data: { gb: sub.buckets[0].totalGB + 1 } });
  const throttled = await diagnose(cust);
  const outOfData = throttled.findings.find((f) => f.code === 'outOfData');
  if (throttled.verdict !== 'throttled' || !outOfData
      || !outOfData.message.includes('top-up')) {
    fail('an exhausted bucket must read THROTTLED with the top-up hint: '
      + JSON.stringify(throttled));
  }
  console.log('OK OUT OF DATA detected: "speed is reduced — a top-up restores full speed" — the'
    + ' answer in one click instead of a ticket');

  /* the top-up cures it, immediately */
  await ctx.post(`${OCS}/subscribers/${sub.id}/credit`,
    { headers: { 'Content-Type': 'application/json' }, data: { gb: 5 } });
  const cured = await diagnose(cust);
  if (cured.findings.some((f) => f.code === 'outOfData')) {
    fail('the top-up did not clear the throttle finding: ' + JSON.stringify(cured));
  }
  console.log('OK the top-up CURED it — the same check now passes');

  /* ---------- act 3: there is a known outage in the area ---------- */
  const alarm = await (await ctx.post(`${API}/tmf-api/alarmManagement/v4/alarm`,
    { headers: H(staff), data: { alarmedObject: `olt-diag-${run}`,
      perceivedSeverity: 'critical', probableCause: 'fiber cut near the exchange' } })).json();
  await sleep(2000);
  const area = await diagnose(cust);
  if (!area.findings.some((f) => f.code === 'areaIncidents' || f.code === 'outage')) {
    fail('an open incident never surfaced in the diagnosis: ' + JSON.stringify(area));
  }
  console.log('OK the OPEN INCIDENT surfaces as a caution — the customer hears about the area'
    + ' problem instead of being told all-clear');
  // tidy: resolve the problem so other suites see a clean sky
  const problems = await (await ctx.get(
    `${API}/tmf-api/serviceProblemManagement/v4/serviceProblem?status=open`, { headers: H(staff) })).json();
  const mine = problems.find((p) => p.affectedObject === `olt-diag-${run}`);
  if (mine) {
    await ctx.patch(`${API}/tmf-api/serviceProblemManagement/v4/serviceProblem/${mine.id}`,
      { headers: H(staff), data: { status: 'resolved' } });
  }

  /* ---------- act 4: a paused line explains itself ---------- */
  await ctx.post(`${API}/tmf-api/serviceInventory/v4/service/${svc.id}/suspend`,
    { headers: H(cust), data: { reason: 'vacation', days: 5 } });
  const paused = await diagnose(cust);
  if (paused.verdict !== 'paused' || !paused.findings.some((f) => f.code === 'paused')) {
    fail('a paused line must say so: ' + JSON.stringify(paused));
  }
  await ctx.post(`${API}/tmf-api/serviceInventory/v4/service/${svc.id}/resume`,
    { headers: H(cust), data: {} });
  console.log('OK a PAUSED line says "nothing flows while it sleeps — resume it" instead of'
    + ' sending anyone hunting for faults');

  /* ---------- ownership: a stranger cannot probe the line ---------- */
  const stranger = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email: `nosy-${run}@example.com`, givenName: 'No', familyName: `Sy${run}` } })).json();
  const nosy = await token(ctx, `nosy-${run}@example.com`, stranger.temporaryPassword);
  const probe = await ctx.post(`${API}/tmf-api/serviceInventory/v4/service/${svc.id}/diagnose`,
    { headers: H(nosy), data: {} });
  if (probe.status() !== 404) fail("a stranger probed someone else's line: " + probe.status());
  console.log('OK owner-scoped: a stranger probing the line gets a 404, never a diagnosis');

  console.log('\nALL DIAGNOSTIC CHECKS PASSED — "slow internet" gets an ANSWER before it gets a'
    + ' ticket: paused, outage, or out-of-data with the cure one click away; only a real'
    + ' all-clear sends anyone digging.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
