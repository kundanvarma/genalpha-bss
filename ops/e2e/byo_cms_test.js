/* Bring-your-own CMS/DAM — reference-mode content seam. Suite #89.
 *
 *  - HOSTED DEFAULT holds: a tenant with no binding stores imagery in the
 *    built-in DAM and /content serves the bytes (reference mode is opt-in).
 *  - SANITY REFERENCE: bind the tenant to an external CMS (Sanity's wire, via
 *    mock-sanity); an upload lands THERE and /content 302-redirects to the CMS
 *    CDN url; a rendition carries the transform params; the CDN serves the bytes.
 *  - WEBHOOK FRESHNESS: an HMAC-signed delete makes the asset unavailable
 *    (/content falls open to a placeholder, never a broken image); an upsert
 *    restores it and bumps a cache-busting version; a forged signature is 401.
 *  - GENERIC HTTP CONNECTOR: the SAME code serves a DIFFERENTLY-shaped CMS
 *    (Strapi's wire, via mock-strapi — multipart upload, array response, relative
 *    /uploads urls) from config alone, zero vendor code.
 *  - PER-TENANT: the binding is the tenant's own; unbinding restores the hosted
 *    DAM. (The cross-tenant wall is RLS, proven platform-wide.)
 *
 * The mock CMSes are reachable from the document CONTAINER as mock-sanity:8080 /
 * mock-strapi:8080; this suite runs on the HOST, so it rewrites a redirect's
 * container host to the host-mapped port to fetch the served bytes.
 */
const crypto = require('crypto');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const DOC = '/tmf-api/documentManagement/v4';
const WEBHOOK_SECRET = 'dev-webhook-secret'; // matches compose SANITY_WEBHOOK_SECRET default (dev only)
// A 1x1 PNG — smallest valid image the DAM accepts.
const PNG_B64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
// mock CMS container host -> host-mapped port (only the demo mocks need this).
const hostReachable = (u) => u
  .replace('http://mock-sanity:8080', 'http://localhost:8131')
  .replace('http://mock-strapi:8080', 'http://localhost:8132');

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body, extraHeaders) {
  const r = await fetch(API + path, { method, redirect: 'manual',
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}),
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(extraHeaders || {}) },
    ...(body !== undefined ? { body: typeof body === 'string' ? body : JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text, location: r.headers.get('location'), contentType: r.headers.get('content-type') };
}
const bind = (tok, cfg) => call('PUT', `${DOC}/contentProvider`, tok, cfg);
const unbind = (tok) => call('DELETE', `${DOC}/contentProvider`, tok);
async function upload(tok, name) {
  const r = await call('POST', `${DOC}/document`, tok, { name, mimeType: 'image/png', content: PNG_B64 });
  if (r.status !== 201) fail(`upload ${name}: ${r.status} ${r.text}`);
  return r.body.id;
}
async function fetchBytes(url) {
  const r = await fetch(hostReachable(url));
  const buf = Buffer.from(await r.arrayBuffer());
  return { status: r.status, contentType: r.headers.get('content-type'), size: buf.length };
}

