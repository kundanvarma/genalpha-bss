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
 *    esimTransfer, dataPlanType, carrierBilling, satellite, privateIdentity)
 *    become TS.43 entitlement statuses; RCS shows on the BSS face
 *  - EAP-AKA: the SIM proves itself (AUTN verified by the USIM, AT_MAC both
 *    ways, RES against XRES); a forged answer is refused; an unbound IMSI is
 *    refused after authenticating; the relay session lives in the database
 *  - the token the ECS issued works without a new challenge; XML on request
 *  - the VoWiFi service flow (emergency address + terms) flips AddrStatus/TC_Status
 *  - a plan without Wi-Fi calling answers DISABLED; a suspended line answers DISABLED
 *  - ODSA: a companion eSIM (watch) checks eligibility, subscribes and gets an
 *    SGP.22 activation code; a primary eSIM transfer to a new phone is granted
 *    AND completed by the orchestrator (old SIM blocked, eSIM profile live,
 *    binding moved, old tokens dead)
 *  - the orchestrator binds a line on activation by itself: an ordered plan's
 *    SIM is known to the ECS without any manual step, the IMSI resolved
 *    through the AUC; a plan change follows
 *  - server-initiated refresh reaches the customer through communication
 *  - OIDC (TS.43 §2.8.2): a client without SIM access is sent to the operator's
 *    sign-in in a real browser and comes back entitled
 *  - the console page shows it in words; another tenant sees nothing
 */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const ECS_FOR_PHONE = 'http://gateway:8080/ts43'; // what the simulated phone dials (inside the fleet)
const ECS = `${API}/ts43`;
const HSS = 'http://localhost:8157';
const PHONE = 'http://localhost:8158';
const CATALOG = `${API}/tmf-api/productCatalogManagement/v4`;
const ENT = `${API}/tmf-api/deviceEntitlement/v1`;
const USER = `${API}/tmf-api/rolesAndPermissionsManagement/v4/user`;
const INBOX = `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=100`;
const run = Date.now();

const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(realm, client, user, pass) {
  const res = await fetch(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: client, username: user, password: pass }) });
  return (await res.json()).access_token;
}
async function call(method, url, tok, body, extra = {}) {
  const headers = { 'Content-Type': 'application/json', ...extra };
  if (tok) headers.Authorization = 'Bearer ' + tok;
  const res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), redirect: 'manual' });
  const text = await res.text();
  let json = null; try { json = JSON.parse(text); } catch { /* not json */ }
  return { status: res.status, body: json, text, headers: res.headers };
}
const phone = async (p) => (await call('POST', `${PHONE}/simulate`, null, { ecsUrl: ECS_FOR_PHONE, ...p })).body;
async function until(what, fn, tries = 30, ms = 2000) {
  for (let i = 0; i < tries; i++) {
    const v = await fn();
    if (v) return v;
    await sleep(ms);
  }
  fail('timed out waiting for ' + what);
}

