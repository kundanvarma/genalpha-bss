/* #124 device_entitlement_test — a GSMA TS.43 entitlement server as an ODA component.
 *
 * Phones (Apple's and Google's entitlement clients) ask the operator's
 * Entitlement Configuration Server which services a subscription entitles
 * them to. Here the ECS is `device-entitlement`; the phone is the simulator in
 * integrations/mock-ts43-device, which runs the REAL protocol: EAP-AKA over
 * HTTP (the USIM's Milenage against an authentication vector from the AUC
 * seam — mock-hss), a token, then per-app configuration. What is proven:
 *
 *  - the PLAN decides: spec characteristics (volte, vowifi, smsoip, companionEsim,
 *    esimTransfer, dataPlanType) become TS.43 entitlement statuses
 *  - EAP-AKA: the SIM proves itself (AUTN verified by the USIM, AT_MAC both
 *    ways, RES against XRES); a forged answer is refused; an unbound IMSI is
 *    refused after authenticating
 *  - the token the ECS issued works without a new challenge
 *  - the VoWiFi service flow (emergency address + terms) flips AddrStatus/TC_Status
 *  - a plan without Wi-Fi calling answers DISABLED; a suspended line answers DISABLED
 *  - ODSA: a companion eSIM (watch) checks eligibility, subscribes and gets an
 *    SGP.22 activation code; a primary eSIM transfer to a new phone is granted
 *  - the BSS face explains it in plain words; another tenant sees nothing
 */
const API = 'http://localhost:8080';
const ECS_FOR_PHONE = 'http://gateway:8080/ts43'; // what the simulated phone dials (inside the fleet)
const ECS = `${API}/ts43`;
const HSS = 'http://localhost:8157';
const PHONE = 'http://localhost:8158';
const CATALOG = `${API}/tmf-api/productCatalogManagement/v4`;
const ENT = `${API}/tmf-api/deviceEntitlement/v1`;
const run = Date.now();

const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };

async function token(realm, client, user, pass) {
  const res = await fetch(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: client, username: user, password: pass }) });
  return (await res.json()).access_token;
}
async function call(method, url, tok, body, extra = {}) {
  const headers = { 'Content-Type': 'application/json', ...extra };
  if (tok) headers.Authorization = 'Bearer ' + tok;
  const res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null; try { json = JSON.parse(text); } catch { /* not json */ }
  return { status: res.status, body: json, text, headers: res.headers };
}
const phone = async (p) => (await call('POST', `${PHONE}/simulate`, null, { ecsUrl: ECS_FOR_PHONE, ...p })).body;

