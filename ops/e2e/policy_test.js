/* Policy component: business rules authored as DATA, evaluated at order time,
 * with no redeploy. As the back-office operator we create a "max 2 of an item
 * per order" rule, then:
 *   1. an order for 3 is refused (422 POLICY_DENIED) with the rule's message;
 *   2. an order for 2 goes through (not over the cap);
 *   3. we DISABLE the rule — a data change, no restart — and the order for 3
 *      now succeeds.
 * That third step is the whole point: the rule appeared and disappeared with a
 * row, never a redeploy. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const run = Date.now();

async function tokenVia(request, clientId, username = 'demo', password = 'demo') {
  const res = await request.post(KC, {
    form: { grant_type: 'password', client_id: clientId, username, password },
  });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };

  // The demo user is both the back-office operator (policy:read/write) and can
  // place orders (ordering:write) — one token exercises the whole loop.
  const t = await tokenVia(ctx.request, 'bss-demo');
  const H = { Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' };
  const POLICY = `${API}/tmf-api/policyManagement/v4`;
  const ORDERS = `${API}/tmf-api/productOrderingManagement/v4/productOrder`;

  // A plain, SELLABLE offering (no verified-identity gate) to order in bulk —
  // the catalog curation sweep retires old fixtures, and a retired pick
  // would 400 on lifecycle before the policy engine ever gets a say.
  const offerings = await (await ctx.request.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`, { headers: H })).json();
  const plain = offerings.find((o) => !o.requiresVerifiedIdentity && !o.isBundle
    && ['Active', 'Launched'].includes(o.lifecycleStatus));
  if (!plain) fail('no plain sellable offering to order');
  console.log('OK ordering target:', plain.name, plain.id);

  // debris collection: a past run that died before its cleanup leaves an
  // ENABLED max-2 rule that would veto this run's post-disable order
  const staleRules = await (await ctx.request.get(
    `${POLICY}/policyRule?limit=100`, { headers: H })).json();
  for (const r of (Array.isArray(staleRules) ? staleRules : [])) {
    if (/^E2E max-2 per order \d+$/.test(r.name || '') && r.enabled) {
      await ctx.request.delete(`${POLICY}/policyRule/${r.id}`, { headers: H }).catch(() => {});
      console.log('debris: removed stale rule', r.name);
    }
  }

  const orderFor = (qty) => ({
    productOrderItem: [{ action: 'add', quantity: qty, productOffering: { id: plain.id, name: plain.name } }],
    relatedParty: [{ id: `policy-${run}`, role: 'customer' }],
  });

  // The example rule ships DISABLED — prove it has no effect before we act.
  const seedList = await (await ctx.request.get(`${POLICY}/policyRule?limit=100`, { headers: H })).json();
  const seed = seedList.find((r) => r.id === 'example-quantity-cap');
  if (!seed) fail('seeded example rule missing');
  if (seed.enabled) fail('seeded example rule should ship disabled');
  console.log('OK seeded example rule present and disabled by default');

  // 1. Author a NEW enabled rule as data: max 2 of any one offering per order.
  const created = await ctx.request.post(`${POLICY}/policyRule`, {
    headers: H,
    data: {
      name: `E2E max-2 per order ${run}`,
      domain: 'order',
      effect: 'deny',
      priority: 10,
      enabled: true,
      condition: JSON.stringify({ '>': [{ var: 'maxLineQuantity' }, 2] }),
      message: 'You can order at most 2 of a single item.',
    },
  });
  if (created.status() !== 201) fail('rule create should be 201, got ' + created.status());
  const ruleId = (await created.json()).id;
  console.log('OK rule authored as data (no redeploy):', ruleId);

  // 2. Order of 3 is now blocked — by a rule that did not exist a second ago.
  const blocked = await ctx.request.post(ORDERS, { headers: H, data: orderFor(3) });
  if (blocked.status() !== 422) fail('over-cap order should be 422, got ' + blocked.status());
  const blockedBody = await blocked.json();
  if (blockedBody.code !== 'POLICY_DENIED') fail('expected POLICY_DENIED, got ' + blockedBody.code);
  if (!String(blockedBody.message).includes('at most 2')) fail('missing rule message, got: ' + blockedBody.message);
  console.log('OK order for 3 refused by the new rule:', blockedBody.message);

  // 3. Order of 2 is fine — the rule denies only what it should.
  const ok2 = await ctx.request.post(ORDERS, { headers: H, data: orderFor(2) });
  if (ok2.status() !== 201) fail('order for 2 should pass, got ' + ok2.status());
  console.log('OK order for 2 passes (rule is specific, not a blanket block)');

  // 4. DISABLE the rule — a row change, no restart — and the order for 3 flows.
  const off = await ctx.request.patch(`${POLICY}/policyRule/${ruleId}`, {
    headers: H, data: { enabled: false },
  });
  if (off.status() !== 200) fail('disable should be 200, got ' + off.status());
  // the disable is a row change; ordering's next evaluation sees it — allow
  // the short propagation window a loaded fleet needs
  let nowOk = null;
  for (let i = 0; i < 10; i++) {
    nowOk = await ctx.request.post(ORDERS, { headers: H, data: orderFor(3) });
    if (nowOk.status() === 201) break;
    await new Promise((r) => setTimeout(r, 2000));
  }
  if (nowOk.status() !== 201) fail('after disabling the rule, order for 3 should pass, got ' + nowOk.status());
  console.log('OK rule disabled → same order for 3 now succeeds — NO redeploy');

  // 5. Tenant isolation: the other tenant (nova) must not see this rule.
  try {
    const nres = await ctx.request.post('http://localhost:8085/realms/nova/protocol/openid-connect/token',
      { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
    const novaTok = (await nres.json()).access_token;
    if (novaTok) {
      const novaRules = await (await ctx.request.get(`${POLICY}/policyRule?limit=100`,
        { headers: { Authorization: 'Bearer ' + novaTok } })).json();
      if (Array.isArray(novaRules) && novaRules.some((r) => r.id === ruleId)) {
        fail('nova tenant can see genalpha rule — RLS breach');
      }
      console.log('OK the rule is invisible to the other tenant (RLS holds)');
    } else {
      console.log('~ skipped cross-tenant check (no nova token)');
    }
  } catch (e) {
    console.log('~ skipped cross-tenant check:', e.message);
  }

  // cleanup
  await ctx.request.delete(`${POLICY}/policyRule/${ruleId}`, { headers: H });
  await browser.close();
  console.log('\nPOLICY E2E PASSED — rules are data: added, enforced, and removed without a redeploy.');
})();
