/* TMF645 service qualification (what the network can DELIVER here). Suite #79.
 *
 *  - TMF679 next door answers the commercial question; this face answers
 *    the technical one from the coverage map — the footprint as DATA
 *  - queryServiceQualification: every technology whose footprint covers
 *    the postcode, longest prefix winning, empty prefix = everywhere
 *  - checkServiceQualification NEVER answers a bare no: fiber where there
 *    is none proposes the best technology the address CAN have; a
 *    bandwidth ask above the plant's ceiling is refused with the ceiling
 *  - a check is a PERSISTED fact readable by its unguessable id; the list
 *    (customer addresses) and the coverage CRUD are back-office only
 *  - nova's hostname sees nova's (empty) footprint, never genalpha's
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const SQM = '/tmf-api/serviceQualificationManagement/v4';
const fail = (m) => { throw new Error(m); };

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo',
      username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, { body, tok, host } = {}) {
  const r = await fetch((host || API) + path, { method,
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(tok ? { Authorization: `Bearer ${tok}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
const chars = (svc) => Object.fromEntries(
  (svc.serviceCharacteristic || []).map((c) => [c.name, c.value]));

(async () => {
  /* ---------- 1. the footprint, answered per place ---------- */
  const stockholm = await call('POST', `${SQM}/queryServiceQualification`,
    { body: { searchCriteria: { place: { postCode: '11122', city: 'Stockholm' } } } });
  if (stockholm.status !== 200) fail(`query: ${stockholm.status} ${stockholm.text.slice(0, 200)}`);
  const sthlmTechs = Object.fromEntries(stockholm.body.serviceQualificationItem
    .map((i) => [chars(i.service).technology, chars(i.service)]));
  if (!sthlmTechs.fiber || sthlmTechs.fiber.maxDownstreamMbps !== 1000) {
    fail('Stockholm should have fiber at 1000: ' + JSON.stringify(sthlmTechs));
  }
  if (!sthlmTechs['5g-fwa']) fail('the everywhere-fallback is missing in Stockholm');
  const rural = await call('POST', `${SQM}/queryServiceQualification`,
    { body: { searchCriteria: { place: { postCode: '99999', city: 'Kiruna' } } } });
  const ruralTechs = rural.body.serviceQualificationItem.map((i) => chars(i.service).technology);
  if (ruralTechs.length !== 1 || ruralTechs[0] !== '5g-fwa') {
    fail('rural should be 5g-fwa ONLY: ' + ruralTechs.join(','));
  }
  console.log('OK THE FOOTPRINT: an anonymous prospect asks "what can I get here?" —'
    + ' Stockholm answers fiber 1000/1000 + the national 5G-FWA fallback; a Kiruna'
    + ' postcode answers 5G-FWA alone. The footprint is DATA (five seeded rows), the'
    + ' empty prefix is the everywhere-match, and the query is the technical shop window.');

  /* ---------- 2. never a bare no ---------- */
  const noFiber = await call('POST', `${SQM}/checkServiceQualification`,
    { body: { place: { postCode: '99999', city: 'Kiruna' },
      serviceQualificationItem: [{ service: { serviceSpecification: { name: 'broadband-fiber' } } }] } });
  if (noFiber.status !== 201) fail(`check: ${noFiber.status} ${noFiber.text.slice(0, 200)}`);
  const noItem = noFiber.body.serviceQualificationItem[0];
  if (noFiber.body.qualificationResult !== 'unqualified'
      || !noItem.eligibilityUnavailabilityReason[0].label.includes('not available at postcode 99999')) {
    fail('rural fiber must be unqualified with the reason');
  }
  const alt = (noItem.alternateServiceProposal || [])[0];
  if (!alt || chars(alt.alternateService).technology !== '5g-fwa') {
    fail('the refusal must propose the best available alternative');
  }
  const tooFast = await call('POST', `${SQM}/checkServiceQualification`,
    { body: { place: { postCode: '33311', city: 'Malmö' },
      serviceQualificationItem: [{ service: { serviceSpecification: { name: 'broadband-fiber' },
        serviceCharacteristic: [{ name: 'minDownstreamMbps', value: 500 }] } }] } });
  const tfItem = tooFast.body.serviceQualificationItem[0];
  if (tooFast.body.qualificationResult !== 'unqualified'
      || !tfItem.eligibilityUnavailabilityReason[0].label.includes('at most 300')) {
    fail('the Malmö plant ceiling should refuse 500: ' + JSON.stringify(tfItem).slice(0, 200));
  }
  const okFiber = await call('POST', `${SQM}/checkServiceQualification`,
    { body: { place: { postCode: '11122', city: 'Stockholm' },
      serviceQualificationItem: [{ service: { serviceSpecification: { name: 'broadband-fiber' },
        serviceCharacteristic: [{ name: 'minDownstreamMbps', value: 500 }] } }] } });
  const okItem = okFiber.body.serviceQualificationItem[0];
  if (okFiber.body.qualificationResult !== 'qualified'
      || chars(okItem.service).maxDownstreamMbps !== 1000) {
    fail('Stockholm fiber at 500 must qualify and answer the delivered 1000');
  }
  console.log('OK NEVER A BARE NO: fiber in Kiruna is refused WITH the 5G-FWA alternative'
    + ' proposed (the TMF645 signature); a 500 Mbps ask against Malmö\'s 300 Mbps plant is'
    + ' refused naming the ceiling; the same ask in Stockholm qualifies and answers the'
    + ' full 1000 the plant delivers.');

  /* ---------- 3. a qualification is a fact, kept ---------- */
  const reread = await call('GET', `${SQM}/checkServiceQualification/${noFiber.body.id}`);
  if (reread.status !== 200) fail(`reread: ${reread.status}`);
  if (reread.body.qualificationResult !== 'unqualified'
      || !(reread.body.place || {}).postCode
      || reread.body.serviceQualificationItem[0].alternateServiceProposal[0] == null) {
    fail('the persisted check lost its substance');
  }
  console.log(`OK THE FACT: check ${noFiber.body.id.slice(0, 8)}… read back by its id —`
    + ' same verdict, same place, same alternative. A qualification is a persisted fact,'
    + ' not a whisper.');

  /* ---------- 4. walls ---------- */
  const anonList = await call('GET', `${SQM}/checkServiceQualification`);
  if (anonList.status === 200) fail('the check LIST leaks customer addresses to anonymous');
  const anonCrud = await call('POST', `${SQM}/coverageMap`,
    { body: { technology: 'evil', maxDownMbps: 1 } });
  if (anonCrud.status === 201) fail('anonymous must not edit the footprint');
  const kai = await token('kai@bss.local', 'kai');
  const kaiCrud = await call('POST', `${SQM}/coverageMap`,
    { body: { technology: 'evil', maxDownMbps: 1 }, tok: kai });
  if (kaiCrud.status !== 403) fail(`a customer must not edit the footprint: ${kaiCrud.status}`);
  const staff = await token('demo', 'demo');
  const staffList = await call('GET', `${SQM}/checkServiceQualification`, { tok: staff });
  if (staffList.status !== 200 || !staffList.body.length) {
    fail('staff should read the qualification history');
  }
  const nova = await call('POST', `${SQM}/queryServiceQualification`,
    { body: { searchCriteria: { place: { postCode: '11122' } } },
      host: 'http://shop.nova.localhost:8080' });
  if (nova.status !== 200 || (nova.body.serviceQualificationItem || []).length !== 0) {
    fail('nova must see its own (empty) footprint, not genalpha\'s: '
      + JSON.stringify(nova.body).slice(0, 150));
  }
  console.log('OK WALLS: the check list (customer addresses) refused anonymous and served'
    + ' staff; the footprint CRUD refused anonymous AND a customer (403); nova\'s hostname'
    + ' answered from nova\'s own empty coverage — the footprint is tenant data like'
    + ' everything else.');

  console.log('\nALL SERVICE-QUALIFICATION CHECKS PASSED — TMF679 says what may be SOLD'
    + ' here, TMF645 now says what can be DELIVERED here: technology, bandwidth, and'
    + ' always the best alternative — from a coverage map the operator edits as data.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
