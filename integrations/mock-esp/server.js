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
// where delivery receipts go (SendGrid's Event Webhook, in miniature)
const WEBHOOK_URL = process.env.WEBHOOK_URL || '';

/** [{to, from, subject, content, apiKey, receivedAt}] */
const mails = [];

/** A real ESP reports back what happened. The mock's world is simple:
 * an address with 'bounce' in it bounces, everything else is delivered —
 * and events echo the sender's custom_args so receipts find their way
 * home, signed with the same API key the send arrived with. */
function report(mail) {
  if (!WEBHOOK_URL) return;
  const events = mail.to.map((email) => ({
    email,
    event: email.includes('bounce') ? 'bounce' : 'delivered',
    reason: email.includes('bounce') ? '550 mailbox unavailable' : undefined,
    timestamp: Math.floor(Date.now() / 1000),
    custom_args: mail.customArgs || {},
  }));
  setTimeout(() => {
    const body = JSON.stringify(events);
    const url = new URL(WEBHOOK_URL);
    const req = http.request({
      hostname: url.hostname, port: url.port, path: url.pathname, method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
        'X-Esp-Token': mail.apiKey,
      },
    }, (res) => res.resume());
    req.on('error', () => {});
    req.end(body);
  }, 300);
}

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
        const record = {
          to,
          from: (mail.from || {}).email || null,
          subject: mail.subject,
          content: ((mail.content || [])[0] || {}).value || '',
          attachments: (mail.attachments || []).map((a) => ({
            filename: a.filename, type: a.type, content: a.content,
          })),
          apiKey: auth.slice('Bearer '.length),
          customArgs: mail.custom_args || {},
          receivedAt: new Date().toISOString(),
        };
        mails.push(record);
        report(record);
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
