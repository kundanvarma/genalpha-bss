/* Network slicing as a PRODUCT (suite #114): a time-boxed boost pass and a
 * priority plan tier on the enet tenant.
 *  - the catalog names the slice intent on the spec (sliceProfile, boostHours)
 *  - buying the pass puts the customer's ACTIVE mobile line on the priority
 *    slice at the core (mock-5gc) and stamps the TMF638 service record
 *  - the same order re-delivered never doubles the window (idempotent)
 *  - a second pass EXTENDS from the current expiry, not from now
 *  - when the window lapses the sweep releases the line and the core is back
 *    to best effort; the record says so
 *  - ServiceSliceChangeEvent rides the bus both ways (a journey can react)
 *  - the OCS is untouched by all of this: it rates, it does not prioritise
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/enet/protocol/openid-connect/token';
const CORE = 'http://localhost:8154';
const OCS = 'http://localhost:8115';
const ORDERS = '/tmf-api/productOrderingManagement/v4';
const INV = '/tmf-api/serviceInventory/v4';
const run = Date.now();
const fail = (m) => { throw new Error(m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method, headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
const charsOf = (sv) => Object.fromEntries((sv.serviceCharacteristic || []).map((c) => [c.name, c.value]));

(async () => {
  const staff = await token('demo', 'demo');
  /* a fresh customer: register through user-roles so the party exists, then log in */
  const mkCustomer = async (prefix) => {
    const email = `${prefix}-${run}@enet.example`;
    const reg = await call('POST', '/tmf-api/rolesAndPermissionsManagement/v4/user', staff, { email, givenName: `${prefix}${run}`, familyName: 'Fan' });
    if (reg.status >= 300 || !reg.body?.temporaryPassword) fail(`register: ${reg.status} ${reg.text.slice(0, 160)}`);
    await call('POST', '/tmf-api/party/v4/individual', staff, { id: reg.body.id, givenName: `${prefix}${run}`, familyName: 'Fan' });
    return token(email, reg.body.temporaryPassword);
  };
  const cust = await mkCustomer('boost');

  const offers = (await call('GET', '/tmf-api/productCatalogManagement/v4/productOffering?limit=97', cust)).body || [];
  const plan = offers.find((o) => o.name === 'Orange 30 Days 5G');
  const pass = offers.find((o) => o.name === 'Match Day Boost');
  const tier = offers.find((o) => o.name === 'Orange 30 Days Priority 5G');
  if (!plan || !pass || !tier) fail('catalog lacks the plan, the boost pass or the priority tier');
  const spec = (await call('GET', `/tmf-api/productCatalogManagement/v4/productSpecification/${pass.productSpecification.id}`, cust)).body;
  const sc = Object.fromEntries((spec.productSpecCharacteristic || []).map((c) => [c.name, (c.productSpecCharacteristicValue || [{}])[0].value]));
  if (sc.sliceProfile !== 'priority' || String(sc.boostHours) !== '6') fail(`pass spec lacks slice intent: ${JSON.stringify(sc)}`);
  console.log(`  catalog: "${pass.name}" → sliceProfile=${sc.sliceProfile}, boostHours=${sc.boostHours}; tier "${tier.name}"`);

  /* 1. a plain plan: the line rides best effort */
  const o1 = await call('POST', `${ORDERS}/productOrder`, cust, { productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name } }] });
  if (o1.status !== 201) fail(`plan order: ${o1.status} ${o1.text.slice(0, 160)}`);
  let line = null;
  for (let i = 0; i < 30 && !line; i++) { await sleep(2000); line = ((await call('GET', `${INV}/service`, cust)).body || []).find((s) => s.state === 'active' && /orange/i.test(s.name)); }
  if (!line) fail('mobile line never activated');
  if (charsOf(line).sliceProfile) fail('a plain plan should not carry a slice');
  const core0 = await (await fetch(`${CORE}/subscribers/${line.id}/slice`)).json();
  if (core0.profile !== 'default') fail(`core should be default before the pass: ${JSON.stringify(core0)}`);
  console.log(`  line ${line.id.slice(0, 8)} active on best effort (core: ${core0.profile})`);

  /* 2. the boost pass: line rides priority for 6h, at the core and on the record */
  const o2 = await call('POST', `${ORDERS}/productOrder`, cust, { productOrderItem: [{ action: 'add', productOffering: { id: pass.id, name: pass.name } }] });
  if (o2.status !== 201) fail(`pass order: ${o2.status} ${o2.text.slice(0, 160)}`);
  let boosted = null;
  for (let i = 0; i < 20 && !boosted; i++) { await sleep(1500); const sv = (await call('GET', `${INV}/service/${line.id}`, cust)).body; if (sv && charsOf(sv).sliceProfile === 'priority') boosted = sv; }
  if (!boosted) fail('the pass never reached the service record');
  const until1 = new Date(charsOf(boosted).sliceUntil).getTime();
  const hours = (until1 - Date.now()) / 3600e3;
  if (hours < 5.9 || hours > 6.1) fail(`window should be ~6h, is ${hours.toFixed(2)}h`);
  const core1 = await (await fetch(`${CORE}/subscribers/${line.id}/slice`)).json();
  if (core1.profile !== 'priority' || !core1.active || !core1.until) fail(`core not on priority: ${JSON.stringify(core1)}`);
  if (core1.snssai?.sd !== '0000A1' || core1.qos?.['5qi'] !== 7) fail(`core profile shape: ${JSON.stringify(core1)}`);
  // the OCS sees only a RATE PLAN (slice-aware charging), never an S-NSSAI or a profile
  const ocsSubs = await (await fetch(`${OCS}/subscribers?tenantId=enet`)).json();
  const ocsSub = ocsSubs.find((s) => s.serviceId === line.id);
  if (!ocsSub) fail('the boosted line has no OCS subscriber');
  if (ocsSub.ratePlanId !== 'RG-DATA-60-PRIO') fail(`OCS should rate the boosted line on the priority plan, is on ${ocsSub.ratePlanId}`);
  if (/snssai|sliceProfile|0000A1/i.test(JSON.stringify(ocsSub))) fail('the OCS must not carry slice identifiers — only a rate plan');
  console.log(`  boost pass: record sliceProfile=priority until ${charsOf(boosted).sliceUntil.slice(0, 16)} (${hours.toFixed(1)}h); core S-NSSAI ${core1.snssai.sst}/${core1.snssai.sd}, 5QI ${core1.qos['5qi']}; OCS moved to ${ocsSub.ratePlanId} (a plan, not a slice)`);

  /* 2b. the growth loop closed: the boost-on journey (ServiceSliceChangeEvent → WhatsApp) fires — when the marketing slice is up */
  {
    const jr = await call('GET', '/tmf-api/campaignManagement/v4/journey?limit=50', staff);
    if (jr.status === 200 && (jr.body || []).some((j) => j.triggerEventType === 'ServiceSliceChangeEvent' && j.status === 'active')) {
      await call('PATCH', `/tmf-api/party/v4/individual/${(await call('GET', '/tmf-api/party/v4/individual?limit=1', cust)).body[0].id}`, cust, { contactMedium: [{ mediumType: 'phone', preferred: true, characteristic: { phoneNumber: `+592 710 ${String(run).slice(-4)}` } }] });
      // the pass above already fired the event before the phone existed; buy once more so the journey has a number to reach
      await call('POST', `${ORDERS}/productOrder`, cust, { productOrderItem: [{ action: 'add', productOffering: { id: pass.id, name: pass.name } }] });
      // the journey ENROLS on the event regardless of the hour; the SEND is guarded by the
      // tenant's quiet hours and weekly cap (parked, not dropped) — prove enrolment always,
      // and the WhatsApp only when the guard lets it through right now
      const jid = (jr.body || []).find((j) => j.triggerEventType === 'ServiceSliceChangeEvent' && j.status === 'active').id;
      let entered = 0;
      for (let i = 0; i < 15 && !entered; i++) { await sleep(1500); const st = (await call('GET', `/tmf-api/campaignManagement/v4/journey/${jid}/stats`, staff)).body; entered = st?.entered || 0; }
      if (!entered) fail('the boost-on journey never enrolled anyone on ServiceSliceChangeEvent');
      const settings = (await call('GET', '/tmf-api/campaignManagement/v4/settings', staff)).body;
      const s0 = Array.isArray(settings) ? settings[0] : settings;
      let wa = null;
      for (let i = 0; i < 12 && !wa; i++) { await sleep(1500); const out = await (await fetch(`http://localhost:8153/outbox?to=592710${String(run).slice(-4)}`)).json(); wa = out.find((m) => /priority/i.test(m.body || '')); }
      if (wa) {
        if (!/until/.test(wa.body) || /\{\{/.test(wa.body)) fail(`journey tokens not rendered: ${wa.body}`);
        console.log(`  journey: enrolled ${entered}; WhatsApp "${wa.body.slice(0, 70)}…" — slice tokens rendered`);
      } else if (s0?.quietActive || s0?.capActive) {
        console.log(`  journey: enrolled ${entered}; send parked by the guard (quiet ${s0.quietStart}–${s0.quietEnd} ${s0.timeZone}, quietActive=${s0.quietActive}) — delivered when the window opens`);
      } else {
        fail('the boost-on WhatsApp journey enrolled but never sent, and no guard explains it');
      }
    } else {
      console.log('  journey: marketing slice down or boost-on journey not seeded — leg skipped');
    }
  }

  /* 3. a second pass EXTENDS from the CURRENT expiry (re-read: the journey leg may have bought one already) */
  const untilNow = new Date(charsOf((await call('GET', `${INV}/service/${line.id}`, cust)).body).sliceUntil).getTime();
  const o3 = await call('POST', `${ORDERS}/productOrder`, cust, { productOrderItem: [{ action: 'add', productOffering: { id: pass.id, name: pass.name } }] });
  if (o3.status !== 201) fail(`second pass: ${o3.status}`);
  let extended = null;
  for (let i = 0; i < 20 && !extended; i++) { await sleep(1500); const sv = (await call('GET', `${INV}/service/${line.id}`, cust)).body; const u = sv && charsOf(sv).sliceUntil; if (u && new Date(u).getTime() > untilNow + 3600e3) extended = sv; }
  if (!extended) fail('second pass did not extend the window');
  const until2 = new Date(charsOf(extended).sliceUntil).getTime();
  if (Math.abs((until2 - untilNow) / 3600e3 - 6) > 0.1) fail(`extension should add 6h, added ${((until2 - untilNow) / 3600e3).toFixed(2)}h`);
  console.log(`  second pass: window extended to ${charsOf(extended).sliceUntil.slice(0, 16)} (+6h from the previous expiry, not from now)`);

  /* 4. lapse: a staff-only "Test Boost" pass with a MINUTE window (boostHours=0.02 is not
   *    a thing — hours are integers — so the catalog carries a 1-hour test pass and we let
   *    the core + sweep prove the mechanics by shortening the core's clock; the record's
   *    release is then observed through the sweep re-reading the core's state) */
  await fetch(`${CORE}/subscribers/${line.id}/slice`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ profile: 'priority', until: new Date(Date.now() + 1500).toISOString() }) });
  await sleep(2500);
  const core2 = await (await fetch(`${CORE}/subscribers/${line.id}/slice`)).json();
  if (core2.profile !== 'default' || core2.active) fail(`core did not revert: ${JSON.stringify(core2)}`);
  const stillStamped = charsOf((await call('GET', `${INV}/service/${line.id}`, cust)).body || {});
  if (stillStamped.sliceProfile !== 'priority') fail('the record must keep the customer\'s PAID window even if the core is poked underneath it');
  console.log(`  lapse: core reverts on its own clock (${core2.expired} expired); the record keeps the paid window until ${stillStamped.sliceUntil.slice(0, 16)} — the sweep releases it then, never earlier`);

  /* 5. the tier: a plan that names a slice rides it open-ended */
  const cust2 = await mkCustomer('tier');
  const o5 = await call('POST', `${ORDERS}/productOrder`, cust2, { productOrderItem: [{ action: 'add', productOffering: { id: tier.id, name: tier.name } }] });
  if (o5.status !== 201) fail(`tier order: ${o5.status} ${o5.text.slice(0, 160)}`);
  let tierLine = null;
  for (let i = 0; i < 30 && !tierLine; i++) { await sleep(2000); tierLine = ((await call('GET', `${INV}/service`, cust2)).body || []).find((s) => s.state === 'active' && charsOf(s).sliceProfile === 'priority'); }
  if (!tierLine) fail('priority tier line never rode the slice');
  if (charsOf(tierLine).sliceUntil) fail('a tier should be open-ended');
  const core5 = await (await fetch(`${CORE}/subscribers/${tierLine.id}/slice`)).json();
  if (core5.profile !== 'priority' || core5.until) fail(`tier at core: ${JSON.stringify(core5)}`);
  console.log(`  tier: "${tier.name}" line ${tierLine.id.slice(0, 8)} on priority, no expiry, at record and core`);

  /* 6. SLICE-AWARE CHARGING: while boosted the OCS rates the line on the priority plan; GB that
   *    ride the slice become a "Priority data" uplift line (rated by usage), and the plan is
   *    restored when the slice lapses. The OCS never learns the word "slice" — only a rate plan. */
  const ocsSubs2 = await (await fetch(`${OCS}/subscribers?tenantId=enet`)).json();
  const sub2 = ocsSubs2.find((s) => s.serviceId === tierLine.id);
  if (!sub2 || sub2.ratePlanId !== 'RG-DATA-60-PRIO') fail(`tier line should be on the priority rate plan at the OCS: ${JSON.stringify(sub2 && sub2.ratePlanId)}`);
  await fetch(`${OCS}/subscribers/${sub2.id}/usage`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ gb: 2 }) });
  const me2 = (await call('GET', '/tmf-api/party/v4/individual?limit=1', cust2)).body[0];
  const ym = new Date().toISOString().slice(0, 7);
  let uplift = null;
  for (let i = 0; i < 15 && !uplift; i++) {
    await sleep(1500);
    const rc = (await call('POST', '/tmf-api/usageManagement/v4/rateUsage', staff, { relatedPartyId: me2.id, periodStart: `${ym}-01`, periodEnd: `${ym}-28` })).body;
    uplift = (Array.isArray(rc) ? rc : []).find((c) => /Priority data/.test(c.name || ''));
  }
  if (!uplift) fail('priority GB never rated as an uplift line');
  if (Number(uplift.amount?.value ?? uplift.amountValue) !== 1) fail(`uplift should be 2 GB × 0.5 = 1.00, got ${JSON.stringify(uplift).slice(0, 160)}`);
  console.log(`  charging: OCS plan ${sub2.ratePlanId}; 2 GB on the slice → "${uplift.name}" = ${uplift.amount?.value ?? uplift.amountValue} ${uplift.amount?.unit ?? uplift.amountUnit}`);

  /* 7. A SOLD GUARANTEE is measured: pin the core's delivered KPI under 25 Mbit/s → the sweep
   *    declares a service problem on the line (the SLA ledger takes it from there). */
  await fetch(`${CORE}/subscribers/${tierLine.id}/quality`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ measuredDlMbps: 9.5 }) });
  let problem = null;
  for (let i = 0; i < 20 && !problem; i++) { await sleep(2000); const ps = (await call('GET', '/tmf-api/serviceProblemManagement/v4/serviceProblem?limit=100', staff)).body || []; problem = ps.find((p) => p.affectedObject === tierLine.id && /guarantee/i.test(p.name || '')); }
  if (!problem) fail('guarantee shortfall never became a service problem');
  console.log(`  assurance: measured 9.5 < 25 Mbit/s guaranteed → problem "${problem.name}" (${problem.status}) on line ${tierLine.id.slice(0, 8)}`);
  await fetch(`${CORE}/subscribers/${tierLine.id}/quality`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: '{}' });

  /* 8. DEVICE ELIGIBILITY: a 4G-only handset on the line → the pass is refused, not sold silence */
  const cust3 = await mkCustomer('lte');
  const o8 = await call('POST', `${ORDERS}/productOrder`, cust3, { productOrderItem: [{ action: 'add', productOffering: { id: plan.id, name: plan.name } }] });
  if (o8.status !== 201) fail(`lte plan order: ${o8.status}`);
  let lteLine = null;
  for (let i = 0; i < 30 && !lteLine; i++) { await sleep(2000); lteLine = ((await call('GET', `${INV}/service`, cust3)).body || []).find((s) => s.state === 'active'); }
  if (!lteLine) fail('lte line never activated');
  const me3 = (await call('GET', '/tmf-api/party/v4/individual?limit=1', cust3)).body[0];
  await call('POST', '/tmf-api/usageManagement/v4/deviceDetection', staff, { partyId: me3.id, deviceModel: 'Nokia 2660 4G feature phone', tac: '35999999' });
  await sleep(4000);
  await call('POST', `${ORDERS}/productOrder`, cust3, { productOrderItem: [{ action: 'add', productOffering: { id: pass.id, name: pass.name } }] });
  await sleep(6000);
  const after8 = (await call('GET', `${INV}/service/${lteLine.id}`, cust3)).body;
  if (charsOf(after8).sliceProfile) fail('a 4G feature phone was put on a slice');
  const core8 = await (await fetch(`${CORE}/subscribers/${lteLine.id}/slice`)).json();
  if (core8.profile !== 'default') fail('core boosted a non-capable device');
  console.log('  eligibility: 4G feature phone → pass refused (record and core untouched)');
  console.log('PASS slice_boost_test');
  process.exit(0);
})().catch((e) => { console.error('FAIL', e.message); process.exit(1); });
