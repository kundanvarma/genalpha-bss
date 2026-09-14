'use strict';
/* The operator's auto-configuration server, mocked — the TR-069 / TR-369
 * (USP) stand-in behind the equipment seam. What a real ACS knows about the
 * router or ONT on a line: reachable or not, uptime, firmware, Wi-Fi clients,
 * and the one action care needs most — a reboot. In-memory, deterministic
 * per line so a demo is repeatable; PUT pins a state for tests. A demo seam
 * target, not a product. Real: Axiros, Friendly, Calix, Nokia Altiplano. */
const http = require('http');

const devices = new Map(); // serviceId -> state
const MODELS = ['Nokia Beacon G6', 'Sagemcom F@st 5670', 'Zyxel EX5601', 'Calix GigaSpire u6'];
const hash = (s) => [...String(s)].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7);

function deviceFor(serviceId) {
  if (!devices.has(serviceId)) {
    const h = hash(serviceId);
    devices.set(serviceId, {
      serviceId,
      model: MODELS[h % MODELS.length],
      serial: 'CPE' + String(h).padStart(10, '0').slice(0, 10),
      state: 'online',
      bootedAt: Date.now() - ((h % 40) + 2) * 86400000, // 2..41 days of uptime
      firmware: `3.${(h % 7) + 1}.${(h % 13)}`,
      firmwareLatest: '3.8.2',
      wifiClients: (h % 6) + 1,
      rebootingUntil: 0,
      lastSeen: new Date().toISOString(),
    });
  }
  const d = devices.get(serviceId);
  if (d.rebootingUntil && Date.now() >= d.rebootingUntil) {
    d.rebootingUntil = 0;
    d.state = 'online';
    d.lastSeen = new Date().toISOString();
  }
  return d;
}

function view(d) {
  const state = d.rebootingUntil ? 'rebooting' : d.state;
  return {
    serviceId: d.serviceId,
    model: d.model,
    serial: d.serial,
    state,
    uptimeSeconds: state === 'online' ? Math.floor((Date.now() - d.bootedAt) / 1000) : 0,
    firmware: d.firmware,
    firmwareLatest: d.firmwareLatest,
    firmwareOutdated: d.firmware !== d.firmwareLatest,
    wifiClients: state === 'online' ? d.wifiClients : 0,
    lastSeen: state === 'online' ? new Date().toISOString() : d.lastSeen,
    '@type': 'CustomerPremisesEquipment',
  };
}

const json = (res, code, body) => {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
};

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (req.method === 'GET' && url.pathname === '/health') return json(res, 200, { status: 'UP', system: 'mock-acs (TR-069 stand-in)' });
  const m = url.pathname.match(/^\/cpe\/([^/]+)(\/reboot)?$/);
  if (!m) return json(res, 404, { error: 'not found' });
  const d = deviceFor(decodeURIComponent(m[1]));
  if (req.method === 'GET' && !m[2]) return json(res, 200, view(d));
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    let payload = {};
    try { payload = body ? JSON.parse(body) : {}; } catch { return json(res, 400, { error: 'bad json' }); }
    if (req.method === 'POST' && m[2]) {
      // a reboot: the box goes away for a few seconds and comes back online with fresh uptime
      d.rebootingUntil = Date.now() + (Number(process.env.REBOOT_SECONDS || 3) * 1000);
      d.bootedAt = d.rebootingUntil;
      d.state = 'online';
      d.lastSeen = new Date().toISOString();
      return json(res, 202, { accepted: true, state: 'rebooting', expectedBackAt: new Date(d.rebootingUntil).toISOString() });
    }
    if (req.method === 'PUT' && !m[2]) {
      // test pin: { state: 'offline' | 'online', firmware?, wifiClients? }
      if (payload.state) { d.state = payload.state; d.rebootingUntil = 0; if (payload.state === 'offline') d.lastSeen = new Date(Date.now() - 47 * 60000).toISOString(); }
      if (payload.firmware) d.firmware = payload.firmware;
      if (payload.wifiClients != null) d.wifiClients = Number(payload.wifiClients);
      return json(res, 200, view(d));
    }
    return json(res, 405, { error: 'method not allowed' });
  });
}).listen(8080, () => console.log('[mock-acs] TR-069 stand-in on :8080 (GET /cpe/{serviceId}, POST /cpe/{serviceId}/reboot, PUT /cpe/{serviceId} to pin)'));
