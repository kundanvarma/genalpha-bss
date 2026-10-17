#!/usr/bin/env node
/*
 * worker-controller — the spawn-rights half of the workforce package.
 *
 * The core BSS can badge, refuse and audit; it deliberately cannot start
 * processes. THIS service — deployed only when the operator opts into the
 * workforce profile — holds the scoped container rights that close the
 * loop: Hire = mint badge + start a worker container with it injected
 * (credentials never pass through a human); Fire = revoke badge + stop
 * the container. The worker's BRAIN comes from the tenant registry's
 * worker-ai-* fields (live file, same as every service mounts): change
 * the model/provider and the controller ROLLS the workers — free by
 * design, because claims are self-expiring leases. A surge poll scales
 * bench workers under the governance ceiling; the claim-side 429 remains
 * the hard backstop.
 *
 * Auth for its API: the caller's Bearer must pass a staff-grade BSS check
 * (GET /ai/v1/governance needs ai:use) — the controller trusts the BSS's
 * verdict, never its own.
 */
const http = require('http');
const { execFileSync, execFile } = require('child_process');
const fs = require('fs');

/* A HIRE REQUEST NAMES THE WORKER, AND THAT NAME IS LOGGED.
 *
 * `HIRED+STARTED ${email} as ${cname}` puts caller-supplied text in a log line,
 * so a CR or LF in it forges a line — and these logs are the audit trail for who
 * was given a badge and when, which is exactly the record worth forging. The
 * services fix this in their logging pattern; this controller is Node, so the
 * console itself is wrapped instead. Same reasoning either way: a new log
 * statement should not be able to reopen the hole by forgetting a helper. */
for (const level of ['log', 'warn', 'error']) {
  const raw = console[level].bind(console);
  console[level] = (...args) => raw(...args.map(
    (a) => (typeof a === 'string' ? a.replace(/[\r\n]+/g, ' ') : a)));
}

const PORT = process.env.PORT || 8080;
const GATEWAY = process.env.BSS_GATEWAY_URL || 'http://gateway:8080';
const TOKEN_URL = process.env.BSS_TOKEN_URL
  || 'http://keycloak:8080/realms/bss/protocol/openid-connect/token';
const PROV_USER = process.env.PROVISIONING_USERNAME || 'demo';
const PROV_PASS = process.env.PROVISIONING_PASSWORD || 'demo';
const WORKER_IMAGE = process.env.WORKER_IMAGE || 'bss-hermes-worker:dev';
// the workers join the CONTROLLER'S own network — detected at runtime, so
// a clone under any directory name (compose project = dir name) still works
function ownNetwork() {
  try {
    const os = require('os');
    const nets = execFileSync('docker', ['inspect', '--format',
      '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}', os.hostname()],
      { encoding: 'utf8' }).trim();
    if (nets) return nets.split('\n')[0];
  } catch { /* not in docker, or docker unreachable — fall through */ }
  return 'bss-java_default';
}
const WORKER_NETWORK = process.env.WORKER_NETWORK || ownNetwork();
const TENANTS_FILE = process.env.BSS_TENANTS_FILE || '/config/tenants.yml';
const TENANT = process.env.BSS_TENANT || 'genalpha';
const LABEL = 'bss.workforce.worker';
const SURGE_POLL_MS = Number(process.env.SURGE_POLL_MS || 60000);
const CONFIG_POLL_MS = Number(process.env.CONFIG_POLL_MS || 15000);

/* ---------- the brain: worker-ai-* from the live registry ---------- */
function brain() {
  let text = '';
  try { text = fs.readFileSync(TENANTS_FILE, 'utf8'); } catch { /* no file in dev */ }
  const block = text.split(/^      - id: /m).find((b) => b.startsWith(TENANT)) || '';
  const field = (name, envFallback) => {
    const m = block.match(new RegExp(`${name}:\\s*(.*)`));
    let v = m ? m[1].trim().replace(/^"|"$/g, '') : '';
    // resolve ${ENV:default} placeholders from the controller's own env
    const p = v.match(/^\$\{(\w+):?([^}]*)\}$/);
    if (p) v = process.env[p[1]] || p[2] || '';
    return v || process.env[envFallback] || '';
  };
  // provider and model only: the provider's KEY and base URL are read by the
  // model door inside the BSS (#283) and never reach a worker container
  return {
    provider: field('worker-ai-provider', 'WORKER_AI_PROVIDER'),
    model: field('worker-ai-model', 'WORKER_AI_MODEL'),
  };
}

