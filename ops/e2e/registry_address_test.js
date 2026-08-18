/* Registry-verified delivery (freg arc, F-P1..F-P4) — the consolidated acceptance.
 *
 *  - GREEN: kai verifies his own Oslo address -> registryMatch=match, and the
 *    PartyAddressVerifiedEvent re-homes him in the CDP (region=Oslo +
 *    addressVerified=true, proven through a live audience resolution).
 *  - FRAUD: the shipped-disabled policy rule, enabled, refuses a hand-typed
 *    home delivery with the registered-address-or-pickup message; the same
 *    order addressSource=registry passes. Rule left disabled, as shipped.
 *  - HONESTY: a protected/unknown person answers no_data (indistinguishable);
 *    a country with no registry bound answers unavailable (postal wash only).
 *  - NO ORACLE: anonymous validation with a party context gets NO registryMatch.
 *  - NO PLANTING: a customer naming another party cannot attribute a
 *    verification event to them (back-office address:write only).
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const GEO = `${API}/tmf-api/geographicAddressManagement/v4`;
const POLICY = `${API}/tmf-api/policyManagement/v4`;
const ORDERS = `${API}/tmf-api/productOrderingManagement/v4`;
const AUD = `${API}/insight/v1/audience`;
const OSLO = { street1: 'Storgata 1', postCode: '0150', city: 'Oslo', country: 'NO' };

async function token(ctx, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}
const sub = (tok) => JSON.parse(Buffer.from(tok.split('.')[1], 'base64url').toString()).sub;

(async () => {
  const ctx = await request.newContext();
  // throw, never process.exit — the fraud-gate leg re-disables the rule in a
  // finally, and an exit would skip it and leave the gate ON for the fleet
  const fail = (m) => { throw new Error(m); };
  // probe audiences registered here are deleted even when a leg fails —
  // an orphaned __registry_* audience pollutes every Saved-audiences screen
  const probes = [];
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'demo', 'demo');
  const kai = await token(ctx, 'kai@bss.local', 'kai');
  const kaiId = sub(kai);
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };
  const KH = { Authorization: 'Bearer ' + kai, 'Content-Type': 'application/json' };

  const validate = async (headers, address, party) => (await (await ctx.post(
    `${GEO}/geographicAddressValidation`,
    { headers, data: { submittedGeographicAddress: address, ...(party ? { relatedParty: party } : {}) } })).json());

  try {
    // 0. the NO->freg binding is in place (idempotent, the standing demo config)
    const bind = await ctx.put(`${GEO}/registry`, { headers: H, data: {
      country: 'NO', provider: 'freg', displayName: 'Folkeregisteret',
      baseUrl: 'http://mock-freg:8080', secretRef: 'FREG_API_KEY' } });
    if (bind.status() !== 200) fail('registry bind: ' + bind.status());
    console.log('OK NO -> freg bound (config, secret-ref only)');

    // 1. GREEN: kai at his registered address
    let m = (await validate(KH, OSLO, { name: 'Kai Kunde' })).registryMatch;
    if (!m || m.outcome !== 'match') fail('kai should match: ' + JSON.stringify(m));
    if (m.registeredAddress?.postCode !== '0150') fail('match should return the registered address');
    console.log('OK GREEN: kai matches the register at ' + m.registeredAddress.street1);

    // 2. the match re-homed him in the CDP — a live audience resolves him
    const audience = await (await ctx.post(AUD, { headers: H, data: {
      name: `__registry_probe_${run}`, population: 'customer',
      criteria: { all: [
        { type: 'trait', key: 'addressVerified', op: 'eq', value: 'true' },
        { type: 'trait', key: 'region', op: 'eq', value: 'Oslo' } ] } } })).json();
    probes.push(audience.id);
    let member = false;
    for (let i = 0; i < 25; i++) {
      const members = await (await ctx.get(`${AUD}/${audience.id}/members`, { headers: H })).json();
      if ((Array.isArray(members) ? members : []).some((x) => x.partyId === kaiId)) { member = true; break; }
      await sleep(1000);
    }
    if (!member) fail('PartyAddressVerifiedEvent never landed as CDP traits (region+addressVerified)');
    console.log('OK CDP: the event filled region=Oslo + addressVerified=true — kai resolves in the audience');

    // 3. HONESTY: protected/unknown -> no_data; unbound country -> unavailable
    m = (await validate(KH, OSLO, { name: 'Sigrid Skjermet' })).registryMatch;
    if (!m || m.outcome !== 'no_data') fail('protected resident should be no_data: ' + JSON.stringify(m));
    m = (await validate(KH, { street1: 'Storgatan 1', postCode: '11122', city: 'Stockholm', country: 'SE' },
      { name: 'Kai Kunde' })).registryMatch;
    if (!m || m.outcome !== 'unavailable') fail('SE should be unavailable: ' + JSON.stringify(m));
    console.log('OK HONESTY: protected -> no_data (indistinguishable); no registry bound -> unavailable');

    // 4. NO ORACLE: anonymous callers get postal wash only
    const anon = await validate({ 'Content-Type': 'application/json' }, OSLO, { name: 'Kai Kunde' });
    if (anon.registryMatch) fail('anonymous caller must not get a registryMatch');
    if (anon.validationResult !== 'success') fail('anonymous postal wash should still validate');
    console.log('OK NO ORACLE: anonymous validation has no registry half');

    // 5. NO PLANTING: kai naming another party does not attribute the event to them
    const probeId = `party-probe-${run}`;
    await validate(KH, OSLO, { name: 'Kai Kunde', id: probeId });
    await sleep(4000);
    const planted = await (await ctx.post(AUD, { headers: H, data: {
      name: `__registry_plant_${run}`, population: 'customer',
      criteria: { all: [{ type: 'trait', key: 'addressVerified', op: 'eq', value: 'true' }] } } })).json();
    probes.push(planted.id);
    const plantedMembers = await (await ctx.get(`${AUD}/${planted.id}/members`, { headers: H })).json();
    if ((Array.isArray(plantedMembers) ? plantedMembers : []).some((x) => x.partyId === probeId)) {
      fail('a customer planted a verification on a party they do not hold');
    }
    console.log('OK NO PLANTING: a customer cannot attribute a verification to another party');

    // 6. FRAUD GATE: the shipped-disabled rule, enabled, refuses manual home delivery
    const rules = await (await ctx.get(`${POLICY}/policyRule?limit=100`, { headers: H })).json();
    const rule = rules.find((r) => r.id === 'example-unverified-address');
    if (!rule) fail('example-unverified-address rule missing');
    if (rule.enabled) fail('the example rule must ship disabled');
    const offs = await (await ctx.get(`${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`,
      { headers: H })).json();
    const phone = offs.find((o) => /iPhone 17$/.test(o.name || ''));
    const order = (source) => ctx.post(`${ORDERS}/productOrder`, { headers: KH, data: {
      relatedParty: [{ id: kaiId, role: 'customer' }],
      productOrderItem: [{ action: 'add', quantity: 1,
        productOffering: { id: phone.id, name: phone.name },
        product: { place: [{ ...OSLO, role: 'shipping', '@type': 'GeographicAddress',
          deliveryMethod: 'home', addressSource: source }] } }] } });
    await ctx.patch(`${POLICY}/policyRule/${rule.id}`, { headers: H, data: { enabled: true } });
    try {
      const denied = await order('manual');
      if (denied.status() !== 422) fail('manual home delivery should be refused, got ' + denied.status());
      const msg = (await denied.json()).message || '';
      if (!msg.includes('registered address')) fail('denial lacks the operator message: ' + msg);
      console.log('OK FRAUD GATE: hand-typed home delivery refused — "' + msg.slice(0, 60) + '…"');
      const passed = await order('registry');
      if (passed.status() !== 201) fail('registry-verified order should pass, got ' + passed.status());
      console.log('OK FRAUD GATE: the registry-verified order passes the same enabled rule');
    } finally {
      await ctx.patch(`${POLICY}/policyRule/${rule.id}`, { headers: H, data: { enabled: false } });
    }
  } finally {
    // runs on ANY leg failure — a stranded __registry_* audience pollutes
    // every Saved-audiences screen (it got into a demo video once)
    for (const id of probes) {
      await ctx.delete(`${AUD}/${id}`, { headers: H }).catch(() => {});
    }
  }
  console.log('OK the rule is disabled again — enforcement stays reversible configuration');

  console.log('\nALL REGISTRY-ADDRESS CHECKS PASSED — the seam verifies against the national register, '
    + 'the match re-homes the customer in the CDP over the bus, the risk/policy gate turns the posture '
    + 'on and off as data, and the register never answers anyone it should not.');
})().catch((e) => { console.error('FAIL: ' + e.message); process.exit(1); });
