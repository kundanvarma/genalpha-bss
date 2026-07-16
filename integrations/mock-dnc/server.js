/*
 * Mock reservation register (Norway: Reservasjonsregisteret) — the
 * do-not-call wash a lawful outbound channel checks BEFORE selling.
 * GET /check?phone= answers {reserved}; numbers ending 9999 are seeded
 * reserved so suites have a citizen who said no. POST /outage {count}
 * makes the register unreachable for N checks — the operator's wash is
 * fail-closed, and that has to be provable.
 */
'use strict';
const http = require('http');
const { URL } = require('url');
const PORT = process.env.PORT || 8080;
let outage = 0;

http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const json = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };
  if (req.method === 'GET' && url.pathname === '/health') return json(200, { status: 'UP' });
  if (req.method === 'POST' && url.pathname === '/outage') {
    let b = ''; req.on('data', (c) => { b += c; });
    req.on('end', () => { outage = Number((JSON.parse(b || '{}')).count) || 0; json(200, { outage }); });
    return;
  }
  if (req.method === 'GET' && url.pathname === '/check') {
    const auth = req.headers.authorization || '';
    if (!auth.startsWith('Bearer ') || auth.length <= 7) return json(401, { error: 'token required' });
    if (outage > 0) { outage -= 1; return json(503, { error: 'register unavailable' }); }
    const phone = (url.searchParams.get('phone') || '').replace(/\D/g, '');
    return json(200, { phone, reserved: phone.endsWith('9999') });
  }
  json(404, { error: 'not found' });
}).listen(PORT, () => console.log(`mock-dnc listening on ${PORT}`));
