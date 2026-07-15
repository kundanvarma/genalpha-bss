/*
 * Mock social platform — stands in for the Meta Marketing API surface an
 * operator's growth team actually touches:
 *  - Custom Audiences: POST /v1/{audienceId}/users with SHA-256 hashed
 *    emails (the BSS pushes a segment for retargeting; PII never leaves
 *    in the clear)
 *  - Lead Ads: GET /v1/{formId}/leads returns lead-gen form entries in
 *    Meta's field_data shape (the BSS pulls them into TMF699 salesLeads)
 *
 * Deliberately in-memory: a demo seam target, not a product.
 */
'use strict';

const http = require('http');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;

/** audienceId -> Set of hashed emails */
const audiences = new Map();
/** formId -> [{id, created_time, field_data}] */
const leadForms = new Map();
let leadSeq = 1000;

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const json = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };
  const readBody = (cb) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      try { cb(JSON.parse(body || '{}')); } catch { json(400, { error: { message: 'unparseable payload' } }); }
    });
  };
  const authed = () => (req.headers.authorization || '').startsWith('Bearer ');

  if (req.method === 'GET' && url.pathname === '/health') {
    return json(200, { status: 'UP' });
  }

  const users = url.pathname.match(/^\/v1\/([^/]+)\/users$/);
  if (users) {
    if (!authed()) return json(401, { error: { message: 'access token required' } });
    if (req.method === 'POST') {
      return readBody((payload) => {
        if (!Array.isArray(payload.schema) || payload.schema[0] !== 'EMAIL_SHA256'
            || !Array.isArray(payload.data)) {
          return json(400, { error: { message: 'schema [EMAIL_SHA256] and data are required' } });
        }
        const bucket = audiences.get(users[1]) || new Set();
        for (const row of payload.data) bucket.add(String(row[0]));
        audiences.set(users[1], bucket);
        json(200, { audience_id: users[1], num_received: payload.data.length,
          num_invalid_entries: 0, session_id: String(Date.now()) });
      });
    }
    if (req.method === 'GET') {
      return json(200, [...(audiences.get(users[1]) || [])]);
    }
  }

  const leads = url.pathname.match(/^\/v1\/([^/]+)\/leads$/);
  if (leads) {
    if (req.method === 'POST') { // test convenience: seed a lead-gen entry
      return readBody((payload) => {
        const bucket = leadForms.get(leads[1]) || [];
        bucket.push({
          id: String(leadSeq++),
          created_time: new Date().toISOString(),
          field_data: Object.entries(payload).map(([name, v]) => ({ name, values: [String(v)] })),
        });
        leadForms.set(leads[1], bucket);
        json(200, { id: bucket[bucket.length - 1].id });
      });
    }
    if (req.method === 'GET') {
      if (!authed()) return json(401, { error: { message: 'access token required' } });
      return json(200, { data: leadForms.get(leads[1]) || [] });
    }
  }

  json(404, { error: { message: 'not found' } });
});

server.listen(PORT, () => console.log(`mock-social listening on ${PORT}`));
