/*
 * Mock MMG — stands in for Mobile Money Guyana, the wallet a Guyanese checkout
 * is expected to offer (works on every network, agents nationwide, bill-pay
 * for GPL/GWI/ISPs). MMG's Merchant/Biller API is behind a partner login, so
 * this mock speaks a plain wallet-redirect shape the MmgPspAdapter needs —
 * swap the paths/fields for the real contract when the partner docs arrive:
 *
 *   POST /v1/payments                 -> { paymentId, redirectUrl }
 *   GET  /v1/payments/{id}            -> { status: APPROVED, amount:{value,currency}, transactionId }
 *   POST /v1/payments/{id}/capture | /refund
 *   GET  /approve/{id}                -> a hosted approve page (the customer's wallet PIN screen)
 *
 * Amounts are WHOLE Guyana dollars (cents were withdrawn in 1992). Auto-approves
 * for the demo; a real MMG waits for the customer's PIN in the app. In-memory.
 */
'use strict';

const http = require('http');
const crypto = require('crypto');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;
const PUBLIC_BASE = process.env.PUBLIC_BASE || '';
const payments = new Map();

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const json = (code, body) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
  const readBody = (cb) => {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => { let b = {}; try { b = raw ? JSON.parse(raw) : {}; } catch { return json(400, { error: 'bad json' }); } cb(b); });
  };
  if (req.method === 'GET' && (url.pathname === '/health' || url.pathname === '/')) return json(200, { ok: true, wallet: 'MMG (mock)' });

  if (req.method === 'POST' && url.pathname === '/v1/payments') {
    return readBody((b) => {
      const id = 'mmg_' + crypto.randomUUID().slice(0, 12);
      payments.set(id, { status: 'APPROVED', amount: b.amount || { value: 0, currency: 'GYD' }, returnUrl: b.returnUrl || '/', reference: b.reference || null });
      console.log(`[mock-mmg] payment ${id} ${b.amount && b.amount.value} ${b.amount && b.amount.currency} — approved (demo)`);
      return json(201, { paymentId: id, redirectUrl: `${PUBLIC_BASE}/approve/${id}` });
    });
  }
  const approve = url.pathname.match(/^\/approve\/([^/]+)$/);
  if (req.method === 'GET' && approve) {
    const p = payments.get(approve[1]);
    let back = (p && p.returnUrl) || '/';
    back += (back.includes('?') ? '&' : '?') + 'resume=' + approve[1];
    res.writeHead(200, { 'Content-Type': 'text/html' });
    return res.end(`<!doctype html><meta charset=utf-8><title>MMG</title>`
      + `<body style="font-family:sans-serif;text-align:center;padding:3rem;background:#1b5e20;color:#fff">`
      + `<h1 style="letter-spacing:2px">mmg</h1><p>Mobile Money Guyana — payment approved with your wallet PIN (demo).</p>`
      + `<p><a href="${back}" style="color:#fff">Back to the shop →</a></p>`
      + `<script>setTimeout(function(){location.href=${JSON.stringify(back)}},1200)</script></body>`);
  }
  const one = url.pathname.match(/^\/v1\/payments\/([^/]+)$/);
  if (req.method === 'GET' && one) {
    const p = payments.get(one[1]);
    if (!p) return json(404, { error: 'unknown payment' });
    return json(200, { paymentId: one[1], status: p.status, amount: p.amount, transactionId: 'MMGTX' + one[1].slice(4).toUpperCase() });
  }
  const cap = url.pathname.match(/^\/v1\/payments\/([^/]+)\/capture$/);
  if (req.method === 'POST' && cap) {
    return readBody(() => { const p = payments.get(cap[1]); if (!p) return json(404, { error: 'unknown payment' }); p.status = 'CAPTURED'; console.log(`[mock-mmg] captured ${cap[1]}`); return json(200, { status: 'CAPTURED', transactionId: 'MMGCP' + cap[1].slice(4).toUpperCase() }); });
  }
  const ref = url.pathname.match(/^\/v1\/payments\/([^/]+)\/refund$/);
  if (req.method === 'POST' && ref) {
    return readBody(() => { const p = payments.get(ref[1]); if (!p) return json(404, { error: 'unknown payment' }); p.status = 'REFUNDED'; console.log(`[mock-mmg] refunded ${ref[1]}`); return json(200, { status: 'REFUNDED', transactionId: 'MMGRF' + ref[1].slice(4).toUpperCase() }); });
  }
  json(404, { error: 'not found' });
});
server.listen(PORT, () => console.log(`[mock-mmg] Mobile Money Guyana stand-in on :${PORT}`));
