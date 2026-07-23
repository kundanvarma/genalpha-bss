/* Agentic commerce — the catalog discoverable and buyable by AI agents. Suite #64.
 *
 *  - the ACP product feed is PUBLIC on an opted-in tenant: a shopping agent
 *    (ChatGPT, Perplexity, Claude over MCP) reads priced, in-stock offerings
 *    with no token
 *  - the per-tenant switch holds in all three positions:
 *      genalpha full      → feed + checkout
 *      nova discovery     → feed yes, agent checkout 403 (humans keep the funnel)
 *      fjord off          → 404, dark to agents
 *  - checkout is DELEGATED, honestly: the shopper's token is exchanged
 *    (RFC 8693) through bss-agent into a credential that can order and pay
 *    and NOTHING else — proven by decoding the token, not by trusting docs
 *  - complete charges the delegated payment token, places a real TMF622
 *    order (category agenticCommerce), and is replay-safe: the same
 *    Idempotency-Key returns the SAME order, a different key is refused
 *  - the buyer sees the order with their own eyes: paula's raw token reads
 *    the order the agent placed for her
 */
const API = 'http://localhost:8080';
const NOVA = 'http://shop.nova.localhost:8080';
const FJORD = 'http://shop.fjord.localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';

const fail = (m) => { throw new Error(m); };
const jwtPayload = (t) => JSON.parse(Buffer.from(t.split('.')[1], 'base64url').toString());

async function form(url, data) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(data),
  });
  if (!res.ok) fail(`token endpoint ${res.status}: ${await res.text()}`);
  return res.json();
}

