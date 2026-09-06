/* #115 social_meta_test — the Meta Graph adapter, end to end, on the Taranga tenant.
 * The tenant is configured provider=meta against mock-social's Graph-shaped mirror
 * (the same routes, payloads and error shape as graph.facebook.com), so this proves
 * the adapter without a real Page: a tagged post and a comment become mentions; a
 * Messenger conversation becomes a care DM that needs a human; publishing lands on
 * the page feed with a facebook.com permalink; the genalpha tenant, still on the
 * dev shape through the deployment fallback, is untouched. */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/taranga/protocol/openid-connect/token';
const SOCIAL = 'http://localhost:8122';
const PAGE = 'taranga-demo-page';
const run = Date.now();
const fail = (m) => { throw new Error(m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body) {
  const r = await fetch(API + path, { method, headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}
const seed = (kind, body) => fetch(`${SOCIAL}/graph-seed/${PAGE}/${kind}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then((r) => r.json());

(async () => {
  const staff = await token('demo', 'demo');

  /* 1. listening: a tagged post and a comment on the page's own post, both from other people */
  const tagged = await seed('tagged', { from: `Ana Fan ${run}`, message: `love the new fibre speeds ${run}` });
  const comment = await seed('comment', { from: `Ben Grumpy ${run}`, message: `network slow tonight ${run}` });
  const s1 = await call('POST', '/insight/v1/listening/sync', staff);
  if (s1.status >= 300 || !s1.body?.enabled) fail(`listening sync: ${s1.status} ${s1.text.slice(0, 160)}`);
  const mentions = (await call('GET', '/insight/v1/listening/mentions', staff)).body || [];
  const m1 = mentions.find((m) => (m.text || '').includes(`fibre speeds ${run}`));
  const m2 = mentions.find((m) => (m.text || '').includes(`slow tonight ${run}`));
  if (!m1 || !m2) fail(`Graph mentions not ingested: ${JSON.stringify(mentions.slice(0, 3))}`);
  if (m1.platform !== 'facebook' || m1.sentiment !== 'positive') fail(`tagged post mapping: ${JSON.stringify(m1)}`);
  if (m2.sentiment !== 'negative') fail(`comment sentiment: ${JSON.stringify(m2)}`);
  const s1b = await call('POST', '/insight/v1/listening/sync', staff);
  if (s1b.body.ingested !== 0) fail(`re-sync must be idempotent, ingested ${s1b.body.ingested}`);
  console.log(`  listening: tagged post ${tagged.id} + comment ${comment.id} → 2 facebook mentions (positive / negative), re-sync idempotent`);

  /* 2. care: a Messenger conversation from a customer with a support ask */
  const conv = await seed('conversation', { platform: 'messenger', from: `Devi Customer ${run}`, message: `my bill is wrong, please help ${run}` });
  const s2 = await call('POST', '/insight/v1/care/sync', staff);
  if (s2.status >= 300) fail(`care sync: ${s2.status} ${s2.text.slice(0, 160)}`);
  const queue = (await call('GET', '/insight/v1/care/queue', staff)).body || [];
  const dm = queue.find((d) => (d.text || '').includes(`bill is wrong, please help ${run}`));
  if (!dm) fail(`Graph conversation not ingested: ${JSON.stringify(queue.slice(0, 2))}`);
  if (dm.platform !== 'messenger' || !dm.needsCare || !dm.ticketRequested) fail(`DM triage: ${JSON.stringify(dm)}`);
  console.log(`  care: messenger message ${conv.id} → DM needs care, ticket requested (${dm.author})`);

  /* 3. publishing: a post goes out on the page feed and reads back with a facebook.com permalink */
  const pub = await call('POST', '/insight/v1/social/publish', staff, { content: `Match Day Boost is on — ${run}` });
  if (pub.status >= 300 || !pub.body?.published) fail(`publish: ${pub.status} ${pub.text.slice(0, 160)}`);
  if (pub.body.provider !== 'meta' || !String(pub.body.permalink).startsWith('https://www.facebook.com/')) fail(`publish shape: ${JSON.stringify(pub.body)}`);
  const posts = (await call('GET', '/insight/v1/social/posts', staff)).body || [];
  if (!posts.find((p) => (p.message || '').includes(`Boost is on — ${run}`))) fail('published post not on the page feed');
  const feed = await (await fetch(`${SOCIAL}/v21.0/${PAGE}/posts`, { headers: { Authorization: 'Bearer x' } })).json();
  if (!(feed.data || []).find((p) => (p.message || '').includes(`Boost is on — ${run}`))) fail('post did not reach the Graph-shaped feed');
  console.log(`  publish: POST /{page}/feed → ${pub.body.id} (${pub.body.permalink})`);

  /* 4. the other tenant is untouched: genalpha still syncs the dev shape through the fallback */
  const gTok = await (async () => { const r = await fetch('http://localhost:8085/realms/bss/protocol/openid-connect/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' }) }); return (await r.json()).access_token; })();
  const g = await call('POST', '/insight/v1/listening/sync', gTok);
  if (g.status >= 300 || !g.body?.enabled) fail(`genalpha listening broke: ${g.status} ${g.text.slice(0, 120)}`);
  const gMentions = (await call('GET', '/insight/v1/listening/mentions', gTok)).body || [];
  if (gMentions.find((m) => (m.text || '').includes(String(run)))) fail('Taranga mentions leaked into the genalpha tenant');
  console.log('  isolation: genalpha syncs on the dev shape, sees none of Taranga\'s mentions');

  console.log('PASS social_meta_test');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
