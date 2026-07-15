/*
 * Mock ESP — stands in for the Email Service Provider an operator already
 * pays for (SendGrid, Mailgun, SES…). It speaks just enough of SendGrid's
 * v3 wire shape for the communication component's forwarder to hit it:
 * POST /v3/mail/send with {personalizations, from, subject, content} and a
 * Bearer API key. GET /mails?to= lets the E2E assert what actually left
 * the building.
 *
 * Deliberately in-memory: it is a demo seam target, not a product.
 */
'use strict';

const http = require('http');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;

/** [{to, from, subject, content, apiKey, receivedAt}] */
const mails = [];

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const json = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };

  if (req.method === 'GET' && url.pathname === '/health') {
    return json(200, { status: 'UP' });
  }

  if (req.method === 'POST' && url.pathname === '/v3/mail/send') {
    const auth = req.headers.authorization || '';
    if (!auth.startsWith('Bearer ') || auth.length <= 'Bearer '.length) {
      return json(401, { errors: [{ message: 'authorization required' }] });
    }
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      try {
        const mail = JSON.parse(body || '{}');
        const to = (((mail.personalizations || [])[0] || {}).to || [])
          .map((t) => t.email).filter(Boolean);
        if (!to.length || !mail.subject) {
          return json(400, { errors: [{ message: 'personalizations[0].to and subject are required' }] });
        }
        mails.push({
          to,
          from: (mail.from || {}).email || null,
          subject: mail.subject,
          content: ((mail.content || [])[0] || {}).value || '',
          apiKey: auth.slice('Bearer '.length),
          receivedAt: new Date().toISOString(),
        });
        // the real v3 Mail Send answers 202 Accepted with no body
        res.writeHead(202); res.end();
      } catch {
        json(400, { errors: [{ message: 'unparseable payload' }] });
      }
    });
    return;
  }

  if (req.method === 'GET' && url.pathname === '/mails') {
    const to = url.searchParams.get('to');
    return json(200, to ? mails.filter((m) => m.to.includes(to)) : mails);
  }

  json(404, { errors: [{ message: 'not found' }] });
});

server.listen(PORT, () => console.log(`mock-esp listening on ${PORT}`));
