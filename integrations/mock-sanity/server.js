/*
 * Mock Sanity — stands in for an operator's headless CMS/DAM when proving the
 * reference-mode content seam without a real Sanity project. It speaks the two
 * shapes the SanityAssetProvider needs:
 *
 *   POST /v<ver>/assets/images/<dataset>   (raw image bytes in the body)
 *     -> mints a Sanity-shaped asset id  image-<hash>-<w>x<h>-<fmt>  and stores
 *        the bytes under <hash>-<w>x<h>.<fmt>; returns { document: { _id, url } }
 *   GET  /images/<projectId>/<dataset>/<file>[?w=&fit=&auto=]
 *     -> serves the stored bytes (the "CDN"); transform params are accepted and
 *        ignored (a real Sanity CDN would resize; the bytes are proof enough)
 *
 * Deliberately in-memory: a demo/test seam target, not a product. Real Sanity
 * needs no adapter change — only the tenant's baseUrl unset and a real token.
 */
'use strict';

const http = require('http');
const crypto = require('crypto');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;
const assets = new Map(); // filename -> { contentType, bytes }

const FMT = {
  'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp', 'image/svg+xml': 'svg',
};
const MIME = Object.fromEntries(Object.entries(FMT).map(([k, v]) => [v, k]));

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const json = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };

  // Upload: collect the raw bytes (binary, not JSON).
  const upload = req.method === 'POST' && /^\/v[^/]+\/assets\/images\/[^/]+$/.test(url.pathname);
  if (upload) {
    const dataset = url.pathname.split('/').pop();
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const bytes = Buffer.concat(chunks);
      const ct = req.headers['content-type'] || 'image/png';
      const fmt = FMT[ct] || 'bin';
      const hash = crypto.createHash('sha1').update(bytes).digest('hex');
      const dims = '1200x800'; // the mock doesn't measure; a plausible constant
      const id = `image-${hash}-${dims}-${fmt}`;
      const file = `${hash}-${dims}.${fmt}`;
      assets.set(file, { contentType: ct, bytes });
      console.log(`[mock-sanity] stored ${file} (${bytes.length}B, ${ct}) in dataset ${dataset}`);
      return json(201, {
        document: {
          _id: id,
          _type: 'sanity.imageAsset',
          url: `${req.headers.host ? 'http://' + req.headers.host : ''}/images/PROJECT/${dataset}/${file}`,
        },
      });
    });
    return;
  }

  // CDN serve: /images/<proj>/<dataset>/<file> — look up by filename, ignore transforms.
  const cdn = url.pathname.match(/^\/images\/[^/]+\/[^/]+\/([^/]+)$/);
  if (req.method === 'GET' && cdn) {
    const asset = assets.get(cdn[1]);
    if (!asset) return json(404, { error: 'unknown asset', file: cdn[1] });
    res.writeHead(200, {
      'Content-Type': asset.contentType,
      'Cache-Control': 'public, max-age=86400',
    });
    return res.end(asset.bytes);
  }

  if (url.pathname === '/health' || url.pathname === '/') {
    return json(200, { ok: true, cms: 'mock-sanity', assets: assets.size });
  }
  return json(404, { error: 'not found', path: url.pathname });
});

server.listen(PORT, () => console.log(`[mock-sanity] Sanity-shaped CMS on :${PORT}`));
