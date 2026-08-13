/* Browse-path caching — serving the shop to everyone on a campaign day. Suite #90.
 *
 * On a surge, thousands of anonymous prospects load the SAME catalog pages. This
 * proves the browse path is cached at the edge so the catalog JVM + Postgres are
 * shielded, WITHOUT ever serving one tenant's catalogue to another or caching a
 * logged-in response.
 *
 *  - CACHE HIT: a second identical anonymous request is served from the edge cache
 *    (the catalog is not re-hit) — the max-age counts down.
 *  - TENANT ISOLATION: genalpha and nova get their OWN catalogues; the cache never
 *    crosses tenants (keyed by Vary: X-Tenant-Id).
 *  - AUTH BYPASS: a logged-in browse is no-store — never enters the shared cache.
 *  - SURGE: a burst of anonymous requests is absorbed (all 200, all cache-served).
 */
const { execSync } = require('child_process');
const API = 'http://localhost:8080';
const CAT = '/tmf-api/productCatalogManagement/v4/productOffering?limit=5';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function head(path, headers) {
  const r = await fetch(API + path, { headers: headers || {} });
  return { status: r.status, cc: r.headers.get('cache-control') || '', body: r };
}
const maxAge = (cc) => { const m = /max-age=(\d+)/.exec(cc || ''); return m ? Number(m[1]) : null; };

(async () => {
  const staff = await (await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' }) })).json();

  /* ---------- 1. CACHE HIT ---------- */
  const a = await head(CAT);
  if (a.status !== 200) fail(`browse GET: ${a.status}`);
  if (!/public/.test(a.cc) || maxAge(a.cc) == null) fail(`no public cache header: "${a.cc}"`);
  await sleep(2500);
  const b = await head(CAT);
  if (!(maxAge(b.cc) < maxAge(a.cc))) {
    fail(`the 2nd request was not served from cache (max-age ${maxAge(a.cc)} -> ${maxAge(b.cc)})`);
  }
  ok(`CACHE HIT: a 2nd anonymous browse is edge-cached (max-age ${maxAge(a.cc)} -> ${maxAge(b.cc)}, catalog not re-hit)`);

  /* ---------- 2. TENANT ISOLATION (host -> tenant, via curl --resolve) ---------- */
  const names = (host) => {
    const cmd = host
      ? `curl -s --resolve ${host}:8080:127.0.0.1 "http://${host}:8080${CAT}"`
      : `curl -s "${API}${CAT}"`;
    const out = execSync(cmd, { encoding: 'utf8' });
    return JSON.parse(out).map((o) => o.name).sort();
  };
  const ga = names(null);           // localhost -> genalpha
  const nv = names('shop.nova.localhost'); // -> nova
  if (!ga.length || !nv.length) fail('one tenant returned an empty catalogue');
  const overlap = ga.filter((n) => nv.includes(n));
  if (JSON.stringify(ga) === JSON.stringify(nv) || overlap.length === ga.length) {
    fail('genalpha and nova returned the SAME catalogue — the cache crossed tenants');
  }
  ok(`TENANT ISOLATION: genalpha and nova get distinct catalogues (${ga.length} vs ${nv.length}); no cross-tenant leak`);

  /* ---------- 3. AUTH BYPASS ---------- */
  const auth = await head(CAT, { Authorization: `Bearer ${staff.access_token}` });
  if (!/no-store/.test(auth.cc)) fail(`an authenticated browse was cacheable: "${auth.cc}"`);
  ok('AUTH BYPASS: a logged-in browse is no-store — it never enters the shared cache');

  /* ---------- 4. SURGE: a burst is absorbed by the edge cache ---------- */
  const N = 60;
  const t0 = Date.now();
  const results = await Promise.all(Array.from({ length: N }, () => head(CAT)));
  const ms = Date.now() - t0;
  const okCount = results.filter((r) => r.status === 200).length;
  const cached = results.filter((r) => { const m = maxAge(r.cc); return m != null && m < 60; }).length;
  if (okCount !== N) fail(`surge: only ${okCount}/${N} returned 200`);
  if (cached < N * 0.8) fail(`surge: only ${cached}/${N} were cache-served — the cache did not absorb the burst`);
  ok(`SURGE: ${N} concurrent anonymous browses in ${ms}ms, all 200, ${cached}/${N} served from the edge cache — the catalog is shielded`);

  /* ---------- 5. GLOBAL CACHE OFF: only the catalog route is cached ----------
   * The edge cache is enabled route-by-route. If it were ever switched on
   * globally (SCG's LocalResponseCache defaults the global filter to ON the
   * moment the per-route filter is enabled), it would cache EVERY authenticated
   * GET — carts, orders, bills — and serve them stale for minutes. This leg
   * proves a write to a dynamic resource is visible on the very next read:
   * create a cart, add a line, and confirm the reread reflects it. */
  const CART = '/tmf-api/shoppingCart/v4/shoppingCart';
  const authHead = { Authorization: `Bearer ${staff.access_token}`, 'Content-Type': 'application/json' };
  const cart = await (await fetch(API + CART, { method: 'POST', headers: authHead, body: '{}' })).json();
  await head(`${CART}/${cart.id}`, { Authorization: `Bearer ${staff.access_token}` }); // a read that a global cache would snapshot
  const cc = (await head(`${CART}/${cart.id}`, { Authorization: `Bearer ${staff.access_token}` })).cc;
  if (/public/.test(cc) || maxAge(cc) > 0) fail(`a non-catalog authed route was edge-cached: "${cc}"`);
  await fetch(`${API}${CART}/${cart.id}`, { method: 'PATCH', headers: authHead,
    body: JSON.stringify({ cartItem: [{ id: 'probe#a#b', key: 'probe#a#b',
      offeringId: '00000000-0000-0000-0000-000000000001', name: 'Probe', quantity: 1, selections: [] }] }) });
  const after = await (await fetch(`${API}${CART}/${cart.id}`,
    { headers: { Authorization: `Bearer ${staff.access_token}` } })).json();
  if ((after.cartItem || []).length !== 1) {
    fail('a cart write was NOT visible on the next read — the gateway cache is caching dynamic routes globally');
  }
  ok('GLOBAL CACHE OFF: a cart write is visible immediately; only the catalog route is edge-cached, never carts/orders/bills');

  console.log('\nALL BROWSE-CACHE CHECKS PASSED — the campaign-day shop is served from the edge: the'
    + ' anonymous price list is cached per-tenant, a burst is absorbed before it reaches the catalog or'
    + ' the database, one tenant never sees another\'s catalogue, a logged-in browse is never cached, and'
    + ' the cache is scoped to the catalog route alone — dynamic state is never served stale.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
