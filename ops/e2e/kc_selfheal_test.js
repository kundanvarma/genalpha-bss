/* KC self-heal proof — run AFTER a keycloak bounce with NO service restarts:
 * the two historical wounds must not reopen: (1) user-create 500 (stale IdP
 * admin token), (2) the order stalling at acknowledged (stale machine tokens
 * on the SOM/ordering path). Both paths must cure their own 401s. */
const { request } = require('playwright');
const API = 'http://localhost:8080';
const run = Date.now();

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const tok = async (u, p, c = 'bss-demo') => (await (await ctx.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: c, username: u, password: p } })).json()).access_token;
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const staff = await tok('demo', 'demo');

  /* wound 1: user-create right after the bounce */
  const email = `heal-${run}@example.com`;
  const mk = await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Heal', familyName: `Kc${run}` } });
  if (mk.status() >= 300) fail('USER-CREATE STILL WOUNDED: ' + mk.status() + ' ' + (await mk.text()).slice(0, 150));
  const login = await mk.json();
  console.log('OK WOUND 1 CLOSED: user-create works right after the bounce — the IdP admin client cured its own 401');

  /* wound 2: an order must reach completed (machine channels re-minting) */
  const cust = await tok(email, login.temporaryPassword, 'bss-biz');
  const offerings = await (await ctx.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`, { headers: H(staff) })).json();
  const plan = offerings.find((o) => o.name === 'GenAlpha Mobile Unlimited 5G');
  if (!plan) fail('no mobile plan to order');
  const order = await (await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`,
    { headers: H(cust), data: { productOrderItem: [{ action: 'add',
      productOffering: { id: plan.id, name: plan.name } }] } })).json();
  if (!order.id) fail('order not created: ' + JSON.stringify(order).slice(0, 150));
  let state = order.state;
  for (let i = 0; i < 30 && state !== 'completed'; i++) {
    await sleep(3000);
    state = (await (await ctx.get(`${API}/tmf-api/productOrderingManagement/v4/productOrder/${order.id}`,
      { headers: H(cust) })).json()).state;
  }
  if (state !== 'completed') fail('WOUND 2 STILL OPEN — the order stalled at: ' + state);
  console.log('OK WOUND 2 CLOSED: the order reached completed with no service restarted — '
    + 'every machine channel on the path re-minted its own token');

  console.log('\nALL KC-SELF-HEAL CHECKS PASSED — a keycloak bounce is now an event, not an incident.');
})();
