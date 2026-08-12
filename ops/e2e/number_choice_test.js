/* Choose-your-number — pick from the pool at checkout. Suite #103.
 *
 * Industry-standard signup UX (Verizon deals you six to pick from; MVNOs sell
 * "your number, your identity"): the shop previews a shortlist of AVAILABLE
 * numbers from the MSISDN pool's window — previewed, never consumed — and a
 * picked number rides the order as an `msisdn` characteristic. Activation
 * honors the wish IF still free; a lost race falls back to next-free, and the
 * pool counter can never mint a wished number twice.
 *
 *  - OFFER: anonymous shortlist, distinct, none already assigned; a shuffle
 *    deals a different hand; nothing consumed by looking.
 *  - WISH WINS: an order carrying msisdn=X activates a service holding X.
 *  - LOST RACE: a second order wishing the SAME X gets a DIFFERENT number.
 *  - NO DUPES: an auto-assign order never receives the consumed X either.
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const ORDERS = '/tmf-api/productOrderingManagement/v4';
const SVC = '/tmf-api/serviceInventory/v4';
const run = Date.now();
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}

// Order a plan carrying (optionally) a wished msisdn; return the order id.
async function orderPlan(kai, plan, wish) {
  const item = { action: 'add', productOffering: { id: plan.id, name: plan.name } };
  if (wish) {
    item.product = { productCharacteristic: [{ name: 'msisdn', value: wish }] };
  }
  const order = await call('POST', `${ORDERS}/productOrder`, kai, { productOrderItem: [item] });
  if (order.status !== 201) fail(`order: ${order.status} ${order.text}`);
  return order.body.id;
}

// The numbers held by services created for the party AFTER a marker time —
// read from the service view's supportingResource values.
async function numbersOf(tok, partyId, sinceIso) {
  const svcs = (await call('GET', `${SVC}/service?relatedPartyId=${partyId}`, tok)).body || [];
  const out = [];
  for (const s of svcs) {
    if (sinceIso && new Date(s.startDate || 0) < new Date(sinceIso)) continue;
    for (const r of (s.supportingResource || [])) {
      if (r.value && /^\+?\d+$/.test(String(r.value).replace('+', ''))) out.push(String(r.value));
    }
  }
  return out;
}

(async () => {
  const kai = await token('kai@bss.local', 'kai');

  /* ---------- 1. the offer ---------- */
  const offer1 = await call('GET', '/tmf-api/resourcePoolManagement/v4/numberOffer?count=6', null);
  if (offer1.status !== 200) fail(`numberOffer: ${offer1.status} ${offer1.text}`);
  const hand1 = (offer1.body || []).map((o) => o.msisdn);
  if (hand1.length < 4) fail('offer too small: ' + JSON.stringify(hand1));
  if (new Set(hand1).size !== hand1.length) fail('offer contains duplicates: ' + hand1);
  const offer2 = await call('GET', `/tmf-api/resourcePoolManagement/v4/numberOffer?count=6&shuffle=${run}`, null);
  const hand2 = (offer2.body || []).map((o) => o.msisdn);
  if (JSON.stringify(hand1) === JSON.stringify(hand2)) fail('a shuffle should deal a different hand');
  const offer1again = await call('GET', '/tmf-api/resourcePoolManagement/v4/numberOffer?count=6', null);
  if (JSON.stringify((offer1again.body || []).map((o) => o.msisdn)) !== JSON.stringify(hand1)) {
    fail('looking at the offer must not consume it (same seed should re-deal the same hand)');
  }
  ok(`OFFER: ${hand1.length} distinct available numbers, anonymous; a shuffle deals a new hand;`
    + ' looking consumes nothing');

  /* ---------- 2. the wish wins ---------- */
  const offers = (await call('GET', '/tmf-api/productCatalogManagement/v4/productOffering?limit=100', kai)).body || [];
  const plan = offers.find((o) => (o.name || '').includes('Unlimited'))
    || offers.find((o) => (o.name || '').includes('Mobile') && !o.isBundle);
  if (!plan) fail('no plan in catalog');
  const me = JSON.parse(Buffer.from(kai.split('.')[1], 'base64url').toString());
  const kaiId = me.party_id || me.sub;
  const marker = new Date(Date.now() - 5000).toISOString();
  const wish = hand1[0];

  await orderPlan(kai, plan, wish);
  let held = [];
  for (let i = 0; i < 20 && !held.includes(wish); i++) {
    await sleep(1500);
    held = await numbersOf(kai, kaiId, marker);
  }
  if (!held.includes(wish)) fail(`the wished number ${wish} was not assigned (held: ${held.join(', ')})`);
  ok(`WISH WINS: the order carried msisdn=${wish} and the activated service holds exactly that number`);

  /* ---------- 3. the lost race + no dupes ---------- */
  // marker2 must be strictly AFTER leg 2's assignment lands — no backdating,
  // or leg-2's own service leaks into the "new" set and reads as a false dupe.
  await sleep(1500);
  const marker2 = new Date().toISOString();
  await orderPlan(kai, plan, wish);          // same wish again — must NOT dupe
  await orderPlan(kai, plan, null);          // auto-assign — must NOT mint the consumed wish
  let after = [];
  for (let i = 0; i < 20; i++) {
    await sleep(1500);
    after = await numbersOf(kai, kaiId, marker2);
    if (after.length >= 2) break;
  }
  if (after.length < 2) fail('the two follow-up orders did not activate in time: ' + after.join(', '));
  const dupes = after.filter((n) => n === wish);
  if (dupes.length > 0) fail(`the consumed wish ${wish} was assigned AGAIN — duplicate number!`);
  if (new Set(after).size !== after.length) fail('two services share a number: ' + after.join(', '));
  ok(`LOST RACE + NO DUPES: re-wishing ${wish} fell back to ${after[0]}, auto-assign got ${after[1]}`
    + ' — the consumed number was never minted twice, and every service holds a distinct number');

  console.log('\nALL NUMBER-CHOICE CHECKS PASSED — the shop deals a hand of real available numbers,'
    + ' a pick rides the order and wins while free, a lost race falls back honestly, and the pool'
    + ' can never issue the same number twice.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
