/* One projection, many surfaces: change a price once, watch it move everywhere. Suite #248.
 *
 * SEO-3 (#179). Five public faces of the catalog — the crawler page, its
 * schema.org document, sitemap.xml, llms.txt and the agentic-commerce feed —
 * used to assemble TMF620 for themselves. Five readers, five chances to
 * disagree, and they did.
 *
 * THE DEFECT THIS SUITE EXISTS FOR, measured on a running fleet before the
 * change: the seeded triple-play bundle was published as 64.98 EUR a month on
 * its crawlable page and as 49.00 EUR one-time in the feed AI shopping agents
 * ingest. 49.00 is the fibre INSTALLATION FEE. Same catalog rows, same
 * instant, two answers — and the wrong one was the one an agent would quote.
 * The feed's rule was "the first one-time price"; the page's rule was "the
 * recurring charge, summed". Both were defensible alone; together they were a
 * commercial misstatement.
 *
 * What is proven here, end to end through the gateway with a real token:
 *
 *  - ONE PRICE, EVERY SURFACE: a fixture offering with a recurring charge AND
 *    a one-time fee is created, and all four surfaces are read. Each must
 *    carry the SAME headline, and it must be the recurring one.
 *  - THE MOVE: the recurring price is then raised by a known amount — ONE
 *    PATCH, to one catalog row — and all four are read again. Every one of
 *    them must show the new number. This is the assertion the ticket asks
 *    for, and the only thing that keeps "one projection" true: without it the
 *    claim degrades into a fourth assembler within a release or two.
 *  - ONE RULE FOR MEMBERSHIP: an offering taken out of its window disappears
 *    from the sitemap, from llms.txt and from the feed together. It used to
 *    linger in the sitemap, which lists every Active offering — a different
 *    question — so a crawler was invited to a page the catalog then refused.
 *  - DELIBERATE DIFFERENCES, NAMED: an offering with no price at all is
 *    carried by the sitemap and dropped by the agentic feed. That is not drift
 *    — a feed row an agent cannot price is noise, while a page that says
 *    "talk to us" is a legitimate page. Asserted so the difference stays a
 *    decision rather than becoming an accident again.
 *  - PROVENANCE: the feed row and the page agree on the offering id, so a fact
 *    published to an agent can be traced back to the catalog row it came from.
 *
 * HONEST LIMITS: the crawler page and the feed are read as an anonymous
 * crawler, which is what they are for; the fixture rides the default tenant.
 * This suite does not assert the sitemap's paging to exhaustion or an index
 * above the shard threshold — that is SEO-4's business, and saying so here is
 * cheaper than discovering later that nobody checked.
 *
 * Fixtures are created, asserted and deleted.
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CAT = '/tmf-api/productCatalogManagement/v4';
/* The gateway dual-serves by User-Agent: a crawler asking for the SHOP url is
 * handed the server-rendered page. Asking the component's own /seo path through
 * the gateway is not a route, and a suite that did would prove nothing a
 * crawler experiences. */
const BOT = 'Mozilla/5.0 (compatible; GPTBot/1.2; +https://openai.com/gptbot)';

const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);
const tag = `SEO3${Date.now()}`;
const fixtures = { offerings: [], prices: [] };

async function form(url, params) {
  const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(params) });
  return r.json();
}
async function token(user, pass) {
  const j = await form(KC, { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass });
  if (!j.access_token) fail(`token(${user}) refused`);
  return j.access_token;
}
async function call(method, p, tok, body) {
  const r = await fetch(API + p, {
    method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}), 'Cache-Control': 'no-cache' },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* not json */ }
  return { status: r.status, body: json, text };
}
async function created(method, p, tok, body) {
  const r = await call(method, p, tok, body);
  if (r.status !== 201 && r.status !== 200) fail(`${method} ${p}: ${r.status} ${r.text.slice(0, 300)}`);
  return r.body;
}
async function crawl(path) {
  const r = await fetch(API + path, { headers: { 'User-Agent': BOT, 'Cache-Control': 'no-cache' } });
  return { status: r.status, text: await r.text() };
}

/* ---------- reading each surface for ONE offering ---------- */

