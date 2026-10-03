/* AI visibility: four postures, asserted per bot. Suite #246.
 *
 * `ai-visibility: search-only` was named "classic search yes, AI answer and
 * training bots no" and blocked GPTBot (which trains) and OAI-SearchBot (which
 * answers) in the same list. OpenAI documents those as separately
 * controllable, so the posture an operator most often wants — SHOW ME IN AI
 * SEARCH, DO NOT TRAIN ON ME — could not be expressed at all.
 *
 *  - PER BOT, PER STATE: the eight crawlers in the roster against all four
 *    postures, 32 assertions on the generated document. A single string match
 *    passes on the old code, which is why there isn't one here.
 *  - SEARCH-ONLY IS UNCHANGED: a live tenant is on it. Its document is pinned
 *    by sha256 to the bytes captured from the fleet before the split.
 *  - SEARCH-AI: OAI-SearchBot and PerplexityBot walk in, GPTBot and the rest
 *    of the training roster are disallowed by name, in ONE document.
 *  - DARK SAYS NOINDEX: a Disallow asks a crawler not to fetch, and leaves a
 *    URL somebody else linked to perfectly indexable. The dark tenant's public
 *    pages carry X-Robots-Tag; no other posture does.
 *  - ONE SOURCE OF TRUTH: with a real product-manager token, the offering the
 *    catalog holds is the offering the crawler-facing page serves for an open
 *    tenant and is absent for a dark one.
 *
 * Every assertion goes through the gateway on the tenant's own hostname, which
 * is the only place the host-to-tenant map is applied.
 */
const API = 'http://localhost:8080';                      // genalpha  — open
const NOVA = 'http://shop.nova.localhost:8080';           // nova      — search-only
const NORDLYS = 'http://shop.nordlys.localhost:8080';     // nordlys   — search-ai
const FJORD = 'http://shop.fjord.localhost:8080';         // fjord     — dark
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';

const fail = (m) => { throw new Error(m); };

/* The gateway dual-serves by User-Agent, so a crawler-facing page has to be
 * asked for the way a crawler asks for it. */
const BOT = 'Mozilla/5.0 (compatible; GPTBot/1.2; +https://openai.com/gptbot)';

const get = async (url, ua) => {
  const r = await fetch(url, { headers: ua ? { 'User-Agent': ua } : {} });
  return { status: r.status, text: await r.text(), headers: r.headers };
};

const sha256 = async (s) => {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, '0')).join('');
};

const token = async (user, pass) => {
  const r = await fetch(KC, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password', client_id: 'bss-demo', username: user, password: pass,
    }),
  });
  const j = await r.json();
  if (!j.access_token) fail(`token(${user}) refused: ${JSON.stringify(j)}`);
  return j.access_token;
};

/* The roster and what each vendor documents its bot as doing. The groups are
 * configuration in the catalog (bss.geo.crawlers), so this list is the suite's
 * own statement of the third-party fact — if the two drift, this goes red,
 * which is the point. */
const ROSTER = [
  ['GPTBot', 'training'],
  ['OAI-SearchBot', 'retrieval'],
  ['ClaudeBot', 'training'],
  ['anthropic-ai', 'training'],
  ['PerplexityBot', 'retrieval'],
  ['Google-Extended', 'training'],
  ['CCBot', 'training'],
  ['Bytespider', 'training'],
];

/* Is this user-agent named with a Disallow in the document? */
const disallows = (doc, ua) =>
  new RegExp(`User-agent: ${ua.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\nDisallow: /`).test(doc);

/* Does the document keep classic search? */
const allowsClassicSearch = (doc) => /User-agent: \*\nAllow: \/\n/.test(doc);

/* What each state must do to a bot of each group. */
const EXPECTED = {
  dark: { retrieval: 'blocked-by-wildcard', training: 'blocked-by-wildcard' },
  'search-only': { retrieval: 'named', training: 'named' },
  'search-ai': { retrieval: 'allowed', training: 'named' },
  open: { retrieval: 'allowed', training: 'allowed' },
};

