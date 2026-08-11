/*
 * Mock Bring/Posten — the SECOND carrier, and the one that speaks PICKUP POINTS
 * (a first-class Nordic delivery method). Shaped after Bring's developer wire
 * (developer.bring.com):
 *
 *   POST /booking/api/booking                 -> book a parcel (consignment number)
 *   GET  /pickuppoint/{country}/postalCode/{pc}.json  -> nearby pickup points
 *   GET  /shipments/{tn}/tracking             -> status (parity with Helthjem)
 *
 * Like the Helthjem mock it ALSO fires a delivery callback so a parcel lands on
 * its own for the demo; a real Bring adapter would poll. In-memory; a seam target.
 */
'use strict';

const http = require('http');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;
const DELIVER_AFTER_MS = parseInt(process.env.DELIVER_AFTER_MS || '15000', 10);
const CARRIER = process.env.CARRIER_NAME || 'Posten/Bring';
const PREFIX = process.env.TRACK_PREFIX || 'BRG'; // consignment prefix (PN for PostNord)
const shipments = new Map();

function post(urlStr, payload) {
  try {
    const u = new URL(urlStr);
    const data = Buffer.from(JSON.stringify(payload));
    const req = http.request({
      hostname: u.hostname, port: u.port || 80, path: u.pathname,
      method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': data.length },
    }, (res) => { res.on('data', () => {}); res.on('end', () => {}); });
    req.on('error', (e) => console.log(`[mock-bring] callback failed: ${e.message}`));
    req.write(data); req.end();
  } catch (e) { console.log(`[mock-bring] bad callback url: ${e.message}`); }
}

function deliver(tn) {
  const s = shipments.get(tn);
  if (!s || s.status === 'DELIVERED') return;
  s.status = 'DELIVERED'; s.carrierStatus = 'levert';
  console.log(`[mock-bring] consignment ${tn} DELIVERED`);
  if (s.callbackUrl) {
    post(s.callbackUrl, {
      carrier: CARRIER, carrierShipmentId: s.carrierShipmentId, trackingNumber: tn,
      shippingOrderId: s.shippingOrderId, tenantId: s.tenantId,
      status: 'DELIVERED', carrierStatus: 'levert',
    });
  }
}

// A few plausible pickup points near any postcode (post office, grocery, locker).
function pickupPoints(postcode) {
  const p = String(postcode || '').trim();
  return [
    { id: `${p}-3001`, name: 'Meny ' + p, address: `Storgata 1, ${p} Oslo`, openingHours: 'Mon–Sun 07–23' },
    { id: `${p}-3002`, name: 'Posten ' + p, address: `Kirkeveien 12, ${p} Oslo`, openingHours: 'Mon–Fri 09–18' },
    { id: `${p}-3003`, name: 'Pakkeboks (locker) ' + p, address: `Jernbanetorget, ${p} Oslo`, openingHours: '24/7' },
  ];
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const send = (code, body) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };

  // Pickup Point API
  const pp = url.pathname.match(/^\/pickuppoint\/([^/]+)\/postalCode\/([^/]+?)(?:\.json)?$/);
  if (req.method === 'GET' && pp) {
    return send(200, { pickupPoint: pickupPoints(pp[2]) });
  }
  const trackMatch = url.pathname.match(/^\/shipments\/([^/]+)\/tracking$/);
  if (req.method === 'GET' && trackMatch) {
    const s = shipments.get(trackMatch[1]);
    if (!s) return send(404, { error: 'unknown consignment' });
    return send(200, { trackingNumber: s.trackingNumber, status: s.status, carrierStatus: s.carrierStatus });
  }

  let raw = '';
  req.on('data', (c) => { raw += c; });
  req.on('end', () => {
    let body = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch { return send(400, { error: 'bad json' }); }

    // Booking API
    if (req.method === 'POST' && url.pathname === '/booking/api/booking') {
      const trackingNumber = PREFIX + Math.random().toString().slice(2, 12);
      const carrierShipmentId = 'con-' + Math.random().toString(36).slice(2, 10);
      const s = {
        trackingNumber, carrierShipmentId,
        shippingOrderId: body.shippingOrderId || null,
        tenantId: body.tenantId || 'genalpha',
        callbackUrl: body.callbackUrl || null,
        status: 'CREATED', carrierStatus: 'booket',
      };
      shipments.set(trackingNumber, s);
      setTimeout(() => deliver(trackingNumber), DELIVER_AFTER_MS);
      console.log(`[mock-bring] booked ${CARRIER} consignment ${trackingNumber} for shippingOrder ${s.shippingOrderId}`);
      return send(200, {
        carrier: CARRIER, carrierShipmentId, trackingNumber,
        trackingUrl: `https://sporing.bring.no/${trackingNumber}`,
        labelRef: `label-${carrierShipmentId}.pdf`,
      });
    }

    if (url.pathname === '/health' || url.pathname === '/') return send(200, { ok: true, carrier: CARRIER });
    return send(404, { error: 'not found', path: url.pathname });
  });
});

server.listen(PORT, () => console.log(`[mock-bring] ${CARRIER}-shaped carrier on :${PORT}`));