(async () => {
  const tok = await token('demo', 'demo');
  try {
    await unbind(tok); // start from a clean, hosted baseline

    /* ---------- 1. hosted default (opt-in: no binding = built-in DAM) ---------- */
    const hostedId = await upload(tok, `byo-hosted-${Date.now()}`);
    let res = await call('GET', `${DOC}/document/${hostedId}/content`, null);
    if (res.status !== 200 || !/image\//.test(res.contentType || '')) {
      fail(`hosted default should serve bytes, got ${res.status} ${res.contentType}`);
    }
    ok(`HOSTED DEFAULT: no binding -> /content serves ${res.contentType} bytes from the built-in DAM`);

    /* ---------- 2. Sanity reference: upload lands in the CMS, /content 302s ---------- */
    let r = await bind(tok, { provider: 'sanity', baseUrl: 'http://mock-sanity:8080',
      projectId: 'gp', dataset: 'production', secretRef: 'SANITY_TOKEN', webhookSecretRef: 'SANITY_WEBHOOK_SECRET' });
    if (r.status !== 200 || r.body.tenantId !== 'genalpha') fail(`bind sanity: ${r.status} ${r.text}`);
    const refId = await upload(tok, `byo-sanity-${Date.now()}`);
    res = await call('GET', `${DOC}/document/${refId}/content`, null);
    if (res.status !== 302 || !/mock-sanity/.test(res.location || '')) {
      fail(`sanity reference should 302 to the CDN, got ${res.status} ${res.location}`);
    }
    const served = await fetchBytes(res.location);
    if (served.status !== 200 || !/image\//.test(served.contentType || '') || served.size === 0) {
      fail(`sanity CDN did not serve bytes: ${served.status} ${served.contentType} ${served.size}B`);
    }
    ok(`SANITY REFERENCE: upload lands in the CMS; /content -> 302 ${res.location.split('/').pop()}; CDN serves ${served.size}B`);

    // rendition carries the transform params
    const hero = await call('GET', `${DOC}/document/${refId}/content?rendition=hero`, null);
    if (hero.status !== 302 || !/w=1200/.test(hero.location || '')) {
      fail(`rendition=hero should carry a transform, got ${hero.location}`);
    }
    ok(`RENDITION: ?rendition=hero -> ${hero.location.split('?')[1]}`);

    /* ---------- 3. webhook freshness ---------- */
    const assetId = res.location.split('/').pop().split('?')[0]
      .replace(/^(.+)-(\d+x\d+)\.(\w+)$/, 'image-$1-$2-$3'); // filename -> Sanity asset id
    const sign = (bodyStr) => {
      const ts = Date.now().toString();
      const sig = crypto.createHmac('sha256', WEBHOOK_SECRET).update(`${ts}.${bodyStr}`).digest('base64url');
      return `t=${ts},v1=${sig}`;
    };
    const webhook = async (op, sigHeader) => {
      const bodyStr = JSON.stringify({ assetId, operation: op });
      return call('POST', `${DOC}/webhook/sanity/genalpha`, null, bodyStr, { 'sanity-webhook-signature': sigHeader || sign(bodyStr) });
    };

    // content-addressed: identical bytes share one CMS asset, so a webhook may
    // match every document that references it (>=1, our fresh one among them).
    let wh = await webhook('delete');
    if (wh.status !== 200 || wh.body.matched < 1) fail(`webhook delete: ${wh.status} ${wh.text}`);
    res = await call('GET', `${DOC}/document/${refId}/content`, null);
    if (res.status !== 200 || !/svg/.test(res.contentType || '')) {
      fail(`after delete, /content should fall open to a placeholder, got ${res.status} ${res.contentType}`);
    }
    ok(`WEBHOOK DELETE: HMAC-verified; the gone asset -> placeholder, not a broken redirect`);

    wh = await webhook('upsert');
    if (wh.status !== 200 || wh.body.matched < 1) fail(`webhook upsert: ${wh.status} ${wh.text}`);
    res = await call('GET', `${DOC}/document/${refId}/content`, null);
    if (res.status !== 302 || !/[?&]v=\d+/.test(res.location || '')) {
      fail(`after upsert, /content should 302 with a cache-bust version, got ${res.status} ${res.location}`);
    }
    ok(`WEBHOOK UPSERT: asset restored -> 302 with cache-bust ${res.location.match(/[?&](v=\d+)/)[1]}`);

    const forged = await webhook('delete', 't=123,v1=forged');
    if (forged.status !== 401) fail(`forged signature should be 401, got ${forged.status}`);
    ok(`SIGNATURE: a forged webhook signature is rejected 401`);

    /* ---------- 4. generic HTTP connector: a differently-shaped CMS, zero vendor code ---------- */
    r = await bind(tok, { provider: 'http', config: {
      uploadUrl: 'http://mock-strapi:8080/api/upload', uploadMode: 'multipart', fileField: 'files',
      assetIdPath: '/0/url', resolveBase: 'http://mock-strapi:8080', renditionMode: 'none' } });
    if (r.status !== 200 || r.body.provider !== 'http') fail(`bind http: ${r.status} ${r.text}`);
    const strapiId = await upload(tok, `byo-strapi-${Date.now()}`);
    res = await call('GET', `${DOC}/document/${strapiId}/content`, null);
    if (res.status !== 302 || !/mock-strapi.*\/uploads\//.test(res.location || '')) {
      fail(`generic connector should 302 to Strapi's relative url, got ${res.status} ${res.location}`);
    }
    const strapiServed = await fetchBytes(res.location);
    if (strapiServed.status !== 200 || strapiServed.size === 0) {
      fail(`strapi did not serve bytes: ${strapiServed.status} ${strapiServed.size}B`);
    }
    ok(`GENERIC CONNECTOR: same code, Strapi's wire (multipart + array + relative url) from config; served ${strapiServed.size}B`);

    /* ---------- 5. per-tenant: the binding is the tenant's; unbinding restores hosted ---------- */
    let cur = await call('GET', `${DOC}/contentProvider`, tok);
    if (cur.status !== 200 || cur.body.tenantId !== 'genalpha') fail(`binding not tenant-scoped: ${cur.status} ${cur.text}`);
    ok(`PER-TENANT: the binding belongs to genalpha (cross-tenant isolation is RLS, proven platform-wide)`);

    await unbind(tok);
    const afterId = await upload(tok, `byo-rehosted-${Date.now()}`);
    res = await call('GET', `${DOC}/document/${afterId}/content`, null);
    if (res.status !== 200 || !/image\//.test(res.contentType || '')) {
      fail(`after unbind, uploads should be hosted again, got ${res.status} ${res.contentType}`);
    }
    ok(`UNBIND: back to the hosted DAM -> /content serves ${res.contentType} bytes`);

    console.log('\nALL BYO-CMS CHECKS PASSED — the built-in DAM is the opt-in default; a tenant can'
      + ' bind its own CMS (Sanity, or any HTTP CMS via config) and BSS references it, delivered by'
      + ' the CMS CDN, kept fresh by HMAC webhooks, with a placeholder when an asset goes away.');
  } finally {
    await unbind(tok).catch(() => {}); // never leave the tenant bound to a demo mock
  }
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
