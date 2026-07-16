/*
 * Mock invoice-distribution partner — stands in for the Peppol access
 * point / print house an operator contracts: POST /invoices takes a
 * finished bill (EHF/Peppol/CII XML for the e-invoice channel, a base64
 * PDF for the print channel) with the tenant's token. It VALIDATES what
 * a real access point would: an 'ehf' payload must carry the EN 16931 /
 * Peppol BIS customization it claims. GET /invoices lets the suites see
 * what actually left the building.
 *
 * Deliberately in-memory: a demo seam target, not a product.
 */
'use strict';

const http = require('http');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;
const EHF_CUSTOMIZATION = 'urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0';

/** [{billNo, format, channel, recipient, contentType, payload, token, receivedAt}] */
const invoices = [];

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const json = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };

  if (req.method === 'GET' && url.pathname === '/health') {
    return json(200, { status: 'UP' });
  }

  if (req.method === 'POST' && url.pathname === '/invoices') {
    const auth = req.headers.authorization || '';
    if (!auth.startsWith('Bearer ') || auth.length <= 'Bearer '.length) {
      return json(401, { error: 'access token required' });
    }
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      try {
        const inv = JSON.parse(body || '{}');
        if (!inv.billNo || !inv.payload) {
          return json(400, { error: 'billNo and payload are required' });
        }
        if (inv.format === 'ehf' && !String(inv.payload).includes(EHF_CUSTOMIZATION)) {
          return json(422, { error: 'an EHF invoice must declare the Peppol BIS 3.0 customization' });
        }
        if (inv.format === 'ehf' && !String(inv.payload).includes('<cbc:PaymentID>')) {
          return json(422, { error: 'EHF (NO-R rules) wants a payment reference' });
        }
        invoices.push({ ...inv, token: auth.slice('Bearer '.length),
          receivedAt: new Date().toISOString() });
        json(202, { accepted: inv.billNo });
      } catch {
        json(400, { error: 'unparseable payload' });
      }
    });
    return;
  }

  if (req.method === 'GET' && url.pathname === '/invoices') {
    const billNo = url.searchParams.get('billNo');
    return json(200, billNo ? invoices.filter((i) => i.billNo === billNo) : invoices);
  }

  json(404, { error: 'not found' });
});

server.listen(PORT, () => console.log(`mock-distribution listening on ${PORT}`));
