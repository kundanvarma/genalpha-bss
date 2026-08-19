/*
 * Mock chat-ops — a Slack-incoming-webhook-shaped sink (SI-P5). The VoC
 * early-warning notifier posts alert texts here in dev; a real Slack/Teams
 * webhook is the same connector with the real URL in the secret env var.
 *
 *   POST /hook       -> { text } accepted (Slack incoming-webhook shape)
 *   GET  /messages   -> everything received (the suite's assertion surface)
 *   GET  /health     -> probe
 */
'use strict';

const http = require('http');

const PORT = process.env.PORT || 8080;
const messages = [];

const server = http.createServer((req, res) => {
  const send = (code, body) => {
    const data = Buffer.from(JSON.stringify(body));
    res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': data.length });
    res.end(data);
  };
  if (req.method === 'GET' && req.url === '/health') return send(200, { status: 'UP' });
  if (req.method === 'GET' && req.url === '/messages') return send(200, messages);
  if (req.method === 'POST' && req.url === '/hook') {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      try {
        const body = JSON.parse(raw);
        messages.push({ text: body.text || '', at: new Date().toISOString() });
        console.log(`[mock-chatops] ${body.text}`);
        send(200, { ok: true });
      } catch { send(400, { error: 'invalid JSON' }); }
    });
    return;
  }
  send(404, { error: 'not found' });
});

server.listen(PORT, () => console.log(`[mock-chatops] webhook sink on :${PORT}`));
