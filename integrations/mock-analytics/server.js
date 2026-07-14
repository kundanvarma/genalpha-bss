/*
 * Mock analytics — stands in for the web-analytics platform an operator
 * already runs (GA4, Matomo, Snowplow…). It speaks just enough of GA4's
 * Measurement Protocol for the insight component's forwarder to hit it:
 * POST /mp/collect?measurement_id=&api_secret= with {client_id, events[]}.
 * GET /events?measurement_id= lets the E2E assert what actually arrived.
 *
 * Deliberately in-memory: it is a demo seam target, not a product.
 */
'use strict';

const http = require('http');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;

/** measurement_id -> [{client_id, name, params, receivedAt}] */
const received = new Map();

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const json = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };

  if (req.method === 'GET' && url.pathname === '/health') {
    return json(200, { status: 'UP' });
  }

  if (req.method === 'POST' && url.pathname === '/mp/collect') {
    const measurementId = url.searchParams.get('measurement_id');
    if (!measurementId || !url.searchParams.get('api_secret')) {
      return json(400, { error: 'measurement_id and api_secret are required' });
    }
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      try {
        const payload = JSON.parse(body || '{}');
        const bucket = received.get(measurementId) || [];
        for (const e of payload.events || []) {
          bucket.push({ client_id: payload.client_id, name: e.name,
            params: e.params || {}, receivedAt: new Date().toISOString() });
        }
        received.set(measurementId, bucket);
        // the real Measurement Protocol answers 204 with no body
        res.writeHead(204); res.end();
      } catch {
        json(400, { error: 'unparseable payload' });
      }
    });
    return;
  }

  if (req.method === 'GET' && url.pathname === '/events') {
    return json(200, received.get(url.searchParams.get('measurement_id')) || []);
  }

  json(404, { error: 'not found' });
});

server.listen(PORT, () => console.log(`mock-analytics listening on ${PORT}`));