(async () => {
  const staff = await token('bss', 'bss-demo', 'demo', 'demo');
  if (!staff) fail('no staff token');
  for (const [name, url] of [['device-entitlement', `${API}/tmf-api/deviceEntitlement/v1/device`], ['mock-hss', `${HSS}/health`], ['mock-ts43-device', `${PHONE}/health`]]) {
    const r = await call('GET', url, name === 'device-entitlement' ? staff : null);
    if (r.status !== 200) fail(`${name} not up (${r.status})`);
  }

  /* 1. two plans: one with everything, one without Wi-Fi calling — the spec decides */
  const mkPlan = async (name, chars) => {
    const spec = (await call('POST', `${CATALOG}/productSpecification`, staff, { name, lifecycleStatus: 'Active',
      productSpecCharacteristic: Object.entries(chars).map(([k, v]) => ({ name: k, valueType: 'string', configurable: false,
        productSpecCharacteristicValue: [{ value: v }] })) })).body;
    const offering = (await call('POST', `${CATALOG}/productOffering`, staff, { name, lifecycleStatus: 'Active',
      category: [{ name: 'Mobile plans' }], productSpecification: { id: spec.id, name: spec.name, '@referredType': 'ProductSpecification' } })).body;
    if (!offering.id) fail('offering not created: ' + JSON.stringify(offering));
    return offering;
  };
  const full = await mkPlan(`Entitled 5G ${run}`, { volte: 'true', vonr: 'true', vowifi: 'true', smsoip: 'true',
    companionEsim: 'true', esimTransfer: 'true', dataPlanType: 'Unmetered' });
  const noWifi = await mkPlan(`No Wi-Fi calling ${run}`, { volte: 'true', vowifi: 'false', smsoip: 'true',
    companionEsim: 'false', esimTransfer: 'false', dataPlanType: 'Metered' });
  console.log('OK two plans: the spec characteristics carry what each plan entitles');

  /* 2. a subscriber: the SIM (IMSI) bound to a party, a line and the plan */
  const party = (await call('POST', `${API}/tmf-api/party/v4/individual`, staff, { givenName: 'Tuva', familyName: `Ent${run}` })).body.id;
  const imsi = '24205' + String(run).slice(-10);
  const msisdn = '4741' + String(run).slice(-6);
  await call('PUT', `${HSS}/subscribers/${imsi}`, null, { msisdn, iccid: `8947${imsi.slice(-11)}1234` });
  const bound = await call('PUT', `${ENT}/subscriber`, staff, { imsi, msisdn, partyId: party, serviceId: `svc-${run}`, offeringId: full.id, imsProvisioned: true });
  if (bound.status !== 200 || bound.body.entitlements.services['Wi-Fi calling (VoWiFi)'] !== 'on — emergency address still needed') {
    fail('binding wrong: ' + bound.text);
  }
  console.log(`OK IMSI ${imsi} bound to Tuva's line on "${full.name}" — the BSS face says: ${JSON.stringify(bound.body.entitlements.services)}`);

  /* 3. the phone runs EAP-AKA and reads its entitlements */
  const first = await phone({ imsi, terminalId: `35${imsi.slice(-13)}`, vendor: 'GenAlphaSim', model: 'SimPhone 1', apps: ['ap2003', 'ap2004', 'ap2005', 'ap2010'] });
  if (!first || !first.token) fail('EAP-AKA did not yield a token: ' + JSON.stringify(first).slice(0, 500));
  const stepNames = first.steps.map((s) => s.step);
  if (!stepNames.includes('USIM answered AKA challenge') || !stepNames.includes('POST EAP-Response/AKA-Challenge')) fail('relay steps missing: ' + JSON.stringify(first.steps));
  const e = first.entitlements;
  if (e.ap2004.EntitlementStatus !== '1' || e.ap2004.AddrStatus !== '0' || e.ap2004.TC_Status !== '0' || e.ap2004.ProvStatus !== '1') fail('VoWiFi block wrong: ' + JSON.stringify(e.ap2004));
  const voice = e.ap2003.VoiceOverCellularEntitleInfo.map((x) => x.RATVoiceEntitleInfoDetails);
  if (voice.length !== 2 || voice[0].EntitlementStatus !== '1' || voice[1].EntitlementStatus !== '1' || voice[1].AccessType !== '2') fail('VoLTE/VoNR block wrong: ' + JSON.stringify(e.ap2003));
  if (e.ap2005.EntitlementStatus !== '1') fail('SMSoIP wrong');
  if (e.ap2010.DataPlanInfo[0].DataPlanInfoDetails.DataPlanType !== 'Unmetered') fail('data plan wrong: ' + JSON.stringify(e.ap2010));
  if (!e.Vers || !e.Token || !e.ap2004.ServiceFlow_URL.endsWith('/ts43/flow/vowifi')) fail('envelope wrong: ' + JSON.stringify(e).slice(0, 300));
  console.log(`OK EAP-AKA over HTTP: the USIM verified the network, the ECS verified RES + AT_MAC, token issued; VoLTE=on VoNR=on VoWiFi=on (address needed) SMSoIP=on data=Unmetered`);

  /* 4. the token works without a new challenge */
  const again = await phone({ imsi, terminalId: `35${imsi.slice(-13)}`, apps: ['ap2004'], token: first.token });
  if (!again.steps.some((s) => s.step === 'GET with token' && s.status === 200) || again.steps.some((s) => s.step === 'GET with EAP_ID')) fail('token path not taken: ' + JSON.stringify(again.steps));
  console.log('OK the ECS token is honoured: no SIM challenge on the second request');

  /* 5. a forged EAP answer is refused; an authenticated but unbound IMSI is refused */
  const q = new URLSearchParams({ terminal_id: 'forger', terminal_vendor: 'x', terminal_model: 'x', terminal_sw_version: '1', entitlement_version: '12.0', vers: '1', app: 'ap2004',
    EAP_ID: `0${imsi}@nai.epc.mnc050.mcc242.3gppnetwork.org` });
  const chal = await fetch(`${ECS}?${q}`, { headers: { Accept: 'application/vnd.gsma.eap-relay.v1.0+json' } });
  const cookie = (chal.headers.get('set-cookie') || '').split(';')[0];
  if (chal.status !== 200 || !cookie.startsWith('ECS_SESSION=')) fail('challenge not issued: ' + chal.status);
  const forged = Buffer.from('0207001c1701000003030040' + '00'.repeat(8) + '0b050000' + '00'.repeat(16), 'hex').toString('base64');
  const bad = await fetch(`${ECS}?${q}`, { method: 'POST', headers: { 'Content-Type': 'application/vnd.gsma.eap-relay.v1.0+json', Cookie: cookie }, body: JSON.stringify({ 'eap-relay-packet': forged }) });
  if (bad.status !== 403) fail('a forged AKA answer was accepted: ' + bad.status);
  const strangerImsi = '24205' + String(run + 7).slice(-10);
  const stranger = (await call('POST', `${PHONE}/simulate`, null, { ecsUrl: ECS_FOR_PHONE, imsi: strangerImsi, apps: ['ap2004'] }));
  if (stranger.status !== 502 || !String(stranger.body.error).includes('403')) fail('an unbound IMSI got an answer: ' + JSON.stringify(stranger.body));
  console.log('OK a forged EAP-Response is refused (403); a SIM the AUC knows but the BSS has not bound is refused (403)');

  /* 6. the VoWiFi service flow completes: emergency address + terms */
  const flow = await fetch(`${ECS}/flow/vowifi`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ imsi, street: 'Storgata 1', postcode: '0155', terms: 'on' }) });
  if (flow.status !== 200) fail('service flow failed: ' + flow.status);
  const afterFlow = await phone({ imsi, terminalId: `35${imsi.slice(-13)}`, apps: ['ap2004'], token: first.token });
  if (afterFlow.entitlements.ap2004.AddrStatus !== '1' || afterFlow.entitlements.ap2004.TC_Status !== '1') fail('flow not reflected: ' + JSON.stringify(afterFlow.entitlements.ap2004));
  console.log('OK the VoWiFi service flow (ServiceFlow_URL) recorded the emergency address and terms: AddrStatus=1 TC_Status=1');

  /* 7. the plan decides: move the line to the plan without Wi-Fi calling */
  await call('PUT', `${ENT}/subscriber`, staff, { imsi, offeringId: noWifi.id });
  const noWifiAnswer = await phone({ imsi, terminalId: `35${imsi.slice(-13)}`, apps: ['ap2004', 'ap2010'], token: first.token });
  if (noWifiAnswer.entitlements.ap2004.EntitlementStatus !== '0' || noWifiAnswer.entitlements.ap2010.DataPlanInfo[0].DataPlanInfoDetails.DataPlanType !== 'Metered') fail('plan change not reflected: ' + JSON.stringify(noWifiAnswer.entitlements));
  await call('PUT', `${ENT}/subscriber`, staff, { imsi, offeringId: full.id });
  console.log('OK plan change: on the plan without Wi-Fi calling the ECS answers DISABLED and a metered data plan');

  /* 8. ODSA companion: a watch checks eligibility, subscribes, gets its eSIM activation code */
  const watch = { companionTerminalId: `35${String(run).slice(-13)}`, companionEid: '89049032' + String(run).slice(-24).padStart(24, '0'), companionVendor: 'GenAlphaSim', companionModel: 'SimWatch' };
  const elig = await phone({ imsi, terminalId: `35${imsi.slice(-13)}`, apps: ['ap2006'], operation: 'CheckEligibility', token: first.token, ...watch });
  if (elig.entitlements.ap2006.CompanionAppEligibility !== '1') fail('companion not eligible: ' + JSON.stringify(elig.entitlements.ap2006));
  const sub = await phone({ imsi, terminalId: `35${imsi.slice(-13)}`, apps: ['ap2006'], operation: 'ManageSubscription', operationType: 0, token: first.token, ...watch });
  const info = sub.entitlements.ap2006;
  if (info.SubscriptionResult !== '2' || !info.DownloadInfo || !info.DownloadInfo.ProfileActivationCode) fail('companion subscription wrong: ' + JSON.stringify(info));
  const code = Buffer.from(info.DownloadInfo.ProfileActivationCode, 'base64').toString();
  if (!code.startsWith('LPA:1$')) fail('activation code is not SGP.22: ' + code);
  const cfg = await phone({ imsi, terminalId: `35${imsi.slice(-13)}`, apps: ['ap2006'], operation: 'AcquireConfiguration', token: first.token, ...watch });
  const companions = cfg.entitlements.ap2006.CompanionConfigurations || [];
  if (!companions.some((c) => c.CompanionConfiguration.ICCID === info.DownloadInfo.ProfileIccid)) fail('companion configuration missing: ' + JSON.stringify(cfg.entitlements.ap2006));
  const listed = (await call('GET', `${ENT}/companionDevice`, staff)).body || [];
  if (!listed.some((c) => c.imsi === imsi && c.status === 'subscribed')) fail('companion not on the BSS face');
  console.log(`OK ODSA companion (ap2006): eligible → subscribed → eSIM profile ${info.DownloadInfo.ProfileIccid} with activation code ${code.slice(0, 20)}… → configuration lists it`);

  /* 9. ODSA primary: the subscription moves to a new eSIM phone */
  const newPhone = { targetTerminalId: `35${String(run + 1).slice(-13)}`, targetEid: '89049032' + String(run + 1).slice(-24).padStart(24, '0'), oldTerminalId: `35${imsi.slice(-13)}` };
  const transfer = await phone({ imsi, terminalId: newPhone.targetTerminalId, apps: ['ap2009'], operation: 'ManageSubscription', operationType: 3, token: first.token, ...newPhone });
  const t = transfer.entitlements.ap2009;
  if (t.SubscriptionResult !== '2' || !t.DownloadInfo || !t.DownloadInfo.ProfileIccid) fail('transfer not granted: ' + JSON.stringify(t));
  console.log(`OK ODSA primary (ap2009) transfer: a new eSIM profile ${t.DownloadInfo.ProfileIccid} for the new phone — the line-side SIM swap is announced on the bus`);

  /* 10. a suspended line loses its entitlements; the BSS face explains */
  await call('PUT', `${ENT}/subscriber`, staff, { imsi, status: 'suspended' });
  const susp = await phone({ imsi, terminalId: `35${imsi.slice(-13)}`, apps: ['ap2003', 'ap2004'], token: first.token });
  if (susp.entitlements.ap2004.EntitlementStatus !== '0') fail('suspended line still entitled');
  const explained = (await call('GET', `${ENT}/subscriber/${imsi}`, staff)).body;
  if (explained.entitlements.lineStatus !== 'suspended' || explained.entitlements.services['Voice over 4G (VoLTE)'] !== 'off') fail('explanation wrong: ' + JSON.stringify(explained.entitlements));
  if (!(explained.devices || []).some((d) => d.model === 'SimPhone 1')) fail('the phone that checked in is not on the BSS face');
  const log = (await call('GET', `${ENT}/ecsRequest?imsi=${imsi}`, staff)).body || [];
  if (!log.some((r) => r.outcome === 'served') || !log.some((r) => r.outcome === 'eap-challenge')) fail('request log incomplete');
  await call('PUT', `${ENT}/subscriber`, staff, { imsi, status: 'active' });
  console.log('OK suspended line → DISABLED; the BSS face lists the device, explains the services in words, and keeps the request log');

  /* 11. another tenant sees nothing */
  const other = await token('taranga', 'bss-demo', 'demo', 'demo');
  const foreign = await call('GET', `${ENT}/subscriber?imsi=${imsi}`, other);
  if (foreign.status !== 200 || (foreign.body || []).length !== 0) fail('tenant isolation broken: ' + foreign.text);
  console.log('OK tenant isolation: Taranga sees none of genalpha\'s subscribers');

  console.log('\nPASS device_entitlement_test — a TS.43 entitlement server as an ODA component: the plan decides, EAP-AKA proves the SIM, '
    + 'VoLTE/VoWiFi/SMSoIP/data-plan answer in TS.43 terms, the service flow completes, ODSA activates a companion eSIM and transfers a primary one, '
    + 'the BSS face explains it, tenants stay apart.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
