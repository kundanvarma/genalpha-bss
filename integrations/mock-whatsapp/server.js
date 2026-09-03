/*
 * Mock WhatsApp Business — stands in for the Meta Cloud API an operator's
 * WhatsApp line runs on. Accepts the text-message shape the WhatsAppForwarder
 * sends and keeps a per-tenant outbox so a suite can prove a message left:
 *
 *   POST /v20.0/{phoneId}/messages   -> { messaging_product, contacts, messages:[{id}] }
 *   GET  /outbox?to=592…             -> the messages sent to that number (newest first)
 *
 * Deliberately in-memory: a demo seam target, not a product.
 */
'use strict';
const http = require('http');
const crypto = require('crypto');
const { URL } = require('url');
const PORT = process.env.PORT || 8080;
const outbox = [];
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const json = (code, body) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
  if (req.method === 'GET' && url.pathname === '/health') return json(200, { ok: true, system: 'mock-whatsapp' });
  if (req.method === 'GET' && url.pathname === '/outbox') {
    const to = (url.searchParams.get('to') || '').replace(/[^0-9]/g, '');
    return json(200, outbox.filter((m) => !to || m.to === to).slice().reverse());
  }
  const m = /^\/v20\.0\/([^/]+)\/messages$/.exec(url.pathname);
  if (req.method === 'POST' && m) {
    let raw = ''; req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      let b = {}; try { b = raw ? JSON.parse(raw) : {}; } catch { return json(400, { error: { message: 'bad json' } }); }
      if (b.messaging_product !== 'whatsapp' || !b.to) return json(400, { error: { message: 'messaging_product=whatsapp and to are required' } });
      const id = 'wamid.' + crypto.randomUUID().replace(/-/g, '').toUpperCase().slice(0, 24);
      outbox.push({ id, phoneId: m[1], tenant: req.headers['x-tenant-id'] || null, to: String(b.to).replace(/[^0-9]/g, ''),
        type: b.type, body: b.text && b.text.body, callbackData: b.biz_opaque_callback_data || null, at: new Date().toISOString() });
      console.log(`[mock-whatsapp] ${m[1]} -> ${b.to}: ${(b.text && b.text.body || '').slice(0, 80)}`);
      return json(200, { messaging_product: 'whatsapp', contacts: [{ input: b.to, wa_id: String(b.to).replace(/[^0-9]/g, '') }], messages: [{ id }] });
    });
    return;
  }
  json(404, { error: { message: 'not found' } });
});
server.listen(PORT, () => console.log(`[mock-whatsapp] Meta Cloud API stand-in on :${PORT}`));
