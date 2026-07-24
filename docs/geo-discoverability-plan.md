# Generative discoverability (GEO) — every tenant's shop AI-citable, switchable — plan

*2026-07-24. The agentic funnel has a transaction half (ACP — agents can
BUY, suite #64) but only half a discovery half: AI answer engines
(ChatGPT, Perplexity, Gemini) recommending a tenant when a human asks
"best mobile plan in Norway" is where ~25% of search is heading. This
arc makes each tenant's shop citable by generative engines — per tenant,
switchable, measured.*

## Research findings (the honest ones)

1. **AI crawlers do NOT execute JavaScript.** GPTBot, ClaudeBot,
   PerplexityBot, OAI-SearchBot read the initial HTML only — so a React
   SPA storefront is largely INVISIBLE to them today, whatever schema it
   carries. **Bot-readable HTML is the load-bearing fix**; everything
   else is decoration without it.
2. **llms.txt is mostly theater right now**: ~10% site adoption, but
   across 515M analyzed LLM-bot events, `/llms.txt` fetches were
   statistically negligible and no major crawler officially consumes it.
   Ship it cheap, label it speculative, never sell it as the feature.
3. **The real control plane is robots.txt**: legitimate AI crawlers
   respect per-agent rules (GPTBot, Google-Extended, ClaudeBot,
   PerplexityBot) — that is where a tenant's "do I appear in AI answers?"
   choice actually executes.
4. **Structured data (schema.org JSON-LD) + entity clarity** are the
   citation signals GEO and SEO share — and ours generates from the
   TMF620 catalog for free.
5. Our **ACP feed is already ahead of the field** — direct structured
   ingestion for shopping agents; GEO extends the same posture to answer
   engines.

## Design — four pieces, honestly weighted

### 1. Bot-readable offering pages (the load-bearing piece)

A **crawler-facing server-rendered variant** of the storefront's public
pages, served by the storefront container itself (no new component):
requests whose User-Agent matches known AI/search crawlers (or path
`/shop/seo/...`) get plain, complete HTML generated from the catalog —
offering name, description, price, category, FAQ from the knowledge
base — with the SPA untouched for humans. Per-tenant branded, i18n'd.
NOT cloaking: same facts the SPA renders, in crawlable form.

### 2. Structured data + the entity layer

- **JSON-LD** on those pages: `Product` + `Offer` (price/currency/
  availability from TMF620), `Organization` (the tenant's brand),
  `FAQPage` on knowledge articles surfaced as a public help center —
  tenant articles are ready-made citation fodder.
- **sitemap.xml per tenant** (offerings + articles), generated live.

### 3. The switch: `ai-visibility` per tenant

The agent-commerce lesson applied to discovery: a per-tenant registry
field `ai-visibility: open | search-only | dark` driving the tenant's
**robots.txt** (the lever crawlers actually obey):
- `open` — all crawlers welcome (default for demo tenants)
- `search-only` — classic search yes; AI training/answer bots
  (GPTBot, Google-Extended, ClaudeBot, PerplexityBot…) disallowed
- `dark` — no crawlers at all
Plus `llms.txt` generated per tenant when `open` — cheap, honest,
labeled speculative in the docs. Live-refreshed like every switch;
newborn tenants default `search-only` (conservative, like agent-commerce
defaults off).

### 4. Measurement (insight component)

AI-referrer attribution: visits with `Referer` from chatgpt.com /
perplexity.ai / gemini.google.com etc. tagged as channel `ai-answer` in
the insight event stream — so GEO lift shows up in the same honest
analytics as campaign lift. No new dashboard in v1; the events carry it.

## The proof (suite #68, geo_discoverability_test.js)

1. A GPTBot-user-agent fetch of an offering page gets COMPLETE HTML
   (name + price present without JS) with valid `Product`/`Offer`
   JSON-LD; a human UA still gets the SPA.
2. robots.txt follows the switch: genalpha `open` (AI bots allowed),
   flip a tenant to `search-only` → GPTBot disallowed while Googlebot
   stays allowed; `dark` → all disallowed. Live-refresh, no restart.
3. sitemap.xml lists real offerings per tenant; nova's sitemap carries
   none of genalpha's.
4. llms.txt exists for an `open` tenant and names the brand + catalog.
5. An ai-answer referred visit lands in insight tagged `ai-answer`.

## Order of work

1. Storefront crawler-variant renderer + JSON-LD + sitemap (the SPA gap).
2. `ai-visibility` switch → per-tenant robots.txt + llms.txt.
3. Insight referrer tagging; suite #68; regressions (storefront, #64);
   docs + landing ("citable by AI answer engines, switchable — and the
   honest note on llms.txt").

## Shipped

**2026-07-24 — suite #68 green first run.** Landed: the crawler-facing
render path on the catalog service (`/seo/offering/{id}`, sitemap.xml,
robots.txt, llms.txt — all generated LIVE from TMF620, nothing authored,
nothing synced) with the gateway DUAL-SERVING by User-Agent route
predicate: the same `/shop/offering/{id}` URL gives GPTBot-class
crawlers complete HTML with schema.org Product/Offer JSON-LD and gives
humans the untouched SPA. The suite proves the two faces EQUAL (bot
price == catalog price) rather than trusting them maintained. The
`ai-visibility: open | search-only | dark` switch landed in the registry
(genalpha open / nova search-only / fjord dark — all three proven by
hostname), driving robots.txt — including the middle state operators
will actually want: classic search yes, AI answer/training bots no.
llms.txt ships for open tenants only, labeled speculative. Newborn
tenants default search-only (appendTenantBlock forces it). DEFERRED,
honestly: the insight ai-answer referrer tagging (leg 5) — it touches
the beacon schema and gets its own slot; the knowledge-base FAQ pages as
public help-center are the second follow-up. Legacy-federated offerings
ride the bot pages automatically (the price fallback covers embedded
refs). Regressions green: storefront, agentic_commerce #64.
