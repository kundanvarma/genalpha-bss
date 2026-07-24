#!/usr/bin/env node
/* mock-legacy-bss — a deliberately OLD-SHAPED stack for the overlay proof:
 * envelope-wrapped JSON, custNo-style keys, a fulfilment queue and an
 * incident list. genalpha wraps THIS through per-tenant seams; suite #67
 * proves the overlay against it. */
const http = require('http');

const products = [
  { PROD_CD: 'LGCY-DSL-20', PROD_NM: 'Heritage DSL 20', PRICE_AMT: '24.90', CURR_CD: 'EUR' },
  { PROD_CD: 'LGCY-VOICE', PROD_NM: 'Heritage Voice Line', PRICE_AMT: '9.90', CURR_CD: 'EUR' },
];
const workOrders = [];
const incidents = [
  { INC_NO: 'INC-9001', SUMMARY: 'Legacy DSL port flapping at DSLAM-041', STATUS: 'OPEN',
    OPENED_TS: new Date(Date.now() - 3600e3).toISOString() },
];

const server = http.createServer((req, res) => {
  const send = (o) => { res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(o)); };
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/api/getProductList') {
    return send({ resultSet: { row: products } }); // the envelope of another era
  }
  if (url.pathname === '/api/createWorkOrder' && req.method === 'POST') {
    let b = ''; req.on('data', (c) => { b += c; });
    req.on('end', () => {
      const wo = { WO_NO: 'WO-' + (1000 + workOrders.length), ...JSON.parse(b || '{}'),
        CREATED_TS: new Date().toISOString() };
      workOrders.push(wo); send({ result: { status: 'ACCEPTED', WO_NO: wo.WO_NO } });
    });
    return;
  }
  if (url.pathname === '/api/listWorkOrders') return send({ resultSet: { row: workOrders } });
  if (url.pathname === '/api/listIncidents') return send({ resultSet: { row: incidents } });
  if (url.pathname === '/api/closeIncident' && req.method === 'POST') {
    let b = ''; req.on('data', (c) => { b += c; });
    req.on('end', () => {
      const { INC_NO, RESOLUTION } = JSON.parse(b || '{}');
      const inc = incidents.find((i) => i.INC_NO === INC_NO);
      if (!inc) return send({ result: { status: 'NOT_FOUND' } });
      inc.STATUS = 'CLOSED'; inc.RESOLUTION = RESOLUTION;
      send({ result: { status: 'CLOSED', INC_NO } });
    });
    return;
  }
  if (url.pathname === '/api/reopenIncident' && req.method === 'POST') {
    incidents[0].STATUS = 'OPEN'; delete incidents[0].RESOLUTION; // suite reset hook
    return send({ result: { status: 'OPEN' } });
  }
  if (url.pathname === '/health') return send({ ok: true });
  res.writeHead(404); res.end();
});
server.listen(8080, () => console.log('mock-legacy-bss on :8080 — the estate being wrapped'));
