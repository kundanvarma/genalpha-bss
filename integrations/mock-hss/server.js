/* Mock HSS / AUC — stands in for the operator's (or, for an MVNO, the host
 * MNO's) Home Subscriber Server: the one place that knows a subscriber's
 * IMSI ↔ ICCID ↔ MSISDN and holds the USIM secrets (K, OPc). The
 * entitlement server's EAP-AKA relay asks it for an AUTHENTICATION VECTOR
 * (RAND, AUTN, XRES, CK, IK — Milenage, TS 35.206) and never sees K.
 *
 *   POST /auc/vector        {imsi}                → {imsi, rand, autn, xres, ck, ik} (hex)
 *   GET  /subscribers/{imsi}                      → {imsi, iccid, msisdn}
 *   PUT  /subscribers/{imsi} {iccid, msisdn, k?, opc?} → upsert (dev seeding)
 *   GET  /subscribers/{imsi}/secrets              → {k, opc} — DEV ONLY, the device
 *                                                   simulator "reads its SIM" here
 *   GET  /health
 *
 * Unknown IMSIs get deterministic dev secrets derived from HSS_SEED, so any
 * IMSI the fleet mints authenticates in a demo. In-memory, a seam target.
 */
'use strict';

const http = require('http');
const crypto = require('crypto');
const { URL } = require('url');
const mil = require('./milenage');

const PORT = process.env.PORT || 8080;
const SEED = process.env.HSS_SEED || 'genalpha-dev-hss';
const AMF = Buffer.from(process.env.HSS_AMF || '8000', 'hex');

const subs = new Map(); // imsi -> {iccid, msisdn, k, opc, sqn}

function devSecrets(imsi) {
  const k = crypto.createHash('sha256').update(`${SEED}:K:${imsi}`).digest().subarray(0, 16);
  const op = crypto.createHash('sha256').update(`${SEED}:OP`).digest().subarray(0, 16);
  return { k, opc: mil.opc(k, op) };
}
function subscriber(imsi) {
  if (!subs.has(imsi)) {
    const s = devSecrets(imsi);
    subs.set(imsi, { imsi, iccid: null, msisdn: null, k: s.k, opc: s.opc, sqn: 1 });
  }
  return subs.get(imsi);
}
const hex = (b) => b.toString('hex');
const pub = (s) => ({ imsi: s.imsi, iccid: s.iccid, msisdn: s.msisdn });

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const send = (code, body) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
  let raw = '';
  req.on('data', (c) => { raw += c; });
  req.on('end', () => {
    let body = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch { return send(400, { error: 'bad json' }); }

    if (req.method === 'GET' && url.pathname === '/health') return send(200, { status: 'UP', subscribers: subs.size });

    if (req.method === 'POST' && url.pathname === '/auc/vector') {
      if (!body.imsi || !/^\d{6,15}$/.test(String(body.imsi))) return send(400, { error: 'imsi required' });
      const s = subscriber(String(body.imsi));
      const sqn = Buffer.alloc(6); sqn.writeUIntBE(s.sqn, 0, 6); s.sqn += 32; // SQN steps per 3GPP practice
      const v = mil.vector(s.k, s.opc, sqn, AMF);
      return send(200, { imsi: s.imsi, rand: hex(v.rand), autn: hex(v.autn), xres: hex(v.xres), ck: hex(v.ck), ik: hex(v.ik) });
    }

    const m = url.pathname.match(/^\/subscribers\/(\d+)(?:\/(secrets))?$/);
    if (m) {
      const imsi = m[1];
      if (req.method === 'GET' && !m[2]) {
        if (!subs.has(imsi)) return send(404, { error: 'unknown imsi' });
        return send(200, pub(subs.get(imsi)));
      }
      if (req.method === 'GET' && m[2] === 'secrets') {
        const s = subscriber(imsi);
        return send(200, { imsi, k: hex(s.k), opc: hex(s.opc) });
      }
      if (req.method === 'PUT') {
        const s = subscriber(imsi);
        if (body.iccid !== undefined) s.iccid = body.iccid;
        if (body.msisdn !== undefined) s.msisdn = body.msisdn;
        if (body.k) s.k = Buffer.from(body.k, 'hex');
        if (body.opc) s.opc = Buffer.from(body.opc, 'hex');
        return send(200, pub(s));
      }
      if (req.method === 'DELETE') { subs.delete(imsi); return send(204, {}); }
    }
    if (req.method === 'GET' && url.pathname === '/subscribers') {
      const by = url.searchParams.get('msisdn') || url.searchParams.get('iccid');
      const list = [...subs.values()].filter((s) => !by || s.msisdn === by || s.iccid === by).map(pub);
      return send(200, list);
    }
    send(404, { error: 'not found' });
  });
});

server.listen(PORT, () => console.log(`mock-hss listening on ${PORT}`));