/* ---------- the model door (#283) ----------
 * A worker calls its model THROUGH the BSS, which meters, caps and redacts the
 * call and holds the provider key. The worker holds only its own door key —
 * issued at hire, revoked first at fire, re-issued on a rolling restart. */
const DOOR_URL = (process.env.BSS_MODEL_DOOR_URL || `${GATEWAY}/ai/v1/modelDoor`).replace(/\/+$/, '');
// Hermes accepts a custom Anthropic base URL only when its path ENDS in /anthropic;
// anything else and it calls api.anthropic.com directly, past the door, in silence
const DOOR_PATHS = { anthropic: '/anthropic' };
function doorFor(provider) {
  const path = DOOR_PATHS[String(provider || '').toLowerCase()];
  return path ? DOOR_URL + path : null;
}
/* No direct path: a tenant whose worker brain the door cannot serve gets no worker. */
function doorReadyBrain() {
  const b = brain();
  const url = doorFor(b.provider);
  if (!url || !b.model) {
    throw new Error(`tenant ${TENANT} is not ready for the model door: worker-ai-provider "${b.provider || ''}"`
      + ` with worker-ai-model "${b.model || ''}" — the door serves ${Object.keys(DOOR_PATHS).join(', ')}`);
  }
  return { ...b, doorUrl: url };
}
async function issueDoorKey(staff, badge, workerId, job) {
  return bss('POST', '/ai/v1/workforce/doorKeys', staff, { badge, workerId, job });
}
async function revokeDoorKey(staff, keyId) {
  if (keyId) await bss('DELETE', `/ai/v1/workforce/doorKeys/${encodeURIComponent(keyId)}`, staff);
}

/* ---------- BSS plumbing ---------- */
async function form(url, data) {
  const r = await fetch(url, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(data) });
  if (!r.ok) throw new Error(`token ${r.status}`);
  return r.json();
}
const staffToken = async () => (await form(TOKEN_URL, {
  grant_type: 'password', client_id: 'bss-demo', username: PROV_USER, password: PROV_PASS,
})).access_token;

