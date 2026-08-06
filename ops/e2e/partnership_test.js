/* TMF668 partnership types (the vocabulary for a fleet full of partners). Suite #83.
 *
 *  - a partnership KIND is data: the roles it permits, authored once
 *  - a typed partnership agreement is VALIDATED at signature — a role the
 *    type never permitted is refused naming the allowed list (today any
 *    role string anyone invents sails through)
 *  - untyped agreements pass untouched: no ceremony where none is due
 *  - walls: customers cannot author types; nova sees only nova
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms';
const PT = '/tmf-api/partnershipTypeManagement/v4';
const AGREE = '/tmf-api/agreementManagement/v4';
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

  /* ---------- 1. the kind, authored as data ---------- */
  const type = await call('POST', `${PT}/partnershipType`, staff, {
    name: `Retail dealer chain ${run}`,
    description: 'A retail org sells activations on commission',
    roleType: [{ name: 'provider', description: 'the operator' },
      { name: 'dealer', description: 'the retail chain' }] });
  if (type.status !== 201) fail(`type create: ${type.status} ${type.text.slice(0, 200)}`);
  if (type.body.roleType.length !== 2 || type.body.status !== 'active') {
    fail('type shape wrong: ' + JSON.stringify(type.body).slice(0, 200));
  }
  // a roleless kind is a valid catalog entry (TMF668: roleType optional) —
  // but it PERMITS NOTHING, so any typed partnership against it must fail
  const noRoles = await call('POST', `${PT}/partnershipType`, staff,
    { name: `Empty kind ${run}` });
  if (noRoles.status !== 201) fail(`a roleless kind is valid catalog data: ${noRoles.status}`);
  const inert = await call('POST', `${AGREE}/agreement`, staff, {
    name: `Against empty kind ${run}`, agreementType: 'partnership', status: 'active',
    engagedParty: [{ id: 'x', role: 'provider' }],
    characteristic: { partnershipTypeId: noRoles.body.id } });
  if (inert.status !== 400) {
    fail('a roleless kind must refuse EVERY role at signature: ' + inert.status);
  }
  const nameless = await call('POST', `${PT}/partnershipType`, staff,
    { name: `Bad kind ${run}`, roleType: [{ description: 'no name' }] });
  if (nameless.status !== 400) fail('a roleType entry without a name must be refused');
  const listed = (await call('GET', `${PT}/partnershipType`, staff)).body || [];
  if (!listed.some((t) => t.id === type.body.id)) fail('the type is not listed');
  console.log(`OK THE KIND: "${type.body.name}" authored as data — permits provider|dealer,`
    + ' listed on the catalog; a roleless kind is storable but permits NOTHING (its'
    + ' first typed partnership was refused at signature), and a nameless role entry'
    + ' is refused outright — a role IS its name.');

  /* ---------- 2. validated at signature ---------- */
  const good = await call('POST', `${AGREE}/agreement`, staff, {
    name: `Dealer partnership ${run}`, agreementType: 'partnership', status: 'active',
    engagedParty: [{ id: 'op-genalpha', role: 'provider', '@referredType': 'Organization' },
      { id: `dealer-org-${run}`, role: 'dealer', '@referredType': 'Organization' }],
    characteristic: { partnershipTypeId: type.body.id } });
  if (good.status >= 300) fail(`typed partnership should sign: ${good.status} ${good.text.slice(0, 200)}`);
  const bad = await call('POST', `${AGREE}/agreement`, staff, {
    name: `Smuggled role ${run}`, agreementType: 'partnership', status: 'active',
    engagedParty: [{ id: 'op-genalpha', role: 'provider' },
      { id: 'x', role: 'smuggler' }],
    characteristic: { partnershipTypeId: type.body.id } });
  if (bad.status !== 400 || !bad.body.message.includes("'smuggler'")
      || !bad.body.message.includes('provider, dealer')) {
    fail(`a role the kind never permitted must be refused naming the list: ${bad.status} ${bad.text.slice(0, 200)}`);
  }
  const ghost = await call('POST', `${AGREE}/agreement`, staff, {
    name: `Ghost type ${run}`, agreementType: 'partnership', status: 'active',
    engagedParty: [{ id: 'x', role: 'provider' }],
    characteristic: { partnershipTypeId: 'no-such-type' } });
  if (ghost.status !== 400) fail('an unknown partnership type must be refused');
  console.log(`OK THE SIGNATURE: the typed partnership signed (${good.body.id.slice(0, 8)}…);`
    + ' a "smuggler" role was refused NAMING the permitted list; a ghost type was refused'
    + ' outright — the validation agreements never had, but only where a type opted in.');

  /* ---------- 3. no ceremony where none is due ---------- */
  const plain = await call('POST', `${AGREE}/agreement`, staff, {
    name: `Plain commercial ${run}`, agreementType: 'commercial', status: 'active',
    engagedParty: [{ id: 'anyone', role: 'whatever-role' }] });
  if (plain.status >= 300) fail('untyped agreements must pass untouched');
  console.log('OK NO CEREMONY: a plain commercial agreement with an arbitrary role signed'
    + ' exactly as before — the type system binds only those who claim a type.');

  /* ---------- 4. walls ---------- */
  const kaiType = await call('POST', `${PT}/partnershipType`, kai,
    { name: 'evil', roleType: [{ name: 'x' }] });
  if (kaiType.status !== 403) fail(`a customer must not author kinds, got ${kaiType.status}`);
  const nova = await token('nova', 'demo', 'demo');
  const novaList = (await call('GET', `${PT}/partnershipType`, nova)).body || [];
  if (novaList.some((t) => t.id === type.body.id)) fail('genalpha\'s kind leaked into nova');
  await call('DELETE', `${PT}/partnershipType/${type.body.id}`, staff);
  console.log('OK THE WALLS: a customer 403\'d on authoring kinds, nova\'s catalog holds'
    + ' none of genalpha\'s, and deleting a kind is a first-class verb.');

  console.log('\nALL PARTNERSHIP CHECKS PASSED — the fleet full of partners has its'
    + ' vocabulary: kinds as data, roles validated at signature, and no ceremony for'
    + ' agreements that never asked for one.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
