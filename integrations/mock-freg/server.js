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
 *
 * Matching normalizes the name (digits stripped, case folded) because the e2e
 * suites mint personas like "Paula Payer1784…". In-memory; a seam target — the
 * real adapter points at Skatteetaten or a distributor, config only.
 */
'use strict';

const http = require('http');

const PORT = process.env.PORT || 8080;

// The registered residents. Keyed by normalized name; kai matches his live
// party address so the demo's green path is real. sigrid is the protected
// (kode 6/7) resident: the registry answers nothing about her, ever.
const RESIDENTS = {
  'kai kunde': { street1: 'Storgata 1', postCode: '0150', city: 'Oslo', movedDate: '2019-06-01' },
  'paula payer': { street1: 'Slottsbakken 7', postCode: '0151', city: 'Oslo', movedDate: '2015-03-15' },
  'wilma payer': { street1: 'Slottsbakken 7', postCode: '0151', city: 'Oslo', movedDate: '2015-03-15' },
  'sonny son': { street1: 'Slottsbakken 7', postCode: '0151', city: 'Oslo', movedDate: '2015-03-15' },
  'sigrid skjermet': { protected: true },
};

const norm = (s) => String(s || '').replace(/\d+/g, '').trim().toLowerCase().replace(/\s+/g, ' ');
const normAddr = (a) => ({
  street1: norm(a && a.street1),
  postCode: String((a && a.postCode) || '').replace(/\s/g, ''),
  city: norm(a && a.city),
});

const server = http.createServer((req, res) => {
  const send = (code, body) => {
    const data = Buffer.from(JSON.stringify(body));
    res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': data.length });
    res.end(data);
  };

  if (req.method === 'GET' && req.url === '/health') return send(200, { status: 'UP' });

  if (req.method === 'POST' && req.url === '/folkeregisteret/api/personer/match') {
    if (!req.headers.authorization) return send(401, { error: 'credential required' });
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      let body;
      try { body = JSON.parse(raw); } catch { return send(400, { error: 'invalid JSON' }); }
      const who = norm(body.name);
      const resident = RESIDENTS[who];
      // Unknown and protected answer identically: the register holds nothing
      // it may share. A CSR must not be able to tell the difference.
      if (!resident || resident.protected) {
        console.log(`[mock-freg] ${who || '(no name)'} -> no_data`);
        return send(200, { result: 'no_data' });
      }
      const claimed = normAddr(body.address);
      const registered = normAddr(resident);
      const match = claimed.street1 === registered.street1
        && claimed.postCode === registered.postCode
        && claimed.city === registered.city;
      console.log(`[mock-freg] ${who} -> ${match ? 'match' : 'mismatch'}`);
      return send(200, {
        result: match ? 'match' : 'mismatch',
        registeredAddress: {
          street1: resident.street1, postCode: resident.postCode, city: resident.city, country: 'NO',
        },
        movedDate: resident.movedDate,
      });
    });
    return;
  }

  send(404, { error: 'not found' });
});

server.listen(PORT, () => console.log(`[mock-freg] Folkeregisteret mock on :${PORT}`));
