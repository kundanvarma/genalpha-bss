'use strict';
/* A content partner's activation platform, mocked — the stand-in behind the
 * partner-entitlement seam's `http` adapter (a streaming or audiobook service
 * the operator resells or bundles). It answers the way real partner APIs do:
 *
 *   POST   /activations            {requestKey, offering, customer}
 *            201 {state:"active", code}      activated now
 *            202 {state:"pending"}           accepted; confirm later
 *            422 {state:"refused", reason}   the partner said no
 *   GET    /activations/:requestKey  the activation as the partner sees it (404 if never received)
 *   DELETE /activations/:requestKey  stop it (204), or 503 when told to fail
 *
 * The requestKey is the operator's idempotency key: the same key twice is the
 * same activation, never a second one — that is what lets the operator ask
 * "did you get it?" after a timeout instead of activating twice.
 *
 * FAILURE SWITCHES, for tests and demos — here, in the stand-in, never in the
 * operator's code. PUT /switches {"<offering name>": "<mode>"}:
 *   instant (default)  201 active at once
 *   late               202 pending; active ~LATE_MS later
 *   timeout            holds the request past the caller's timeout, then is
 *                      active anyway — the classic "did it work?" case
 *   refuse             422 refused
 *   deactivate-fails   activation works; DELETE answers 503
 * GET /switches, DELETE /switches resets. GET /activations?offering=… lists. */
const http = require('http');

const LATE_MS = Number(process.env.LATE_MS || 4000);
const HANG_MS = Number(process.env.HANG_MS || 8000);
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const activations = new Map(); // requestKey -> activation
let switches = {};

const code = (offering) => {
  const prefix = String(offering).toUpperCase().split(/[^A-Z0-9]+/).filter(Boolean).map((w) => w[0]).join('') || 'VAS';
  const block = () => Array.from({ length: 4 }, () => ALPHABET[Math.floor(Math.random() * ALPHABET.length)]).join('');
  return `${prefix}-${block()}-${block()}`;
};
const modeOf = (offering) => switches[offering] || 'instant';
const settle = (a) => {
  if (a.state === 'pending' && Date.now() >= a.activeAt) {
    a.state = 'active';
    a.code = code(a.offering);
  }
  return a;
};
const view = (a) => ({ requestKey: a.requestKey, offering: a.offering, customer: a.customer, state: a.state,
  code: a.state === 'active' ? a.code : undefined, reason: a.reason, received: a.received, deactivated: a.deactivated });

function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(body === undefined ? '' : JSON.stringify(body));
}

function read(req) {
  return new Promise((resolve) => {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => { try { resolve(raw ? JSON.parse(raw) : {}); } catch { resolve({}); } });
  });
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const parts = url.pathname.split('/').filter(Boolean);
  if (url.pathname === '/health') return send(res, 200, { status: 'UP' });

  if (parts[0] === 'switches') {
    if (req.method === 'PUT') { switches = { ...switches, ...(await read(req)) }; return send(res, 200, switches); }
    if (req.method === 'DELETE') { switches = {}; return send(res, 204); }
    return send(res, 200, switches);
  }

  if (parts[0] !== 'activations') return send(res, 404, { reason: 'not found' });

  if (req.method === 'POST' && parts.length === 1) {
    const body = await read(req);
    if (!body.requestKey || !body.offering) return send(res, 400, { reason: 'requestKey and offering are required' });
    const existing = activations.get(body.requestKey);
    if (existing) {
      // the same key again: the same activation, counted once
      existing.received += 1;
      settle(existing);
      return send(res, existing.state === 'active' ? 201 : existing.state === 'refused' ? 422 : 202, view(existing));
    }
    const mode = modeOf(body.offering);
    const a = { requestKey: body.requestKey, offering: body.offering, customer: body.customer, received: 1,
      state: 'pending', activeAt: 0, code: null, reason: null, deactivated: false, mode };
    activations.set(a.requestKey, a);
    if (mode === 'refuse') {
      a.state = 'refused';
      a.reason = `${body.offering} cannot be activated for this customer (the partner declined)`;
      return send(res, 422, view(a));
    }
    if (mode === 'late') {
      a.activeAt = Date.now() + LATE_MS;
      return send(res, 202, view(a));
    }
    if (mode === 'timeout') {
      // the partner works on it, the caller gives up waiting — it IS active by the time anyone asks
      a.activeAt = Date.now() + HANG_MS / 2;
      setTimeout(() => send(res, 201, view(settle(a))), HANG_MS);
      return undefined;
    }
    a.activeAt = Date.now();
    return send(res, 201, view(settle(a)));
  }

  if (req.method === 'GET' && parts.length === 1) {
    const offering = url.searchParams.get('offering');
    return send(res, 200, [...activations.values()].map(settle).filter((a) => !offering || a.offering === offering).map(view));
  }

  const a = activations.get(decodeURIComponent(parts[1] || ''));
  if (req.method === 'GET') return a ? send(res, 200, view(settle(a))) : send(res, 404, { reason: 'no such activation' });
  if (req.method === 'DELETE') {
    if (!a) return send(res, 404, { reason: 'no such activation' });
    // the switch as it stands NOW: once it is turned off, the partner hears again
    if (modeOf(a.offering) === 'deactivate-fails') return send(res, 503, { reason: 'partner platform unavailable' });
    a.deactivated = true;
    a.state = 'deactivated';
    return send(res, 204);
  }
  return send(res, 405, { reason: 'method not allowed' });
}).listen(8080, () => console.log('mock partner platform on :8080'));
