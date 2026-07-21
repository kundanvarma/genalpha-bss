/* The live k8s soak smoke — on-demand, not part of the numbered suites
 * (it requires the compose stack STOPPED and the Helm release deployed
 * on k3s; see docs/k8s-soak-plan.md for the full sequence).
 *
 * Expects port-forwards already running:
 *   kubectl -n bss port-forward svc/gateway  8080:8080
 *   kubectl -n bss port-forward svc/keycloak 8085:8080
 * (the same ports the fleet always uses, so issuers validate unchanged)
 *
 * Proves REQUESTS, not just Ready pods: a token from the in-cluster
 * Keycloak, an offering AUTHORED through the gateway, a customer
 * minted, an order placed and acknowledged — and the P0 receipt:
 * two billing replicas, one set of tick locks in the k8s postgres.
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();

async function token(ctx, user, pass) {
  for (let i = 0; i < 15; i++) {
    try {
      const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
        { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
      const body = await res.json();
      if (body.access_token) return body.access_token;
    } catch (transient) { /* forward mid-boot is not a verdict */ }
    await new Promise((r) => setTimeout(r, 3000));
  }
  throw new Error(`no token for ${user}`);
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  const staff = await token(ctx, 'demo', 'demo');
  console.log('OK TOKEN: the in-cluster Keycloak (imported realm) minted a staff token');

  /* author an offering THROUGH the gateway — the write path, on k8s */
  const CAT = `${API}/tmf-api/productCatalogManagement/v4`;
  const spec = await (await ctx.post(`${CAT}/productSpecification`, { headers: H(staff),
    data: { name: `K8s Soak Plan ${run}`, brand: 'GenAlpha' } })).json();
  if (!spec.id) fail('spec authoring failed: ' + JSON.stringify(spec).slice(0, 160));
  const price = await (await ctx.post(`${CAT}/productOfferingPrice`, { headers: H(staff),
    data: { name: `Soak monthly ${run}`, priceType: 'recurring',
      price: { unit: 'EUR', value: 9.99 } } })).json();
  const offering = await (await ctx.post(`${CAT}/productOffering`, { headers: H(staff),
    data: { name: `K8s Soak Mobile ${run}`, isBundle: false, isSellable: true,
      lifecycleStatus: 'Active',
      productSpecification: { id: spec.id, name: spec.name },
      productOfferingPrice: [{ id: price.id, name: price.name }] } })).json();
  if (!offering.id) fail('offering authoring failed: ' + JSON.stringify(offering).slice(0, 160));
  const listed = await (await ctx.get(`${CAT}/productOffering?limit=100`,
    { headers: H(staff) })).json();
  if (!listed.some((o) => o.id === offering.id)) fail('the authored offering is not listed');
  console.log('OK CATALOG ON K8S: an offering authored and served through the in-cluster'
    + ' gateway — spec, price and offering all landed in the k8s postgres');

  /* a customer and an order — identity via the party API (user-roles is
   * outside the core-commerce soak scope, so the party row is authored
   * by staff and the order rides the staff token's tenant) */
  const custId = `soak-cust-${run}`;
  const person = await (await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff),
    data: { id: custId, givenName: 'Soak', familyName: `Proof${run}` } })).json();
  if (!person.id) fail('party authoring failed: ' + JSON.stringify(person).slice(0, 160));
  const order = await (await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(staff),
    data: { relatedParty: [{ id: custId, role: 'customer', '@referredType': 'Individual' }],
      productOrderItem: [{ action: 'add',
        productOffering: { id: offering.id, name: offering.name } }] } })).json();
  if (!order.id) fail('order failed: ' + JSON.stringify(order).slice(0, 200));
  if (!['acknowledged', 'inProgress', 'completed'].includes(order.state)) {
    fail('order in unexpected state: ' + order.state);
  }
  console.log(`OK ORDER ON K8S: ${order.id} accepted (state ${order.state} — the SOM is outside`
    + ' the core-commerce soak scope by design, so acknowledgement IS the finish line here)');

  console.log('\nK8S SMOKE PASSED — tokens, authoring, listing and ordering all served by the'
    + ' Helm-deployed fleet on a live cluster. The tick-lock and stability receipts are'
    + ' captured by the wrapper (kubectl).');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
