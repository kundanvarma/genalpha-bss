/*
 * Mock Folkeregisteret (Freg) — the national-registry seam target for NO.
 * Shaped after the shared-services idea of Skatteetaten's person-match: the
 * consumer submits a person + a claimed address, the registry answers whether
 * that person is registered at it. The "Privat virksomhet" rights package
 * returns non-confidential data only — and a PROTECTED person (kode 6/7)
 * answers no_data, indistinguishable from not-found, BY DESIGN.
 *
 *   GET  /health                          -> probe (console Test button)
 *   POST /folkeregisteret/api/personer/match
 *        {name, birthDate?, address:{street1,postCode,city}}
 *        -> {result: match|mismatch|no_data, registeredAddress?, movedDate?}
 *   GET  /folkeregisteret/api/personer/<personRef>
 *        -> {result: ok, name, registeredAddress, movedDate}
 *           protected/unknown -> {result: no_data}  (indistinguishable)
 *   GET  /hendelser?seq=<n>               -> the registry EVENT FEED: all
 *        events with seq > n, oldest first, [{seq, type, personRef, payload}]
 *        type: addressChange | nameChange | death. Consumers store their own
 *        cursor (last processed seq) and poll — the real thing is the same
 *        shape behind Maskinporten auth.
 *   POST /hendelser {type, personRef, payload}
 *        -> test/demo injector: appends the event AND applies it to the
 *           resident (an addressChange moves them, so the follow-up person
 *           fetch returns the new address — like the real registry would).
 *
 * Matching normalizes the name (digits stripped, case folded) because the e2e
 * suites mint personas like "Paula Payer1784…". In-memory; a seam target — the
 * real adapter points at Skatteetaten or a distributor, config only.
 */
'use strict';

const http = require('http');

const PORT = process.env.PORT || 8080;

// The registered residents. kai matches his live party address so the demo's
// green path is real. sigrid is the protected (kode 6/7) resident: the
// registry answers nothing about her, ever. personRef is the stable person
// identifier a consumer stores to poll the event feed (fnr-shaped, fake).
const RESIDENTS = [
  { personRef: '01018012345', name: 'Kai Kunde', street1: 'Storgata 1', postCode: '0150', city: 'Oslo', movedDate: '2019-06-01' },
  { personRef: '02027512346', name: 'Paula Payer', street1: 'Slottsbakken 7', postCode: '0151', city: 'Oslo', movedDate: '2015-03-15' },
  { personRef: '03037812347', name: 'Wilma Payer', street1: 'Slottsbakken 7', postCode: '0151', city: 'Oslo', movedDate: '2015-03-15' },
  { personRef: '10101012348', name: 'Sonny Son', street1: 'Slottsbakken 7', postCode: '0151', city: 'Oslo', movedDate: '2015-03-15' },
  { personRef: '04046912349', name: 'Sigrid Skjermet', protected: true },
];

// The event feed: strictly sequential, append-only. seq starts at 1.
const FEED = [];

const norm = (s) => String(s || '').replace(/\d+/g, '').trim().toLowerCase().replace(/\s+/g, ' ');
const normAddr = (a) => ({
  street1: norm(a && a.street1),
  postCode: String((a && a.postCode) || '').replace(/\s/g, ''),
  city: norm(a && a.city),
});

const byName = (name) => RESIDENTS.find((r) => norm(r.name) === norm(name));
const byRef = (ref) => RESIDENTS.find((r) => r.personRef === String(ref || ''));

const addressOf = (r) => ({ street1: r.street1, postCode: r.postCode, city: r.city, country: 'NO' });

const server = http.createServer((req, res) => {
  const send = (code, body) => {
    const data = Buffer.from(JSON.stringify(body));
    res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': data.length });
    res.end(data);
  };
  const url = new URL(req.url, 'http://localhost');

  if (req.method === 'GET' && url.pathname === '/health') return send(200, { status: 'UP' });

  if (req.method === 'POST' && url.pathname === '/folkeregisteret/api/personer/match') {
    if (!req.headers.authorization) return send(401, { error: 'credential required' });
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      let body;
      try { body = JSON.parse(raw); } catch { return send(400, { error: 'invalid JSON' }); }
      const resident = byName(body.name);
      // Unknown and protected answer identically: the register holds nothing
      // it may share. A CSR must not be able to tell the difference.
      if (!resident || resident.protected) {
        console.log(`[mock-freg] ${norm(body.name) || '(no name)'} -> no_data`);
        return send(200, { result: 'no_data' });
      }
      const claimed = normAddr(body.address);
      const registered = normAddr(resident);
      const match = claimed.street1 === registered.street1
        && claimed.postCode === registered.postCode
        && claimed.city === registered.city;
      console.log(`[mock-freg] ${norm(body.name)} -> ${match ? 'match' : 'mismatch'}`);
      return send(200, {
        result: match ? 'match' : 'mismatch',
        registeredAddress: addressOf(resident),
        movedDate: resident.movedDate,
      });
    });
    return;
  }

  // Person fetch by stable ref — the re-sync consumer's re-fetch after a feed
  // event. Protected and unknown are one and the same answer, BY DESIGN.
  if (req.method === 'GET' && url.pathname.startsWith('/folkeregisteret/api/personer/')) {
    if (!req.headers.authorization) return send(401, { error: 'credential required' });
    const ref = url.pathname.substring('/folkeregisteret/api/personer/'.length);
    const resident = byRef(ref);
    if (!resident || resident.protected) {
      console.log(`[mock-freg] person ${ref} -> no_data`);
      return send(200, { result: 'no_data' });
    }
    console.log(`[mock-freg] person ${ref} -> ok`);
    return send(200, {
      result: 'ok',
      name: resident.name,
      registeredAddress: addressOf(resident),
      movedDate: resident.movedDate,
    });
  }

  // The event feed: everything AFTER the caller's cursor, oldest first.
  if (req.method === 'GET' && url.pathname === '/hendelser') {
    if (!req.headers.authorization) return send(401, { error: 'credential required' });
    const after = Number(url.searchParams.get('seq') || 0);
    return send(200, FEED.filter((e) => e.seq > after));
  }

  // Test/demo injector: append a feed event and APPLY it to the resident so
  // re-fetch tells the same story the feed does.
  if (req.method === 'POST' && url.pathname === '/hendelser') {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      let body;
      try { body = JSON.parse(raw); } catch { return send(400, { error: 'invalid JSON' }); }
      const type = String(body.type || '');
      if (!['addressChange', 'nameChange', 'death'].includes(type)) {
        return send(400, { error: 'type must be addressChange|nameChange|death' });
      }
      const resident = byRef(body.personRef);
      if (!resident) return send(404, { error: 'unknown personRef' });
      const payload = body.payload || {};
      if (type === 'addressChange' && payload.address && !resident.protected) {
        resident.street1 = payload.address.street1 || resident.street1;
        resident.postCode = payload.address.postCode || resident.postCode;
        resident.city = payload.address.city || resident.city;
        resident.movedDate = payload.movedDate || new Date().toISOString().slice(0, 10);
      }
      if (type === 'nameChange' && payload.name && !resident.protected) {
        resident.name = String(payload.name);
      }
      if (type === 'death') {
        resident.deceased = true;
      }
      const event = { seq: FEED.length + 1, type, personRef: resident.personRef, payload };
      FEED.push(event);
      console.log(`[mock-freg] hendelse #${event.seq} ${type} ${resident.personRef}`);
      return send(201, event);
    });
    return;
  }

  send(404, { error: 'not found' });
});

server.listen(PORT, () => console.log(`[mock-freg] Folkeregisteret mock on :${PORT}`));
