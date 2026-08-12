/* The Claude market provider — an LLM inside a seam, numbers never from the model. Suite #99.
 *
 * A bring-your-own market-feed provider behind the SAME wire as mock-market:
 * Claude CURATES a messy raw tariff dataset (it only SELECTS source-row ids and
 * names the competitor); every emitted name/price/dataGb is then EXTRACTED AND
 * COPIED from the source row by code — model recall can never mint a number.
 * No API key = a deterministic rule-based curation of the same source
 * (fail-open); either mode must satisfy every check below.
 *
 *  - WIRE: /offers speaks mock-market's exact shape (the Advisor can't tell).
 *  - TOKEN: no bearer → 401 (a subscription, like the real thing).
 *  - EXTRACT-AND-COPY: every offer's name/dataGb/price EXACTLY equals its
 *    source row (notes carries src:<rowId> · as-of <date> — the work shown).
 *  - CURATED: wrong-market, non-mobile, draft and garbage rows are absent.
 *  - MODE HONESTY: /health names the curation mode (model | deterministic).
 *
 * The Advisor-side acceptance is suite #52 (product_advisor_test) run against
 * this provider — same findings, same numbers, no advisor change.
 */
const BASE = 'http://localhost:8138';
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);

(async () => {
  /* ---------- 1. wire + token ---------- */
  const noTok = await fetch(`${BASE}/offers`);
  if (noTok.status !== 401) fail(`no token should be 401, got ${noTok.status}`);
  const res = await fetch(`${BASE}/offers`, { headers: { Authorization: 'Bearer suite-subscription-token' } });
  if (res.status !== 200) fail(`offers: ${res.status}`);
  const offers = await res.json();
  if (!Array.isArray(offers) || !offers.length) fail('no offers curated');
  for (const o of offers) {
    for (const k of ['competitor', 'name', 'dataGb', 'price', 'notes']) {
      if (o[k] === undefined) fail(`offer missing '${k}' (wire drift): ` + JSON.stringify(o));
    }
    if (typeof o.price.value !== 'number' || !o.price.unit) fail('price shape drift: ' + JSON.stringify(o.price));
  }
  ok(`WIRE + TOKEN: ${offers.length} offers in mock-market's exact shape; no bearer → 401`);

  /* ---------- 2. extract-and-copy: every number comes from the source ---------- */
  const raw = await (await fetch(`${BASE}/source`)).json();
  const byId = new Map(raw.rows.map((r) => [r.id, r]));
  for (const o of offers) {
    const m = /src:([^\s·]+)/.exec(o.notes || '');
    if (!m) fail('offer without a source ref (the work not shown): ' + JSON.stringify(o));
    const src = byId.get(m[1]);
    if (!src) fail(`offer cites source row '${m[1]}' which does not exist — a hallucinated id survived`);
    const gb = Number(src.data_gb ?? src.dataGb ?? src.gb);
    const price = Number(src.monthly_price ?? src.price);
    if (o.name !== src.plan_name || o.dataGb !== gb || o.price.value !== price) {
      fail(`offer diverges from its source row ${m[1]} — a model-minted value got through: `
        + JSON.stringify(o) + ' vs ' + JSON.stringify(src));
    }
    if (!(o.notes || '').includes(`as-of ${raw.asOf}`)) fail('offer missing the as-of date: ' + o.notes);
  }
  ok('EXTRACT-AND-COPY: every offer\'s name/dataGb/price EXACTLY equals its cited source row,'
    + ' and every offer carries src ref + as-of — no model-minted numbers, provably');

  /* ---------- 3. curated: the junk is gone ---------- */
  const names = offers.map((o) => o.name).join(' | ');
  for (const [bad, why] of [['Svea Mix 20', 'wrong market'], ['RivalTel Fiber 500', 'not mobile'],
    ['DRAFT', 'draft row'], ['BizCom Fleet 100', 'business segment']]) {
    if (names.includes(bad)) fail(`curation let through a ${why} row: ${bad}`);
  }
  ok('CURATED: wrong-market, non-mobile, draft and business rows are all absent from the feed');

  /* ---------- 4. mode honesty ---------- */
  const health = await (await fetch(`${BASE}/health`)).json();
  if (!health.mode) fail('health does not name the curation mode');
  ok(`MODE HONESTY: /health names the curation mode — "${health.mode}" (model with a key,`
    + ' deterministic without; both must pass every check above)');

  console.log('\nALL MARKET-PROVIDER CHECKS PASSED — the fleet\'s first LLM-inside-a-seam adapter:'
    + ' the model curates and pairs, the CODE copies and verifies every number against the source,'
    + ' the pairing shows its work, and the seam fails open to rules. The Advisor cannot tell.');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
