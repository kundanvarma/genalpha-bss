/* The load harness — throughput gets numbers, honestly.
 *
 * A plain-Node closed-loop driver: C workers, each firing the next
 * request the moment the last one answers, for D seconds. Reports RPS
 * and p50/p95/p99 per scenario. No dependencies, no magic — the numbers
 * mean "this stack, this hardware", nothing more.
 *
 * NOTE the wide ring (P0): the gateway's fleet-wide ceiling WILL refuse
 * a load test at default dials — that is it working. Raise it first:
 *   GLOBAL_RATE_CAPACITY=1000000 docker compose up -d gateway
 * and restore afterwards with plain `docker compose up -d gateway`.
 *
 * Usage: node loadtest.js [--seconds 15] [--workers 10] [--scenario all]
 * Scenarios: catalog (anonymous browse), shop (storefront page),
 *            bills (authenticated bill list)
 */
'use strict';
const http = require('http');

const API = process.env.LOAD_TARGET || 'http://localhost:8080';
const args = process.argv.slice(2);
const opt = (name, dflt) => {
  const i = args.indexOf('--' + name);
  return i >= 0 ? args[i + 1] : dflt;
};
const SECONDS = Number(opt('seconds', 15));
const WORKERS = Number(opt('workers', 10));
const PICK = opt('scenario', 'all');

const agent = new http.Agent({ keepAlive: true, maxSockets: WORKERS * 2 });

function fire(path, headers) {
  return new Promise((resolve) => {
    const started = process.hrtime.bigint();
    const req = http.get(API + path, { agent, headers }, (res) => {
      res.resume();
      res.on('end', () => resolve({
        ms: Number(process.hrtime.bigint() - started) / 1e6,
        status: res.statusCode,
      }));
    });
    req.on('error', () => resolve({ ms: -1, status: 0 }));
  });
}

async function token() {
  return new Promise((resolve, reject) => {
    const body = 'grant_type=password&client_id=bss-demo&username=demo&password=demo';
    const req = http.request('http://localhost:8085/realms/bss/protocol/openid-connect/token',
      { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded',
        'Content-Length': Buffer.byteLength(body) } }, (res) => {
        let data = '';
        res.on('data', (c) => { data += c; });
        res.on('end', () => resolve(JSON.parse(data).access_token));
      });
    req.on('error', reject);
    req.end(body);
  });
}

function percentile(sorted, p) {
  return sorted.length ? sorted[Math.min(sorted.length - 1,
    Math.floor((p / 100) * sorted.length))] : 0;
}

async function runScenario(name, path, headers) {
  const latencies = [];
  let errors = 0;
  let throttled = 0;
  const deadline = Date.now() + SECONDS * 1000;
  await Promise.all(Array.from({ length: WORKERS }, async () => {
    while (Date.now() < deadline) {
      const r = await fire(path, headers);
      if (r.status === 429) {
        throttled += 1;
      } else if (r.ms < 0 || r.status >= 500) {
        errors += 1;
      } else {
        latencies.push(r.ms);
      }
    }
  }));
  latencies.sort((a, b) => a - b);
  const result = {
    scenario: name,
    requests: latencies.length,
    rps: Number((latencies.length / SECONDS).toFixed(1)),
    p50: Number(percentile(latencies, 50).toFixed(1)),
    p95: Number(percentile(latencies, 95).toFixed(1)),
    p99: Number(percentile(latencies, 99).toFixed(1)),
    errors,
    throttled,
  };
  console.log(`${name.padEnd(8)} ${String(result.rps).padStart(7)} req/s   `
    + `p50 ${String(result.p50).padStart(7)} ms   p95 ${String(result.p95).padStart(7)} ms   `
    + `p99 ${String(result.p99).padStart(7)} ms   errors ${errors}`
    + (throttled ? `   THROTTLED ${throttled} (raise the wide ring for load tests)` : ''));
  return result;
}

(async () => {
  console.log(`load: ${WORKERS} workers, ${SECONDS}s per scenario, target ${API}\n`);
  const results = [];
  if (PICK === 'all' || PICK === 'catalog') {
    results.push(await runScenario('catalog',
      '/tmf-api/productCatalogManagement/v4/productOffering?limit=20'));
  }
  if (PICK === 'all' || PICK === 'shop') {
    results.push(await runScenario('shop', '/shop/'));
  }
  if (PICK === 'all' || PICK === 'bills') {
    const staff = await token();
    results.push(await runScenario('bills',
      '/tmf-api/customerBillManagement/v4/customerBill?limit=20',
      { Authorization: 'Bearer ' + staff }));
  }
  console.log('\n' + JSON.stringify(results));
})();
