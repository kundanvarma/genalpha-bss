/* Cross-tenant priors — the fleet's lent elasticity evidence: an unnamed
 * aggregate distribution, k-anonymity floored, or nothing at all. */
const { request } = require('playwright');
(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const tok = (await (await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } })).json()).access_token;
  const r = await (await ctx.get('http://localhost:8080/ai/v1/elasticityBenchmark',
    { headers: { Authorization: 'Bearer ' + tok }, timeout: 300000 })).json();
  if (r.available !== true) fail('benchmark unavailable: ' + JSON.stringify(r).slice(0, 200));
  if (!(r.contributingTenants >= 3)) fail('below the k-anonymity floor yet available');
  const text = JSON.stringify(r);
  for (const name of ['genalpha', 'nova', 'aurora', 'fjord']) {
    if (text.includes(name)) fail('a tenant is NAMED in the aggregate: ' + name);
  }
  if (!(r.minPct <= r.medianChurnBaselinePct && r.medianChurnBaselinePct <= r.maxPct)) fail('distribution broken');
  console.log(`OK LENT EVIDENCE: ${r.contributingTenants} unnamed tenants -> median churn baseline `
    + `${r.medianChurnBaselinePct}% (range ${r.minPct}-${r.maxPct}) — aggregate only, k-floored`);
  console.log('\nALL BENCHMARK CHECKS PASSED — small operators borrow the fleet\'s evidence without anyone\'s data leaving home.');
})();
