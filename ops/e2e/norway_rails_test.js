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

  /* ======================= PART B: bill distribution ========================
   * The consent chain (e-invoice rail -> digital mailbox -> print), the
   * direct-debit loop (mandate file -> cycle claim -> OCR settlement), and
   * the protected-address guarantee on the letter payload.
   * Prereqs: mock-efaktura :8147, mock-digipost :8148, mock-avtalegiro :8149.
   * (EID STEP-UP is a deployment exercise, not code: the broker is an OIDC
   * IdP federated into keycloak with acr mapped to the verified-identity
   * claim product-ordering already accepts — see docs/norway-rails-plan.md.)
   * ------------------------------------------------------------------------ */
  const ctxB = await request.newContext();
  const fail2 = (m) => { throw new Error(m); };
  const HB = { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' };
  const BILLS = `${API}/tmf-api/customerBillManagement/v4`;
  const EFAK = 'http://localhost:8147';
  const MAILBOX = 'http://localhost:8148';
  const AVTALE = 'http://localhost:8149';
  const DIST = 'http://localhost:8124';
  const kidOf = (billNo) => billNo.replace(/\D/g, '');

  try {
    const offerings2 = await (await ctxB.get(
      `${CATALOG}/productOffering?lifecycleStatus=Active&limit=100`, { headers: HB })).json();
    const plan = offerings2.find((o) => o.name === 'GenAlpha Mobile 10 GB')
      || offerings2.find((o) => o.isBundle !== true && (o.productOfferingPrice || []).length > 0);
    if (!plan) fail2('no billable offering on the shelf');

    // a customer with a login, a party record, consent rows and one order
    const mkCustomer = async (givenName, familyName, consents, extraMedium) => {
      const email = `${givenName.toLowerCase()}-${run}@example.com`;
      const login = await (await ctxB.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
        { headers: HB, data: { email, givenName, familyName } })).json();
      await ctxB.post(`${PARTY}/individual`, { headers: HB, data: {
        id: login.id, givenName, familyName,
        contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }]
          .concat(extraMedium || []) } });
      for (const channel of consents) {
        const consent = await ctxB.post(`${BILLS}/partyBillingChannel`,
          { headers: HB, data: { partyId: login.id, channel } });
        if (consent.status() !== 200) fail2(`consent ${channel}: ` + consent.status());
      }
      const order = await ctxB.post(`${ORDERS}/productOrder`, { headers: HB, data: {
        productOrderItem: [{ id: '1', action: 'add',
          productOffering: { id: plan.id, name: plan.name } }],
        relatedParty: [{ id: login.id, role: 'customer' }] } });
      if (order.status() !== 201) fail2(`${givenName} order: ` + order.status());
      return login.id;
    };
    const billOf = async (partyId) => {
      for (let i = 0; i < 30; i++) {
        await ctxB.post(`${BILLS}/billingRun`, { headers: HB });
        const list = await (await ctxB.get(
          `${BILLS}/customerBill?relatedPartyId=${partyId}&limit=10`, { headers: HB })).json();
        if (list.length) return list[0];
        await sleep(2000);
      }
      fail2('no bill was cut for ' + partyId);
    };
    const channelOn = async (billId, want) => {
      for (let i = 0; i < 15; i++) {
        const bill = await (await ctxB.get(
          `${BILLS}/customerBill/${billId}`, { headers: HB })).json();
        if (bill.distributionChannel === want) return bill;
        await sleep(1000);
      }
      fail2(`bill ${billId} never recorded channel '${want}'`);
    };

    // ---- 6. CONSENT CHAIN: efaktura(paula) -> mailbox(wilma) -> print ------
    // paula is a seeded e-invoice user: alias lookup HITS -> the RFP rail
    const paulaId = await mkCustomer('Paula', `Payer${run}`, ['efaktura', 'mailbox']);
    const paulaBill = await billOf(paulaId);
    await channelOn(paulaBill.id, 'efaktura');
    let rfp = null;
    for (let i = 0; i < 15 && !rfp; i++) {
      await sleep(1500);
      const rfps = await (await ctxB.get(`${EFAK}/rfp?kid=${kidOf(paulaBill.billNo)}`)).json();
      rfp = rfps[0] || null;
    }
    if (!rfp) fail2('the request-for-payment never reached the e-invoice rail');
    if (!rfp.aliasRef || !rfp.aliasRef.startsWith('alias-')) fail2('RFP carries no alias: ' + JSON.stringify(rfp));
    if (rfp.kid !== kidOf(paulaBill.billNo)) fail2('RFP KID mismatch');
    console.log('OK CHAIN/EFAKTURA: paula\'s bill rode the e-invoice rail — alias looked up'
      + ` per send, KID ${rfp.kid} on the RFP, channel recorded on the bill`);

    // wilma consented to BOTH but holds no e-invoice alias: the per-send
    // lookup MISSES and the same bill falls to the mailbox — the design point
    const wilmaId = await mkCustomer('Wilma', `Payer${run}`, ['efaktura', 'mailbox']);
    const wilmaBill = await billOf(wilmaId);
    await channelOn(wilmaBill.id, 'mailbox');
    let letter = null;
    for (let i = 0; i < 15 && !letter; i++) {
      await sleep(1500);
      const letters = await (await ctxB.get(`${MAILBOX}/letters?partyRef=${wilmaId}`)).json();
      letter = letters.find((l) => (l.subject || '').includes(wilmaBill.billNo)) || null;
    }
    if (!letter) fail2('the letter never reached the mailbox');
    if (!letter.invoiceMeta || letter.invoiceMeta.kid !== kidOf(wilmaBill.billNo)) {
      fail2('the mailbox letter carries no pay-from-mailbox metadata');
    }
    console.log('OK CHAIN/MAILBOX: wilma\'s alias lookup missed at send time and the SAME bill'
      + ' fell to the digital mailbox, invoice metadata attached');

    // no alias, no mailbox consent: the floor is the existing print partner
    const printyId = await mkCustomer('Printy', `Person${run}`, ['efaktura']);
    const printyBill = await billOf(printyId);
    await channelOn(printyBill.id, 'print');
    let printJob = null;
    for (let i = 0; i < 15 && !printJob; i++) {
      await sleep(1500);
      const jobs = await (await ctxB.get(`${DIST}/invoices?billNo=${printyBill.billNo}`)).json();
      printJob = jobs.find((j) => j.channel === 'print') || null;
    }
    if (!printJob) fail2('the fallback print job never reached the distribution partner');
    console.log('OK CHAIN/PRINT: no consent anywhere -> the bill fell to the EXISTING print'
      + ' partner path — the chain\'s floor, unchanged');

    // every send recorded the channel on the delivery ledger too
    const ledger = await (await ctxB.get(`${BILLS}/billDistribution`, { headers: HB })).json();
    for (const [no, want] of [[paulaBill.billNo, 'efaktura'],
      [wilmaBill.billNo, 'mailbox'], [printyBill.billNo, 'print']]) {
      const row = ledger.find((r) => r.billNo === no);
      if (!row || row.channel !== want || row.status !== 'sent') {
        fail2(`ledger row for ${no} should be sent via ${want}: ` + JSON.stringify(row));
      }
    }
    console.log('OK CHANNEL RECORD: all three sends carry their channel on the delivery ledger'
      + ' (BillDistributedEvent rides bss.billing.events off the same rows)');

    // ---- 7. DIRECT DEBIT: mandate file -> cycle claim -> OCR settles -------
    const reg = await ctxB.post(`${AVTALE}/mandates`,
      { data: { partyRef: printyId, accountRef: '12345678903' } });
    if (reg.status() !== 201) fail2('bank-side mandate signup: ' + reg.status());
    const mandateFile = await (await ctxB.get(`${AVTALE}/mandateFile`)).json();
    const ingest = await ctxB.post(`${BILLS}/directDebit/mandateFile`,
      { headers: HB, data: mandateFile });
    if (ingest.status() !== 200) fail2('mandate file ingest: ' + ingest.status());
    const mandates = await (await ctxB.get(
      `${BILLS}/directDebit/mandate?partyId=${printyId}`, { headers: HB })).json();
    if (!mandates.length || mandates[0].status !== 'active') {
      fail2('the mandate is not active on the party profile: ' + JSON.stringify(mandates));
    }
    console.log('OK MANDATE: the bank\'s batch file registered the mandate (MandateRegisteredEvent)');

    const claimRun = await (await ctxB.post(`${BILLS}/directDebit/claimRun`, { headers: HB })).json();
    if (!claimRun.claims || claimRun.claims < 1) fail2('the cycle run claimed nothing: ' + JSON.stringify(claimRun));
    const railClaims = await (await ctxB.get(`${AVTALE}/claims?kid=${kidOf(printyBill.billNo)}`)).json();
    if (!railClaims.length) fail2('the claim never reached the direct-debit rail');
    console.log(`OK CLAIM: the open bill became a claim on the rail (KID ${kidOf(printyBill.billNo)})`);

    const settlementRes = await ctxB.get(`${AVTALE}/settlementFile`);
    if (settlementRes.status() !== 200) fail2('no settlement file was produced: ' + settlementRes.status());
    const settlement = await settlementRes.text();
    const applied = await ctxB.post(`${BILLS}/directDebit/settlementFile`,
      { headers: { ...HB, 'Content-Type': 'text/plain' }, data: settlement });
    if (applied.status() !== 200) fail2('settlement ingest: ' + applied.status());
    const settledBill = await (await ctxB.get(
      `${BILLS}/customerBill/${printyBill.id}`, { headers: HB })).json();
    if (settledBill.state !== 'settled') fail2('the OCR settlement did not close the bill: ' + settledBill.state);
    const ourClaims = await (await ctxB.get(`${BILLS}/directDebit/claim`, { headers: HB })).json();
    const ourClaim = ourClaims.find((c) => c.billNo === printyBill.billNo);
    if (!ourClaim || ourClaim.status !== 'settled') fail2('the claim never flipped to settled');
    console.log('OK SETTLEMENT: the OCR file came home through the remittance door and SETTLED'
      + ' the bill (SettlementReceivedEvent) — a new settlement source next to the PSP flow');

    // ---- 8. PROTECTED BILLING: the letter carries no street, ever ----------
    const shielded = await (await ctxB.post(`${PARTY}/individual`, { headers: HB, data: {
      givenName: 'Skjermet', familyName: `Faktura${run}`,
      contactMedium: [
        { mediumType: 'postalAddress', characteristic:
          { street1: 'Hemmeligveien 13', postCode: '0567', city: 'Oslo', country: 'NO' } },
      ] } })).json();
    const shieldLink = await ctxB.post(`${PARTY}/individual/${shielded.id}/registryLink`,
      { headers: HB, data: { personRef: PROTECTED_REF } });
    if (shieldLink.status() !== 200) fail2('protected registryLink: ' + shieldLink.status());
    await ctxB.post(`${BILLS}/partyBillingChannel`,
      { headers: HB, data: { partyId: shielded.id, channel: 'mailbox' } });
    const shieldOrder = await ctxB.post(`${ORDERS}/productOrder`, { headers: HB, data: {
      productOrderItem: [{ id: '1', action: 'add',
        productOffering: { id: plan.id, name: plan.name } }],
      relatedParty: [{ id: shielded.id, role: 'customer' }] } });
    if (shieldOrder.status() !== 201) fail2('protected order: ' + shieldOrder.status());
    const shieldBill = await billOf(shielded.id);
    await channelOn(shieldBill.id, 'mailbox');
    let shieldLetter = null;
    for (let i = 0; i < 15 && !shieldLetter; i++) {
      await sleep(1500);
      const letters = await (await ctxB.get(`${MAILBOX}/letters?partyRef=${shielded.id}`)).json();
      shieldLetter = letters.find((l) => (l.subject || '').includes(shieldBill.billNo)) || null;
    }
    if (!shieldLetter) fail2('the protected party\'s letter never arrived');
    const letterText = JSON.stringify(shieldLetter);
    if (letterText.includes('Hemmeligveien')) fail2('STREET LEAKED into the protected letter payload');
    if (/street1|street2|streetName/i.test(letterText)) fail2('a street field leaked into the letter');
    if (!shieldLetter.content.includes('0567')) fail2('postCode should survive for routing');
    console.log('OK PROTECTED BILLING: the protected party\'s letter carries NO street on the'
      + ' wire — postCode/city survive, the masked party API is the only source');

    console.log('\nALL PART-B LEGS GREEN');
  } finally {
    await ctxB.dispose();
  }
})().catch((e) => { console.error('FAIL: ' + e.message); process.exit(1); });
