/*
 * Mock FSM — stands in for the operator's OWN workforce-management system
 * (TM Forum ODA component TMFC046 "Workforce Management"), which exposes
 * TMF646 Appointment Management: searchTimeSlot, appointment create/cancel.
 * Real systems behind the SAME seam: ServiceNow FSM for Telecommunications
 * (speaks TMF646 natively), Oracle Field Service (capacity/showBookingGrid),
 * Salesforce Field Service (appointment booking / getSlots) via named adapters.
 *
 * The BSS never pretends to own this calendar: it asks, shows what it is
 * told, books here and stores the returned id. This mock keeps its own crews
 * (per work zone by postcode prefix) and quota per window, so capacity here
 * is visibly NOT the BSS roster: windows start on the half hour.
 *
 * Deliberately in-memory: a demo seam target, not a product.
 */
'use strict';

const http = require('http');
const { URL } = require('url');
const crypto = require('crypto');

const PORT = process.env.PORT || 8080;
const TZ_OFFSET = process.env.TZ_OFFSET || '-04:00'; // Georgetown
const DAYS_AHEAD = parseInt(process.env.DAYS_AHEAD || '5', 10);
const STARTS = (process.env.WINDOW_STARTS || '08:30,12:30,15:30').split(',');
// crews by zone: Guyana's 7-digit postcode starts with the REGION digit
// (4 = Demerara-Mahaica incl. Georgetown, 7 = Cuyuni-Mazaruni incl. Bartica) -> quota per window
const ZONES = [
  { prefix: '7', name: 'Bartica', quota: 1 },
  { prefix: '4', name: 'Georgetown', quota: 2 },
  { prefix: '', name: 'default', quota: 2 },
];

const appointments = new Map(); // id -> appointment

function zoneOf(place) {
  const pc = String((place && (place.postCode || place.postcode)) || '');
  return ZONES.find((z) => pc.startsWith(z.prefix));
}

function windows() {
  const out = [];
  const today = new Date();
  for (let d = 1; d <= DAYS_AHEAD + 2 && out.length < DAYS_AHEAD * STARTS.length; d++) {
    const day = new Date(today.getTime() + d * 86400000);
    if (day.getUTCDay() === 0) continue; // no Sunday crews
    const ymd = day.toISOString().slice(0, 10);
    for (const t of STARTS) {
      const [hh, mm] = t.split(':').map((x) => parseInt(x, 10));
      const start = `${ymd}T${t}:00${TZ_OFFSET}`;
      const end = `${ymd}T${String(hh + 2).padStart(2, '0')}:${String(mm).padStart(2, '0')}:00${TZ_OFFSET}`; // two-hour windows, same day
      out.push({ start, end });
    }
  }
  return out;
}

function booked(start, zone) {
  return [...appointments.values()].filter((a) => a.status === 'confirmed'
    && new Date(a.validFor.startDateTime).getTime() === new Date(start).getTime()
    && a.zone === zone.name).length;
}

function readJson(req) {
  return new Promise((resolve) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => { try { resolve(body ? JSON.parse(body) : {}); } catch { resolve({}); } });
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const send = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(body === undefined ? '' : JSON.stringify(body));
  };
  if (req.method === 'GET' && url.pathname === '/health') return send(200, { status: 'UP', system: 'mock-fsm (TMF646)' });

  if (req.method === 'POST' && url.pathname.endsWith('/searchTimeSlot')) {
    const body = await readJson(req);
    const zone = zoneOf(body.relatedPlace);
    const wanted = Array.isArray(body.requestedTimeSlot) ? body.requestedTimeSlot : [];
    const inWanted = (s) => !wanted.length || wanted.some((w) => {
      const vf = w.validFor || w; const from = new Date(vf.startDateTime).getTime(); const to = new Date(vf.endDateTime).getTime();
      const t = new Date(s).getTime(); return t >= from && t < to;
    });
    const availableTimeSlot = windows().filter((w) => inWanted(w.start)).map((w) => {
      const remaining = zone.quota - booked(w.start, zone);
      return remaining > 0 ? { validFor: { startDateTime: w.start, endDateTime: w.end }, remaining, '@type': 'TimeSlot' } : null;
    }).filter(Boolean);
    console.log(`[mock-fsm] searchTimeSlot zone=${zone.name} category=${body.category || '-'} -> ${availableTimeSlot.length} windows`);
    return send(201, { id: crypto.randomUUID(), '@type': 'SearchTimeSlot', status: 'done',
      searchDate: new Date().toISOString(), searchResult: availableTimeSlot.length ? 'success' : 'no availability',
      relatedPlace: body.relatedPlace, availableTimeSlot });
  }

  if (req.method === 'POST' && url.pathname.endsWith('/appointment')) {
    const body = await readJson(req);
    const vf = body.validFor || {};
    if (!vf.startDateTime || !vf.endDateTime) return send(400, { code: '400', reason: 'validFor required' });
    const zone = zoneOf(body.relatedPlace);
    if (!windows().some((w) => new Date(w.start).getTime() === new Date(vf.startDateTime).getTime())) {
      return send(409, { code: '409', reason: 'no crew window at that time' });
    }
    if (booked(vf.startDateTime, zone) >= zone.quota) return send(409, { code: '409', reason: 'window quota exhausted' });
    const id = 'FSM-' + crypto.randomUUID().slice(0, 8).toUpperCase();
    const appt = { id, href: `/appointment/${id}`, '@type': 'Appointment', status: 'confirmed', category: body.category || null,
      description: body.description || null, externalId: body.externalId || null, validFor: vf, relatedPlace: body.relatedPlace || null,
      relatedParty: body.relatedParty || [], relatedEntity: body.relatedEntity || [], zone: zone.name,
      creationDate: new Date().toISOString() };
    appointments.set(id, appt);
    console.log(`[mock-fsm] booked ${id} ${vf.startDateTime} zone=${zone.name}`);
    return send(201, appt);
  }

  const m = /\/appointment\/([^/]+)$/.exec(url.pathname);
  if (m) {
    const appt = appointments.get(m[1]);
    if (!appt) return send(404, { code: '404', reason: 'no such appointment' });
    if (req.method === 'GET') return send(200, appt);
    if (req.method === 'PATCH') {
      const body = await readJson(req);
      if (body.status === 'cancelled') { appt.status = 'cancelled'; appt.lastUpdate = new Date().toISOString(); console.log(`[mock-fsm] cancelled ${appt.id}`); }
      return send(200, appt);
    }
  }
  if (req.method === 'GET' && url.pathname.endsWith('/appointment')) return send(200, [...appointments.values()]);
  send(404, { code: '404', reason: 'not found' });
});

server.listen(PORT, () => console.log(`[mock-fsm] TMF646 workforce stand-in on :${PORT} (${TZ_OFFSET}, windows ${STARTS.join('/')})`));
