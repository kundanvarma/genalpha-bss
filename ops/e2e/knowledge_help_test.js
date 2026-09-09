/* #120 knowledge_help_test — contextual help, limited to the module context.
 * The shelf for a screen is a tag; WHO may read it is the audience gate from the
 * token: a customer never sees a product how-to even by asking for its tag, a
 * CSR sees the desk shelf, a product owner the console how-tos. Ask answers from
 * the same shelf, caches, and records what nobody wrote yet. Taranga tenant. */
const API = 'http://localhost:8080';
const fail = (m) => { throw new Error(m); };
async function token(realm, user, pass) {
  const r = await fetch(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token ${user}: ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method, headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
const KB = '/tmf-api/knowledgeManagement/v4/article';
const shelf = async (tok, tag) => (await call('GET', `${KB}?tag=${encodeURIComponent(tag)}`, tok)).body || [];

(async () => {
  const mira = await token('taranga', 'mira@taranga.example', 'mira');       // customer
  const sigrid = await token('taranga', 'sigrid@taranga.example', 'sigrid'); // product owner
  const ingrid = await token('taranga', 'ingrid@taranga.example', 'ingrid'); // marketing
  const demo = await token('taranga', 'demo', 'demo');                       // author (knowledge:write), ai:use

  /* 1. the shelves exist per screen */
  const po = await shelf(sigrid, 'pane:approvals');
  if (!po.some((a) => /Approving, rejecting and holding/.test(a.title))) fail('product owner cannot see the approvals shelf (seed run?)');
  const shop = await shelf(mira, 'shop:bills');
  if (!shop.some((a) => /Understanding your bill/.test(a.title))) fail('customer cannot see the bills shelf');
  console.log(`  shelves: pane:approvals → ${po.length} for the product owner · shop:bills → ${shop.length} for the customer`);

  /* 2. the module wall: a customer asking for the product shelf gets nothing; searching finds only customer articles */
  const leak = await shelf(mira, 'pane:approvals');
  if (leak.length) fail(`customer can read product how-tos: ${leak.map((a) => a.title).join(', ')}`);
  const search = (await call('GET', `${KB}?q=${encodeURIComponent('envelope approval launch')}`, mira)).body || [];
  if (search.some((a) => a.audience !== 'customer' && a.audience !== 'all')) fail('customer search leaked a staff article');
  const marketing = await shelf(ingrid, 'pane:envelopes');
  if (!marketing.length) fail('marketing (campaign:write) should read the back-office shelf');
  const csrOnly = await shelf(sigrid, 'csr:tickets');
  console.log(`  wall: customer → 0 product articles, search returns only customer/all · marketing sees back-office shelf · product owner sees CSR shelf (${csrOnly.length})`);

  /* 3. Ask: grounded on the shelf, cached on repeat, and a gap when nobody wrote it */
  const q = 'How do I hold a launch until marketing is ready?';
  const a1 = (await call('POST', '/ai/v1/knowledgeAsk', demo, { question: q, context: 'pane:approvals' })).body;
  if (!a1 || !(a1.sources || []).length) fail(`ask should answer with sources: ${JSON.stringify(a1).slice(0, 200)}`);
  const a2 = (await call('POST', '/ai/v1/knowledgeAsk', demo, { question: q, context: 'pane:approvals' })).body;
  if (!a2.cached) fail('the second identical ask should come from the cache');
  const nonsense = `zorblax quantum tariff ${Date.now()}`;
  const a3 = (await call('POST', '/ai/v1/knowledgeAsk', demo, { question: nonsense, context: 'pane:copilot' })).body;
  if (!a3.gap) fail('an unanswerable question should be recorded as a gap');
  const gaps = (await call('GET', '/ai/v1/knowledgeGaps', sigrid)).body || [];
  const g = gaps.find((x) => x.question === nonsense.toLowerCase());
  if (!g || g.context !== 'pane:copilot') fail(`gap not listed for staff: ${JSON.stringify(gaps.slice(0, 2))}`);
  const dismissed = await call('DELETE', `/ai/v1/knowledgeGaps/${g.id}`, sigrid);
  if (dismissed.status >= 300) fail(`dismiss: ${dismissed.status}`);
  if (((await call('GET', '/ai/v1/knowledgeGaps', sigrid)).body || []).some((x) => x.id === g.id)) fail('dismissed gap still listed');
  const noGaps = await call('GET', '/ai/v1/knowledgeGaps', mira);
  if (noGaps.status < 400) fail('customers must not read the gap list');
  console.log(`  ask: ${a1.sources.length} sources (${a1.model || 'stub'}) · repeat cached · gap "${g.question.slice(0, 24)}…" recorded from ${g.context} · customer refused (${noGaps.status})`);

  console.log('PASS knowledge_help_test');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
