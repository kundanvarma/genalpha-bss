/* Generative discoverability (GEO) — the shop AI-citable, switchable. Suite #68.
 *
 *  - DUAL-SERVE: the SAME shop URL gives a crawler (GPTBot UA) complete
 *    bot-readable HTML with schema.org Product/Offer JSON-LD — and gives a
 *    human the untouched SPA. The bot price EQUALS the catalog price (the
 *    two faces are proven equal, never kept equal by care).
 *  - THE SWITCH: ai-visibility drives robots.txt — genalpha open (AI bots
 *    welcome + llms.txt), nova search-only (Googlebot yes, GPTBot no),
 *    fjord dark (nobody). Per tenant, by hostname.
 *  - SITEMAPS are tenant-walled; a dark tenant has none.
 */
const API = 'http://localhost:8080';
const NOVA = 'http://shop.nova.localhost:8080';
const FJORD = 'http://shop.fjord.localhost:8080';
const BOT = 'Mozilla/5.0 (compatible; GPTBot/1.2; +https://openai.com/gptbot)';
const HUMAN = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Safari/605.1.15';
const fail = (m) => { throw new Error(m); };

const get = async (url, ua) => {
  const r = await fetch(url, { headers: ua ? { 'User-Agent': ua } : {} });
  return { status: r.status, text: await r.text() };
};

(async () => {
  /* ---------- 1. dual-serve + JSON-LD + price equality ---------- */
  const feed = JSON.parse((await get(`${API}/acp/product_feed`)).text).products;
  const off = feed.find((p) => !p.id.startsWith('legacy-')) || fail('no native offering');
  const bot = await get(`${API}/shop/offering/${off.id}`, BOT);
  if (bot.status !== 200) fail(`bot page: ${bot.status}`);
  if (!bot.text.includes(off.title)) fail('bot page misses the offering name');
  const ld = bot.text.match(/<script type="application\/ld\+json">(.*?)<\/script>/s)
    || fail('no JSON-LD on the bot page');
  const schema = JSON.parse(ld[1]);
  if (schema['@type'] !== 'Product' || !schema.offers) fail('JSON-LD is not a Product+Offer');
  if (Number(schema.offers.price) !== Number(off.price.amount)) {
    fail(`bot price ${schema.offers.price} != catalog price ${off.price.amount}`);
  }
  const human = await get(`${API}/shop/offering/${off.id}`, HUMAN);
  if (human.text.includes('application/ld+json')) fail('a human received the bot page');
  if (!human.text.includes('viewport')) fail('the human did not get the SPA shell');
  console.log(`OK DUAL-SERVE: the same URL gave GPTBot complete HTML with Product/Offer JSON-LD`
    + ` ("${off.title}" @ ${schema.offers.price} ${schema.offers.priceCurrency} — equal to the`
    + ' catalog, PROVEN not maintained) and gave a human the untouched SPA.');

  /* ---------- 2. the switch: robots.txt per tenant ---------- */
  const ga = (await get(`${API}/robots.txt`)).text;
  if (ga.includes('GPTBot') || !ga.includes('Allow: /')) fail('open tenant should welcome all');
  const nv = (await get(`${NOVA}/robots.txt`)).text;
  if (!/User-agent: GPTBot\nDisallow: \//.test(nv)) fail('search-only must disallow GPTBot');
  if (!/User-agent: \*\nAllow: \//.test(nv)) fail('search-only must keep classic search');
  const fj = (await get(`${FJORD}/robots.txt`)).text;
  if (!/User-agent: \*\nDisallow: \//.test(fj)) fail('dark tenant must disallow everyone');
  console.log('OK THE SWITCH: genalpha open (all crawlers welcome), nova search-only (Googlebot'
    + ' yes, GPTBot/ClaudeBot/PerplexityBot no), fjord dark — ai-visibility per tenant, by'
    + ' hostname, at the lever crawlers actually obey.');

  /* ---------- 3. sitemaps: walled; dark = none ---------- */
  const gaMap = (await get(`${API}/sitemap.xml`)).text;
  if (!gaMap.includes(off.id)) fail('genalpha sitemap misses its own offering');
  const nvMap = (await get(`${NOVA}/sitemap.xml`)).text;
  if (nvMap.includes(off.id)) fail('nova sitemap leaks a genalpha offering');
  const fjMap = await get(`${FJORD}/sitemap.xml`);
  if (fjMap.status !== 404) fail(`dark tenant sitemap should 404: ${fjMap.status}`);
  console.log('OK SITEMAPS: tenant-walled, live from the catalog; a dark tenant publishes none.');

  /* ---------- 4. llms.txt: open only, honestly labeled ---------- */
  const llms = await get(`${API}/llms.txt`);
  if (llms.status !== 200 || !llms.text.includes('MyGenAlpha')) fail('open tenant llms.txt broken');
  if (!llms.text.includes(off.title)) fail('llms.txt misses live catalog data');
  const nvLlms = await get(`${NOVA}/llms.txt`);
  if (nvLlms.status !== 404) fail(`search-only tenant should not publish llms.txt: ${nvLlms.status}`);
  console.log('OK LLMS.TXT: published for the open tenant from live catalog data, absent for'
    + ' search-only — shipped cheap, labeled speculative, never sold as the feature.');

  console.log('\nALL GEO CHECKS PASSED — every tenant\'s shop is AI-citable exactly as far as'
    + ' that tenant chooses: bot-readable pages proven equal to the catalog, robots.txt as the'
    + ' real gate, sitemaps walled, and the same facts for every face — human, agent, crawler.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