/** The number the crawler page's structured data leads with. */
async function fromJsonLd(id) {
  const { status, text } = await crawl(`/shop/offering/${id}`);
  if (status !== 200) return { absent: true, status };
  const m = text.match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/);
  if (!m) fail('the crawler page carries no JSON-LD');
  let doc; try { doc = JSON.parse(m[1]); } catch (e) { fail(`JSON-LD does not parse: ${e.message}`); }
  return { price: doc.offers && doc.offers.price, period: doc.offers && doc.offers.priceSpecification && doc.offers.priceSpecification.unitCode };
}
/** The number the human-readable line on the same page leads with. */
async function fromPageText(id) {
  const { status, text } = await crawl(`/shop/offering/${id}`);
  if (status !== 200) return { absent: true, status };
  // THE PAGE A HUMAN READS IS THE STOREFRONT'S OWN SSR, and has been since the
  // Java bot page that used to answer /shop/offering/** was deleted (the gateway
  // says so where it routes this path: the machine-readable head now comes from
  // /seo/offering/{id}/meta instead). This used to look for that dead page's
  // `Price: <b>…</b>`, match nothing, and — because Number('') is 0 — report the
  // page as publishing 0.00. A missing price read as a free product, for however
  // many runs: the extractor broke and the number it invented looked like a bug
  // in the shop.
  //
  // The price table's total row is the monthly figure a reader sees:
  //   <tr class="total"><td>Total per month</td><td class="num">42.00 NOK</td></tr>
  const m = text.match(/<tr class="total">.*?<td class="num">([^<]*)<\/td>/s);
  return { price: m && m[1].trim() };
}
/** The number llms.txt quotes for it. */
async function fromLlms(id) {
  const { text } = await crawl('/llms.txt');
  const line = text.split('\n').find((l) => l.includes(`/shop/offering/${id})`));
  if (!line) return { absent: true };
  const m = line.match(/— ([0-9.]+) ([A-Z]{3})(\/([a-z]+))?/);
  return { price: m && m[1], period: m && m[4] };
}
/** The number the agentic-commerce feed publishes for it. */
async function fromFeed(id) {
  const { text } = await crawl(`/acp/product_feed?id=${id}`);
  let doc; try { doc = JSON.parse(text); } catch { fail('the agent feed is not JSON'); }
  const row = (doc.products || []).find((p) => p.id === id);
  if (!row) return { absent: true };
  return { price: row.price && row.price.amount, type: row.price_type, period: row.recurring_period, link: row.link };
}
/** Whether the sitemap invites a crawler to it. */
async function inSitemap(id) {
  const { text } = await crawl('/sitemap.xml');
  return text.includes(`/shop/offering/${id}<`);
}

/* A price that was never found is NOT zero. Number('') is 0, so the old spelling
 * turned "the extractor matched nothing" into a confident "0.00" — the failure
 * that hid a broken extractor behind what looked like a product defect. */
const money = (v) => (v == null || String(v).trim() === '' ? null : Number(v).toFixed(2));

