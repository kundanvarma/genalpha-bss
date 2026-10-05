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

**2026-10-03 — SEO-2 (#178): the structured data is serialised, and three
facts stopped being constants.** Suite #246 (`geo_structured_data_test`).

What an operator can now rely on, in operator language:

- **A product name can contain anything.** The crawler-facing document used to
  be assembled from 22 string fragments with its own escaping, so the first
  offering named with a quote, a backslash or a newline published structured
  data no crawler would accept — and a rejected document is invisible, not
  merely untidy. It is now written by Jackson from records (`SchemaOrg`,
  `SchemaOrgProjection`, `JsonLd`). `Fiber 500 "Pro" \ Home`, with a newline
  and a `</script>` tag in its description, round-trips byte for byte.
- **Availability is read, not assumed.** Every page used to say *InStock*,
  including a mobile plan, which is not a thing a warehouse keeps. The
  warehouse now answers for a stock-managed product (*InStock* / *OutOfStock*,
  from the TMF687 rows the configurator already reads) and ordering semantics
  answer where it keeps no rows (*OnlineOnly*, or *InStoreOnly* for a
  dealer-only offer, or *Discontinued* once it is off the ladder).
- **The headline price is the one the customer pays every month.** The
  generator preferred the one-time component, so *GenAlpha Fiber 1000* — a
  39.99/month line with a 49.00 installation fee — advertised **49.00** as its
  price. That is a commercial misstatement, not a formatting slip. A
  subscription's Offer now carries the sum of its recurring charges (the same
  arithmetic the shop shows a human, bundle discount included) with the
  billing period declared beside it, and the page still tells a person about
  the one-time charge in words.
- **Language and money belong to the operator.** The page declares the
  tenant's own `locale` (genalpha `en`, nova `no`) instead of `en` for
  everybody, and prices in the tenant's own `currency` where a price names no
  unit of its own. The shop shell (`apps/storefront/index.html`) no longer
  spells a language at all: the gateway's per-hostname `tenant-config.js`
  stamps it, because one build serves every operator.
- **Pictures and specifications reach the page.** Image attachments are
  published as absolute, fetchable URLs (a datasheet beside them is not a
  product image), and specification characteristics — data allowance, speed,
  network, roaming — are structured properties with their units instead of
  being dropped.

### Honest limits

- **"Validates" is not what is asserted.** There is no offline schema.org
  validator in this repo and the suite has no network, so suite #246 asserts
  **well-formed JSON** (a real parse of the exact bytes in the script element)
  plus the presence and correctness of the required fields. Whether Google
  accepts the document is a separate question and is deliberately not claimed.
- **No reviews, no ratings.** This page displays none, so publishing them
  would be fabricated structured data — the kind that gets a site penalised.
  Nothing in the generator can emit them.
- **Two availability answers are a guard, not a proven path.** The catalog's
  own door already answers 404 for a retired, expired or dealer-only offering
  to an anonymous crawler, so *Discontinued* and *InStoreOnly* cannot be
  reached through the page today. They are pinned by the service's unit tests
  and will become reachable when SEO-1/SEO-3 change that door; the suite says
  plainly that it does not walk them.
- **A characteristic named as a code stays off the page.** A fact the author
  wrote as `chargingSpecId`, `volte` or `sliceProfile`, with no description
  and no unit, is internal and is withheld — screens speak operator language,
  and a crawler's page is a screen. The consequence is that a genuinely
  customer-facing fact authored as a key (`dataAllowance`, `maxDownMbps`) is
  withheld too. The fix is to name it for a person in the catalog, not to
  loosen the rule.
- **One truthful headline, not a breakdown.** The Offer carries one price. A
  full breakdown — activation fees, early-termination, per-seat arithmetic —
  belongs in the richer feeds, and the schema Offer is not the place to put
  it.
- **Absolute URLs need the gateway.** They are built from the gateway's
  `X-Forwarded-Host`, which is the only thing that knows the host a visitor
  came in on; a request that reaches the component directly keeps relative
  URLs rather than publishing an internal service name.

## The fourth posture — "AI search yes, training no" (SEO-1, #177)

**2026-10-03 — suite #246, `ops/e2e/ai_visibility_states_test.js` (#246).**