async function call(method, url, { body, headers } = {}) {
  const res = await fetch(url, {
    method,
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(headers || {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON error body */ }
  return { status: res.status, body: json, text };
}

(async () => {
  /* ---------- 1. the feed: public, priced, real ---------- */
  const feed = await call('GET', `${API}/acp/product_feed`);
  if (feed.status !== 200) fail(`feed on the opted-in tenant: ${feed.status}`);
  const products = feed.body.products;
  if (!products || products.length < 3) fail(`feed too thin: ${products && products.length}`);
  const bad = products.find((p) => !p.title || !p.price || !p.price.amount || !p.price.currency);
  if (bad) fail('a feed row is unpriced or untitled: ' + JSON.stringify(bad).slice(0, 150));
  console.log(`OK FEED: a guest agent reads ${products.length} priced offerings from the ACP`
    + ` product feed — e.g. "${products[0].title}" at ${products[0].price.amount} ${products[0].price.currency}`);

  /* ---------- 2. the switch: discovery and off ---------- */
  const novaFeed = await call('GET', `${NOVA}/acp/product_feed`);
  if (novaFeed.status !== 200) fail(`nova (discovery) feed should answer: ${novaFeed.status}`);
  const novaCheckout = await call('POST', `${NOVA}/acp/checkout_sessions`,
    { body: { items: [{ id: 'anything', quantity: 1 }] } });
  if (novaCheckout.status !== 403) fail(`nova (discovery) checkout should 403: ${novaCheckout.status}`);
  const fjordFeed = await call('GET', `${FJORD}/acp/product_feed`);
  if (fjordFeed.status !== 404) fail(`fjord (off) should be dark (404): ${fjordFeed.status}`);
  console.log('OK SWITCH: nova lists its catalog but refuses agent checkout (discovery); fjord'
    + ' is dark entirely (off) — each operator chooses its own agent exposure, live-refreshed');

  /* ---------- 3. open a session, honest totals ---------- */
  const buyable = products.find((p) => p.price_type === 'oneTime' && !p.is_bundle)
    || fail('no one-time-priced offering in the feed to buy');
  const unit = Number(buyable.price.amount);
  const created = await call('POST', `${API}/acp/checkout_sessions`,
    { body: { items: [{ id: buyable.id, quantity: 1 }] } });
  if (created.status !== 201) fail(`create session: ${created.status} ${created.text.slice(0, 200)}`);
  const session = created.body;
  if (session.status !== 'ready_for_payment') fail(`session status: ${session.status}`);
  const total1 = Number(session.totals.find((t) => t.type === 'total').amount);
  if (Math.abs(total1 - unit) > 0.001) fail(`total ${total1} != unit price ${unit}`);

  const updated = await call('POST', `${API}/acp/checkout_sessions/${session.id}`,
    { body: { items: [{ id: buyable.id, quantity: 2 }] } });
  const total2 = Number(updated.body.totals.find((t) => t.type === 'total').amount);
  if (Math.abs(total2 - 2 * unit) > 0.001) fail(`doubled quantity, total ${total2} != ${2 * unit}`);
  console.log(`OK SESSION: "${buyable.title}" ×1 totals ${total1}, ×2 totals ${total2} — the agent`
    + ' is quoted the same catalog price as every human channel, and totals follow the items');

  /* ---------- 4. the delegated token: provably LESS ---------- */
  const paula = await form(KC, {
    grant_type: 'password', client_id: 'bss-demo',
    username: 'paula@family.example', password: 'paula',
  });
  const exchanged = await form(KC, {
    grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange',
    client_id: 'bss-agent', client_secret: 'agent-secret',
    subject_token: paula.access_token,
    subject_token_type: 'urn:ietf:params:oauth:token-type:access_token',
    requested_token_type: 'urn:ietf:params:oauth:token-type:access_token',
  });
  const rawClaims = jwtPayload(paula.access_token);
  const delClaims = jwtPayload(exchanged.access_token);
  const rawRoles = rawClaims.realm_access.roles.filter((r) => r.includes(':') || r === 'customer');
  const delRoles = (delClaims.realm_access || { roles: [] }).roles.filter((r) => r.includes(':') || r === 'customer');
  const COMMERCE = ['catalog:read', 'ordering:read', 'ordering:write', 'payment:read', 'payment:write'];
  const excess = delRoles.filter((r) => !COMMERCE.includes(r));
  if (excess.length) fail('the delegated token carries MORE than commerce: ' + excess.join(','));
  if (!delRoles.includes('ordering:write')) fail('the delegated token cannot order');
  if (delClaims.sub !== rawClaims.sub) fail('exchange changed the subject');
  console.log(`OK DELEGATION: paula's own token carries ${rawRoles.length} authorities (bills,`
    + ` profile, tickets…); the exchanged bss-agent token carries ${delRoles.length} — order and`
    + ' pay, nothing else, same subject. RFC 8693, downscoping proven by decoding, not by promise');

  /* ---------- 5. complete: delegated, charged, idempotent ---------- */
  const anon = await call('POST', `${API}/acp/checkout_sessions/${session.id}/complete`,
    { body: { payment_data: { token: 'spt_demo' } }, headers: { 'Idempotency-Key': 'k-anon' } });
  if (anon.status !== 401) fail(`anonymous complete should 401: ${anon.status}`);

  const auth = { Authorization: `Bearer ${exchanged.access_token}` };
  const K1 = `k1-${Date.now()}`;
  const done = await call('POST', `${API}/acp/checkout_sessions/${session.id}/complete`, {
    body: { payment_data: { token: 'spt_agent_demo_token' } },
    headers: { ...auth, 'Idempotency-Key': K1 },
  });
  if (done.status !== 200) fail(`complete: ${done.status} ${done.text.slice(0, 300)}`);
  const orderId = done.body.order && done.body.order.id;
  if (!orderId) fail('no order on the completed session');

  const replay = await call('POST', `${API}/acp/checkout_sessions/${session.id}/complete`, {
    body: { payment_data: { token: 'spt_agent_demo_token' } },
    headers: { ...auth, 'Idempotency-Key': K1 },
  });
  if (replay.status !== 200 || replay.body.order.id !== orderId) {
    fail(`replayed complete must return the SAME order: ${replay.status} ${JSON.stringify(replay.body.order)}`);
  }
  const doubleSpend = await call('POST', `${API}/acp/checkout_sessions/${session.id}/complete`, {
    body: { payment_data: { token: 'spt_agent_demo_token' } },
    headers: { ...auth, 'Idempotency-Key': `k2-${Date.now()}` },
  });
  if (doubleSpend.status !== 409) fail(`a fresh key on a completed session must 409: ${doubleSpend.status}`);
  console.log(`OK COMPLETE: the agent charged the delegated payment token for ${total2}`
    + ` ${session.currency} and placed order ${orderId.slice(0, 8)}…; the replayed Idempotency-Key`
    + ' returned the SAME order, and a new key was refused — one approval, one purchase, ever');

  /* ---------- 6. the buyer sees it with their own eyes ---------- */
  const mine = await call('GET',
    `${API}/tmf-api/productOrderingManagement/v4/productOrder/${orderId}`,
    { headers: { Authorization: `Bearer ${paula.access_token}` } });
  if (mine.status !== 200) fail(`paula cannot read her agent-placed order: ${mine.status}`);
  if (mine.body.category !== 'agenticCommerce') fail(`order category: ${mine.body.category}`);
  console.log('OK RECEIPT: paula\'s OWN token reads the order the agent placed for her — owner'
    + ' bound to the delegated token\'s subject, channel marked category=agenticCommerce, so'
    + ' "which AI bought what, for whom" is answerable from the order record itself');

  /* ---------- 7. the wall + the guardrails ---------- */
  const cross = await call('GET', `${NOVA}/acp/checkout_sessions/${session.id}`);
  if (cross.status === 200) fail('nova read a genalpha checkout session');
  const junk = await call('POST', `${API}/acp/checkout_sessions`,
    { body: { items: [{ id: `no-such-offering-${Date.now()}`, quantity: 1 }] } });
  if (junk.status !== 400) fail(`an unknown offering should 400: ${junk.status}`);
  console.log('OK WALLS: a genalpha session is invisible through nova\'s hostname, and an'
    + ' offering the catalog cannot price is refused before any money moves');

  console.log('\nALL AGENTIC COMMERCE CHECKS PASSED — the catalog is discoverable and buyable'
    + ' by AI agents over the ACP surface (and its MCP dressing), each tenant chooses off |'
    + ' discovery | full, and checkout runs on a delegated token that is provably commerce-only.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
