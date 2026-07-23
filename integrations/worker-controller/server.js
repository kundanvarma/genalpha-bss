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
 * the key/model/provider and the controller ROLLS the workers — free by
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

const PORT = process.env.PORT || 8080;
const GATEWAY = process.env.BSS_GATEWAY_URL || 'http://gateway:8080';
const TOKEN_URL = process.env.BSS_TOKEN_URL
  || 'http://keycloak:8080/realms/bss/protocol/openid-connect/token';
const PROV_USER = process.env.PROVISIONING_USERNAME || 'demo';
const PROV_PASS = process.env.PROVISIONING_PASSWORD || 'demo';
const WORKER_IMAGE = process.env.WORKER_IMAGE || 'bss-hermes-worker:dev';
const WORKER_NETWORK = process.env.WORKER_NETWORK || 'bss-java_default';
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
  return {
    provider: field('worker-ai-provider', 'WORKER_AI_PROVIDER'),
    baseUrl: field('worker-ai-base-url', 'WORKER_AI_BASE_URL'),
    apiKey: field('worker-ai-api-key', 'WORKER_AI_API_KEY'),
    model: field('worker-ai-model', 'WORKER_AI_MODEL'),
  };
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

/* ---------- docker ---------- */
const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8' });
function listWorkers() {
  const out = docker('ps', '-a', '--filter', `label=${LABEL}`, '--format',
    '{{.Names}}\t{{.Status}}\t{{.Label "bss.workforce.job"}}\t{{.Label "bss.workforce.badge"}}\t{{.Label "bss.workforce.bench"}}');
  return out.trim().split('\n').filter(Boolean).map((l) => {
    const [name, status, job, badge, bench] = l.split('\t');
    return { name, status, job, badge, bench: bench === 'true' };
  });
}
function startWorker(name, job, badgeUser, badgePass, bench) {
  const b = brain();
  if (!b.provider || !b.model) throw new Error('no worker brain configured (worker-ai-* / WORKER_AI_*)');
  const args = ['run', '-d', '--name', name, '--network', WORKER_NETWORK,
    '--label', `${LABEL}=true`, '--label', `bss.workforce.job=${job}`,
    '--label', `bss.workforce.badge=${badgeUser}`, '--label', `bss.workforce.bench=${bench}`,
    '-e', `BSS_WORKER_USERNAME=${badgeUser}`, '-e', `BSS_WORKER_PASSWORD=${badgePass}`,
    '-e', `BSS_GATEWAY_URL=${GATEWAY}`, '-e', `BSS_TOKEN_URL=${TOKEN_URL}`,
    '-e', `WORKER_JOB=${job}`, '-e', `WORKER_AI_PROVIDER=${b.provider}`,
    '-e', `WORKER_AI_MODEL=${b.model}`,
    '-e', `WORK_INTERVAL_SECONDS=${process.env.WORK_INTERVAL_SECONDS || 900}`];
  if (b.baseUrl) args.push('-e', `WORKER_AI_BASE_URL=${b.baseUrl}`);
  if (b.apiKey) args.push('-e', `WORKER_AI_API_KEY=${b.apiKey}`);
  args.push(WORKER_IMAGE);
  docker(...args);
}

/* ---------- hire / fire ---------- */
async function hire(name, job, bench = false) {
  const staff = await staffToken();
  const email = `worker-${job}-${name}-${Date.now()}@bss.local`;
  const user = await bss('POST', '/tmf-api/rolesAndPermissionsManagement/v4/user', staff,
    { email, givenName: name, familyName: job });
  await bss('POST', '/tmf-api/rolesAndPermissionsManagement/v4/permission', staff,
    { user: { id: user.id }, userRole: { name: 'digital-worker' } });
  const cname = `wf-${name}-${Date.now() % 100000}`;
  startWorker(cname, job, email, user.temporaryPassword, bench);
  console.log(`HIRED+STARTED ${email} as ${cname} (${job}${bench ? ', bench' : ''})`);
  return { container: cname, badge: email, job };
}
async function fire(cname) {
  const w = listWorkers().find((x) => x.name === cname);
  if (!w) throw new Error('no such worker container');
  const staff = await staffToken();
  try {
    const users = await bss('GET',
      `/tmf-api/rolesAndPermissionsManagement/v4/user?username=${encodeURIComponent(w.badge)}`, staff);
    if (users[0]) {
      const pid = Buffer.from(`${users[0].id}~digital-worker`).toString('base64url');
      await bss('DELETE', `/tmf-api/rolesAndPermissionsManagement/v4/permission/${pid}`, staff);
    }
  } catch (e) { console.log('badge revoke skipped:', e.message.slice(0, 100)); }
  docker('rm', '-f', cname);
  console.log(`FIRED ${w.badge} (${cname}): badge revoked, container stopped`);
}

/* ---------- the brain watcher: rolling restart on config change ---------- */
let lastBrain = JSON.stringify(brain());
setInterval(() => {
  try {
    const now = JSON.stringify(brain());
    if (now === lastBrain) return;
    lastBrain = now;
    console.log('BRAIN CHANGED — rolling the workers (leases free themselves)');
    for (const w of listWorkers()) {
      try {
        const badgePass = docker('inspect', w.name, '--format',
          '{{range .Config.Env}}{{println .}}{{end}}')
          .split('\n').find((l) => l.startsWith('BSS_WORKER_PASSWORD='))?.slice(20);
        docker('rm', '-f', w.name);
        startWorker(w.name, w.job, w.badge, badgePass, w.bench);
        console.log(`rolled ${w.name} onto the new brain`);
      } catch (e) { console.log(`roll failed for ${w.name}: ${e.message.slice(0, 120)}`); }
    }
  } catch (e) { console.log('brain watch error:', e.message.slice(0, 120)); }
}, CONFIG_POLL_MS);

/* ---------- surge: scale bench workers under the ceiling ---------- */
setInterval(async () => {
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
}, SURGE_POLL_MS);

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
      let body = '';
      req.on('data', (c) => { body += c; });
      req.on('end', async () => {
        try {
          const { name = 'worker', job = 'care' } = JSON.parse(body || '{}');
          send(201, await hire(String(name).toLowerCase().replace(/[^a-z0-9-]/g, ''), job));
        } catch (e) { send(500, { error: e.message.slice(0, 300) }); }
      });
      return;
    }
    const del = url.pathname.match(/^\/workers\/([\w-]+)$/);
    if (req.method === 'DELETE' && del) {
      await fire(del[1]);
      return send(200, { fired: del[1] });
    }
    send(404, { error: 'unknown path' });
  } catch (e) { send(500, { error: e.message.slice(0, 300) }); }
});
server.listen(PORT, () => console.log(`worker-controller on :${PORT} — image ${WORKER_IMAGE}`));
