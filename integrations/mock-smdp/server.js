/* Mock SM-DP+ — stands in for the certified eSIM profile server an operator
 * buys (Thales, IDEMIA, G+D, Kigen…). Speaks the operator side of GSMA
 * SGP.22 ES2+ (JSON): the BSS orders and confirms profiles, gets the matching
 * id the phone's activation code carries, cancels or releases; the SM-DP+
 * reports download progress back on the operator's notification door.
 *
 *   POST /gsma/rsp2/es2plus/downloadOrder     {header, eid?, iccid?, profileType?} → {header, iccid}
 *   POST /gsma/rsp2/es2plus/confirmOrder      {header, iccid, eid?, matchingId?, releaseFlag} → {header, matchingId, smdpAddress}
 *   POST /gsma/rsp2/es2plus/cancelOrder       {header, iccid, matchingId?, finalProfileStatusIndicator}
 *   POST /gsma/rsp2/es2plus/releaseProfile    {header, iccid}
 *   POST /simulate/install   {matchingId | iccid | activationCode}  — the phone "downloaded" the
 *                            profile: posts handleDownloadProgressInfo (points 3 and 4) to
 *                            ES2PLUS_NOTIFY_URL/<functionRequesterIdentifier>/handleDownloadProgressInfo
 *   GET  /profiles, GET /health
 * Production adds mutual TLS and the SM-DP+'s own SAS-certified vault. In-memory; DEV ONLY.
 */
'use strict';

const http = require('http');
const crypto = require('crypto');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;
const ADDRESS = process.env.SMDP_ADDRESS || 'rsp.mock-smdp.example';
const NOTIFY = (process.env.ES2PLUS_NOTIFY_URL || '').replace(/\/+$/, '');

const profiles = new Map(); // iccid -> {iccid, eid, profileType, matchingId, state, requester}

const ok = (extra = {}) => ({ header: { functionExecutionStatus: { status: 'Executed-Success' } }, ...extra });
const failed = (code, message) => ({ header: { functionExecutionStatus: { status: 'Failed', statusCodeData: { subjectCode: '8.2', reasonCode: code, message } } } });
const mintIccid = () => '8947' + Array.from({ length: 15 }, () => Math.floor(Math.random() * 10)).join('');
const mintMatchingId = () => crypto.randomBytes(8).toString('hex').toUpperCase().match(/.{4}/g).join('-');

async function notify(p, point, status) {
  if (!NOTIFY) return { skipped: true };
  const url = `${NOTIFY}/${encodeURIComponent(p.requester)}/handleDownloadProgressInfo`;
  const body = { header: { functionRequesterIdentifier: 'mock-smdp', functionCallIdentifier: crypto.randomUUID() },
    eid: p.eid, iccid: p.iccid, profileType: p.profileType, timestamp: new Date().toISOString(),
    notificationPointId: point, notificationPointStatus: { status } };
  try {
    const res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Admin-Protocol': 'gsma/rsp/v2.2.0' }, body: JSON.stringify(body) });
    return { url, status: res.status };
  } catch (e) {
    return { url, error: e.message };
  }
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const send = (code, body) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
  let raw = '';
  req.on('data', (c) => { raw += c; });
  req.on('end', async () => {
    let body = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch { return send(400, failed('1.2', 'bad json')); }
    if (req.method === 'GET' && url.pathname === '/health') return send(200, { status: 'UP', profiles: profiles.size, address: ADDRESS });
    if (req.method === 'GET' && url.pathname === '/profiles') return send(200, [...profiles.values()]);

    const fn = url.pathname.match(/^\/gsma\/rsp2\/es2plus\/(\w+)$/);
    if (fn && req.method === 'POST') {
      const requester = body.header && body.header.functionRequesterIdentifier;
      if (!requester || !body.header.functionCallIdentifier) return send(400, failed('2.1', 'header.functionRequesterIdentifier and functionCallIdentifier are mandatory'));
      switch (fn[1]) {
        case 'downloadOrder': {
          const iccid = body.iccid || mintIccid();
          if (profiles.has(iccid) && profiles.get(iccid).state !== 'released') return send(200, failed('3.8', 'ICCID already in use'));
          profiles.set(iccid, { iccid, eid: body.eid || null, profileType: body.profileType || 'default', matchingId: null, state: 'allocated', requester });
          return send(200, ok({ iccid }));
        }
        case 'confirmOrder': {
          const p = profiles.get(body.iccid);
          if (!p) return send(200, failed('3.9', 'unknown ICCID'));
          if (body.eid) p.eid = body.eid;
          p.matchingId = body.matchingId || mintMatchingId();
          p.state = body.releaseFlag === false ? 'confirmed' : 'released-for-download';
          return send(200, ok({ eid: p.eid || undefined, matchingId: p.matchingId, smdpAddress: ADDRESS }));
        }
        case 'cancelOrder': {
          const p = profiles.get(body.iccid);
          if (!p) return send(200, failed('3.9', 'unknown ICCID'));
          p.state = 'cancelled';
          return send(200, ok());
        }
        case 'releaseProfile': {
          const p = profiles.get(body.iccid);
          if (!p) return send(200, failed('3.9', 'unknown ICCID'));
          p.state = 'released';
          return send(200, ok());
        }
        case 'handleDownloadProgressInfo':
          return send(200, ok());
        default:
          return send(404, failed('1.1', 'unknown function'));
      }
    }

    // the phone side, simulated: the LPA downloaded and installed the profile
    if (req.method === 'POST' && url.pathname === '/simulate/install') {
      let matchingId = body.matchingId;
      if (!matchingId && body.activationCode) matchingId = String(body.activationCode).split('$')[2];
      const p = [...profiles.values()].find((x) => (matchingId && x.matchingId === matchingId) || (body.iccid && x.iccid === body.iccid));
      if (!p) return send(404, { error: 'no such profile' });
      if (!['released-for-download', 'confirmed'].includes(p.state)) return send(409, { error: `profile is ${p.state}` });
      p.state = 'downloading';
      const n3 = await notify(p, 3, 'Executed-Success');
      p.state = 'installed';
      const n4 = await notify(p, 4, 'Executed-Success');
      return send(200, { iccid: p.iccid, matchingId: p.matchingId, state: p.state, notifications: [n3, n4] });
    }
    send(404, { error: 'not found' });
  });
});

server.listen(PORT, () => console.log(`mock-smdp listening on ${PORT} as ${ADDRESS}`));
