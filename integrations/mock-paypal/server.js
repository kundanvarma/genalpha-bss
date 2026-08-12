/*
 * Mock PayPal — the SECOND redirect provider, proving the redirect PSP seam is
 * not Klarna-special. Speaks the PayPal Orders v2 shape the PayPalPspAdapter needs:
 *
 *   POST /v2/checkout/orders                  -> { id, approve_url }
 *   GET  /v2/checkout/orders/{id}             -> { approved, amount, currency, authorization_code }
 *   POST /v2/checkout/orders/{id}/capture     -> { captured, capture_id }
 *   POST /v2/checkout/orders/{id}/refund      -> { refunded, refund_id }
 *   GET  /approve/{id}                         -> a hosted approve page that redirects back
 *
 * The order auto-approves (the demo customer "approves" instantly); a real PayPal
 * waits for the customer at the approve link. In-memory; a seam target.
 */
'use strict';

const http = require('http');
const crypto = require('crypto');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;
// Where the BROWSER reaches this mock (approve_url must be browser-reachable);
// a real PayPal returns its own paypal.com URL, so no equivalent is needed.
const PUBLIC_BASE = process.env.PUBLIC_BASE || '';
const orders = new Map();

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const json = (code, body) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };

  // GET an order's approval state
  const get = url.pathname.match(/^\/v2\/checkout\/orders\/([^/]+)$/);
  if (req.method === 'GET' && get) {
    const o = orders.get(get[1]);
    if (!o) return json(404, { error: 'unknown order' });
    return json(200, {
      approved: true, amount: o.amount, currency: o.currency,
      authorization_code: 'PPL-' + get[1].slice(3),
    });
  }

  // a cosmetic hosted approve page — redirect back to the shop's return url
  const approve = url.pathname.match(/^\/approve\/([^/]+)$/);
  if (req.method === 'GET' && approve) {
    const o = orders.get(approve[1]);
    let back = (o && o.returnUrl) || '/';
    back += (back.includes('?') ? '&' : '?') + 'paypal_order=' + approve[1]; // return with the order
    res.writeHead(200, { 'Content-Type': 'text/html' });
    return res.end(`<!doctype html><meta charset=utf-8><title>PayPal</title>`
      + `<body style="font-family:sans-serif;text-align:center;padding:3rem">`
      + `<h2>PayPal</h2><p>Pay with PayPal — approved (demo).</p>`
      + `<p><a href="${back}">Return to the shop →</a></p>`
      + `<script>setTimeout(function(){location.href=${JSON.stringify(back)}},1200)</script></body>`);
  }

  // capture / refund an order
  const cap = url.pathname.match(/^\/v2\/checkout\/orders\/([^/]+)\/capture$/);
  if (req.method === 'POST' && cap) {
    req.resume();
    if (!orders.get(cap[1])) return json(404, { error: 'unknown order' });
    console.log(`[mock-paypal] captured ${cap[1]}`);
    return json(200, { captured: true, capture_id: 'PPCAP-' + cap[1].slice(3) });
  }
  const ref = url.pathname.match(/^\/v2\/checkout\/orders\/([^/]+)\/refund$/);
  if (req.method === 'POST' && ref) {
    req.resume();
    if (!orders.get(ref[1])) return json(404, { error: 'unknown order' });
    console.log(`[mock-paypal] refunded ${ref[1]}`);
    return json(200, { refunded: true, refund_id: 'PPREF-' + ref[1].slice(3) });
  }

  if (req.method === 'POST' && url.pathname === '/v2/checkout/orders') {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      let body = {};
      try { body = raw ? JSON.parse(raw) : {}; } catch { return json(400, { error: 'bad json' }); }
      const id = 'pp_' + crypto.randomBytes(8).toString('hex');
      orders.set(id, { amount: body.amount, currency: body.currency || 'EUR', returnUrl: body.returnUrl || '/' });
      console.log(`[mock-paypal] order ${id} for ${body.amount} ${body.currency}`);
      return json(200, { id, approve_url: `${PUBLIC_BASE}/approve/${id}` });
    });
    return;
  }

  if (url.pathname === '/health' || url.pathname === '/') return json(200, { ok: true, psp: 'mock-paypal', orders: orders.size });
  return json(404, { error: 'not found', path: url.pathname });
});

server.listen(PORT, () => console.log(`[mock-paypal] PayPal-shaped redirect PSP on :${PORT}`));
