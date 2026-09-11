/* Mock TS.43 device — the phone's Entitlement Client, simulated. Apple's and
 * Google's clients run GSMA TS.43 against the operator's Entitlement
 * Configuration Server (ECS): EAP-AKA over HTTP ("EAP relay"), then a token,
 * then per-app configuration (VoLTE, VoWiFi, SMSoIP, data plan, ODSA for
 * companion / primary eSIMs). This simulator does exactly that so the flow
 * is provable without an OEM device: it "reads its SIM" (K/OPc from the HSS
 * mock's dev-only secrets door), answers the AKA challenge like a USIM,
 * verifies the server's AT_MAC, and hands back what the ECS said.
 *
 *   POST /simulate  {ecsUrl, imsi, terminalId?, vendor?, model?, apps?:[…],
 *                    operation?, operationType?, companionTerminalId?, companionEid?,
 *                    targetTerminalId?, targetEid?, oldTerminalId?, token?}
 *                 → {token, entitlements (the ECS JSON), steps:[…]}
 *   GET  /health
 * In-memory, no dependencies. A demo seam target, not a product.
 */
'use strict';

const http = require('http');
const { URL } = require('url');
const mil = require('./milenage');

const PORT = process.env.PORT || 8080;
const HSS = process.env.HSS_BASE_URL || 'http://mock-hss:8080';
const RELAY = 'application/vnd.gsma.eap-relay.v1.0+json';

async function json(url, opts = {}) {
  const res = await fetch(url, opts);
  const text = await res.text();
  let body = null; try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  return { status: res.status, headers: res.headers, body };
}

/** One full TS.43 entitlement configuration request, EAP-AKA relay included. */
async function simulate(p) {
  const steps = [];
  const imsi = String(p.imsi);
  const mcc = imsi.slice(0, 3); const mnc = imsi.slice(3, 5).padStart(3, '0');
  const eapId = `0${imsi}@nai.epc.mnc${mnc}.mcc${mcc}.3gppnetwork.org`;
  const secrets = (await json(`${HSS}/subscribers/${imsi}/secrets`)).body;
  if (!secrets || !secrets.k) throw new Error('no SIM secrets for ' + imsi);
  const k = Buffer.from(secrets.k, 'hex'); const opc = Buffer.from(secrets.opc, 'hex');
  const apps = Array.isArray(p.apps) ? p.apps : (p.apps ? [p.apps] : ['ap2004']);
  const q = new URLSearchParams();
  q.set('terminal_id', p.terminalId || '35' + imsi.slice(-13));
  q.set('terminal_vendor', p.vendor || 'GenAlphaSim');
  q.set('terminal_model', p.model || 'SimPhone 1');
  q.set('terminal_sw_version', p.swVersion || '1.0');
  q.set('entitlement_version', p.entitlementVersion || '12.0');
  q.set('vers', '1');
  for (const a of apps) q.append('app', a);
  for (const [k2, v] of Object.entries({ operation: p.operation, operation_type: p.operationType,
    companion_terminal_id: p.companionTerminalId, companion_terminal_eid: p.companionEid,
    companion_terminal_vendor: p.companionVendor, companion_terminal_model: p.companionModel,
    target_terminal_id: p.targetTerminalId, target_terminal_eid: p.targetEid,
    old_terminal_id: p.oldTerminalId, notif_token: p.notifToken, notif_action: p.notifAction,
    terminal_iccid: p.terminalIccid, plan_id: p.planId })) {
    if (v !== undefined && v !== null && v !== '') q.set(k2, String(v));
  }
  let token = p.token || null;
  let cookie = null;
  let config = null;
  if (token) {
    q.set('token', token);
    const r = await json(`${p.ecsUrl}?${q}`, { headers: { Accept: 'application/json, ' + RELAY } });
    steps.push({ step: 'GET with token', status: r.status });
    if (r.status === 200 && r.headers.get('content-type')?.includes('json') && !(r.body && r.body['eap-relay-packet'])) config = r.body;
    else token = null; // token refused: fall through to EAP-AKA
  }
  if (!config) {
    q.delete('token');
    q.set('EAP_ID', eapId);
    const r1 = await json(`${p.ecsUrl}?${q}`, { headers: { Accept: RELAY + ', application/json' } });
    steps.push({ step: 'GET with EAP_ID', status: r1.status, contentType: r1.headers.get('content-type') });
    if (r1.status !== 200 || !r1.body || !r1.body['eap-relay-packet']) throw new Error('ECS did not start EAP-AKA: ' + r1.status + ' ' + JSON.stringify(r1.body).slice(0, 200));
    cookie = (r1.headers.get('set-cookie') || '').split(';')[0];
    const challenge = mil.parseAka(Buffer.from(r1.body['eap-relay-packet'], 'base64'));
    if (challenge.code !== mil.EAP.REQUEST || challenge.type !== mil.EAP.TYPE_AKA || challenge.subtype !== mil.EAP.AKA_CHALLENGE) throw new Error('not an EAP-Request/AKA-Challenge');
    const rand = challenge.attrs[mil.EAP.AT_RAND].subarray(2);
    const autn = challenge.attrs[mil.EAP.AT_AUTN].subarray(2);
    const usim = mil.answer(k, opc, rand, autn);   // the USIM verifies the network (AUTN's MAC)
    if (!usim) throw new Error('AUTN rejected by the USIM: the network could not prove itself');
    const keys = mil.deriveKeys(eapId, usim.ik, usim.ck);
    if (!mil.verifyMac(Buffer.from(r1.body['eap-relay-packet'], 'base64'), keys.kAut)) throw new Error('server AT_MAC invalid');
    steps.push({ step: 'USIM answered AKA challenge', res: usim.res.toString('hex') });
    const resAttr = Buffer.concat([Buffer.from([0, 64]), usim.res]); // RES length in bits, then RES
    const response = mil.buildAka(mil.EAP.RESPONSE, challenge.identifier, mil.EAP.AKA_CHALLENGE, [{ type: mil.EAP.AT_RES, value: resAttr }], keys.kAut);
    const r2 = await json(`${p.ecsUrl}?${q}`, { method: 'POST',
      headers: { 'Content-Type': RELAY, Accept: 'application/json, ' + RELAY, Cookie: cookie },
      body: JSON.stringify({ 'eap-relay-packet': response.toString('base64') }) });
    steps.push({ step: 'POST EAP-Response/AKA-Challenge', status: r2.status });
    if (r2.status !== 200) throw new Error('EAP-AKA rejected: ' + r2.status + ' ' + JSON.stringify(r2.body).slice(0, 300));
    config = r2.body;
    token = config && config.Token ? config.Token.token : null;
  }
  return { token, entitlements: config, steps };
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const send = (code, body) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
  let raw = '';
  req.on('data', (c) => { raw += c; });
  req.on('end', async () => {
    if (req.method === 'GET' && url.pathname === '/health') return send(200, { status: 'UP' });
    if (req.method === 'POST' && url.pathname === '/simulate') {
      let body;
      try { body = JSON.parse(raw || '{}'); } catch { return send(400, { error: 'bad json' }); }
      if (!body.ecsUrl || !body.imsi) return send(400, { error: 'ecsUrl and imsi required' });
      try { return send(200, await simulate(body)); } catch (e) { return send(502, { error: e.message }); }
    }
    send(404, { error: 'not found' });
  });
});

server.listen(PORT, () => console.log(`mock-ts43-device listening on ${PORT}`));
