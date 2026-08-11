/*
 * Mock Logistics — stands in for the parcel carrier an operator ships with.
 * Shaped after Helthjem's model (developer.helthjem.no): book a shipment, get
 * a parcel/tracking number and a PDF label, then track the parcel to delivery.
 * Real Norwegian carriers behind the SAME seam: Helthjem, Posten/Bring
 * (Booking + Event Cast), PostNord/Strålfors (Shipment v3 + OAuth2).
 *
 * The BSS never pretends to be the carrier: fulfilment (TMF700) books here at
 * dispatch, stores the returned tracking number on the shipping order, and the
 * carrier reports delivery. Helthjem is poll-style (fetch tracking); for demo
 * immediacy this mock ALSO fires a delivery callback so a parcel visibly lands
 * on its own — a real Helthjem adapter would poll getTracking instead.
 *
 * Deliberately in-memory: a demo seam target, not a product.
 */
'use strict';

const http = require('http');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;
// How long until a booked parcel is delivered (demo: fast). in_transit halfway.
const DELIVER_AFTER_MS = parseInt(process.env.DELIVER_AFTER_MS || '15000', 10);
const CARRIER = process.env.CARRIER_NAME || 'Helthjem';

const shipments = new Map(); // trackingNumber -> shipment

function post(urlStr, payload) {
  try {
    const u = new URL(urlStr);
    const data = Buffer.from(JSON.stringify(payload));
    const req = http.request({
      hostname: u.hostname, port: u.port || 80, path: u.pathname,
      method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': data.length },
    }, (res) => { res.on('data', () => {}); res.on('end', () => {}); });
    req.on('error', (e) => console.log(`[mock-logistics] callback failed: ${e.message}`));
    req.write(data); req.end();
  } catch (e) { console.log(`[mock-logistics] bad callback url: ${e.message}`); }
}

function advance(tn) {
  const s = shipments.get(tn);
  if (!s || s.status === 'DELIVERED' || s.status === 'CANCELLED') return;
  s.status = 'DELIVERED';
  s.carrierStatus = 'levert'; // Helthjem's word for "delivered"
  s.events.push({ status: 'DELIVERED', at: new Date().toISOString(), location: 'Recipient' });
  console.log(`[mock-logistics] parcel ${tn} DELIVERED`);
  if (s.callbackUrl) {
    post(s.callbackUrl, {
      carrier: CARRIER, carrierShipmentId: s.carrierShipmentId, trackingNumber: tn,
      shippingOrderId: s.shippingOrderId, tenantId: s.tenantId,
      status: 'DELIVERED', carrierStatus: 'levert',
    });
  }
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const send = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };
  let raw = '';
  req.on('data', (c) => { raw += c; });
  req.on('end', () => {
    let body = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch { return send(400, { error: 'bad json' }); }

    // Book a shipment (fulfilment calls this at dispatch). Helthjem returns a
    // parcel/tracking number + a label; we echo the caller's refs for callback.
    if (req.method === 'POST' && url.pathname === '/shipments') {
      const trackingNumber = 'HJ' + Math.random().toString().slice(2, 12);
      const carrierShipmentId = 'shp-' + Math.random().toString(36).slice(2, 10);
      const shipment = {
        trackingNumber, carrierShipmentId,
        shippingOrderId: body.shippingOrderId || null,
        tenantId: body.tenantId || 'genalpha',
        callbackUrl: body.callbackUrl || null,
        serviceLevel: body.serviceLevel || 'HOME_STANDARD',
        recipient: body.recipient || null,
        status: 'CREATED', carrierStatus: 'booket',
        events: [{ status: 'CREATED', at: new Date().toISOString(), location: 'Warehouse' }],
      };
      shipments.set(trackingNumber, shipment);
      // in_transit halfway, delivered at the end — a parcel that moves on its own
      setTimeout(() => {
        const s = shipments.get(trackingNumber);
        if (s && s.status === 'CREATED') {
          s.status = 'IN_TRANSIT'; s.carrierStatus = 'underveis';
          s.events.push({ status: 'IN_TRANSIT', at: new Date().toISOString(), location: 'Sorting centre Oslo' });
        }
      }, Math.max(1000, Math.floor(DELIVER_AFTER_MS / 2)));
      setTimeout(() => advance(trackingNumber), DELIVER_AFTER_MS);
      console.log(`[mock-logistics] booked ${CARRIER} parcel ${trackingNumber} for shippingOrder ${shipment.shippingOrderId}`);
      return send(201, {
        carrier: CARRIER, carrierShipmentId, trackingNumber,
        trackingUrl: `https://sporing.helthjem.no/${trackingNumber}`,
        labelRef: `label-${carrierShipmentId}.pdf`,
        estimatedDelivery: new Date(Date.now() + DELIVER_AFTER_MS).toISOString(),
      });
    }

    // Poll tracking (the poll-style path a real Helthjem adapter would use).
    const trackMatch = url.pathname.match(/^\/shipments\/([^/]+)\/tracking$/);
    if (req.method === 'GET' && trackMatch) {
      const s = shipments.get(trackMatch[1]);
      if (!s) return send(404, { error: 'unknown tracking number' });
      return send(200, {
        trackingNumber: s.trackingNumber, status: s.status,
        carrierStatus: s.carrierStatus, events: s.events,
      });
    }

    // Fetch the label PDF (stub — returns a reference, not real bytes).
    const labelMatch = url.pathname.match(/^\/shipments\/([^/]+)\/label$/);
    if (req.method === 'GET' && labelMatch) {
      const s = shipments.get(labelMatch[1]);
      if (!s) return send(404, { error: 'unknown shipment' });
      return send(200, { labelRef: `label-${s.carrierShipmentId}.pdf`, format: 'PDF' });
    }

    if (url.pathname === '/health' || url.pathname === '/') return send(200, { ok: true, carrier: CARRIER });
    return send(404, { error: 'not found' });
  });
});

server.listen(PORT, () => console.log(`[mock-logistics] ${CARRIER}-shaped carrier on :${PORT} (deliver after ${DELIVER_AFTER_MS}ms)`));