The three-state switch above had a governance bug in it, found by an
engineering review of the Shop spec (#176) rather than by a failure. The
state named *"classic search yes, AI answer/training bots no"* blocked
`GPTBot` and `OAI-SearchBot` in the same list. Those are not the same
crawler and OpenAI documents them as separately controllable: `GPTBot`
collects pages into a training corpus, `OAI-SearchBot` fetches a page to
answer a question now and cite the source. Blocking them together meant
**no tenant could express the posture most operators actually want** —
be findable in an AI answer, stay out of the training data. It was not a
crash; it was a setting an operator would have believed.

### What a tenant can now say

| `ai-visibility` | Classic search | AI search / retrieval | Training crawlers | `llms.txt` | noindex |
|---|---|---|---|---|---|
| `dark` | blocked | blocked | blocked | 404 | **yes** |
| `search-only` | allowed | blocked | blocked | 404 | no |
| `search-ai` *(new)* | allowed | **allowed** | blocked | 200 | no |
| `open` | allowed | allowed | allowed | 200 | no |

`search-only` was **not** redefined. A tenant is live on it, and quietly
changing the meaning of a posture an operator chose would move their
privacy stance without them asking. Its document is pinned twice — by
sha256 in the suite against the bytes captured from the running fleet
before the split, and as a literal in `CrawlerPolicyTest` — so a reorder
or an added vendor name turns a build red rather than shipping.

### Retrieval and training are a tag, not two lists

`bss.geo.crawlers` (product-catalog) is **one ordered list** of
`user-agent:group` entries, read from configuration with a
`BSS_GEO_CRAWLERS` override, because which bots exist and what each is
for is a third-party fact that drifts — a vendor rename must not need a
release. One ordered list rather than a retrieval list and a training
list, on purpose: emitting a retrieval block and then a training block
would have reordered the live tenant's robots.txt, and byte-identical is
the whole requirement. `CrawlerPolicy` generates the document; nothing
about a crawler is a constant in code.

### A Disallow is not a noindex

`dark` published `User-agent: *\nDisallow: /` and nothing else, which
asks a crawler not to **fetch** the page. It does not ask anyone not to
**list** it: a search engine that finds a dark tenant's URL on somebody
else's site can index the bare URL *precisely because* it obeyed the
Disallow and never learned there was nothing to show. A dark tenant's
public responses now carry `X-Robots-Tag: noindex, nofollow`, stamped at
the gateway by `CrawlerVisibilityFilter` — the gateway because that is
the only place a per-tenant header can reach a page (the storefront
serves one static build and knows nothing about tenants), and only for
`dark`, because noindex on a `search-only` tenant would delist the
classic search it explicitly asked to keep.

### Honest limits

- **Crawler names and their separation are a third-party fact** as of
  3 October 2026. That is why the roster is configuration: it can be
  corrected by an operator without a release. It will drift.
- **`robots.txt` is advisory.** A crawler that ignores it is unaffected
  by any of this. This makes the *stated* policy expressible, not
  enforced; nothing here is a technical control.
- **The shipped roster is exactly the eight names `search-only` already
  blocked**, no additions. Anthropic's `Claude-SearchBot` and Google's
  own retrieval agents belong in the retrieval group and are **not**
  there, because adding a name changes the live tenant's published
  document. Growing the roster is a deliberate decision, made in
  configuration, not a tidy-up.
- **noindex is a header, not a meta tag.** The storefront ships one
  static shell for every tenant, so a per-tenant `<meta name="robots">`
  needs the server-side rendering of #180. Crawlers honour the header;
  this is the mechanism, not a workaround — but a reader looking for the
  tag in the HTML will not find it.
- **`search-ai` is carried by a simulation tenant** (`nordlys`) so the
  suite can prove the two groups diverge in one document. No live
  operator was moved onto it.
- The 2026-07-24 deferrals above still stand: insight `ai-answer`
  referrer tagging and public help-center FAQ pages are unbuilt.

## One projection behind every surface (SEO-3, #179)

*Built 4 October 2026. The foundation the rendering change sits on.*

Five public faces of the catalog — the crawler page, its schema.org document,
`sitemap.xml`, `llms.txt` and the agentic-commerce feed — each assembled TMF620
for themselves. Five readers, five chances to disagree, and they did.

### What was measured, before anything was written

On a running fleet, the seeded triple-play bundle *GenAlpha One Home & Mobile*
was published two ways at the same instant:

| Surface | Published | |
|---|---|---|
| crawler page + JSON-LD | **64.98 EUR / month** plus 49.00 once | the monthly charge |
| agentic-commerce feed | **49.00 EUR one-time** | the fibre *installation fee* |

Both numbers came from the same four catalog rows (25.00 + 39.99 − 15.00 +
14.99). Only the rule for choosing between them differed: the page summed the
recurring charges, the feed took the first one-time price. Each rule was
defensible alone. Together they were a commercial misstatement, and the wrong
one was the number an AI shopping agent would quote.

Two more disagreements, same cause:

- **Availability.** The page computed it from the warehouse and the lifecycle;
  the feed published the constant `in_stock` for everything.
- **Membership.** `sitemap.xml` and `llms.txt` listed every offering with
  lifecycle *Active* — a different question from *sellable*. An offering past
  its window or sold only through a dealer was advertised to search engines and
  then refused by the page the crawler followed.

### What was built

`PublicCatalog` produces a `PublicOffering`: identity, a **list** of price
components, the headline charge and the upfront total beside it, availability,
bundle shape, specification facts, freshness, and **provenance** — the
offering, specification and price ids each published fact was read from. Every
surface is now an adapter over it:

| Surface | Adapter |
|---|---|
| crawler page + JSON-LD | `SchemaOrgProjection` — schema.org's vocabulary only |
| `sitemap.xml`, `llms.txt` | `GeoController`, straight from the projection |
| agentic-commerce feed | `AcpFeedController` — the ACP wire contract unchanged |

**Differences that remain are decisions, and are named in code.** Each surface
passes the *channel* it speaks for, so an offering sold on the web and withheld
from AI agents stays a choice. The feed drops an offering it cannot price — a
row an agent cannot act on is noise — while the sitemap still lists it, because
a page that says "talk to us" is a legitimate page.

### The cost this nearly hid

The first implementation made the projection eager, so rendering a sitemap
asked the warehouse and the specification about every offering on the shelf —
eighty downstream calls for a document that reads neither. Measured: **115
seconds and a 500**. Availability and facts are now computed on first read
(`Lazy`), and a test pins the call counts rather than trusting the comment:
sitemap 115 s → **0.45 s**, llms.txt 143 s → **0.16 s**.

The agentic feed still makes one warehouse call per row, because it is the one
surface that publishes availability for a whole shelf and the warehouse has no
bulk read. Measured on a fleet with headroom that costs **0.72 s for 37
offerings** — the honest price of having stopped publishing a constant, and
cheap enough to leave alone. A bulk availability read is worth building when a
shelf is ten times this, not before.

### The proof (suite #248, `one_projection_test.js`)

A fixture with a 42.00 monthly charge and a 99.00 joining fee is created, and
all four surfaces are read: each must lead with 42.00, and say it recurs. The
monthly price is then raised to 47.50 — **one PATCH, one catalog row** — and all
four must move. The offering is then taken out of its window and must leave the
sitemap, `llms.txt` and the feed together.

That last assertion is the one that matters for the life of this code. "One
projection" is a claim that rots silently; without a test that holds the faces
beside each other, it degrades into a fourth assembler within a release or two
and nothing goes red when it does. The unit test `OneProjectionTest` was run
against a deliberately reverted rule and watched fail with
`expected: <64.98> but was: <49.00>` before being trusted.

### Honest limits

- **Paging is unchanged.** One shelf depth (500) is now used everywhere, so a
  tenant who outgrows it loses the same rows from every surface at once rather
  than different rows from each. Paging to exhaustion and a sitemap index above
  the shard threshold remain SEO-4's business (#180).
- **The feed's availability vocabulary is coarser than ours.** An orderable
  plan reads `in_stock` there, because ACP has no token for "not a stocked
  thing, and you can order it". Saying `out_of_stock` would be false and
  inventing a token would break the contract; the projection keeps the finer
  distinction and the adapter loses it at the wire.
- **Provenance is carried, not yet published.** `PublicOffering` records the
  specification and price ids behind every fact, and no surface prints them
  yet. The discovery surface that exposes them is #181.
- **`Lazy` is not thread-safe**, deliberately: a projection is built and read
  inside one request.
- **The first performance numbers were taken on a starved laptop and were
  wrong by two orders of magnitude.** With 155 MB free in the Docker VM the
  feed read 72 s and one warehouse call took ~1.9 s; after shedding ten
  unrelated containers the same feed read 0.72 s. The eager-projection defect
  was real and is fixed, but any figure measured on a saturated fleet says more
  about the fleet than the code. The numbers quoted above are the ones taken
  with 3.8 GB free.

## A factual discovery surface (SEO-5, #181)

*Built 5 October 2026, on the projection SEO-3 laid.*

Generative engines, shopping agents and partners need product data they can be
held to — not marketing copy, and not a scrape of rendered HTML.
`/discovery/v1/products` and `/discovery/v1/products/{id}` serve it, as a third
face on the same `PublicCatalog` projection the crawler page and the
agentic-commerce feed read.

### What makes it a discovery feed rather than a marketing feed

- **Every fact traces back.** Each product carries the offering id, the
  specification id and the price ids it was read from, plus the API to check
  them against. Suite #255 follows one of those price ids into TMF620 and
  asserts the number matches. A price with no traceable source is marketing.
- **The whole price, not one number.** The headline, the one-off beside it, and
  every component behind both — and the components sum to the headline. The
  agentic feed can carry only one figure and must; this surface does not have
  to, so a bundle stops being a number an agent has to infer.
- **Absence is a value.** An offering with no price carries no price object
  rather than a zero. A specification that declares nothing produces no facts.
- **Freshness is real.** `generatedAt` per response, `lastUpdate` and the
  offering's own window per product — because an agent that cannot tell how old
  a price is will quote a withdrawn one with confidence.

### The agentic contract is untouched

The ACP feed still carries exactly its ten fields, in the same order, with the
same price shape. The suite asserts that explicitly by rejecting any field the
feed did not have before: the richer surface sits beside that contract and must
never leak into it. `agentic_commerce_test` was re-run and is green.

### Who sees it

The tenant's own `ai-visibility` decides, through a dedicated
`Visibility.servesDiscoveryFeed()` — true for `open` and `search-ai`, false for
`search-only` and `dark`. A tenant that has said *classic search yes, AI no*
does not get a machine-readable catalogue, because that posture would otherwise
be meaningless. A rich public catalogue is also the easiest price-scraping
surface an operator can expose, and that is a commercial decision rather than a
technical default.

The same projection is reachable to MCP agents through a new ontology
capability, `productCatalog.discovery`, so an agent retrieving through MCP and
an agent reading the public feed cannot be told different prices.

### Honest limits

- **Money carries the catalog's own scale.** A computed sum keeps two decimal
  places; a stored amount keeps whatever it was stored with, so the same ninety-
  nine euros can read `99` here and `99.00` elsewhere. Normalising to two places
  would be wrong for a zero-decimal currency, so nothing is invented — but a
  consumer that string-matches money will be surprised, and that is worth saying
  before it is sold.
- Conditions and eligibility are **not** in the feed. A price conditioned on
  characteristic picks is excluded entirely rather than published with its
  condition, exactly as it is on every other public surface. Publishing the
  condition shape is a further step.
- `llms.txt` remains what it was: an emerging convention some crawlers read and
  none are obliged to. Nothing here claims a ranking effect.
- The surface is proven, its consumption is not. No crawler is obliged to read
  it, and an agent caching it for a week will still quote a stale price — no
  feed design prevents that.

## The whole shelf, and honest HTTP (SEO-4 part, #180)

*Built 5 October 2026. Two defects out of #180 that do not need server-side
rendering and should not wait for it. **The SSR arc itself is not done** — see
below for what remains and why it was not attempted in one sitting.*

### The silent ceiling

Every public surface read a flat 500 offerings and stopped. No signal, no
marker, no log. A tenant with 600 offerings published 500 of them and nothing
anywhere said which hundred were missing, or that any were. `llms.txt` was
worse at 200.

The same ceiling applied to the **price index**, and that one is not cosmetic: a
price beyond it made its offering look unpriced, and an unpriced offering is
dropped from the agentic feed entirely. An agent reads a missing product as a
withdrawn one.

Both now page to exhaustion. A ceiling still exists at 10,000, because reading
"to exhaustion" against a catalogue of unknown size is its own hazard — one
request can otherwise hold a connection open across a hundred thousand rows —
but hitting it is **published** (`truncated: true` on the discovery feed) rather
than silent, so a consumer can tell a complete feed from a cut one.

Proven on a shelf of 506: sitemap, llms.txt and discovery all publish the same
506, and the offering past the old ceiling still carries its price and is still
sold by the agentic feed.

### A 200 on a URL that does not exist

`try_files $uri /index.html` answered **200 with the app shell for any path**.
`/shop/nonsense` looked exactly like a real page to a crawler, a link checker
and a monitor — because it *was* a real page: the shell, which then rendered
nothing. A 200 on a URL that does not exist is the most expensive kind of wrong,
because nothing downstream can detect it.

The storefront's nginx now enumerates the app's routes, including the ones that
**require an identifier** — `/shop/offering` with no id is not a page and now
says so. Real routes still answer 200; the suite asserts both directions,
because an over-tight enumeration would lock out a working page.

**This duplicates the router in `App.jsx`, deliberately and temporarily.** The
alternative is a config file claiming every URL is valid. When SSR lands the
router answers for real and those two location blocks are deleted, not
maintained.

### What remains of #180, and why it was not done here

The ticket says plainly: *"This is the arc, not a ticket — scope it before
starting."* It is right. The remaining work is a genuine arc:

1. **A server-rendering runtime.** A Vite SSR build and a Node process, which
   replaces nginx's role and puts the storefront on the request path for every
   public page — needing the timeout, failure and observability treatment any
   other service gets.
2. **Removing browser globals from module scope.** `auth.js` and `App.jsx` read
   `window.BSS_STOREFRONT_CONFIG` at import time. Nothing renders on a server
   until that is unpicked.
3. **Server-resolvable data loading.** Public pages fetch in `useEffect` today;
   SSR needs a loader per public route, plus a hydration payload that does not
   re-fetch and does not flash.
4. **Category and help pages as first-class routes.** They do not exist as
   routes at all today, so there is no crawl graph to render.
5. **The canonical slug contract** — slug plus immutable id tail, with a 301
   from the legacy `/shop/offering/<id>`.
6. **Then, and only then, deleting the crawler User-Agent route** — the ticket's
   own honest limit says keep it until SSR is proven in production, because
   removing it first makes a bad deploy invisible to crawlers and visible to
   Google.

Items 1 to 3 are the arc. They are sequenced, not parallel, and attempting them
in one sitting produces a half-migrated storefront — which is worse than the
current state, because the current state is at least coherent.

### SSR tracer bullet: the shop renders on a server (5 October 2026)

The first item of the arc, taken on its own: *can this React application render a
real public page to complete HTML on a server at all?* It could not, and the
three reasons were not visible from the ticket.

1. **Two modules read `window` at import time.** `auth.js` and `address.js`
   captured the tenant manifest as a module constant, so the graph threw before
   anything rendered. Thirteen more read it during render. All now go through
   `src/config.js`, which answers on either side.
2. **The app gates every route behind a client session bootstrap.** `App.jsx`
   starts in `boot` and resolves the session in an effect — and effects do not
   run during `renderToString`. **Every public page rendered the string
   "Loading…" and nothing else.** This is the one that mattered: without finding
   it, the arc would have produced a technically-successful SSR deployment
   serving crawlers a spinner. A server render has no session to wait for, so it
   starts `ready`; the client is untouched.
3. **The router's basename.** The gateway strips `/shop` before the storefront
   sees a request, but the router keeps the basename so its links carry the
   prefix a browser follows. The server has to put it back.

With those closed, `src/entry-server.jsx` renders the real `App` through
`StaticRouter`, and `apps/storefront/server/ssr.mjs` fetches the discovery
projection *before* rendering and seeds it through `src/ssr-data.js`. The Shop
page reads that seed for its first state and otherwise behaves exactly as
before. Suite #257 proves it in plain Node: 3,121 bytes of real page, the
tenant's brand, both server-resolved products, links carrying `/shop/`, and two
tenants rendered back to back without leaking into each other.

**What is deliberately not done.** The runtime is not wired to the gateway and
the crawler User-Agent route is untouched — the ticket's own limit says keep it
until SSR is proven in production. Only the Shop route reads seeded data; every
other page would still render its loading state. `setConfig`/`setInitialData`
are module state around a synchronous render, which is correct for one render at
a time and must move to request-scoped storage before serving traffic. And
`i18n.js` still captures the tenant at import, making locale per-process rather
than per-request — right for one tenant, wrong for concurrent ones.

Every file touched here was already at or over the 300-line limit, so each
`import { config }` had to be paid for out of the same file. That is the third
time this week; it is a real tax on the storefront now, not a theoretical one.
