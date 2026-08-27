/*
 * Mock Digipost-style digital mailbox — the fallback channel when the
 * e-invoice rail has no consent. Letters in (with optional invoice
 * metadata enabling pay-from-mailbox), a recipient lookup for the
 * curious, everything queryable for e2e asserts.
 *
 *   GET  /health                    -> probe
 *   POST /letters {partyRef, subject, content, invoiceMeta?}
 *        -> 201 stores the letter (the provider handles addressing +
 *           print-fallback for non-users; the BSS's job is honest content)
 *   GET  /letters?partyRef=X        -> stored letters for e2e asserts
 *   POST /recipients/lookup {name}  -> {user: true|false} (seeded: wilma)
 *   POST /recipients {name}         -> test injector: register a mailbox user
 *
 * In-memory; a seam target — a second driver (e-Boks style) is the same
 * port with different config.
 */
'use strict';

const http = require('http');

const PORT = process.env.PORT || 8080;

// Seeded mailbox users: wilma reads her mail digitally.
const RECIPIENTS = ['Wilma Payer'];
const LETTERS = [];

const norm = (s) => String(s || '').replace(/\d+/g, '').trim().toLowerCase().replace(/\s+/g, ' ');

const server = http.createServer((req, res) => {
  const send = (code, body) => {
    const data = Buffer.from(JSON.stringify(body));
    res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': data.length });
    res.end(data);
  };
  const url = new URL(req.url, 'http://localhost');
  const withBody = (fn) => {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      let body;
      try { body = JSON.parse(raw || '{}'); } catch { return send(400, { error: 'invalid JSON' }); }
      fn(body);
    });
  };

  if (req.method === 'GET' && url.pathname === '/health') return send(200, { status: 'UP' });

  if (req.method === 'POST' && url.pathname === '/letters') {
    return withBody((body) => {
      if (!body.partyRef || !body.subject || !body.content) {
        return send(400, { error: 'partyRef, subject and content required' });
      }
      const letter = { ...body, receivedAt: new Date().toISOString() };
      LETTERS.push(letter);
      return send(201, letter);
    });
  }

  if (req.method === 'GET' && url.pathname === '/letters') {
    const partyRef = url.searchParams.get('partyRef');
    return send(200, LETTERS.filter((l) => !partyRef || l.partyRef === partyRef));
  }

  if (req.method === 'POST' && url.pathname === '/recipients/lookup') {
    return withBody((body) =>
      send(200, { user: RECIPIENTS.some((r) => norm(r) === norm(body.name)) }));
  }

  if (req.method === 'POST' && url.pathname === '/recipients') {
    return withBody((body) => {
      if (!body.name) return send(400, { error: 'name required' });
      if (!RECIPIENTS.some((r) => norm(r) === norm(body.name))) RECIPIENTS.push(body.name);
      return send(201, { name: body.name });
    });
  }

  return send(404, { error: 'not found' });
});

server.listen(PORT, () => console.log(`mock-digipost on :${PORT}`));
