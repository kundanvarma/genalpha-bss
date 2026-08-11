/*
 * Mock Klarna — the redirect/BNPL provider for proving the payment session flow
 * without a real Klarna merchant account. It speaks the shape the KlarnaPspAdapter
 * needs:
 *
 *   POST /payments/v1/sessions           -> { session_id, redirect_url }
 *   GET  /payments/v1/sessions/{id}      -> { approved, amount, currency, authorization_code }
 *   GET  /approve/{id}                   -> a hosted approve page that redirects back
 *
 * The session auto-approves (the demo customer "approves" instantly); a real
 * Klarna waits for the customer at redirect_url. In-memory; a seam target.
 */
'use strict';

const http = require('http');
const crypto = require('crypto');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;
// Where the BROWSER reaches this mock (redirect_url must be browser-reachable);
// a real Klarna returns its own klarna.com URL, so no equivalent is needed.
const PUBLIC_BASE = process.env.PUBLIC_BASE || '';
const sessions = new Map();

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const json = (code, body) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };

  // GET a session's authorization state
  const get = url.pathname.match(/^\/payments\/v1\/sessions\/([^/]+)$/);
  if (req.method === 'GET' && get) {
    const s = sessions.get(get[1]);
    if (!s) return json(404, { error: 'unknown session' });
    return json(200, {
      approved: true, amount: s.amount, currency: s.currency,
      authorization_code: 'kln_' + get[1].slice(3),
    });
  }

  // a cosmetic hosted approve page — redirect back to the shop's return url
  const approve = url.pathname.match(/^\/approve\/([^/]+)$/);
  if (req.method === 'GET' && approve) {
    const s = sessions.get(approve[1]);
    let back = (s && s.returnUrl) || '/';
    back += (back.includes('?') ? '&' : '?') + 'klarna_session=' + approve[1]; // return with the session
    res.writeHead(200, { 'Content-Type': 'text/html' });
    return res.end(`<!doctype html><meta charset=utf-8><title>Klarna</title>`
      + `<body style="font-family:sans-serif;text-align:center;padding:3rem">`
      + `<h2>Klarna</h2><p>Pay in 3 — approved (demo).</p>`
      + `<p><a href="${back}">Return to the shop →</a></p>`
      + `<script>setTimeout(function(){location.href=${JSON.stringify(back)}},1200)</script></body>`);
  }

  // capture / refund an order (BNPL captures on ship, refunds after)
  const cap = url.pathname.match(/^\/payments\/v1\/sessions\/([^/]+)\/capture$/);
  if (req.method === 'POST' && cap) {
    req.resume();
    if (!sessions.get(cap[1])) return json(404, { error: 'unknown session' });
    console.log(`[mock-klarna] captured ${cap[1]}`);
    return json(200, { captured: true, capture_id: 'cap_' + cap[1].slice(3) });
  }
  const ref = url.pathname.match(/^\/payments\/v1\/sessions\/([^/]+)\/refund$/);
  if (req.method === 'POST' && ref) {
    req.resume();
    if (!sessions.get(ref[1])) return json(404, { error: 'unknown session' });
    console.log(`[mock-klarna] refunded ${ref[1]}`);
    return json(200, { refunded: true, refund_id: 'ref_' + ref[1].slice(3) });
  }

  if (req.method === 'POST' && url.pathname === '/payments/v1/sessions') {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      let body = {};
      try { body = raw ? JSON.parse(raw) : {}; } catch { return json(400, { error: 'bad json' }); }
      const id = 'ks_' + crypto.randomBytes(8).toString('hex');
      sessions.set(id, { amount: body.amount, currency: body.currency || 'EUR', returnUrl: body.returnUrl || '/' });
      console.log(`[mock-klarna] session ${id} for ${body.amount} ${body.currency}`);
      return json(200, { session_id: id, redirect_url: `${PUBLIC_BASE}/approve/${id}` });
    });
    return;
  }

  if (url.pathname === '/health' || url.pathname === '/') return json(200, { ok: true, psp: 'mock-klarna', sessions: sessions.size });
  return json(404, { error: 'not found', path: url.pathname });
});

server.listen(PORT, () => console.log(`[mock-klarna] Klarna-shaped BNPL PSP on :${PORT}`));
