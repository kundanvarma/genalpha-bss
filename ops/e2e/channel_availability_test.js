/* #118 channel_availability_test — an offer sold only through some channels.
 * TMF620 ProductOffering.channel is enforced server-side: a dealer-only pack is
 * invisible and unorderable from the web shop, visible from the store, held back
 * from AI shopping agents, unknown channels are refused at authoring, staff see
 * everything, and an empty list still means "everywhere". Taranga tenant. */
const API = 'http://localhost:8080';
const run = Date.now();
const fail = (m) => { throw new Error(m); };
async function token(realm, user, pass) {
  const r = await fetch(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token: ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body, headers = {}) {
  const r = await fetch(API + path, { method, headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
const CAT = '/tmf-api/productCatalogManagement/v4';

(async () => {
  const staff = await token('taranga', 'demo', 'demo');
  const mira = await token('taranga', 'mira@taranga.example', 'mira');

  /* 1. authoring: a typo is refused; a real channel list is stored */
  const bad = await call('POST', `${CAT}/productOffering`, staff, { name: `Typo pack ${run}`, lifecycleStatus: 'Active', isSellable: true, channel: [{ id: 'webapp' }] });
  if (bad.status !== 400 || !/unknown channel 'webapp'/.test(bad.text)) fail(`typo should be refused: ${bad.status} ${bad.text.slice(0, 120)}`);
  const cats = (await call('GET', `${CAT}/category?limit=50`, staff)).body || [];
  const topups = cats.find((c) => c.name === 'Top-ups');
  const price = (await call('POST', `${CAT}/productOfferingPrice`, staff, { name: `Dealer pack ${run} price`, priceType: 'oneTime', price: { unit: 'NOK', value: 79 }, lifecycleStatus: 'Active' })).body;
  const dealer = await call('POST', `${CAT}/productOffering`, staff, { name: `Dealer-only pack ${run}`, description: 'Sold at the counter only.', lifecycleStatus: 'Active', isSellable: true,
    category: topups ? [{ id: topups.id, name: 'Top-ups' }] : [], productOfferingPrice: [{ id: price.id, name: price.name }], channel: [{ id: 'store', name: 'Store / dealer' }] });
  if (dealer.status !== 201 || !dealer.body.channel || dealer.body.channel[0].id !== 'store') fail(`dealer pack: ${dealer.status} ${dealer.text.slice(0, 160)}`);
  const id = dealer.body.id;
  console.log(`  authoring: 'webapp' refused with the registered list; "${dealer.body.name}" stored with channel [store]`);

  /* 2. the web shop (default, and explicit) does not see it; the store does; staff always do */
  const seen = async (tok, headers) => ((await call('GET', `${CAT}/productOffering?limit=99`, tok, null, headers)).body || []).some((o) => o.id === id);
  const one = async (tok, headers) => (await call('GET', `${CAT}/productOffering/${id}`, tok, null, headers)).status;
  if (await seen(mira, {})) fail('customer without a channel header (web) can see the dealer pack');
  if (await seen(mira, { 'X-Channel': 'web' }) || (await one(mira, { 'X-Channel': 'web' })) !== 404) fail('web shop can see the dealer pack');
  if (!(await seen(mira, { 'X-Channel': 'store' })) || (await one(mira, { 'X-Channel': 'store' })) !== 200) fail('store cannot see the dealer pack');
  if (!(await seen(staff, {})) || (await one(staff, {})) !== 200) fail('staff cannot see the dealer pack');
  if ((await one(staff, { 'X-Channel': 'app' })) !== 404) fail('staff asking for the app channel should get 404');
  console.log('  visibility: web 404 · store 200 · staff 200 · staff-as-app 404');

  /* 3. ordering from the web shop is refused; the same order from the store goes through */
  const ORD = '/tmf-api/productOrderingManagement/v4/productOrder';
  const web = await call('POST', ORD, mira, { productOrderItem: [{ action: 'add', productOffering: { id, name: dealer.body.name } }] }, { 'X-Channel': 'web' });
  if (web.status < 400) fail(`web order should be refused, got ${web.status}`);
  const store = await call('POST', ORD, mira, { productOrderItem: [{ action: 'add', productOffering: { id, name: dealer.body.name } }] }, { 'X-Channel': 'store' });
  if (store.status !== 201) fail(`store order: ${store.status} ${store.text.slice(0, 160)}`);
  console.log(`  ordering: web ${web.status} · store ${store.status}`);

  /* 4. AI shopping agents: the ACP feed leaves it out; an everywhere-offer is in */
  const feed = await call('GET', '/acp/product_feed', null, null, { Host: 'shop.taranga.localhost:8080' });
  if (feed.status === 200) {
    const items = feed.body?.products || feed.body?.items || feed.body || [];
    const inFeed = JSON.stringify(items).includes(id);
    if (inFeed) fail('dealer-only pack leaked into the agent feed');
    console.log(`  agents: ACP feed served ${Array.isArray(items) ? items.length : '?'} products, the dealer pack is not among them`);
  } else {
    console.log(`  agents: ACP feed not open on this host (${feed.status}) — skipped`);
  }

  /* 5. an empty list means everywhere — nothing already on the shelf changed */
  const plain = ((await call('GET', `${CAT}/productOffering?limit=99`, mira, null, { 'X-Channel': 'web' })).body || []).find((o) => /Taranga Mobile 20 GB/.test(o.name));
  if (!plain) fail('an offer without a channel list vanished from the web');
  const patched = await call('PATCH', `${CAT}/productOffering/${id}`, staff, { channel: [] });
  if (patched.status >= 300 || !(await seen(mira, { 'X-Channel': 'web' }))) fail('clearing the list should make it sellable everywhere');
  console.log('  default: offers with no channel list stay on every channel; clearing the list reopens the web');

  console.log('PASS channel_availability_test');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
