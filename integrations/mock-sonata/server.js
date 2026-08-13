/*
 * Mock owner OSS — MEF LSO Sonata, the inter-operator ordering face a fibre owner
 * exposes to an access seeker. Shaped after the Sonata Service Ordering API:
 *
 *   POST /mefApi/serviceOrdering/v1/serviceOrder   -> accept an access-seeker order
 *                                                     (returns acknowledged + an id)
 *
 * Like the carrier mocks, it then fires an ASYNC callback so the access line comes
 * up on its own — the honest wholesale flow (the seeker WAITS for the owner, then
 * activates on the owner's notification). A real adapter would receive the same
 * notification (or poll). In-memory; a seam target.
 */
'use strict';

const http = require('http');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;
const ACTIVATE_AFTER_MS = parseInt(process.env.ACTIVATE_AFTER_MS || '5000', 10);
const OWNER = process.env.OWNER_NAME || 'owner';
const orders = new Map();
let seq = 1000;

function post(urlStr, payload) {
  try {
    const u = new URL(urlStr);
    const data = Buffer.from(JSON.stringify(payload));
    const req = http.request({
      hostname: u.hostname, port: u.port || 80, path: u.pathname + (u.search || ''),
      method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': data.length },
    }, (res) => { res.on('data', () => {}); res.on('end', () => {}); });
    req.on('error', (e) => console.log(`[mock-sonata] callback failed: ${e.message}`));
    req.write(data); req.end();
  } catch (e) { console.log(`[mock-sonata] bad callback url: ${e.message}`); }
}

function activate(id) {
  const o = orders.get(id);
  if (!o || o.state === 'completed') return;
  o.state = 'completed';
  console.log(`[mock-sonata] ${OWNER} order ${id} COMPLETED -> notifying ${o.callbackUrl}`);
  if (o.callbackUrl) {
    post(o.callbackUrl, { sonataOrderId: id, state: 'completed', accessOwner: OWNER });
  }
}

const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    const path = req.url.split('?')[0];
    if (req.method === 'POST' && /\/serviceOrder$/.test(path)) {
      let dto = {};
      try { dto = JSON.parse(body || '{}'); } catch { /* ignore */ }
      const id = `SON-${OWNER}-${seq++}`;
      const order = {
        id, state: 'acknowledged', buyerRef: dto.externalId || null,
        callbackUrl: dto.callbackUrl || null,
      };
      orders.set(id, order);
      console.log(`[mock-sonata] ${OWNER} accepted access-seeker order ${id} (buyerRef ${order.buyerRef})`);
      setTimeout(() => activate(id), ACTIVATE_AFTER_MS);
      res.writeHead(201, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ id, state: 'acknowledged', '@type': 'ServiceOrder' }));
      return;
    }
    if (req.method === 'GET' && path === '/healthz') {
      res.writeHead(200); res.end('ok'); return;
    }
    res.writeHead(404); res.end('not found');
  });
});
server.listen(PORT, () => console.log(`[mock-sonata] ${OWNER} OSS listening on ${PORT} (activate after ${ACTIVATE_AFTER_MS}ms)`));
