/*
 * Mock 5G core — stands in for the part of a 5G standalone core that decides
 * WHICH SLICE a subscriber's traffic rides: the policy function / network
 * slice selection (3GPP PCF + NSSF, or a vendor's slice manager — Mavenir,
 * Ericsson, Nokia, Samsung). The BSS never pretends to be it: it tells the
 * core "this line rides this slice profile until then", the core applies it
 * to the subscriber's session and reverts it on its own clock. Charging is a
 * DIFFERENT system (the OCS — Mavenir CCS, Matrixx, Amdocs, CSG…) and rates
 * the traffic wherever it rides; this mock owns priority, not money.
 *
 *   PUT    /subscribers/{serviceId}/slice     { profile, snssai:{sst,sd}, qos:{5qi,priority,gbrDlMbps}, until }
 *   DELETE /subscribers/{serviceId}/slice     back to the default (best-effort) slice
 *   GET    /subscribers/{serviceId}/slice     the CURRENT slice (expired = default)
 *   GET    /profiles                          the profiles this core knows
 *   GET    /health
 *
 * In-memory; a demo seam target, not a product.
 */
'use strict';
const http = require('http');
const { URL } = require('url');
const PORT = process.env.PORT || 8080;

// What a slice profile IS to the core: an S-NSSAI (slice type + differentiator)
// and a QoS profile (5QI, ARP priority, a guaranteed bit rate where the
// operator sells one). "default" is eMBB best effort — everyone's baseline.
const PROFILES = {
  default: { snssai: { sst: 1, sd: '000000' }, qos: { '5qi': 9, priority: 8 }, label: 'Best effort (eMBB)' },
  priority: { snssai: { sst: 1, sd: '0000A1' }, qos: { '5qi': 7, priority: 3, gbrDlMbps: 25, gbrUlMbps: 10 }, label: 'Priority (eMBB, prioritised)' },
  gaming: { snssai: { sst: 1, sd: '0000A2' }, qos: { '5qi': 3, priority: 2, gbrDlMbps: 20, gbrUlMbps: 10, latencyMs: 20 }, label: 'Low latency (gaming/live)' },
  'live-video': { snssai: { sst: 1, sd: '0000A3' }, qos: { '5qi': 7, priority: 3, gbrUlMbps: 20 }, label: 'Uplink priority (live video)' },
};
const subs = new Map(); // serviceId -> { profile, until, appliedAt, tenantId }
const quality = new Map(); // serviceId -> pinned KPI overrides (tests)

function current(serviceId) {
  const s = subs.get(serviceId);
  if (!s) return { serviceId, profile: 'default', ...PROFILES.default, active: false };
  if (s.until && new Date(s.until).getTime() <= Date.now()) {
    subs.delete(serviceId); // the core reverts on its own clock — nobody has to remember
    console.log(`[mock-5gc] ${serviceId} slice '${s.profile}' expired -> default`);
    return { serviceId, profile: 'default', ...PROFILES.default, active: false, expired: s.profile, expiredAt: s.until };
  }
  return { serviceId, profile: s.profile, ...PROFILES[s.profile], until: s.until || null, appliedAt: s.appliedAt, active: true };
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const send = (code, body) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
  if (req.method === 'GET' && url.pathname === '/health') return send(200, { status: 'UP', system: 'mock-5gc (PCF/NSSF stand-in)' });
  if (req.method === 'GET' && url.pathname === '/profiles') return send(200, Object.entries(PROFILES).map(([k, v]) => ({ profile: k, ...v })));
  // What the slice actually DELIVERED to a line: the core's own KPI for the last
  // window (a real core exposes this through its NWDAF / slice-assurance NF). A
  // test can pin a degraded value to prove the guarantee path.
  const q = /^\/subscribers\/([^/]+)\/quality$/.exec(url.pathname);
  if (q) {
    const serviceId = decodeURIComponent(q[1]);
    if (req.method === 'PUT') {
      let raw = ''; req.on('data', (c) => { raw += c; });
      req.on('end', () => { try { quality.set(serviceId, JSON.parse(raw || '{}')); } catch { return send(400, { error: 'bad json' }); } return send(200, current(serviceId)); });
      return;
    }
    const cur = current(serviceId); const pinned = quality.get(serviceId) || {};
    const gbr = cur.qos && cur.qos.gbrDlMbps ? cur.qos.gbrDlMbps : null;
    return send(200, { serviceId, profile: cur.profile, active: cur.active,
      measuredDlMbps: pinned.measuredDlMbps != null ? pinned.measuredDlMbps : (cur.active ? (gbr ? gbr * 2.4 : 48) : 18),
      measuredLatencyMs: pinned.measuredLatencyMs != null ? pinned.measuredLatencyMs : (cur.active ? 22 : 45),
      windowMinutes: 15, measuredAt: new Date().toISOString() });
  }
  const m = /^\/subscribers\/([^/]+)\/slice$/.exec(url.pathname);
  if (!m) return send(404, { error: 'not found' });
  const serviceId = decodeURIComponent(m[1]);
  if (req.method === 'GET') return send(200, current(serviceId));
  if (req.method === 'DELETE') { subs.delete(serviceId); console.log(`[mock-5gc] ${serviceId} -> default (released)`); return send(200, current(serviceId)); }
  if (req.method === 'PUT') {
    let raw = ''; req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      let b = {}; try { b = raw ? JSON.parse(raw) : {}; } catch { return send(400, { error: 'bad json' }); }
      const profile = b.profile || 'priority';
      if (!PROFILES[profile]) return send(422, { error: `unknown slice profile '${profile}'`, known: Object.keys(PROFILES) });
      if (b.until && Number.isNaN(new Date(b.until).getTime())) return send(400, { error: 'until must be an ISO date-time' });
      subs.set(serviceId, { profile, until: b.until || null, appliedAt: new Date().toISOString(), tenantId: b.tenantId || null });
      console.log(`[mock-5gc] ${serviceId} -> slice '${profile}'${b.until ? ' until ' + b.until : ''}`);
      return send(200, current(serviceId));
    });
    return;
  }
  send(405, { error: 'method not allowed' });
});
server.listen(PORT, () => console.log(`[mock-5gc] slice control stand-in on :${PORT} — profiles: ${Object.keys(PROFILES).join(', ')}`));
