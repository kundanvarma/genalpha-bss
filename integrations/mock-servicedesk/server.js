/*
 * Mock service desk — the reference NAMED signal connector (SI-P2). Speaks a
 * Zendesk-shaped wire:
 *
 *   GET /health                  -> probe
 *   GET /api/v2/tickets.json     -> { tickets: [{id, subject, description, status, priority}] }
 *
 * The seeded tickets carry PLANTED PII (fødselsnummer, phone, email) in
 * Norwegian and English — on purpose: a connector sync must prove the SI-P1
 * firewall catches what a real desk would leak. In-memory; a seam target —
 * a real desk is the same adapter with a real baseUrl + token.
 */
'use strict';

const http = require('http');

const PORT = process.env.PORT || 8080;

const TICKETS = [
  { id: 9001, subject: 'Internett nede i hele natt',
    description: 'Ruteren blinker rødt. Ring meg på 99 88 77 66. Mitt fødselsnummer er 010199 12345 om dere trenger det.',
    status: 'open', priority: 'high' },
  { id: 9002, subject: 'Billing question',
    description: 'I was charged twice this month — receipt sent from anna.kunde@example.com. Please refund one.',
    status: 'open', priority: 'normal' },
  { id: 9003, subject: 'Slow speeds every evening',
    description: 'Fiber 500 drops to 40 Mbit after 20:00, every single day this week. Very disappointed.',
    status: 'pending', priority: 'normal' },
  { id: 9004, subject: 'Takk for rask hjelp',
    description: 'Bare skryt — agenten fikset TV-pakken på minuttet. Fornøyd kunde!',
    status: 'solved', priority: 'low' },
];

const server = http.createServer((req, res) => {
  const send = (code, body) => {
    const data = Buffer.from(JSON.stringify(body));
    res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': data.length });
    res.end(data);
  };
  if (req.method === 'GET' && req.url === '/health') return send(200, { status: 'UP' });
  if (req.method === 'GET' && req.url.startsWith('/api/v2/tickets')) {
    if (!req.headers.authorization) return send(401, { error: 'credential required' });
    console.log(`[mock-servicedesk] served ${TICKETS.length} tickets`);
    return send(200, { tickets: TICKETS });
  }
  send(404, { error: 'not found' });
});

server.listen(PORT, () => console.log(`[mock-servicedesk] Zendesk-shaped desk on :${PORT}`));
