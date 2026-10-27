/* Partner activations, honest about time (#355, part of #352). Today's partner
 * add-on (Netflix Standard) through the gateway with a real customer token,
 * against the fleet's partner stand-in (mock-partner, GenAlpha's partner
 * platform in tenants.yml) and its failure switches:
 *
 *   instant   active at once, a code, the order completes            (as before)
 *   late      the service waits "activating"; the sweep confirms; the order completes
 *   timeout   the call times out — unknown, not failed; the sweep ASKS, finds it
 *             active, and the partner received exactly ONE activation (A3)
 *   refuse    the item fails with the partner's reason; the order is "failed"
 *   deactivate-fails  ending the service still ends it here; the partner is told
 *             again until it hears
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const PARTNER = 'http://localhost:8164';
const ORDERS = `${API}/tmf-api/productOrderingManagement/v4/productOrder`;
const SERVICES = `${API}/tmf-api/serviceInventory/v4/service`;
const run = Date.now();

const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json', 'X-Channel': 'web' });

(async () => {
  const ctx = await request.newContext();
  const token = async (u, p) => (await (await ctx.post(KC, { form: {
    grant_type: 'password', client_id: 'bss-demo', username: u, password: p } })).json()).access_token;
  const staff = await token('demo', 'demo');
  const offers = await (await ctx.get(`${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`)).json();
  const netflix = offers.find((o) => o.name === 'Netflix Standard');
  if (!netflix) fail('Netflix Standard missing');
  const setMode = async (mode) => {
    await ctx.delete(`${PARTNER}/switches`);
    if (mode) await ctx.put(`${PARTNER}/switches`, { data: { 'Netflix Standard': mode } });
  };

  // one fresh customer per case keeps the services list unambiguous
  async function customer(tag) {
    const email = `partner-${tag}-${run}@example.com`;
    const made = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
      { headers: H(staff), data: { email, givenName: 'Pia', familyName: `Partner${tag}${run}` } })).json();
    return token(email, made.temporaryPassword);
  }
  async function buy(t) {
    const res = await ctx.post(ORDERS, { headers: H(t), data: { productOrderItem: [{ id: '1', action: 'add', quantity: 1,
      productOffering: { id: netflix.id, name: netflix.name, '@referredType': 'ProductOffering' } }] } });
    if (res.status() !== 201) fail('order refused: ' + res.status());
    return (await res.json()).id;
  }
  const orderState = async (t, id) => (await (await ctx.get(`${ORDERS}/${id}`, { headers: H(t) })).json()).state;
  const service = async (t) => ((await (await ctx.get(SERVICES, { headers: H(t) })).json()) || [])
    .find((s) => s.name === 'Netflix Standard');
  const chr = (s, n) => (s?.serviceCharacteristic || []).find((c) => c.name === n)?.value;
  async function until(what, check, ms = 40000) {
    for (let waited = 0; waited < ms; waited += 1500) {
      const v = await check();
      if (v) return v;
      await sleep(1500);
    }
    return fail(`${what} never happened within ${ms / 1000}s`);
  }
  const partnerSaw = async (serviceId) => (await (await ctx.get(`${PARTNER}/activations/${serviceId}`)).json());

  /* instant — as before, now through the partner's REST shape */
  await setMode(null);
  const a = await customer('instant');
  const o1 = await buy(a);
  const s1 = await until('the instant activation', async () => {
    const s = await service(a); return s?.state === 'active' && chr(s, 'activationCode') ? s : null; });
  await until('the instant order completing', async () => (await orderState(a, o1)) === 'completed');
  if (!/^NS-[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(chr(s1, 'activationCode'))) fail('bad code ' + chr(s1, 'activationCode'));
  console.log(`OK instant: ${chr(s1, 'activationCode')} from the partner platform, order completed`);

  /* late — waits honestly, then the sweep confirms */
  await setMode('late');
  const b = await customer('late');
  const o2 = await buy(b);
  const waiting = await until('the late activation to show as waiting', async () => {
    const s = await service(b); return s?.state === 'inactive' ? s : null; }, 20000);
  if (!/activating/.test(chr(waiting, 'partnerStatus') || '')) fail('a waiting service should say it is activating: ' + JSON.stringify(waiting.serviceCharacteristic));
  if ((await orderState(b, o2)) === 'completed') fail('the order completed before the partner confirmed');
  const s2 = await until('the sweep confirming the late activation', async () => {
    const s = await service(b); return s?.state === 'active' && chr(s, 'activationCode') ? s : null; });
  await until('the late order completing', async () => (await orderState(b, o2)) === 'completed');
  console.log(`OK late: "${chr(waiting, 'partnerStatus')}" → confirmed ${chr(s2, 'activationCode')}, order completed only then`);

  /* timeout — unknown, not failed; asked before resent; ONE activation at the partner (A3) */
  await setMode('timeout');
  const c = await customer('timeout');
  const o3 = await buy(c);
  const s3 = await until('the timed-out activation converging', async () => {
    const s = await service(c); return s?.state === 'active' && chr(s, 'activationCode') ? s : null; }, 60000);
  await until('the timed-out order completing', async () => (await orderState(c, o3)) === 'completed');
  const seen = await partnerSaw(s3.id);
  if (seen.received !== 1) fail(`A3: the partner received ${seen.received} activation requests for one service — expected exactly 1`);
  if (seen.code !== chr(s3, 'activationCode')) fail('A3: the code we hold is not the partner\'s');
  console.log(`OK A3 timeout: no answer in time → the sweep asked → one activation at the partner (received ${seen.received}), ${seen.code}`);

  /* refuse — the item fails, with the partner's own reason */
  await setMode('refuse');
  const d = await customer('refuse');
  const o4 = await buy(d);
  const refused = await until('the refusal showing on the service', async () => {
    const s = await service(d); return /refused/.test(chr(s, 'partnerStatus') || '') ? s : null; }, 20000);
  if (refused.state === 'active') fail('a refused partner service came up');
  await until('the refused order failing', async () => (await orderState(d, o4)) === 'failed', 20000);
  console.log(`OK refuse: order "failed", service says "${chr(refused, 'partnerStatus')}"`);

  /* deactivate-fails — the ending stands here; the partner is told again until it hears */
  await setMode('deactivate-fails');
  const e = await customer('deact');
  await buy(e);
  const s5 = await until('the activation before the ending', async () => {
    const s = await service(e); return s?.state === 'active' ? s : null; });
  const product = await until('the product in inventory', async () =>
    ((await (await ctx.get(`${API}/tmf-api/productInventory/v4/product?status=active`, { headers: H(e) })).json()) || [])
      .find((p) => p.name === 'Netflix Standard'));
  const ending = await ctx.post(ORDERS, { headers: H(e), data: { productOrderItem: [{ id: '1', action: 'delete', product: { id: product.id } }] } });
  if (ending.status() !== 201) fail('ending refused: ' + ending.status());
  await until('the service ending here', async () => (await service(e))?.state === 'terminated');
  if ((await partnerSaw(s5.id)).deactivated) fail('the partner was supposed to be unable to deactivate');
  await setMode(null);   // the partner recovers
  await until('the sweep telling the partner again', async () => (await partnerSaw(s5.id)).deactivated === true);
  console.log('OK deactivate-fails: ended here at once; the partner, once back, was told — deactivated');

  await setMode(null);
  console.log('\nALL PARTNER-ACTIVATION CHECKS PASSED — instant, late, timeout (one activation), refusal, and an ending the partner heard late.');
})().catch(async (err) => {
  console.error('FAIL:', err.message.split('\n')[0]);
  try { const c = await request.newContext(); await c.delete(`${PARTNER}/switches`); } catch { /* best effort */ }
  process.exit(1);
});
