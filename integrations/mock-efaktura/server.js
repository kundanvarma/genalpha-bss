/*
 * Mock eFaktura-style e-invoice rail — the consumer e-invoice seam target
 * for NO. Two faces, like the real thing (Mastercard Payment Services,
 * coordinated by Bits): the ALIAS LOOKUP the biller runs per send (since
 * the 2022 "Ja takk til alle" regime the consent registry is the bank's,
 * not the biller's — a miss means "no consent, use your next channel"),
 * and the REQUEST-FOR-PAYMENT drop keyed on the KID.
 *
 *   GET  /health                 -> probe
 *   POST /alias/lookup {name?, email?, phone?}
 *        -> {aliasRef} for a registered identity, 404 {result:'no_alias'}
 *           for everyone else — the miss IS the answer, by design
 *   POST /users {name, email?}   -> test/demo injector: registers an
 *        identity as an e-invoice user (mirrors mock-freg's /hendelser)
 *   POST /rfp                    -> stores the request-for-payment
 *        (requires kid + amount; this is what a bank app would show)
 *   GET  /rfp?kid=NNN            -> stored RFPs for e2e asserts
 *
 * Name matching strips digits + case-folds (e2e-minted personas look like
 * "Paula Payer1756..."). In-memory; a seam target — the real adapter is a
 * certified partner integration, config only.
 */
'use strict';

const http = require('http');

const PORT = process.env.PORT || 8080;

// Seeded e-invoice users: paula + kai said "ja takk" long ago.
const USERS = [
  { name: 'Paula Payer', aliasRef: 'alias-paula-001' },
  { name: 'Kai Kunde', aliasRef: 'alias-kai-002' },
];

const RFPS = [];

const norm = (s) => String(s || '').replace(/\d+/g, '').trim().toLowerCase().replace(/\s+/g, ' ');

const userOf = (body) => USERS.find((u) =>
  (body.name && norm(u.name) === norm(body.name))
  || (body.email && u.email && norm(u.email) === norm(body.email)));

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

  if (req.method === 'POST' && url.pathname === '/alias/lookup') {
    return withBody((body) => {
      const user = userOf(body);
      if (!user) return send(404, { result: 'no_alias' });
      return send(200, { aliasRef: user.aliasRef });
    });
  }

  if (req.method === 'POST' && url.pathname === '/users') {
    return withBody((body) => {
      if (!body.name) return send(400, { error: 'name required' });
      const existing = userOf(body);
      if (existing) return send(200, existing);
      const user = { name: body.name, email: body.email, aliasRef: 'alias-' + (USERS.length + 1) + '-' + Date.now() };
      USERS.push(user);
      return send(201, user);
    });
  }

  if (req.method === 'POST' && url.pathname === '/rfp') {
    return withBody((body) => {
      if (!body.kid || !body.amount) return send(400, { error: 'kid and amount required' });
      const rfp = { ...body, receivedAt: new Date().toISOString() };
      RFPS.push(rfp);
      return send(201, rfp);
    });
  }

  if (req.method === 'GET' && url.pathname === '/rfp') {
    const kid = url.searchParams.get('kid');
    return send(200, RFPS.filter((r) => !kid || r.kid === kid));
  }

  return send(404, { error: 'not found' });
});

server.listen(PORT, () => console.log(`mock-efaktura on :${PORT}`));
