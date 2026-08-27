/* Norway rails — part A acceptance (registry sync, protected addresses,
 * directory obligation, credit-decision port). Plan: docs/norway-rails-plan.md.
 *
 *  - FEED: a registry addressChange hendelse moves kai's party address
 *    end-to-end through mock-freg's event feed + the party-account re-sync
 *    (link -> inject -> poll -> party updated; PartyAddressVerifiedEvent is
 *    REUSED, no new event type for updates). Restored afterwards.
 *  - CURSOR: a drained feed re-polls to processed=0 — idempotent by cursor.
 *  - PROTECTED: a party linked to the protected resident loses street data
 *    everywhere (staff read included; postal code + city survive for
 *    pickup-point routing) and still CHECKS OUT — no street address appears
 *    anywhere in the order either.
 *  - DIRECTORY: the delta export ships the listed party and NEVER the
 *    secret-number or protected-address parties — the compliance point.
 *  - CREDIT: the frozen test identity gets the DISTINCT machine code
 *    CREDIT_FROZEN at order time (the storefront keys the prepaid path on
 *    it), and the stored record is a decision, never a report.
 *
 * Prereqs: composed stack up (gateway :8080, keycloak :8085, mock-freg :8141),
 * kai persona seeded (registry arc). Run: node ops/e2e/norway_rails_test.js
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const FREG = 'http://localhost:8141';
const PARTY = `${API}/tmf-api/party/v4`;
const ORDERS = `${API}/tmf-api/productOrderingManagement/v4`;
const CATALOG = `${API}/tmf-api/productCatalogManagement/v4`;
const KAI_REF = '01018012345'; // kai's personRef in mock-freg
const PROTECTED_REF = '04046912349'; // the protected (kode 6/7) resident

async function token(ctx, user, pass) {
  const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}
const sub = (tok) => JSON.parse(Buffer.from(tok.split('.')[1], 'base64url').toString()).sub;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { throw new Error(m); };
  const staff = await token(ctx, 'demo', 'demo');
  const kai = await token(ctx, 'kai@bss.local', 'kai');
  const kaiId = sub(kai);
  const H = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };
  const run = Date.now();

  const hendelse = async (type, personRef, payload) => {
    const res = await ctx.post(`${FREG}/hendelser`, { data: { type, personRef, payload } });
    if (res.status() !== 201) fail(`hendelse inject: ${res.status()}`);
  };
  const syncUntil = async (predicate, what) => {
    for (let i = 0; i < 15; i++) {
      await ctx.post(`${PARTY}/registrySync/run`, { headers: H });
      if (await predicate()) return;
      await sleep(1000);
    }
    fail(`registry sync never converged: ${what}`);
  };
  const partyOf = async (id) =>
    (await (await ctx.get(`${PARTY}/individual/${id}`, { headers: H })).json());
  const streetOf = (p) => {
    for (const m of p.contactMedium || []) {
      const c = m.characteristic || {};
      if (c.street1) return c.street1;
    }
    return null;
  };

  try {
    // ---- 1. FEED: an addressChange hendelse moves kai end-to-end -------------
    let link = await ctx.post(`${PARTY}/individual/${kaiId}/registryLink`,
      { headers: H, data: { personRef: KAI_REF } });
    if (link.status() !== 200) fail('kai registryLink: ' + link.status());
    await hendelse('addressChange', KAI_REF,
      { address: { street1: 'Nygata 9', postCode: '0155', city: 'Oslo' } });
    await syncUntil(async () => streetOf(await partyOf(kaiId)) === 'Nygata 9',
      'kai should live at Nygata 9');
    console.log('OK FEED: the registry hendelse moved kai to Nygata 9 (link -> feed -> re-fetch -> party)');

    // ---- 2. CURSOR: drained feed re-polls to processed=0 ---------------------
    await ctx.post(`${PARTY}/registrySync/run`, { headers: H }); // drain fully
    const again = await (await ctx.post(`${PARTY}/registrySync/run`, { headers: H })).json();
    if (again.processed !== 0) fail('drained feed should process 0, got ' + again.processed);
    console.log('OK CURSOR: re-poll of a drained feed processes nothing (lastSeq ' + again.lastSeq + ')');

    // ---- 3. PROTECTED: street data gone everywhere, checkout still works -----
    const prot = await (await ctx.post(`${PARTY}/individual`, { headers: H, data: {
      givenName: 'Skjermet', familyName: `Testperson${run}`,
      contactMedium: [
        { mediumType: 'postalAddress', characteristic:
          { street1: 'Hemmeligveien 13', postCode: '0567', city: 'Oslo', country: 'NO' } },
        { mediumType: 'mobile', characteristic: { phoneNumber: '+4790000099' } },
      ] } })).json();
    link = await ctx.post(`${PARTY}/individual/${prot.id}/registryLink`,
      { headers: H, data: { personRef: PROTECTED_REF } });
    if (link.status() !== 200) fail('protected registryLink: ' + link.status());
    const protRead = await ctx.get(`${PARTY}/individual/${prot.id}`, { headers: H });
    const protBody = await protRead.text();
    const protParty = JSON.parse(protBody);
    if (protParty.addressProtected !== true) fail('addressProtected flag missing');
    if (protBody.includes('Hemmeligveien')) fail('street leaked in the STAFF read');
    const postal = (protParty.contactMedium || []).find((m) => m.mediumType === 'postalAddress');
    if (!postal || postal.characteristic.postCode !== '0567') {
      fail('postal code should SURVIVE protection (pickup-point routing)');
    }
    console.log('OK PROTECTED: street scrubbed+masked for staff too; postCode/city survive');

    // ...and the protected party still checks out — no street anywhere
    const offerings = await (await ctx.get(
      `${CATALOG}/productOffering?lifecycleStatus=Active&limit=20`, { headers: H })).json();
    const simple = (Array.isArray(offerings) ? offerings : [])
      .filter((o) => o.isBundle !== true);
    let order = null;
    for (const o of simple) {
      const res = await ctx.post(`${ORDERS}/productOrder`, { headers: H, data: {
        description: `norway-rails protected checkout ${run}`,
        productOrderItem: [{ id: '1', action: 'add',
          productOffering: { id: o.id, name: o.name } }],
        relatedParty: [{ id: prot.id, role: 'customer' }] } });
      if (res.status() === 201) { order = await res.json(); break; }
    }
    if (!order) fail('protected party could not check out with any simple offering');
    if (JSON.stringify(order).includes('Hemmeligveien')) fail('street leaked into the order');
    console.log('OK PROTECTED CHECKOUT: order ' + order.id + ' placed, no street address anywhere');

    // ---- 4. DIRECTORY: export excludes secret + protected --------------------
    const secret = await (await ctx.post(`${PARTY}/individual`, { headers: H, data: {
      givenName: 'Hemmelig', familyName: `Nummer${run}`, birthDate: '1980-01-01',
      contactMedium: [{ mediumType: 'mobile', characteristic: { phoneNumber: '+4790000098' } }],
    } })).json();
    const listed = await (await ctx.post(`${PARTY}/individual`, { headers: H, data: {
      givenName: 'Oppført', familyName: `Person${run}`, birthDate: '1979-01-01',
      contactMedium: [{ mediumType: 'mobile', characteristic: { phoneNumber: '+4790000097' } }],
    } })).json();
    for (const [id, data] of [
      [listed.id, { exposure: 'full' }],
      [secret.id, { exposure: 'full', secretNumber: true }],
      [prot.id, { exposure: 'full' }],
    ]) {
      const set = await ctx.post(`${PARTY}/individual/${id}/directorySetting`, { headers: H, data });
      if (set.status() !== 200) fail('directorySetting: ' + set.status());
    }
    const secretSetting = await (await ctx.get(
      `${PARTY}/individual/${secret.id}/directorySetting`, { headers: H })).json();
    if (secretSetting[0].exposure !== 'reserved') fail('secretNumber must FORCE reserved');
    const exportRun = await (await ctx.post(`${PARTY}/directoryExport/run`, { headers: H })).json();
    const exportText = JSON.stringify(exportRun.rows || []);
    const ids = (exportRun.rows || []).map((r) => r.partyId);
    if (!ids.includes(listed.id)) fail('listed party missing from the export');
    if (ids.includes(secret.id)) fail('SECRET NUMBER LEAKED into the directory export');
    if (ids.includes(prot.id)) fail('PROTECTED PARTY LEAKED into the directory export');
    if (exportText.includes('+4790000098')) fail('secret phone number leaked in an export row');
    console.log(`OK DIRECTORY: export run ${exportRun.id} ships the listed party only `
      + `(${exportRun.rowCount} rows; secret + protected suppressed)`);

    // ---- 5. CREDIT: the frozen identity gets the DISTINCT rejection ----------
    const frozen = await (await ctx.post(`${PARTY}/individual`, { headers: H, data: {
      givenName: 'Frossen', familyName: `Kreditt${run}` } })).json();
    const pin = await ctx.post(`${ORDERS}/creditDecision/mockOutcome`,
      { headers: H, data: { ref: frozen.id, decision: 'frozen' } });
    if (pin.status() !== 200) fail('mockOutcome: ' + pin.status());
    const offering = simple[0];
    const refused = await ctx.post(`${ORDERS}/productOrder`, { headers: H, data: {
      productOrderItem: [{ id: '1', action: 'add',
        productOffering: { id: offering.id, name: offering.name } }],
      relatedParty: [{ id: frozen.id, role: 'customer' }] } });
    if (refused.status() !== 422) fail('frozen order should 422, got ' + refused.status());
    const reason = await refused.json();
    if (reason.code !== 'CREDIT_FROZEN') fail('distinct code missing: ' + JSON.stringify(reason));
    const decisions = await (await ctx.get(
      `${ORDERS}/creditDecision?relatedPartyId=${frozen.id}`, { headers: H })).json();
    if (!decisions.length || decisions[0].decision !== 'frozen') fail('frozen decision not recorded');
    if (JSON.stringify(decisions[0]).includes('report')) fail('a report-shaped field is stored');
    console.log('OK CREDIT: frozen identity -> 422 CREDIT_FROZEN (prepaid-path key), decision recorded, no report');

    console.log('\nALL PART-A LEGS GREEN');
  } finally {
    // good citizenship: put kai back where the standing demo expects him
    try {
      await hendelse('addressChange', KAI_REF,
        { address: { street1: 'Storgata 1', postCode: '0150', city: 'Oslo' } });
      await syncUntil(async () => streetOf(await partyOf(kaiId)) === 'Storgata 1',
        'kai restored to Storgata 1');
      console.log('OK CLEANUP: kai restored to Storgata 1');
    } catch (e) {
      console.log('WARN cleanup: ' + e.message);
    }
    await ctx.dispose();
  }

  /* ------------------------- PART B (not built yet) -------------------------
   * Placeholders for the bill-distribution half of the arc (BillDistributor
   * in services/billing + eFaktura/AvtaleGiro/Digipost mocks + mock eID
   * broker). When part B lands, add these legs:
   *
   *  - BILL FALLBACK: flip a party's consent flags and prove one bill rides
   *    eFaktura -> digital mailbox -> print/PDF in that order, and that
   *    every send records the channel actually used (BillDistributedEvent
   *    {channel}); a collections notice rides the SAME seam.
   *  - AVTALEGIRO: mandate file in -> stored payment authorization
   *    (MandateRegisteredEvent); cycle claim out; OCR settlement in closes
   *    the bill (SettlementReceivedEvent) next to the PSP flow.
   *  - PROTECTED BILLING: the protected-address party from leg 3 receives
   *    their bill WITHOUT a street address on any channel payload.
   *  - EID STEP-UP: mock eID broker federated in keycloak satisfies the
   *    verified-identity gate (acr -> step-up claim) end-to-end, so a
   *    verified-identity offering checks out after broker login.
   * ------------------------------------------------------------------------ */
})().catch((e) => { console.error('FAIL: ' + e.message); process.exit(1); });
