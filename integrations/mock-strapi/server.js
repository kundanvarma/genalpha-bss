/*
 * Mock Strapi — the open-source CMS behind the GENERIC HTTP connector, for
 * proving "any CMS, zero vendor code" against a shape UNLIKE Sanity's. It speaks
 * Strapi's real Upload wire:
 *
 *   POST /api/upload        multipart/form-data, file field "files"
 *     -> 200 [{ id, url: "/uploads/<file>", formats: { thumbnail|small|medium } }]
 *   GET  /uploads/<file>    -> the stored bytes
 *
 * The difference from Sanity is the whole point: multipart (not raw), an ARRAY
 * response (not { document }), and a RELATIVE url (not a CDN host). The connector
 * handles all of it from config alone — no Strapi-specific Java. Deliberately
 * in-memory; real Strapi is the same wire (a heavier, opt-in container proof).
 */
'use strict';

const http = require('http');
const crypto = require('crypto');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;
const files = new Map(); // filename -> { contentType, bytes }

const EXT = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp', 'image/svg+xml': 'svg' };

// Minimal binary-safe multipart parser for a single file part.
function parseFirstFile(buf, contentType) {
  const m = /boundary=(.+)$/.exec(contentType || '');
  if (!m) return null;
  const boundary = '--' + m[1].trim();
  let start = buf.indexOf(boundary);
  if (start < 0) return null;
  start += boundary.length + 2; // skip boundary + CRLF
  const headerEnd = buf.indexOf('\r\n\r\n', start);
  if (headerEnd < 0) return null;
  const headers = buf.slice(start, headerEnd).toString('utf8');
  const bodyStart = headerEnd + 4;
  const bodyEnd = buf.indexOf(Buffer.from('\r\n' + boundary), bodyStart);
  if (bodyEnd < 0) return null;
  const bytes = buf.slice(bodyStart, bodyEnd);
  const ct = /Content-Type:\s*([^\r\n]+)/i.exec(headers);
  return { bytes, contentType: ct ? ct[1].trim() : 'application/octet-stream' };
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const json = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };

  if (req.method === 'POST' && url.pathname === '/api/upload') {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const part = parseFirstFile(Buffer.concat(chunks), req.headers['content-type']);
      if (!part) return json(400, { error: 'no file part' });
      const ext = EXT[part.contentType] || 'bin';
      const hash = crypto.createHash('sha1').update(part.bytes).digest('hex').slice(0, 10);
      const base = `upload_${hash}.${ext}`;
      files.set(base, part);
      // Strapi generates named responsive formats for large-enough images; the
      // mock registers the same bytes under each (proof, not real resizing).
      const formats = {};
      for (const size of ['thumbnail', 'small', 'medium']) {
        const fname = `${size}_${base}`;
        files.set(fname, part);
        formats[size] = { url: `/uploads/${fname}` };
      }
      console.log(`[mock-strapi] stored ${base} (${part.bytes.length}B, ${part.contentType})`);
      return json(200, [{
        id: files.size,
        name: base,
        mime: part.contentType,
        url: `/uploads/${base}`,
        formats,
      }]);
    });
    return;
  }

  const served = url.pathname.match(/^\/uploads\/([^/]+)$/);
  if (req.method === 'GET' && served) {
    const f = files.get(served[1]);
    if (!f) return json(404, { error: 'not found', file: served[1] });
    res.writeHead(200, { 'Content-Type': f.contentType, 'Cache-Control': 'public, max-age=86400' });
    return res.end(f.bytes);
  }

  if (url.pathname === '/health' || url.pathname === '/') {
    return json(200, { ok: true, cms: 'mock-strapi', files: files.size });
  }
  return json(404, { error: 'not found', path: url.pathname });
});

server.listen(PORT, () => console.log(`[mock-strapi] Strapi-shaped CMS on :${PORT}`));
