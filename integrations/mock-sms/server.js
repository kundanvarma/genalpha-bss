/*
 * Mock SMS gateway — stands in for the A2P SMS provider an operator already
 * pays for (Twilio, Sinch, Vonage…). It speaks just enough of Twilio's
 * Messages wire shape for the communication component's SMS forwarder to hit
 * it: POST /2010-04-01/Messages.json with {To, From, Body} and a Bearer
 * token. GET /messages?to= lets the E2E assert what actually left the
 * building.
 *
 * Deliberately in-memory: a demo seam target, not a product.
 */
'use strict';

const http = require('http');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;

/** [{to, from, body, sid, apiKey, receivedAt}] */
const messages = [];

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const json = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };

  if (req.method === 'GET' && url.pathname === '/health') {
    return json(200, { status: 'UP' });
  }

  if (req.method === 'POST' && url.pathname === '/2010-04-01/Messages.json') {
    const auth = req.headers.authorization || '';
    if (!auth.startsWith('Bearer ') || auth.length <= 'Bearer '.length) {
      return json(401, { message: 'authorization required' });
    }
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      try {
        const m = JSON.parse(body || '{}');
        if (!m.To || !m.Body) {
          return json(400, { message: 'To and Body are required' });
        }
        const sid = 'SM' + Math.random().toString(16).slice(2, 14);
        const record = {
          to: m.To, from: m.From || null, body: m.Body,
          sid, apiKey: auth.slice('Bearer '.length),
          receivedAt: new Date().toISOString(),
        };
        messages.push(record);
        // Twilio answers 201 with the message resource
        return json(201, { sid, status: 'queued', to: record.to, from: record.from, body: record.body });
      } catch {
        json(400, { message: 'unparseable payload' });
      }
    });
    return;
  }

  if (req.method === 'GET' && url.pathname === '/messages') {
    const to = url.searchParams.get('to');
    return json(200, to ? messages.filter((m) => m.to === to) : messages);
  }

  json(404, { message: 'not found' });
});

server.listen(PORT, () => console.log(`mock-sms listening on ${PORT}`));