async function bss(method, path, token, body) {
  const r = await fetch(GATEWAY + path, { method,
    headers: { Authorization: `Bearer ${token}`,
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  if (!r.ok) throw new Error(`${method} ${path} -> ${r.status}: ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : {};
}

/* the BSS decides who may command the controller */
async function callerIsStaff(authorization) {
  if (!authorization) return false;
  const r = await fetch(GATEWAY + '/ai/v1/governance', { headers: { Authorization: authorization } });
  return r.ok;
}

/* hiring and firing are GOVERNANCE signatures: the probe's path needs
 * ai:admin server-side, so the BSS's 200 IS the admin verdict */
async function callerIsAdmin(authorization) {
  if (!authorization) return false;
  const r = await fetch(GATEWAY + '/ai/v1/governance/adminCheck',
    { headers: { Authorization: authorization } });
  return r.ok;
}

/* ---------- docker ---------- */
const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8' });
function listWorkers() {
  const out = docker('ps', '-a', '--filter', `label=${LABEL}`, '--format',
    '{{.Names}}\t{{.Status}}\t{{.Label "bss.workforce.job"}}\t{{.Label "bss.workforce.badge"}}\t{{.Label "bss.workforce.bench"}}\t{{.Label "bss.workforce.doorkey"}}\t{{.Label "bss.workforce.workerid"}}');
  return out.trim().split('\n').filter(Boolean).map((l) => {
    const [name, status, job, badge, bench, doorKey, workerId] = l.split('\t');
    return { name, status, job, badge, bench: bench === 'true', doorKey: doorKey || null, workerId: workerId || null };
  });
}
function startWorker(name, job, badgeUser, badgePass, bench, door) {
  const b = doorReadyBrain();
  const args = ['run', '-d', '--name', name, '--network', WORKER_NETWORK,
    '--label', `${LABEL}=true`, '--label', `bss.workforce.job=${job}`,
    '--label', `bss.workforce.badge=${badgeUser}`, '--label', `bss.workforce.bench=${bench}`,
    '--label', `bss.workforce.doorkey=${door.keyId}`, '--label', `bss.workforce.workerid=${door.workerId || ''}`,
    '-e', `BSS_WORKER_USERNAME=${badgeUser}`, '-e', `BSS_WORKER_PASSWORD=${badgePass}`,
    '-e', `BSS_GATEWAY_URL=${GATEWAY}`, '-e', `BSS_TOKEN_URL=${TOKEN_URL}`,
    '-e', `WORKER_JOB=${job}`, '-e', `WORKER_AI_PROVIDER=${b.provider}`,
    '-e', `WORKER_AI_MODEL=${b.model}`,
    '-e', `WORK_INTERVAL_SECONDS=${process.env.WORK_INTERVAL_SECONDS || 900}`];
  // the door, and this worker's own key to it — the provider key never leaves the BSS
  args.push('-e', `WORKER_AI_BASE_URL=${b.doorUrl}`, '-e', `WORKER_AI_API_KEY=${door.key}`);
  args.push(WORKER_IMAGE);
  docker(...args);
}

/* ---------- one change at a time ----------
 * Hire, fire, a rolling restart and a surge decision all change the crew. A
 * roll is async now (it issues a door key before it swaps the container), and
 * two overlapping rolls — a config change and its revert — once left a worker
 * missing mid-swap, so the surge saw a short crew and hired three "bench3"s past
 * its cap. Every crew change therefore runs through this one queue, in order. */
let crewChanges = Promise.resolve();
let rolling = false;
function serially(change) {
  const next = crewChanges.then(change, change);
  crewChanges = next.catch(() => {});
  return next;
}

/* ---------- hire / fire ---------- */
async function hire(name, job, bench = false) {
  doorReadyBrain();   // refuse BEFORE a badge is minted: no worker without the door
  const staff = await staffToken();
  const email = `worker-${job}-${name}-${Date.now()}@bss.local`;
  const user = await bss('POST', '/tmf-api/rolesAndPermissionsManagement/v4/user', staff,
    { email, givenName: name, familyName: job });
  await bss('POST', '/tmf-api/rolesAndPermissionsManagement/v4/permission', staff,
    { user: { id: user.id }, userRole: { name: 'digital-worker' } });
  const cname = `wf-${name}-${Date.now() % 100000}`;
  const key = await issueDoorKey(staff, email, user.id, job);
  try {
    startWorker(cname, job, email, user.temporaryPassword, bench, { key: key.key, keyId: key.id, workerId: user.id });
  } catch (e) {
    await revokeDoorKey(staff, key.id).catch(() => {});   // a key with no worker is a key nobody needs
    throw e;
  }
  console.log(`HIRED+STARTED ${email} as ${cname} (${job}${bench ? ', bench' : ''})`);
  return { container: cname, badge: email, job };
}
async function fire(cname) {
  const w = listWorkers().find((x) => x.name === cname);
  if (!w) throw new Error('no such worker container');
  const staff = await staffToken();
  // the door key FIRST: from this moment the worker's model calls are refused,
  // whatever happens to the container. A failure is reported, not swallowed —
  // the container still stops (the safer state), and the caller is told.
  let doorKeyRevoked = true;
  let warning = null;
  try {
    await revokeDoorKey(staff, w.doorKey);
    if (!w.doorKey) { doorKeyRevoked = false; warning = 'this worker predates the model door and held no door key'; }
  } catch (e) {
    doorKeyRevoked = false;
    warning = `the door key could not be revoked: ${e.message.slice(0, 160)} — revoke it on the governance desk`;
    console.log(`FIRE ${cname}: ${warning}`);
  }
  try {
    const users = await bss('GET',
      `/tmf-api/rolesAndPermissionsManagement/v4/user?username=${encodeURIComponent(w.badge)}`, staff);
    if (users[0]) {
      const pid = Buffer.from(`${users[0].id}~digital-worker`).toString('base64url');
      await bss('DELETE', `/tmf-api/rolesAndPermissionsManagement/v4/permission/${pid}`, staff);
    }
  } catch (e) { console.log('badge revoke skipped:', e.message.slice(0, 100)); }
  docker('rm', '-f', cname);
  console.log(`FIRED ${w.badge} (${cname}): door key ${doorKeyRevoked ? 'revoked' : 'NOT revoked'}, badge revoked, container stopped`);
  return { fired: cname, doorKeyRevoked, ...(warning ? { warning } : {}) };
}

/* A rolling restart re-keys the worker: a fresh door key for the new container,
 * then the old key revoked — and a worker from before the door is moved onto it. */
async function roll(w, why) {
  const staff = await staffToken();
  const badgePass = docker('inspect', w.name, '--format', '{{range .Config.Env}}{{println .}}{{end}}')
    .split('\n').find((l) => l.startsWith('BSS_WORKER_PASSWORD='))?.slice(20);
  let workerId = w.workerId;
  if (!workerId) {
    const users = await bss('GET',
      `/tmf-api/rolesAndPermissionsManagement/v4/user?username=${encodeURIComponent(w.badge)}`, staff);
    workerId = users[0]?.id || null;
  }
  const key = await issueDoorKey(staff, w.badge, workerId, w.job);
  docker('rm', '-f', w.name);
  startWorker(w.name, w.job, w.badge, badgePass, w.bench, { key: key.key, keyId: key.id, workerId });
  await revokeDoorKey(staff, w.doorKey).catch((e) => console.log(`old door key of ${w.name} not revoked: ${e.message.slice(0, 100)}`));
  console.log(`rolled ${w.name} (${why})`);
}

/* ---------- the brain watcher: rolling restart on config change ---------- */
let lastBrain = JSON.stringify(brain());
setInterval(() => {
  try {
    const now = JSON.stringify(brain());
    if (now === lastBrain) return;
    lastBrain = now;
    console.log('BRAIN CHANGED — rolling the workers (leases free themselves)');
    serially(async () => {
      rolling = true;
      try {
        for (const w of listWorkers()) {
          try { await roll(w, 'onto the new brain'); } catch (e) { console.log(`roll failed for ${w.name}: ${e.message.slice(0, 120)}`); }
        }
      } finally { rolling = false; }
    });
  } catch (e) { console.log('brain watch error:', e.message.slice(0, 120)); }
}, CONFIG_POLL_MS);

/* ---------- surge: scale bench workers under the ceiling ---------- */
setInterval(() => serially(async () => {
  if (rolling) return;   // a crew mid-roll is short by design — never staff against it
  try {
    const staff = await staffToken();
    const kpis = await bss('GET', '/ai/v1/workforce/kpis', staff);
    const s = kpis.staffing || {};
    const workers = listWorkers();
    const bench = workers.filter((w) => w.bench);
    const cap = s.maxWorkers > 0 ? s.maxWorkers : Number(process.env.SURGE_MAX || 3);
    if (s.surge && workers.length < cap) {
      console.log(`SURGE (backlog ${s.backlogDepth}/${s.activeWorkers} active) — adding a bench worker`);
      await hire(`bench${bench.length + 1}`, 'generalist', true);
    } else if (!s.surge && bench.length > 0) {
      console.log('surge relieved — releasing one bench worker');
      await fire(bench[bench.length - 1].name);
    }
  } catch (e) { console.log('surge poll error:', e.message.slice(0, 120)); }
}), SURGE_POLL_MS);

/* ---------- the API ---------- */
const server = http.createServer(async (req, res) => {
  const send = (code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(obj)); };
  try {
    const url = new URL(req.url, 'http://x');
    if (url.pathname === '/health') return send(200, { ok: true });
    if (!(await callerIsStaff(req.headers.authorization))) {
      return send(403, { error: 'staff token required — the BSS decides who commands the controller' });
    }
    if (req.method === 'GET' && url.pathname === '/workers') {
      return send(200, listWorkers());
    }
    if (req.method === 'POST' && url.pathname === '/workers') {
      if (!(await callerIsAdmin(req.headers.authorization))) {
        return send(403, { error: 'hiring needs ai:admin — the BSS decides who signs' });
      }
      let body = '';
      req.on('data', (c) => { body += c; });
      req.on('end', async () => {
        try {
          const { name = 'worker', job = 'care' } = JSON.parse(body || '{}');
          send(201, await serially(() => hire(String(name).toLowerCase().replace(/[^a-z0-9-]/g, ''), job)));
        } catch (e) { send(500, { error: e.message.slice(0, 300) }); }
      });
      return;
    }
    const del = url.pathname.match(/^\/workers\/([\w-]+)$/);
    if (req.method === 'DELETE' && del) {
      if (!(await callerIsAdmin(req.headers.authorization))) {
        return send(403, { error: 'firing needs ai:admin — the BSS decides who signs' });
      }
      return send(200, await serially(() => fire(del[1])));
    }
    send(404, { error: 'unknown path' });
  } catch (e) { send(500, { error: e.message.slice(0, 300) }); }
});
server.listen(PORT, () => {
  console.log(`worker-controller on :${PORT} — image ${WORKER_IMAGE}, model door ${DOOR_URL}`);
  // no worker keeps a direct path: anything started before the door is re-keyed onto it
  setTimeout(() => serially(async () => {
    rolling = true;
    try {
      for (const w of listWorkers().filter((x) => !x.doorKey)) {
        try { await roll(w, 'moved onto the model door'); } catch (e) { console.log(`could not move ${w.name} onto the door: ${e.message.slice(0, 120)}`); }
      }
    } finally { rolling = false; }
  }), 5000);
});
