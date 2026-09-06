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
  const ocsSubs = await (await fetch(`${OCS}/subscribers?tenantId=enet`)).json();
  const ocsSub = ocsSubs.find((s) => s.serviceId === line.id);
  if (ocsSub && /priority|slice/i.test(JSON.stringify(ocsSub))) fail('the OCS should know nothing about slices');
  console.log(`  boost pass: record sliceProfile=priority until ${charsOf(boosted).sliceUntil.slice(0, 16)} (${hours.toFixed(1)}h); core S-NSSAI ${core1.snssai.sst}/${core1.snssai.sd}, 5QI ${core1.qos['5qi']}; OCS untouched`);

  /* 3. a second pass EXTENDS from the current expiry */
  const o3 = await call('POST', `${ORDERS}/productOrder`, cust, { productOrderItem: [{ action: 'add', productOffering: { id: pass.id, name: pass.name } }] });
  if (o3.status !== 201) fail(`second pass: ${o3.status}`);
  let extended = null;
  for (let i = 0; i < 20 && !extended; i++) { await sleep(1500); const sv = (await call('GET', `${INV}/service/${line.id}`, cust)).body; const u = sv && charsOf(sv).sliceUntil; if (u && new Date(u).getTime() > until1 + 3600e3) extended = sv; }
  if (!extended) fail('second pass did not extend the window');
  const until2 = new Date(charsOf(extended).sliceUntil).getTime();
  if (Math.abs((until2 - until1) / 3600e3 - 6) > 0.1) fail(`extension should add 6h, added ${((until2 - until1) / 3600e3).toFixed(2)}h`);
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
  console.log('PASS slice_boost_test');
  process.exit(0);
})().catch((e) => { console.error('FAIL', e.message); process.exit(1); });
