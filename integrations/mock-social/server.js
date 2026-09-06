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
// Seq bases are time-seeded so a RESTARTED mock never reissues ids that
// downstream services already persisted (else idempotency dedupes them away).
let leadSeq = Date.now();
/** account -> [{id, platform, author, text, created_time}] — brand mentions for
 *  social listening (the BSS pulls these in and scores sentiment). */
const mentions = new Map();
let mentionSeq = Date.now() + 1;
/** account -> [{id, message, created_time}] — organic posts the brand published */
const published = new Map();
let postSeq = Date.now() + 2;
/** account -> [{id, platform, author, handle, text, created_time}] — inbound
 *  direct messages: private support conversations the care team must answer
 *  (distinct from public mentions). The BSS pulls these and opens tickets. */
const dms = new Map();
let dmSeq = Date.now() + 3;
/** 'graph:'+page -> {tagged, posts(with comments), conversations, tags} — the Graph-shaped mirror */
const graph = new Map();
let graphSeq = Date.now() + 4;

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

  // ---------------------------------------------------------------------------
  // META GRAPH API SHAPE — the same brand data behind the real wire format, so
  // the 'meta' adapter is proven end to end here before a real Page is wired:
  //   GET  /vNN.N/{page}/tagged                      posts that tag the page
  //   GET  /vNN.N/{page}/feed?fields=…comments…      page posts with comments
  //   POST /vNN.N/{page}/feed {message}              publish
  //   GET  /vNN.N/{page}/posts                       published posts
  //   GET  /vNN.N/{page}/conversations?platform=     messenger | instagram threads
  //   GET  /vNN.N/{ig}/tags                          Instagram mentions
  //   POST /vNN.N/{audience}/users {payload:{schema,data}}
  //   GET  /vNN.N/{form}/leads                       (Lead Ads shape is shared)
  // Seeding (test convenience): POST /graph-seed/{page}/tagged {from,message},
  //   /graph-seed/{page}/comment {from,message}, /graph-seed/{page}/conversation
  //   {platform,from,message}, /graph-seed/{ig}/tag {username,caption}
  // ---------------------------------------------------------------------------
  const seed = url.pathname.match(/^\/graph-seed\/([^/]+)\/(tagged|comment|conversation|tag)$/);
  if (seed && req.method === 'POST') {
    return readBody((p) => {
      const key = 'graph:' + seed[1];
      const now = new Date().toISOString().replace(/\.\d{3}Z$/, '+0000');
      const g = graph.get(key) || { tagged: [], posts: [], conversations: [], tags: [] };
      let id;
      if (seed[2] === 'tagged') {
        id = `${seed[1]}_${graphSeq++}`;
        g.tagged.push({ id, message: String(p.message || ''), from: { name: p.from || 'Someone', id: p.fromId || 'u' + graphSeq }, created_time: now, permalink_url: `https://www.facebook.com/${id}` });
      } else if (seed[2] === 'comment') {
        if (!g.posts.length) g.posts.push({ id: `${seed[1]}_${graphSeq++}`, message: 'Welcome to our page', created_time: now, comments: { data: [] } });
        const post = g.posts[g.posts.length - 1];
        id = `${post.id}_${graphSeq++}`;
        post.comments.data.push({ id, message: String(p.message || ''), from: { name: p.from || 'Someone', id: p.fromId || 'u' + graphSeq }, created_time: now });
      } else if (seed[2] === 'conversation') {
        id = `m_${graphSeq++}`;
        g.conversations.push({ id: `t_${graphSeq++}`, platform: p.platform || 'messenger', updated_time: now,
          messages: { data: [{ id, message: String(p.message || ''), from: { name: p.from || 'Someone', id: p.fromId || 'u' + graphSeq, username: p.username }, created_time: now }] } });
      } else {
        id = `${graphSeq++}`;
        g.tags.push({ id, caption: String(p.caption || ''), username: p.username || 'someone', timestamp: now, permalink: `https://www.instagram.com/p/${id}/` });
      }
      graph.set(key, g);
      json(200, { id });
    });
  }
  const gm = url.pathname.match(/^\/v\d+\.\d+\/([^/]+)\/(tagged|feed|posts|conversations|tags|users|leads)$/);
  if (gm) {
    if (!authed()) return json(400, { error: { message: 'An access token is required to request this resource.', code: 104 } });
    const key = 'graph:' + gm[1];
    const g = graph.get(key) || { tagged: [], posts: [], conversations: [], tags: [] };
    const now = new Date().toISOString().replace(/\.\d{3}Z$/, '+0000');
    switch (gm[2]) {
      case 'tagged': return json(200, { data: g.tagged, paging: {} });
      case 'feed':
        if (req.method === 'POST') {
          return readBody((p) => {
            const post = { id: `${gm[1]}_${graphSeq++}`, message: String(p.message || ''), created_time: now, permalink_url: '', comments: { data: [] } };
            post.permalink_url = `https://www.facebook.com/${post.id}`;
            g.posts.push(post); graph.set(key, g);
            json(200, { id: post.id });
          });
        }
        return json(200, { data: g.posts.map((p) => ({ id: p.id, message: p.message, created_time: p.created_time, comments: p.comments })), paging: {} });
      case 'posts': return json(200, { data: g.posts.map((p) => ({ id: p.id, message: p.message, created_time: p.created_time, permalink_url: p.permalink_url })), paging: {} });
      case 'conversations': {
        const platform = url.searchParams.get('platform') || 'messenger';
        return json(200, { data: g.conversations.filter((c) => c.platform === platform).map(({ platform: _p, ...c }) => c), paging: {} });
      }
      case 'tags': return json(200, { data: g.tags, paging: {} });
      case 'users':
        if (req.method === 'POST') {
          return readBody((payload) => {
            const pl = payload.payload || {};
            if (!Array.isArray(pl.data) || !pl.schema) return json(400, { error: { message: 'payload.schema and payload.data are required', code: 100 } });
            const bucket = audiences.get(gm[1]) || new Set();
            for (const row of pl.data) bucket.add(String(Array.isArray(row) ? row[0] : row));
            audiences.set(gm[1], bucket);
            return json(200, { audience_id: gm[1], num_received: pl.data.length, num_invalid_entries: 0, session_id: String(Date.now()) });
          });
        }
        return json(200, [...(audiences.get(gm[1]) || [])]);
      case 'leads': return json(200, { data: leadForms.get(gm[1]) || [], paging: {} });
      default: break;
    }
  }

  // Google Customer Match shape: POST /google/v1/{listId}/members with
  // {operations:[{create:{hashed_email}}]} — a different wire shape to prove the
  // BSS speaks more than one ad platform through one connector abstraction.
  const gmembers = url.pathname.match(/^\/google\/v1\/([^/]+)\/members$/);
  if (gmembers) {
    if (!authed()) return json(401, { error: { message: 'access token required' } });
    if (req.method === 'POST') {
      return readBody((payload) => {
        if (!Array.isArray(payload.operations)) {
          return json(400, { error: { message: 'operations[] required (Customer Match)' } });
        }
        const bucket = audiences.get('g:' + gmembers[1]) || new Set();
        let n = 0;
        for (const op of payload.operations) {
          const h = op && op.create && op.create.hashed_email;
          if (h) { bucket.add(String(h)); n++; }
        }
        audiences.set('g:' + gmembers[1], bucket);
        json(200, { listId: gmembers[1], received: n });
      });
    }
    if (req.method === 'GET') return json(200, [...(audiences.get('g:' + gmembers[1]) || [])]);
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

  // Organic publishing: posts the brand puts OUT on its own handle.
  const posts = url.pathname.match(/^\/v1\/([^/]+)\/posts$/);
  if (posts) {
    if (!authed()) return json(401, { error: { message: 'access token required' } });
    if (req.method === 'POST') {
      return readBody((payload) => {
        const bucket = published.get(posts[1]) || [];
        const p = { id: String(postSeq++), message: String(payload.message || ''),
          created_time: new Date().toISOString() };
        bucket.push(p);
        published.set(posts[1], bucket);
        json(200, { id: p.id, permalink: `https://social.example/${posts[1]}/${p.id}` });
      });
    }
    if (req.method === 'GET') {
      return json(200, { data: published.get(posts[1]) || [] });
    }
  }

  // Social listening: brand mentions on an account's handle.
  const ment = url.pathname.match(/^\/v1\/([^/]+)\/mentions$/);
  if (ment) {
    if (req.method === 'POST') { // seed a mention (test convenience / webhook)
      return readBody((payload) => {
        const bucket = mentions.get(ment[1]) || [];
        const m = {
          id: String(mentionSeq++),
          platform: payload.platform || 'x',
          author: payload.author || 'someone',
          text: String(payload.text || ''),
          created_time: new Date().toISOString(),
        };
        bucket.push(m);
        mentions.set(ment[1], bucket);
        json(200, { id: m.id });
      });
    }
    if (req.method === 'GET') {
      if (!authed()) return json(401, { error: { message: 'access token required' } });
      return json(200, { data: mentions.get(ment[1]) || [] });
    }
  }

  // Social care: inbound DIRECT MESSAGES on an account's handle — private
  // 1:1 conversations (support asks, complaints) the care team must triage.
  const dm = url.pathname.match(/^\/v1\/([^/]+)\/dms$/);
  if (dm) {
    if (req.method === 'POST') { // seed a DM (test convenience / webhook)
      return readBody((payload) => {
        const bucket = dms.get(dm[1]) || [];
        const m = {
          id: String(dmSeq++),
          platform: payload.platform || 'x',
          author: payload.author || 'someone',
          handle: payload.handle || ('@' + (payload.author || 'someone')),
          text: String(payload.text || ''),
          created_time: new Date().toISOString(),
        };
        bucket.push(m);
        dms.set(dm[1], bucket);
        json(200, { id: m.id });
      });
    }
    if (req.method === 'GET') {
      if (!authed()) return json(401, { error: { message: 'access token required' } });
      return json(200, { data: dms.get(dm[1]) || [] });
    }
  }

  json(404, { error: { message: 'not found' } });
});

server.listen(PORT, () => console.log(`mock-social listening on ${PORT}`));