(async () => {
  const staff = await token('bss', 'bss-demo', 'demo', 'demo');
  if (!staff) fail('no staff token');
  for (const [name, url] of [['device-entitlement', `${ENT}/device`], ['mock-hss', `${HSS}/health`], ['mock-ts43-device', `${PHONE}/health`]]) {
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
  const full = await mkPlan(`Entitled 5G ${run}`, { volte: 'true', vonr: 'true', vowifi: 'true', smsoip: 'true', rcs: 'true',
    companionEsim: 'true', esimTransfer: 'true', dataPlanType: 'Unmetered', carrierBilling: 'true', satellite: 'true', privateIdentity: 'true' });
  const noWifi = await mkPlan(`No Wi-Fi calling ${run}`, { volte: 'true', vowifi: 'false', smsoip: 'true',
    companionEsim: 'false', esimTransfer: 'false', dataPlanType: 'Metered' });
  console.log('OK two plans: the spec characteristics carry what each plan entitles');

  /* 2. a customer with a login; the SIM (IMSI) bound to a party, a line and the plan */
  const firstName = `Tuva${run}`;
  const email = `tuva-${run}@example.com`;
  const login = (await call('POST', USER, staff, { email, givenName: firstName, familyName: `Ent${run}` })).body;
  if (!login || !login.temporaryPassword) fail('user create failed: ' + JSON.stringify(login));
  const party = login.id;
  await call('POST', `${API}/tmf-api/party/v4/individual`, staff, { id: party, givenName: firstName, familyName: `Ent${run}` });
  const imsi = '24205' + String(run).slice(-10);
  const msisdn = '4741' + String(run).slice(-6);
  await call('PUT', `${HSS}/subscribers/${imsi}`, null, { msisdn, iccid: `8947${imsi.slice(-11)}1234` });
  const bound = await call('PUT', `${ENT}/subscriber`, staff, { imsi, msisdn, partyId: party, serviceId: `svc-${run}`, offeringId: full.id, imsProvisioned: true });
  if (bound.status !== 200 || bound.body.entitlements.services['Wi-Fi calling (VoWiFi)'] !== 'on — emergency address still needed') {
    fail('binding wrong: ' + bound.text);
  }
  if (!String(bound.body.entitlements.services['RCS messaging']).startsWith('on')) fail('RCS not on the BSS face: ' + JSON.stringify(bound.body.entitlements.services));
  console.log(`OK IMSI ${imsi} bound to Tuva's line on "${full.name}" — the BSS face says: ${JSON.stringify(bound.body.entitlements.services)}`);

  /* 3. the phone runs EAP-AKA and reads its entitlements — the newer apps included */
  const terminalId = `35${imsi.slice(-13)}`;
  const first = await phone({ imsi, terminalId, vendor: 'GenAlphaSim', model: 'SimPhone 1', apps: ['ap2003', 'ap2004', 'ap2005', 'ap2010', 'ap2012', 'ap2013', 'ap2014', 'ap2016'], notifToken: `fcm-${run}`, notifAction: 2 });
  if (!first || !first.token) fail('EAP-AKA did not yield a token: ' + JSON.stringify(first).slice(0, 500));
  const stepNames = first.steps.map((s) => s.step);
  if (!stepNames.includes('USIM answered AKA challenge') || !stepNames.includes('POST EAP-Response/AKA-Challenge')) fail('relay steps missing: ' + JSON.stringify(first.steps));
  const e = first.entitlements;
  if (e.ap2004.EntitlementStatus !== '1' || e.ap2004.AddrStatus !== '0' || e.ap2004.TC_Status !== '0' || e.ap2004.ProvStatus !== '1') fail('VoWiFi block wrong: ' + JSON.stringify(e.ap2004));
  const voice = e.ap2003.VoiceOverCellularEntitleInfo.map((x) => x.RATVoiceEntitleInfoDetails);
  if (voice.length !== 2 || voice[0].EntitlementStatus !== '1' || voice[1].EntitlementStatus !== '1' || voice[1].AccessType !== '2') fail('VoLTE/VoNR block wrong: ' + JSON.stringify(e.ap2003));
  if (e.ap2005.EntitlementStatus !== '1') fail('SMSoIP wrong');
  if (e.ap2010.DataPlanInfo[0].DataPlanInfoDetails.DataPlanType !== 'Unmetered') fail('data plan wrong: ' + JSON.stringify(e.ap2010));
  if (e.ap2012.EntitlementStatus !== '1' || e.ap2012.TC_Status !== '0') fail('carrier billing wrong: ' + JSON.stringify(e.ap2012));
  if (e.ap2013.EntitlementStatus !== '1' || !e.ap2013.PrivateUserID || e.ap2013.PrivateUserID.includes(imsi)) fail('private identity wrong: ' + JSON.stringify(e.ap2013));
  if (e.ap2014.MSISDN !== '+' + msisdn) fail('phone number wrong: ' + JSON.stringify(e.ap2014));
  if (e.ap2016.EntitlementStatus !== '1') fail('satellite wrong: ' + JSON.stringify(e.ap2016));
  if (!e.Vers || !e.Token || !e.ap2004.ServiceFlow_URL.endsWith('/ts43/flow/vowifi')) fail('envelope wrong: ' + JSON.stringify(e).slice(0, 300));
  console.log('OK EAP-AKA over HTTP: the USIM verified the network, the ECS verified RES + AT_MAC, token issued; VoLTE/VoNR/VoWiFi/SMSoIP on, data unmetered, carrier billing on, private Wi-Fi identity is a pseudonym, phone number returned, satellite on');

  /* 4. the token works without a new challenge; XML when the client asks for it */
  const again = await phone({ imsi, terminalId, apps: ['ap2004'], token: first.token });
  if (!again.steps.some((s) => s.step === 'GET with token' && s.status === 200) || again.steps.some((s) => s.step === 'GET with EAP_ID')) fail('token path not taken: ' + JSON.stringify(again.steps));
  const xmlQ = new URLSearchParams({ terminal_id: terminalId, terminal_vendor: 'x', terminal_model: 'x', terminal_sw_version: '1', entitlement_version: '12.0', vers: '1', app: 'ap2004', token: first.token });
  const xml = await fetch(`${ECS}?${xmlQ}`, { headers: { Accept: 'text/vnd.wap.connectivity-xml' } });
  const xmlText = await xml.text();
  if (xml.status !== 200 || !xml.headers.get('content-type').includes('text/vnd.wap.connectivity-xml')
    || !xmlText.includes('<wap-provisioningdoc version="1.1">') || !xmlText.includes('<parm name="AppID" value="ap2004"/>')
    || !xmlText.includes('<parm name="EntitlementStatus" value="1"/>')) fail('XML face wrong: ' + xml.status + ' ' + xmlText.slice(0, 300));
  console.log('OK the ECS token is honoured (no SIM challenge); the same answer renders as TS.43 XML on request');

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
  const afterFlow = await phone({ imsi, terminalId, apps: ['ap2004'], token: first.token });
  if (afterFlow.entitlements.ap2004.AddrStatus !== '1' || afterFlow.entitlements.ap2004.TC_Status !== '1') fail('flow not reflected: ' + JSON.stringify(afterFlow.entitlements.ap2004));
  console.log('OK the VoWiFi service flow (ServiceFlow_URL) recorded the emergency address and terms: AddrStatus=1 TC_Status=1');

  /* 7. the plan decides: move the line to the plan without Wi-Fi calling */
  await call('PUT', `${ENT}/subscriber`, staff, { imsi, offeringId: noWifi.id });
  const noWifiAnswer = await phone({ imsi, terminalId, apps: ['ap2004', 'ap2010'], token: first.token });
  if (noWifiAnswer.entitlements.ap2004.EntitlementStatus !== '0' || noWifiAnswer.entitlements.ap2010.DataPlanInfo[0].DataPlanInfoDetails.DataPlanType !== 'Metered') fail('plan change not reflected: ' + JSON.stringify(noWifiAnswer.entitlements));
  await call('PUT', `${ENT}/subscriber`, staff, { imsi, offeringId: full.id });
  console.log('OK plan change: on the plan without Wi-Fi calling the ECS answers DISABLED and a metered data plan');

  /* 8. ODSA companion: a watch checks eligibility, subscribes, gets its eSIM activation code */
  const watch = { companionTerminalId: `35${String(run).slice(-13)}`, companionEid: '89049032' + String(run).slice(-24).padStart(24, '0'), companionVendor: 'GenAlphaSim', companionModel: 'SimWatch' };
  const elig = await phone({ imsi, terminalId, apps: ['ap2006'], operation: 'CheckEligibility', token: first.token, ...watch });
  if (elig.entitlements.ap2006.CompanionAppEligibility !== '1') fail('companion not eligible: ' + JSON.stringify(elig.entitlements.ap2006));
  const sub = await phone({ imsi, terminalId, apps: ['ap2006'], operation: 'ManageSubscription', operationType: 0, token: first.token, installProfile: true, ...watch });
  const info = sub.entitlements.ap2006;
  if (info.SubscriptionResult !== '2' || !info.DownloadInfo || !info.DownloadInfo.ProfileActivationCode) fail('companion subscription wrong: ' + JSON.stringify(info));
  const code = Buffer.from(info.DownloadInfo.ProfileActivationCode, 'base64').toString();
  if (!code.startsWith('LPA:1$rsp.mock-smdp.example$') || info.DownloadInfo.ProfileSmdpAddress !== 'rsp.mock-smdp.example') fail('the profile did not come from the SM-DP+ (ES2+): ' + code + ' ' + JSON.stringify(info.DownloadInfo));
  const lpa = sub.steps.find((s) => s.step === 'LPA downloaded the profile from the SM-DP+');
  if (!lpa || lpa.status !== 200) fail('the LPA could not download the profile: ' + JSON.stringify(sub.steps));
  const cfg = await phone({ imsi, terminalId, apps: ['ap2006'], operation: 'AcquireConfiguration', token: first.token, ...watch });
  const companions = cfg.entitlements.ap2006.CompanionConfigurations || [];
  if (!companions.some((c) => c.CompanionConfiguration.ICCID === info.DownloadInfo.ProfileIccid)) fail('companion configuration missing: ' + JSON.stringify(cfg.entitlements.ap2006));
  const installed = await until('the SM-DP+ download notification to reach the ECS', async () => {
    const listed = (await call('GET', `${ENT}/companionDevice`, staff)).body || [];
    return listed.find((c) => c.imsi === imsi && c.profileState === 'installed' && c.status === 'active') || null;
  }, 10);
  if (!installed.matchingId || code.indexOf(installed.matchingId) < 0) fail('matching id not the SM-DP+\'s: ' + JSON.stringify(installed));
  console.log(`OK ODSA companion (ap2006) through the SM-DP+ (ES2+ downloadOrder + confirmOrder): profile ${info.DownloadInfo.ProfileIccid}, matching id ${installed.matchingId}, LPA downloaded it, the SM-DP+'s handleDownloadProgressInfo made the watch ACTIVE`);

  /* 9. a suspended line loses its entitlements; the BSS face explains; refresh reaches the customer */
  await call('PUT', `${ENT}/subscriber`, staff, { imsi, status: 'suspended' });
  const susp = await phone({ imsi, terminalId, apps: ['ap2003', 'ap2004'], token: first.token });
  if (susp.entitlements.ap2004.EntitlementStatus !== '0') fail('suspended line still entitled');
  const explained = (await call('GET', `${ENT}/subscriber/${imsi}`, staff)).body;
  if (explained.entitlements.lineStatus !== 'suspended' || explained.entitlements.services['Voice over 4G (VoLTE)'] !== 'off') fail('explanation wrong: ' + JSON.stringify(explained.entitlements));
  if (!(explained.devices || []).some((d) => d.model === 'SimPhone 1' && d.pushRegistered)) fail('the phone that checked in (with its push token) is not on the BSS face');
  await call('PUT', `${ENT}/subscriber`, staff, { imsi, status: 'active' });
  const refresh = (await call('POST', `${ENT}/subscriber/${imsi}/reconfigure`, staff, { apps: ['ap2004'] })).body;
  if (!refresh || !refresh.targets || !refresh.targets.some((t) => t.channel === 'push' && t.messageId)) fail('refresh notice not sent over push: ' + JSON.stringify(refresh));
  const custTok = await token('bss', 'bss-biz', email, login.temporaryPassword);
  const notice = await until('the refresh notice in the customer\'s messages', async () => {
    const r = await call('GET', INBOX, custTok);
    return (r.body || []).find((m) => m.messageType === 'push' && m.content && m.content.includes('ap2004'));
  }, 10);
  const log = (await call('GET', `${ENT}/ecsRequest?imsi=${imsi}`, staff)).body || [];
  if (!log.some((r) => r.outcome === 'served') || !log.some((r) => r.outcome === 'eap-challenge') || !log.some((r) => r.outcome === 'notified')) fail('request log incomplete');
  console.log(`OK suspended line → DISABLED; the BSS face explains it; a server-initiated refresh reached the phone as a push through communication (${notice.messageType}: ${notice.content.slice(0, 60)}…)`);

  /* 10. the orchestrator binds a line by itself: order a plan, the ECS knows the SIM without any manual step */
  const order = await call('POST', `${API}/tmf-api/productOrderingManagement/v4/productOrder`, staff, {
    productOrderItem: [{ action: 'add', productOffering: { id: full.id, name: full.name } }],
    relatedParty: [{ id: party, role: 'customer' }] });
  if (order.status !== 201) fail('order failed: ' + order.status + ' ' + order.text);
  const autoBound = await until('the orchestrator to bind the activated line', async () => {
    const list = (await call('GET', `${ENT}/subscriber?partyId=${party}`, staff)).body || [];
    return list.find((s) => s.serviceId && !s.serviceId.startsWith('svc-') && s.iccid && s.msisdn && s.offeringId === full.id) || null;
  });
  const autoImsi = autoBound.imsi;
  if (!autoImsi || autoImsi === imsi) fail('auto-bound line has no IMSI of its own: ' + JSON.stringify(autoBound));
  const autoPhone = await phone({ imsi: autoImsi, terminalId: `35${autoImsi.slice(-13)}`, apps: ['ap2003', 'ap2014'] });
  if (!autoPhone || !autoPhone.token || autoPhone.entitlements.ap2014.MSISDN !== '+' + autoBound.msisdn) fail('the auto-bound line does not authenticate: ' + JSON.stringify(autoPhone).slice(0, 300));
  console.log(`OK activation bound the line by itself: SIM ${autoBound.iccid.slice(-6)} → IMSI ${autoImsi} (from the AUC), number ${autoBound.msisdn}, plan "${full.name}" — the phone authenticates and reads its number`);

  /* 11. a plan change through the orchestrator follows into the ECS */
  const products = (await call('GET', `${API}/tmf-api/productInventory/v4/product?relatedPartyId=${party}&status=active&limit=10`, staff)).body || [];
  const bssProduct = products.find((p) => p.name === full.name);
  if (!bssProduct) fail('no active BSS product for the party');
  const modify = await call('POST', `${API}/tmf-api/productOrderingManagement/v4/productOrder`, staff, {
    productOrderItem: [{ action: 'modify', product: { id: bssProduct.id, realizingService: [{ id: autoBound.serviceId }] },
      productOffering: { id: noWifi.id, name: noWifi.name } }],
    relatedParty: [{ id: party, role: 'customer' }] });
  if (modify.status !== 201) fail('plan change failed: ' + modify.status + ' ' + modify.text);
  await until('the ECS to follow the plan change', async () => {
    const s = (await call('GET', `${ENT}/subscriber?imsi=${autoImsi}`, staff)).body || [];
    return s[0] && s[0].offeringId === noWifi.id ? s[0] : null;
  });
  console.log('OK TMF622 modify: the orchestrator told the ECS about the new plan');

  /* 12. ODSA primary: the subscription moves to a new eSIM phone — and the orchestrator completes it */
  const newPhone = { targetTerminalId: `35${String(run + 1).slice(-13)}`, targetEid: '89049032' + String(run + 1).slice(-24).padStart(24, '0'), oldTerminalId: `35${autoImsi.slice(-13)}` };
  await call('PUT', `${ENT}/subscriber`, staff, { imsi: autoImsi, offeringId: full.id });
  const transfer = await phone({ imsi: autoImsi, terminalId: newPhone.targetTerminalId, apps: ['ap2009'], operation: 'ManageSubscription', operationType: 3, token: autoPhone.token, installProfile: true, ...newPhone });
  const t = transfer.entitlements.ap2009;
  if (t.SubscriptionResult !== '2' || !t.DownloadInfo || !t.DownloadInfo.ProfileIccid) fail('transfer not granted: ' + JSON.stringify(t));
  if (t.DownloadInfo.ProfileSmdpAddress !== 'rsp.mock-smdp.example') fail('the transferred profile did not come from the SM-DP+: ' + JSON.stringify(t.DownloadInfo));
  const completed = await until('the orchestrator to complete the eSIM transfer and the SM-DP+ to report it installed', async () => {
    const d = (await call('GET', `${ENT}/subscriber/${autoImsi}`, staff)).body;
    const tr = (d.transfers || []).find((x) => x.newIccid === t.DownloadInfo.ProfileIccid);
    return tr && tr.status === 'completed' && tr.profileState === 'installed' && d.iccid === t.DownloadInfo.ProfileIccid ? d : null;
  });
  const sim = (await call('GET', `${API}/tmf-api/serviceInventory/v4/service/${autoBound.serviceId}/sim`, staff)).body;
  if (!sim || !String(sim.iccid).endsWith(t.DownloadInfo.ProfileIccid.slice(-5))) fail('the line is not on the new eSIM profile: ' + JSON.stringify(sim));
  const oldTokenStill = await phone({ imsi: autoImsi, terminalId: newPhone.oldTerminalId, apps: ['ap2004'], token: autoPhone.token });
  if (!oldTokenStill.steps.some((s) => s.step === 'GET with EAP_ID')) fail('the old phone\'s token survived the transfer');
  console.log(`OK ODSA primary (ap2009) transfer: new eSIM profile ${t.DownloadInfo.ProfileIccid} — the orchestrator blocked the old SIM, activated the profile on the line, the binding moved, the old phone\'s token is dead`);

  /* 12b. RCS (GSMA RCC.14): the RCS client's configuration door — the plan decides, XML answers */
  const rcsOn = (await call('POST', `${PHONE}/simulate-rcs`, null, { rcsUrl: 'http://gateway:8080/rcs/autoconfig', imsi })).body;
  if (!rcsOn || rcsOn.status !== 200 || rcsOn.version !== '1' || !String(rcsOn.contentType).includes('text/vnd.wap.connectivity-xml')
    || !rcsOn.document.includes('<parm name="AppID" value="ap2001"/>') || !rcsOn.document.includes('<parm name="ChatAuth" value="1"/>')
    || !rcsOn.document.includes('Home_network_domain_name')) fail('RCS configuration wrong: ' + JSON.stringify(rcsOn).slice(0, 500));
  await call('PUT', `${ENT}/subscriber`, staff, { imsi, offeringId: noWifi.id });
  const rcsOff = (await call('POST', `${PHONE}/simulate-rcs`, null, { rcsUrl: 'http://gateway:8080/rcs/autoconfig', imsi })).body;
  if (!rcsOff || rcsOff.version !== '0' || rcsOff.document.includes('ChatAuth')) fail('RCS not disabled on the plan without it: ' + JSON.stringify(rcsOff).slice(0, 300));
  await call('PUT', `${ENT}/subscriber`, staff, { imsi, offeringId: full.id });
  console.log('OK RCS auto-configuration (RCC.14): EAP-AKA on the same door, RCC.07 XML with the IMS access and services on the RCS plan; version 0 (disabled) on the plan without it');

  /* 13. OIDC (TS.43 §2.8.2): a client without SIM access is sent to sign in, in a real browser */
  const oidcQ = new URLSearchParams({ terminal_id: `tablet-${run}`, terminal_vendor: 'GenAlphaSim', terminal_model: 'SimTablet', terminal_sw_version: '1', entitlement_version: '12.0', vers: '1', app: 'ap2004' });
  const redirect = await fetch(`${ECS}?${oidcQ}`, { redirect: 'manual' });
  const loc = redirect.headers.get('location') || '';
  if (redirect.status !== 302 || !loc.includes('/protocol/openid-connect/auth') || !loc.includes('client_id=bss-ecs')) fail('no OIDC redirect: ' + redirect.status + ' ' + loc);
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto(`${ECS}?${oidcQ}`);
  await page.waitForSelector('input[name="username"]', { timeout: 20000 });
  await page.fill('input[name="username"]', email); await page.fill('input[name="password"]', login.temporaryPassword);
  await page.click('input[type="submit"], button[type="submit"]');
  await page.waitForURL((u) => u.toString().includes('/ts43?') && u.toString().includes('token='), { timeout: 30000 });
  const oidcBody = JSON.parse(await page.locator('body').innerText());
  if (!oidcBody.ap2004 || oidcBody.ap2004.EntitlementStatus !== '1') fail('OIDC-authenticated request not entitled: ' + JSON.stringify(oidcBody).slice(0, 300));
  console.log('OK OIDC: a tablet without SIM access was sent to the operator\'s sign-in, came back with an ECS token and its Wi-Fi calling entitlement');

  /* 14. the console page, in words (a fresh browser context: staff, not the customer) */
  const staffPage = await (await browser.newContext()).newPage();
  await staffPage.goto('http://localhost:8080/console/');
  await staffPage.waitForSelector('input[name="username"]', { timeout: 20000 });
  await staffPage.fill('input[name="username"]', 'demo'); await staffPage.fill('input[name="password"]', 'demo');
  await staffPage.click('input[type="submit"], button[type="submit"]');
  await staffPage.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  await staffPage.waitForSelector('#tabs .tab', { timeout: 10000 });
  await staffPage.locator('text=Care & Ops').first().click().catch(() => {});
  await staffPage.locator('#tabs .tab', { hasText: 'Device entitlements' }).first().click();
  await staffPage.locator('.decision-row', { hasText: msisdn }).first().waitFor({ timeout: 20000 });
  await staffPage.locator('.decision-row', { hasText: msisdn }).first().click();
  await staffPage.locator('text=What the phone may use').waitFor({ timeout: 20000 });
  const drawer = await staffPage.locator('body').innerText();
  if (!drawer.includes('Wi-Fi calling (VoWiFi)') || !drawer.includes('SimPhone 1')) fail('console drawer incomplete');
  await browser.close();
  console.log('OK the console shows the line in words: what the phone may use, and the phone that checked in');

  /* 15. another tenant sees nothing */
  const other = await token('taranga', 'bss-demo', 'demo', 'demo');
  const foreign = await call('GET', `${ENT}/subscriber?imsi=${imsi}`, other);
  if (foreign.status !== 200 || (foreign.body || []).length !== 0) fail('tenant isolation broken: ' + foreign.text);
  console.log('OK tenant isolation: Taranga sees none of genalpha\'s subscribers');

  console.log('\nPASS device_entitlement_test — a TS.43 entitlement server as an ODA component: the plan decides, EAP-AKA proves the SIM, '
    + 'every TS.43 app answers in its own terms (JSON and XML), the orchestrator binds lines and completes eSIM transfers by itself, refresh reaches the phone, '
    + 'OIDC covers clients without a SIM, the console explains it, tenants stay apart.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