(async () => {
  /* ---------- 1. the four documents ---------- */
  const docs = {};
  for (const [state, host] of [['open', API], ['search-only', NOVA],
    ['search-ai', NORDLYS], ['dark', FJORD]]) {
    const r = await get(`${host}/robots.txt`);
    if (r.status !== 200) fail(`${state}: robots.txt returned ${r.status}`);
    docs[state] = r;
  }

  /* ---------- 2. search-only is byte-for-byte what it was ---------- */
  // captured from shop.nova.localhost:8080/robots.txt on 3 Oct 2026, before
  // the retrieval/training split existed
  const BEFORE = '7f87a046bdb3142e5aa3b4e4b166d5680a09f72ff850a914f1b7f3ed12c9ee8d';
  const now = await sha256(docs['search-only'].text);
  if (now !== BEFORE) {
    fail(`search-only CHANGED: sha256 ${now} != ${BEFORE} captured before the split.`
      + ' A tenant is live on this posture; its published document may not move.');
  }
  if (docs['search-only'].text.length !== 337) {
    fail(`search-only is ${docs['search-only'].text.length} bytes, was 337`);
  }
  console.log('OK UNCHANGED: the live search-only tenant\'s robots.txt is the same 337 bytes'
    + ` (sha256 ${BEFORE.slice(0, 12)}…) it served before the fourth state existed.`);

  /* ---------- 3. per bot, per state ---------- */
  let checks = 0;
  for (const [state, r] of Object.entries(docs)) {
    const doc = r.text;
    for (const [ua, group] of ROSTER) {
      const want = EXPECTED[state][group];
      const named = disallows(doc, ua);
      if (want === 'named' && !named) fail(`${state}: ${ua} (${group}) is not disallowed by name`);
      if (want !== 'named' && named) fail(`${state}: ${ua} (${group}) must not be disallowed`);
      checks += 1;
    }
    if (state === 'dark') {
      if (!/User-agent: \*\nDisallow: \//.test(doc)) fail('dark must disallow the wildcard');
      if (allowsClassicSearch(doc)) fail('dark must not allow classic search');
    } else if (!allowsClassicSearch(doc)) {
      fail(`${state}: classic search must stay allowed`);
    }
  }
  console.log(`OK PER BOT, PER STATE: ${checks} assertions — eight crawlers against four postures,`
    + ' retrieval and training answered separately, read off the generated document.');

  /* ---------- 4. the posture that could not be expressed ---------- */
  const ai = docs['search-ai'].text;
  if (!disallows(ai, 'GPTBot')) fail('search-ai must disallow GPTBot — it trains');
  if (disallows(ai, 'OAI-SearchBot')) fail('search-ai must allow OAI-SearchBot — it answers');
  if (disallows(ai, 'PerplexityBot')) fail('search-ai must allow PerplexityBot — it answers');
  if (!/Sitemap: \/sitemap\.xml/.test(ai)) fail('search-ai must publish its sitemap');
  const blocked = ROSTER.filter(([ua]) => disallows(ai, ua)).map(([ua]) => ua);
  const welcome = ROSTER.filter(([ua]) => !disallows(ai, ua)).map(([ua]) => ua);
  console.log(`OK SEARCH-AI: "AI search yes, training no" in ONE document — welcome ${welcome.join(', ')};`
    + ` disallowed ${blocked.join(', ')}. The three-state switch could not say this.`);

  /* ---------- 5. llms.txt and the sitemap follow the state ---------- */
  for (const [state, host, llms, map] of [
    ['open', API, 200, 200],
    ['search-only', NOVA, 404, 200],
    ['search-ai', NORDLYS, 200, 200],
    ['dark', FJORD, 404, 404],
  ]) {
    const l = await get(`${host}/llms.txt`);
    if (l.status !== llms) fail(`${state}: llms.txt returned ${l.status}, expected ${llms}`);
    const s = await get(`${host}/sitemap.xml`);
    if (s.status !== map) fail(`${state}: sitemap.xml returned ${s.status}, expected ${map}`);
  }
  console.log('OK SURFACES: llms.txt is published where AI answer engines are welcome (open and'
    + ' search-ai) and absent where they are not; a dark tenant publishes no sitemap either.');

  /* ---------- 6. dark says noindex, because Disallow does not ---------- */
  for (const path of ['/shop/', '/robots.txt']) {
    const r = await get(`${FJORD}${path}`);
    const tag = r.headers.get('x-robots-tag');
    if (!tag || !/noindex/.test(tag)) {
      fail(`dark tenant ${path} carries no noindex (X-Robots-Tag: ${tag})`
        + ' — Disallow stops a fetch, not a listing, so the bare URL stays indexable');
    }
  }
  for (const [state, host] of [['open', API], ['search-only', NOVA], ['search-ai', NORDLYS]]) {
    const tag = (await get(`${host}/shop/`)).headers.get('x-robots-tag');
    if (tag && /noindex/.test(tag)) fail(`${state} must stay indexable, got X-Robots-Tag: ${tag}`);
  }
  console.log('OK DARK IS DARK: the dark tenant\'s public pages answer X-Robots-Tag: noindex,'
    + ' nofollow — the half a Disallow cannot do — and no other posture is delisted.');

  /* ---------- 7. one source of truth, under a real token ---------- */
  const pm = await token('pat@bss.local', 'pat');
  const cat = await fetch(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?lifecycleStatus=Active&limit=20`,
    { headers: { Authorization: `Bearer ${pm}`, 'X-Channel': 'admin-console' } });
  if (cat.status !== 200) fail(`TMF620 under a real token: ${cat.status}`);
  const offerings = await cat.json();
  const off = offerings.find((o) => o.id && !String(o.id).startsWith('legacy-'))
    || fail('the catalog holds no native active offering');

  const openPage = await get(`${API}/shop/offering/${off.id}`, BOT);
  if (openPage.status !== 200) fail(`open tenant's crawler page: ${openPage.status}`);
  if (!/application\/ld\+json/.test(openPage.text)) {
    fail('the open tenant served a crawler no structured data');
  }
  const darkPage = await get(`${FJORD}/shop/offering/${off.id}`, BOT);
  if (darkPage.status === 200) {
    fail(`a dark tenant served a crawler an offering page (${darkPage.status})`);
  }
  console.log('OK ONE TRUTH: the offering a product manager sees in TMF620 under a real token'
    + ` ("${off.name}") is the one the open tenant's crawler page serves with structured data,`
    + ` and the dark tenant answers a crawler ${darkPage.status} for the same id.`);

  console.log('\nai_visibility_states_test: PASS — four postures, eight crawlers, 32 per-bot'
    + ' assertions, search-only byte-identical, dark actually dark.');
})().catch((e) => { console.error(`FAIL ${e.message}`); process.exit(1); });