(async () => {
  const staff = await token('pat@bss.local', 'pat');

  /* ---------- the fixture: a line with a monthly charge and a joining fee ---------- */

  const monthly = await created('POST', `${CAT}/productOfferingPrice`, staff, {
    name: `${tag} monthly`, priceType: 'recurring', recurringChargePeriodType: 'month',
    price: { unit: 'EUR', value: 42.00 }, lifecycleStatus: 'Active',
  });
  fixtures.prices.push(monthly.id);
  const joining = await created('POST', `${CAT}/productOfferingPrice`, staff, {
    name: `${tag} joining fee`, priceType: 'oneTime',
    price: { unit: 'EUR', value: 99.00 }, lifecycleStatus: 'Active',
  });
  fixtures.prices.push(joining.id);

  const offering = await created('POST', `${CAT}/productOffering`, staff, {
    name: `${tag} Fibre`, description: 'A line with a monthly charge and a joining fee.',
    lifecycleStatus: 'Active', isSellable: true,
    productOfferingPrice: [{ id: monthly.id }, { id: joining.id }],
  });
  fixtures.offerings.push(offering.id);
  ok(`fixture offering ${offering.id} — 42.00/month plus a 99.00 joining fee`);

  /* ---------- 1. every surface leads with the same charge ---------- */

  const read = async (where) => ({
    jsonLd: await fromJsonLd(offering.id),
    pageText: await fromPageText(offering.id),
    llms: await fromLlms(offering.id),
    feed: await fromFeed(offering.id),
    where,
  });

  const before = await read('before');
  const seen = {
    'schema.org JSON-LD': money(before.jsonLd.price),
    'the page a human reads': money((before.pageText.price || '').split(' ')[0]),
    'llms.txt': money(before.llms.price),
    'the agentic feed': money(before.feed.price),
  };
  console.log('   ' + JSON.stringify(seen));
  for (const [surface, value] of Object.entries(seen)) {
    if (value === null) {
      fail(`${surface} published NO PRICE this suite could read — the surface or its`
        + ' markup changed and the reader did not. That is a broken check, not a free product.');
    }
    if (value !== '42.00') {
      fail(`${surface} published ${value}, not the 42.00 monthly charge`
        + ' — this is the defect SEO-3 exists to close (the joining fee was 99.00)');
    }
  }
  ok('all four surfaces lead with the 42.00 monthly charge, not the 99.00 joining fee');

  if (before.feed.type !== 'recurring') fail(`the feed calls it ${before.feed.type}, not recurring`);
  if (before.feed.period !== 'month') fail(`the feed says the period is ${before.feed.period}`);
  if (before.jsonLd.period !== 'MON') fail(`the JSON-LD says the period is ${before.jsonLd.period}`);
  ok('and all of them say it RECURS monthly — a joining fee has no period');

  /* ---------- 2. the move: one PATCH, four surfaces ---------- */

  await created('PATCH', `${CAT}/productOfferingPrice/${monthly.id}`, staff,
    { price: { unit: 'EUR', value: 47.50 } });
  ok('the monthly charge is raised to 47.50 — ONE row, in the catalog');

  const after = await read('after');
  const moved = {
    'schema.org JSON-LD': money(after.jsonLd.price),
    'the page a human reads': money((after.pageText.price || '').split(' ')[0]),
    'llms.txt': money(after.llms.price),
    'the agentic feed': money(after.feed.price),
  };
  console.log('   ' + JSON.stringify(moved));
  for (const [surface, value] of Object.entries(moved)) {
    if (value !== '47.50') {
      fail(`${surface} still says ${value} after the catalog moved to 47.50`
        + ' — it is assembling TMF620 for itself again');
    }
  }
  ok('one price changed once, and it moved on every surface');

  /* ---------- 3. one rule decides who is listed ---------- */

  const past = new Date(Date.now() - 86400000).toISOString();
  await created('PATCH', `${CAT}/productOffering/${offering.id}`, staff, {
    validFor: { startDateTime: '2020-01-01T00:00:00Z', endDateTime: past },
  });
  ok('the offering is taken out of its window — still Active, but no longer sellable');

  const gone = { sitemap: await inSitemap(offering.id), llms: await fromLlms(offering.id), feed: await fromFeed(offering.id) };
  if (gone.sitemap) fail('the sitemap still invites a crawler to an out-of-window offering');
  if (!gone.llms.absent) fail('llms.txt still quotes an out-of-window offering to answer engines');
  if (!gone.feed.absent) fail('the agent feed still sells an out-of-window offering');
  ok('out of its window, it left the sitemap, llms.txt and the agent feed together');

  /* ---------- 4. a difference that is a decision, not drift ---------- */

  const unpriced = await created('POST', `${CAT}/productOffering`, staff, {
    name: `${tag} Enterprise`, description: 'Priced on application.',
    lifecycleStatus: 'Active', isSellable: true,
  });
  fixtures.offerings.push(unpriced.id);

  if (!(await inSitemap(unpriced.id))) fail('an unpriced offering has a page; the sitemap should list it');
  const unpricedFeed = await fromFeed(unpriced.id);
  if (!unpricedFeed.absent) fail('the agent feed carried a row it cannot price');
  ok('an offering with no price is listed for crawlers and withheld from agents — on purpose');

  /* ---------- 5. provenance ---------- */

  const liveFeed = await fromFeed(fixtures.offerings[0]);
  if (!liveFeed.absent && liveFeed.link !== `/shop/offering/${fixtures.offerings[0]}`) {
    fail('the feed row does not link back to the offering it came from');
  }
  ok('every feed row links back to the catalog offering behind it');

  /* ---------- cleanup ---------- */

  for (const id of fixtures.offerings) await call('DELETE', `${CAT}/productOffering/${id}`, staff);
  for (const id of fixtures.prices) await call('DELETE', `${CAT}/productOfferingPrice/${id}`, staff);
  ok('fixtures removed');

  console.log('\nPASS one_projection_test');
})().catch(async (e) => {
  console.error('\nFAIL ' + e.message);
  try {
    const staff = await token('pat@bss.local', 'pat');
    for (const id of fixtures.offerings) await call('DELETE', `${CAT}/productOffering/${id}`, staff);
    for (const id of fixtures.prices) await call('DELETE', `${CAT}/productOfferingPrice/${id}`, staff);
  } catch { /* the failure above is the news */ }
  process.exit(1);
});
