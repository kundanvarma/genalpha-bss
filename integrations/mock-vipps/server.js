/*
 * Mock Vipps MobilePay — the wallet redirect provider for proving the payment
 * session flow without a real Vipps merchant. Speaks the ePayment shape the
 * VippsPspAdapter needs (amounts in MINOR units — øre):
 *
 *   POST /epayment/v1/payments           -> { reference, redirectUrl }
 *   GET  /epayment/v1/payments/{ref}     -> { state: AUTHORIZED, amount, pspReference }
 *   POST /epayment/v1/payments/{ref}/capture | /refund
 *   POST /recurring/v3/agreements        -> { agreementId }   (recurring "avtale")
 *   POST /recurring/v3/agreements/{id}/charges -> { state: CHARGED, chargeId }
 *   GET  /approve/{ref}                  -> a hosted approve page that redirects back
 *
 * The payment auto-authorizes (the demo customer taps "Betal" instantly); a real
 * Vipps waits in the app. In-memory; a seam target.
 */
'use strict';

const http = require('http');
const crypto = require('crypto');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;
const PUBLIC_BASE = process.env.PUBLIC_BASE || '';
const payments = new Map();
const agreements = new Map();

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const json = (code, body) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
  const readBody = (cb) => {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      let body = {};
      try { body = raw ? JSON.parse(raw) : {}; } catch { return json(400, { error: 'bad json' }); }
      cb(body);
    });
  };

  const get = url.pathname.match(/^\/epayment\/v1\/payments\/([^/]+)$/);
  if (req.method === 'GET' && get) {
    const p = payments.get(get[1]);
    if (!p) return json(404, { error: 'unknown payment' });
    return json(200, { reference: get[1], state: p.state, amount: p.amount,
      pspReference: 'vpp_' + get[1].slice(3) });
  }

  // the hosted approve page — orange, one tap, back to the shop
  const approve = url.pathname.match(/^\/approve\/([^/]+)$/);
  if (req.method === 'GET' && approve) {
    const p = payments.get(approve[1]);
    let back = (p && p.returnUrl) || '/';
    back += (back.includes('?') ? '&' : '?') + 'resume=' + approve[1];
    res.writeHead(200, { 'Content-Type': 'text/html' });
    return res.end(`<!doctype html><meta charset=utf-8><title>Vipps</title>`
      + `<body style="font-family:sans-serif;text-align:center;padding:3rem;background:#ff5b24;color:#fff">`
      + `<h1 style="letter-spacing:1px">vipps</h1><p>Betalt med Vipps — godkjent (demo).</p>`
      + `<p><a href="${back}" style="color:#fff">Tilbake til butikken →</a></p>`
      + `<script>setTimeout(function(){location.href=${JSON.stringify(back)}},1200)</script></body>`);
  }

  const cap = url.pathname.match(/^\/epayment\/v1\/payments\/([^/]+)\/capture$/);
  if (req.method === 'POST' && cap) {
    return readBody(() => {
      const p = payments.get(cap[1]);
      if (!p) return json(404, { error: 'unknown payment' });
      p.state = 'CAPTURED';
      console.log(`[mock-vipps] captured ${cap[1]}`);
      return json(200, { state: 'CAPTURED', pspReference: 'vcp_' + cap[1].slice(3) });
    });
  }
  const ref = url.pathname.match(/^\/epayment\/v1\/payments\/([^/]+)\/refund$/);
  if (req.method === 'POST' && ref) {
    return readBody(() => {
      const p = payments.get(ref[1]);
      if (!p) return json(404, { error: 'unknown payment' });
      p.state = 'REFUNDED';
      console.log(`[mock-vipps] refunded ${ref[1]}`);
      return json(200, { state: 'REFUNDED', pspReference: 'vrf_' + ref[1].slice(3) });
    });
  }

  // recurring: an approved payment becomes an agreement ("avtale")
  if (req.method === 'POST' && url.pathname === '/recurring/v3/agreements') {
    return readBody((body) => {
      const id = 'agr_' + crypto.randomBytes(8).toString('hex');
      agreements.set(id, { paymentReference: body.paymentReference || null });
      console.log(`[mock-vipps] agreement ${id} from payment ${body.paymentReference}`);
      return json(200, { agreementId: id, status: 'ACTIVE' });
    });
  }
  const chg = url.pathname.match(/^\/recurring\/v3\/agreements\/([^/]+)\/charges$/);
  if (req.method === 'POST' && chg) {
    if (!agreements.get(chg[1])) { req.resume(); return json(404, { error: 'unknown agreement' }); }
    return readBody((body) => {
      const id = 'chr_' + crypto.randomBytes(8).toString('hex');
      console.log(`[mock-vipps] agreement ${chg[1]} charged ${body.amount && body.amount.value} øre`);
      return json(200, { state: 'CHARGED', chargeId: id, amount: body.amount });
    });
  }

  if (req.method === 'POST' && url.pathname === '/epayment/v1/payments') {
    return readBody((body) => {
      const id = 'vp_' + crypto.randomBytes(8).toString('hex');
      payments.set(id, { state: 'AUTHORIZED', amount: body.amount || {},
        returnUrl: body.returnUrl || '/' });
      console.log(`[mock-vipps] payment ${id} for ${body.amount && body.amount.value} øre`);
      return json(200, { reference: id, redirectUrl: `${PUBLIC_BASE}/approve/${id}` });
    });
  }

  if (url.pathname === '/health' || url.pathname === '/') return json(200, { ok: true, psp: 'mock-vipps', payments: payments.size });
  return json(404, { error: 'not found', path: url.pathname });
});

server.listen(PORT, () => console.log(`[mock-vipps] Vipps-shaped wallet PSP on :${PORT}`));
