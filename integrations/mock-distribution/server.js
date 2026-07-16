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
// where the buyer's Peppol Invoice Response is forwarded (the operator's
// billing webhook) — carrying the same token the invoice arrived with
const RESPONSE_WEBHOOK_URL = process.env.RESPONSE_WEBHOOK_URL || '';

/** [{billNo, format, channel, recipient, contentType, payload, token, receivedAt}] */
const invoices = [];
/** Chaos switch for the suites: fail the next N ingestions with a 503,
 * the way a real access point has a bad afternoon. */
let outage = 0;

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const json = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };

  if (req.method === 'GET' && url.pathname === '/health') {
    return json(200, { status: 'UP' });
  }

  if (req.method === 'POST' && url.pathname === '/outage') {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      outage = Number((JSON.parse(body || '{}')).count) || 0;
      json(200, { outage });
    });
    return;
  }

  if (req.method === 'POST' && url.pathname === '/invoices') {
    if (outage > 0) {
      outage -= 1;
      return json(503, { error: 'the access point is having a bad afternoon — try again' });
    }
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

  // The BUYER ANSWERS: POST /invoices/{billNo}/respond {code, note} makes
  // the access point mint a Peppol Invoice Response (UBL ApplicationResponse,
  // UNECE 4343 code: AB/IP/AP/RE/CA/UQ/PD) and forward it to the operator's
  // webhook with the same tenant token the invoice arrived under.
  const respond = url.pathname.match(/^\/invoices\/([^/]+)\/respond$/);
  if (req.method === 'POST' && respond) {
    const billNo = decodeURIComponent(respond[1]);
    const invoice = invoices.find((i) => i.billNo === billNo && i.channel === 'einvoice');
    if (!invoice) return json(404, { error: 'no e-invoice with that bill number' });
    if (!RESPONSE_WEBHOOK_URL) return json(503, { error: 'no response webhook configured' });
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      const { code = 'AP', note = '' } = JSON.parse(body || '{}');
      const xml = `<?xml version="1.0" encoding="UTF-8"?>
<ApplicationResponse xmlns="urn:oasis:names:specification:ubl:schema:xsd:ApplicationResponse-2"
    xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
    xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
  <cbc:CustomizationID>urn:fdc:peppol.eu:poacc:trns:invoice_response:3</cbc:CustomizationID>
  <cbc:ProfileID>urn:fdc:peppol.eu:poacc:bis:invoice_response:3</cbc:ProfileID>
  <cac:DocumentResponse>
    <cac:Response><cbc:ResponseCode>${code}</cbc:ResponseCode>${note
      ? `<cbc:Description>${note.replace(/&/g, '&amp;').replace(/</g, '&lt;')}</cbc:Description>` : ''}</cac:Response>
    <cac:DocumentReference><cbc:ID>${billNo}</cbc:ID></cac:DocumentReference>
  </cac:DocumentResponse>
</ApplicationResponse>`;
      const target = new URL(RESPONSE_WEBHOOK_URL);
      const fwd = http.request({
        hostname: target.hostname, port: target.port, path: target.pathname, method: 'POST',
        headers: {
          'Content-Type': 'application/xml',
          'Content-Length': Buffer.byteLength(xml),
          'X-Distribution-Token': invoice.token,
        },
      }, (r) => { r.resume(); json(202, { forwarded: billNo, code }); });
      fwd.on('error', () => json(502, { error: 'operator webhook unreachable' }));
      fwd.end(xml);
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
