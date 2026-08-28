/* PRICE-PARITY MODE — policy is tenant config, honesty is the invariant.
 *
 *  - a fresh form-born operator is UNIFORM by default: a channel-conditioned
 *    pricing rule is refused with a clear 400, and the manifest attests
 *    priceParity=uniform to humans and agents alike
 *  - the host flips the operator to per-channel (operator-as-a-form, live):
 *    the same rule now creates, evaluation prices agent and shop DIFFERENTLY
 *    from one context variable, and the manifest says so openly
 *  - what is forbidden is not differentiation — it is differentiation that
 *    hides; the attestation follows the policy either way
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
const OP = `parity${String(run).slice(-6)}`;
const POLICY = `${API}/tmf-api/policyManagement/v4`;

async function token(ctx, realm, client, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: client, username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const host = await token(ctx, 'bss', 'bss-demo', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });

  /* ---------- a fresh operator, born uniform ---------- */
  const born = await (await ctx.post(`${API}/onboarding/v1/operator`, { headers: H(host),
    data: { id: OP, name: `Parity Probe`, locale: 'en', currency: 'EUR' } })).json();
  if (born.id !== OP) fail('operator not minted: ' + JSON.stringify(born));
  let staff = null;
  for (let i = 0; i < 30 && !staff; i++) {
    await sleep(3000);
    staff = await token(ctx, OP, 'bss-demo', 'demo', 'demo').catch(() => null);
  }
  if (!staff) fail('no staff token from the fresh realm');
  console.log(`OK operator '${OP}' minted live — the fleet picked it up without a restart`);

  const manifest = async () => (await (await ctx.get(`${API}/app/tenant-config.json`,
    { headers: { Host: `shop.${OP}.localhost` } })).json());
  let att = await manifest();
  if (att.priceParity !== 'uniform') fail('a fresh operator must attest uniform: ' + JSON.stringify(att));
  console.log('OK the manifest attests priceParity=uniform — one price everywhere, verifiable');

  /* ---------- uniform mode has teeth ---------- */
  const channelRule = {
    name: `Agent channel deal ${run}`, domain: 'pricing', effect: 'adjust',
    priority: 100, enabled: true,
    condition: JSON.stringify({ '==': [{ var: 'channel' }, 'agent'] }),
    message: 'agent channel -10%', adjustmentType: 'percent', adjustmentValue: -10,
  };
  // the newborn tenant's tokens reach the fleet on the registry refresh
  // tick — outlast a first-seconds 401 before judging the refusal
  let refused = null;
  for (let i = 0; i < 6; i++) {
    refused = await ctx.post(`${POLICY}/policyRule`, { headers: H(staff), data: channelRule });
    if (refused.status() !== 401) break;
    await new Promise((r) => setTimeout(r, 10000));
  }
  if (refused.status() !== 400) fail('uniform mode must refuse channel pricing: ' + refused.status());
  const problem = await refused.json();
  if (!/parity/i.test(problem.message || '')) fail('the refusal must explain itself: ' + JSON.stringify(problem));
  console.log('OK UNIFORM HAS TEETH: the channel-priced rule was refused with the reason on its face');

  /* ---------- the host flips the policy — live ---------- */
  const flip = await (await ctx.patch(`${API}/onboarding/v1/operator/${OP}`, { headers: H(host),
    data: { priceParityMode: 'per-channel' } })).json();
  if (!flip.mutated) fail('parity flip refused: ' + JSON.stringify(flip));
  let flipped = false;
  for (let i = 0; i < 20 && !flipped; i++) {
    await sleep(3000);
    att = await manifest();
    flipped = att.priceParity === 'per-channel';
  }
  if (!flipped) fail('the manifest never attested per-channel');
  console.log('OK the manifest now attests priceParity=per-channel — differentiated, and SAYS SO');

  const created = await ctx.post(`${POLICY}/policyRule`, { headers: H(staff), data: channelRule });
  if (created.status() >= 300) fail('per-channel mode must accept the rule: ' + created.status());

  /* ---------- one context variable, two honest prices ---------- */
  const priceIn = async (channel) => (await (await ctx.post(`${POLICY}/price`, { headers: H(staff),
    data: { context: { subtotal: 100, offeringIds: [], channel } } })).json());
  const agent = await priceIn('agent');
  const shop = await priceIn('shop');
  if (!(agent.adjustments || []).length || Number(agent.total) !== 90) {
    fail('agent channel must price at 90: ' + JSON.stringify(agent));
  }
  if ((shop.adjustments || []).length) fail('the shop channel must be untouched: ' + JSON.stringify(shop));
  console.log('OK EVALUATION: subtotal 100 -> agent 90, shop 100 — one context variable, no hidden math');

  /* ---------- cleanup: the probe operator leaves no trace ---------- */
  await ctx.delete(`http://localhost:8085/admin/realms/${OP}`, { headers: {
    Authorization: 'Bearer ' + (await (await ctx.post(
      'http://localhost:8085/realms/master/protocol/openid-connect/token',
      { form: { grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: 'admin' } }
    )).json()).access_token } }).catch(() => {});
  console.log('OK cleanup: probe realm deleted (its tenants.yml block is inert without it)');

  console.log('\nALL PRICE-PARITY CHECKS PASSED — pricing policy is tenant config with teeth, '
    + 'and the attestation tells the truth in BOTH modes.');
})();
