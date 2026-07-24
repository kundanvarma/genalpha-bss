# genalpha-bss — a composable, multi-tenant, agentic BSS on TM Forum ODA

A vendor-neutral telecom **Business Support System** built as **33 composable ODA components**
(Spring Boot microservices exposing TMF Open APIs) plus **six channels** (five web, one mobile), behind one API
gateway. Any OIDC identity provider, any PostgreSQL, any Kafka-protocol broker — nothing
operator-specific is hardcoded. Two demo operators run side by side on a single deployment to
prove it.

**Every feature is verified end-to-end in a real browser** — sixty-six Playwright suites drive the
storefront, guest checkout, the consoles, the mobile app, tenant isolation, role administration,
campaign journeys with holdout-measured lift, revenue attribution, A/B arms and segment-read
branch steps, the per-tenant ESP email seam with delivery receipts and bounce suppression,
tenant-wide frequency caps and quiet hours, GA4 Data API audience import, Meta-shaped Custom
Audience push (SHA-256, never in the clear) and Lead Ads import into the TMF699
lead-to-opportunity sales funnel, the omnichannel TMF683 interaction record (every martech and
system message logs itself; external systems write into the same timeline), the bill as a PDF and
the per-tenant distribution seam (one tenant ships EHF/Peppol BIS 3.0 e-invoices to an access
point, the other ships PDF print jobs — config, not code; outbox-backed with retries), remittance
ingestion (the bank's camt.054, Nets OCR giro or BAI2 lockbox file settles bills by KID through
the card path's own guarantee; everything unclear parks as unapplied cash), the retail dealer channel
(the CSP + external-retail model — think Elkjøp/Power: starter kits with attribution and the SIM baked into the box, counter
sales, and commission that accrues pending, hardens after the withdrawal window and claws back
honestly — with a dealer console for the chain's clerks and a machine partner API for chains
with their own POS, where the chain's own phone rides the sale as context, never a billable item; plus the TELESALES
channel — the dialer pulls consented, DNC-washed **dial lists** from the same insight segments
campaigns use (reserved citizens excluded and counted, never listed), the do-not-call wash runs
fail-closed before any offer exists, the call produces an offer that binds only on the customer's
written confirmation (angrerettloven; **cold prospects** earn their identity by registering with
the offered email), commission is born with the agreement — not the call — and the partner's
dialer logs every call onto the TMF683 record), the **Product advisor**
(receipts before advice: top-up attach counted from inventory, market price gaps from a per-tenant
market-intelligence feed seam, the tenant's LLM narrating but never inventing numbers — and every
suggestion becomes at most an "In study" DRAFT a product owner must promote — with **tiered
model routing**: the task class lives at the call site, so copywriting rides the cheap model while
product judgment rides the careful one — down to **different providers per tier**: a local
openai-compatible endpoint for volume, a frontier Anthropic-dialect API for judgment, both at
once, per tenant, as config)), the **content seam over object storage** (one TMF667 surface,
three homes for the bytes: in-row Postgres by default, the S3 protocol — AWS/MinIO/R2 — or
Azure Blob's own SharedKey dialect, because "any cloud" is only true if Azure is a first-class
citizen; the database receipt proves where the bytes live), **search upgraded in place** (a pg_trgm typo
net under the strict customer search — it only speaks when strict finds nothing — and
language-aware full-text where each tenant's knowledge base stems in its own tongue: "regning"
finds "regningene" — and a **pgvector semantic net**: "why is my internet so slow" finds the
fair-use article that contains neither word, cosine neighbours speaking only when keyword search
is silent, under an honest distance ceiling that returns nothing for nonsense; embeddings ride a
provider seam — deterministic keyless stub or any openai-compatible model; all Postgres-native),
**P0 production hardening with receipts** (every mutating scheduled tick — dunning, bill delivery,
campaign sends, commissions — claims a row-lease before running, so suite #56 runs billing as TWO
replicas and the mock print house still receives exactly ONE copy of the bill; a fleet-wide
rate-limit ring on every path — per subject, per client, per IP — trips into an honest 429 with
Retry-After; and `ops/backup.sh` + `ops/restore-drill.sh` prove the backup by RESTORING it into a
throwaway container and finding the sentinel row — an untested backup is a hope, this one is a
fact; the rest of the road is written down honestly in [docs/hardening.md](docs/hardening.md)),
**P1: fast and interruptible** (the billing run now commits each account's bill in its OWN
transaction with a run ledger anyone can read — suite #57 kills billing MID-RUN and the
re-triggered run resumes to exactly-one-bill-per-customer, because the bill itself is the
checkpoint; rate-limit buckets moved to Redis so a restarted gateway still refuses inside the
same window and N replicas enforce ONE exact ceiling; a plain-Node load harness gives throughput
its first honest numbers — ~680 req/s catalog browse at p95 39 ms on one laptop running all 30
JVMs, [docs/perf-baselines.md](docs/perf-baselines.md) — with a smoke-SLO tripwire in the suite;
and Prometheus now evaluates alert rules over the whole fleet, 32 scrape targets up from 5),
**P2: the subscriber's rights as features** (the GDPR data passport — a customer exports
THEMSELVES, the fan-out riding their own token across every service so the right of access needs
no new authority; erasure honest in both directions — an active subscriber refuses with 409,
bookkeeping categories are retained WITH their named legal basis, the profile anonymizes in
place, the login dies scrubbed at the IdP, and every erasure leaves an immutable audit row;
retention as TickGuard-guarded clocks; PCI scope verified-then-claimed — the vault holds
`pspToken/lastFour`, never a PAN; suite #58, the whole story in
[docs/privacy.md](docs/privacy.md)), **post-quantum readiness — *ready*, never "proof"** (the
BSS is crypto-agile by construction: identity, transport and payment crypto all live behind
seams, so migrating is swapping what a seam points at, not a rewrite; today SIM secrets are
AES-256-GCM at rest, PKCE is SHA-256, and a hybrid **X25519MLKEM768** TLS key exchange —
ML-KEM-768, FIPS 203 — guards recorded traffic against *harvest-now-decrypt-later*; the one
classically vulnerable primitive, RSA token signatures at the IdP, is named honestly and swaps
to ML-DSA at the identity seam when the OIDC ecosystem lands it — the full crypto inventory,
holding nothing back, in [docs/pqc-readiness.md](docs/pqc-readiness.md)), the **AI control plane** (every LLM turn authenticated,
METERED — tokens and cost on the audit ledger — and governed: per-tenant spend ceilings that
refuse fail-closed with an honest 429 when crossed, an operator kill-switch, and agent ACTIONS
on the same ledger as the words — the advisor's draft-adoption lands as
`catalog.createDraftOffering → <offering-id>`, so the trail answers *which AI touched which
resource*, tenant-walled by RLS like everything else; the refusals audit themselves too, in
their own transactions, because a rollback must never erase its own evidence — suite #59),
**the individualized shop** (a signed-in customer's "For you" rail that is genuinely THEIRS:
consented browsing stitches into interests, TMF680 supplies the ranked candidates, an open
churn-risk alert adds a loyalty flag, and the governed LLM writes one caption grounded only in
that customer's own trail — "Picked for you after your look at devices" — metered and budgeted
by the AI control plane, cached per party so a browsing session costs ONE model call, none of
it exists for the customer who declined consent, and the same rail follows the customer onto
the MOBILE APP — one governed rail, every channel; suite #60), **personalization you can
CHAT into existence** (the product owner tells the copilot what device-browsing guests should
see — banner and pinned offering — and the proposal becomes a policy row (domain
'personalization') on one click, applied with the owner's own token, never the model's; the
next consenting guest is greeted by the chatted shop, and the rule disables in Rules like any
other data; suite #61), **next-hit session personalization** (what you look at on page N shapes
page N+1: the last offering you viewed leads the next page — recency beats the all-time
favourite — and a "Pick up where you left off" rail hands back the very offerings you just
saw, most-recent first, consent-gated and tenant-walled; suite #62), **"customers who bought this also bought"**
(item-to-item market-basket affinity on every product page — computed from what the tenant's
customers actually own together, ranked by co-ownership, minimum-support floored so a single
basket can never be read back; public and aggregate like Amazon's, tenant-walled; the demo
data shows it working — Samsung buyers overwhelmingly also take the flagship home bundle;
suite #63), **agentic commerce — the catalog discoverable and BUYABLE by AI agents**
(an **Agentic Commerce Protocol** merchant surface — the open standard ChatGPT Instant Checkout
and Perplexity drive — implemented to spec `2026-04-17`: a public product feed projected from
TMF620 and the full `checkout_sessions` lifecycle riding TMF663 cart + TMF622 order underneath,
plus the same surface worn as **MCP tools** so Claude and any MCP agent can search, compare and
buy; checkout runs on a **delegated token** — the shopper's credential exchanged (RFC 8693,
Keycloak standard token exchange) through a commerce-only client, so the agent's token can order
and pay and *provably nothing else* — the suite decodes it: 25 authorities down to 5; complete is
idempotent — the same Idempotency-Key returns the SAME order, a fresh key on a completed session
is refused — and every agent order is channel-marked `agenticCommerce` with the buyer bound as
relatedParty, readable by the customer's own token; and the part an operator will ask about
first: a **per-tenant switch** `agent-commerce: off | discovery | full` in the shared registry,
gateway-enforced, live-refreshed, DEFAULT OFF — a tenant is dark to agents until it opts in,
and `discovery` lists the catalog while keeping checkout human; suite #64, the research and
design in [docs/agentic-commerce-plan.md](docs/agentic-commerce-plan.md)), **the digital
workforce — AI agents that WORK for the operator** (an extension package where an autonomous
agent — **Hermes Agent is the documented reference runtime**, but the interfaces are MCP + OIDC
so any agent hires in — is EMPLOYED on back-office and care queues: the task queue derives LIVE
from real backlogs (unassigned tickets, unapplied cash), claims are leases, completion is
**verified** — a worker cannot mark done what is not done — and escalations carry a reason and
are counted, not punished; **the badge is the opt-in**: a `digital-worker` staff grant on the
same TMF672 surface as any human, revoke = fired, and the tenant's one AI kill-switch stops
workers and copilots alike; refunds/cease/erasure only ever become **approval requests** a
human executes with their own token — the worker can never approve its own ask; and the
**Workforce dashboard** shows the shift honestly: deflection, handle time, the REOPEN RATE
(the metric a vendor would hide), minutes-saved labeled `estimate: true` beside its baselines,
and self-reported model cost labeled as the worker's own word; suite #65 proves the whole
employment — hire, shift, refusal to fake, the gate, the firing — plus a browser leg where one
dashboard click refunds under the human's token; day-1 package in
[integrations/hermes-worker](integrations/hermes-worker/README.md), design in
[docs/agentic-workforce-plan.md](docs/agentic-workforce-plan.md)), and
**MVNO onboarding in an afternoon** (`ops/onboard-tenant.sh`: the tenant fleet is a shared config
file, a new operator is a realm clone + a tenant block + a restart — suite #49 stands one up and
bills its first customer in ~2 minutes, no image rebuilt — and **operator-as-a-form**: the host
admin mints a new operator from five console fields, every service live-refreshes the shared
registry with zero restarts, and `shop.<id>.localhost` wears the new brand the moment the
gateway notices), the AI-slice
lead-to-assure loop and BankID step-up against the full stack.
**Eleven official TM Forum CTKs pass with zero failures**: the five core (TMF620/622/632/637/666)
plus TMF663 shopping-cart, TMF669 party-role, TMF687 product-stock, TMF635 usage, TMF677
usage-consumption and TMF678 customer-bill. See the full, honest scorecard — including the two
intentional hardened gaps (payment, communication) — in
[docs/ctk-conformance.md](docs/ctk-conformance.md); reproduce any row with [ops/ctk](ops/ctk/README.md).

- 🎬 **Guided demo** — open `http://localhost:8080/flow/demo.html`, sign in as `demo`, press ▶: five narrated acts drive the LIVE system (order-to-activation, a rule born without a deploy, a reacting price, keep-your-number, leave-and-teach-the-AI) while Live Flow lights up beside them. Nothing on that page is mocked.
- 🛰️ **[Autonomy Accelerated — the 5G AI Slice PoC](https://kundanvarma.github.io/genalpha-bss/poc-ai-slice.html)** — the full lead-to-assure loop (AI intent → feasibility + edge upsell → token-priced quote → order → autonomous fibre-cut self-heal), drivable by an AI agent over MCP
- 📖 **[The Honest Machine](https://kundanvarma.github.io/genalpha-bss/book/book.html)** — *a build memoir · verify everything*: how one person and an AI built a complete telecom suite — from catalog to cash to campaigns to copilots — and proved every piece of it. Forty-six chapters, the receipts included ([PDF](docs/book/The-Honest-Machine.pdf))
- 📕 **[The Operator's Manual](https://kundanvarma.github.io/genalpha-bss/manual/manual.html)** — the reference companion, by role and by task: surfaces & sign-ins, the product owner's console, the CSR's acts, billing & money operations, partner channels, tenant minting, AI tiers, the seam catalog, env reference, extension APIs, the mocks ([PDF](docs/manual/Operators-Manual.pdf))
- 📄 **[Product overview](https://kundanvarma.github.io/genalpha-bss/overview.html)** — the whole system as a readable webpage (browser Print → PDF for a shareable document)
- 🧩 **[Product modeling — build a complicated bundle](docs/product-modeling.md)** — fixed components, pick-N-of-M choice groups, configurable variants, terms and mixed pricing, all as TMF620 data; worked example: GenAlpha Family Max
- 🧾 **[Bill distribution & remittance](docs/bill-distribution.md)** — the bill both ways: PDF, seven e-invoice formats as config rows (EHF, Peppol BIS, CII, A-NZ, XRechnung, EDIFACT, Factur-X), print & e-invoice channels with per-customer preference, the outbox-backed delivery ledger with buyer Invoice Responses, and money home by camt.054 / Nets OCR / BAI2 with an unapplied-cash worklist
- 📏 **[Product rules — how to use them](docs/product-rules.md)** — author order rules and dynamic pricing as data: console walkthrough, dry-run, customer experience, JSON-logic context reference, API examples
- 🛡️ **[Production hardening](docs/hardening.md)** — what is done and proven (tick locks under two live replicas, a crash-resumable billing run with its own ledger, Redis-backed rate ceilings that survive restarts, a backup that provably restores, fleet-wide alert rules, the secret gate, GDPR as endpoints — and the **Helm chart run live on three clouds** — local k3s, AWS EKS, Azure AKS — billing at 2 replicas with one set of tick leases against a managed database each time, [receipts](docs/k8s-soak-plan.md)) and the honest list a real deployment still owes: managed HA Postgres/Kafka, TLS in transit, a third-party pen test
- 🔏 **[Privacy & compliance](docs/privacy.md)** — the GDPR data passport and eraser (self-service export on the caller's own token, erasure with the law's own exceptions, retention clocks, the audit that outlives the erased), verified PCI scope, and the honest boundary statements for lawful intercept and regional DR
- 📊 **[Performance baselines](docs/perf-baselines.md)** — throughput's first honest numbers from `ops/load/loadtest.js` (one laptop, all 30 JVMs: ~680 req/s catalog at p95 39 ms, ~450 req/s authenticated reads through JWT+RLS), the caveats stated, and the smoke-SLO tripwire suite #57 arms
- 🧅 **[The overlay strategy](docs/overlay-gtm.md)** — genalpha-bss as the agentic layer ON TOP of the BSS an operator already runs: a legacy stack is just another per-tenant seam, never-two-writers data mastery, and the strangler-fig roadmap where the next brand launches natively in minutes — honestly labeled strategy until the wrapped-legacy proof suite lands
- 🔐 **[Post-quantum readiness](docs/pqc-readiness.md)** — the honest crypto inventory: one vulnerable primitive (RSA token signatures, swappable at the IdP seam), hybrid-TLS guidance for harvest-now-decrypt-later, and why seams make PQC a checklist, not a rewrite
- 📐 **[Architecture views](docs/architecture.md)** — component map, tenancy model, order-to-bill flow, event backbone
- 🧩 **[ODA Composer](https://kundanvarma.github.io/genalpha-bss/composer.html)** — pick the modules a deployment needs; dependencies enforced; output is a Helm values override

## A look at it

**🎬 The journey film — a human using the real product, filmed across every screen.**
One take, no mocks: Mia browses the Samsung with a real **photo gallery whose hero — and price —
follow the colour pick** (Titanium Edition costs more: a conditioned price component, no colour
SKUs; no PIM either — catalog + document store), configures the bundle, joins mid-checkout, pays;
the back office composes Family Max and ships a 15% pricing rule — as data; an agent fulfils her
order; her number, SIM PUK, Netflix entitlement and inbox light up in the shop; Emil carries his
company line on mobile; Norway's Nova Telecom runs the same build in Norwegian and kroner — its
device imagery arrives live from **Nova's own PIM**, Nils changes plans, Birgit reads Fjellheim's
consolidated invoice and sets the **split-billing device allowance**; and Live Flow narrates every
event underneath, on a quantum-ready Java 25 fleet.
**[▶ Watch the journey (MP4)](docs/media/journey.mp4)** · re-record any time with
`node ops/e2e/journey_video.js`

<p align="center">
  <a href="docs/media/journey.mp4"><img src="docs/media/journey-poster.png" width="88%" alt="The journey film: a customer configures the bundle in the real storefront — click to watch"></a>
</p>

**The guided demo, driving itself** — five narrated acts against the live system: a customer
orders and the machine activates them untouched; a business rule is born, enforced, and retired
without a deployment; a pricing rule turns €100 into €85; a customer keeps their number joining
us (NRDB port-in), then leaves with it — and the AI records the goodbye as a churn outcome it
learns from. Everything you see is a real API call; Live Flow lights up as the events land.
Run it yourself at `/flow/demo.html` ([full-speed MP4](docs/media/guided-demo.mp4)):

<p align="center">
  <img src="docs/media/guided-demo.gif" width="92%" alt="The guided demo: five narrated acts driving the live BSS while Live Flow lights up">
</p>

**Live Flow** — watch a business process happen, step by step. An event-driven BSS's value is
loose coupling, which makes the magic invisible; this makes it legible to anyone. It reconstructs
live process instances from the `bss.*.events` stream and narrates each step in plain English —
a customer orders, the ordering component captures it and publishes an event, communications and
the orchestrator pick it up, the service activates, the customer is notified. Three processes run
live: retail **Order → Activate**, the B2B **Lead → Assure** slice (with a fibre cut that
self-heals), and **Churn → Retention** driven by the AI back-office. A companion
[architecture view](docs/img/live-flow.png) shows the same events as component choreography.

<p align="center">
  <img src="docs/img/live-flow-process.png" width="90%" alt="Live Flow — business processes narrated step by step as they happen">
</p>

One build of each channel serves every tenant; the host decides the brand — **and the language
and currency**. The tenant manifest carries `locale` + `currency` alongside logo, name and color:
GenAlpha sells in English and EUR; Nova Telecom is a Norwegian operator — same build, Norwegian
chrome (Tilbud, Handlekurv, Til kassen), NOK prices formatted the Norwegian way (`299,00 kr/md.`),
and a Norwegian Keycloak sign-in. Prices carry their own currency unit end to end (catalog → cart
→ bill), so multi-currency needs no FX machinery — one currency per operator, the telco norm:

<p align="center">
  <img src="docs/img/storefront-genalpha.png" width="49%" alt="GenAlpha storefront (teal)">
  <img src="docs/img/storefront-nova.png" width="49%" alt="Nova Telecom white-label storefront (purple)">
</p>

Marketing runs the whole growth engine from the back-office console — event-triggered journeys,
A/B arms, an AI copy assistant, and **holdout-measured lift that proves a campaign worked in
money, not vibes** — while CSR agents get an AI copilot summarizing the customer 360; and the
modular mobile app recomposes around what the customer owns:

<p align="center">
  <img src="docs/img/console-campaigns.png" width="49%" alt="Campaigns tab with AI assist in the console">
  <img src="docs/img/csr-copilot.png" width="49%" alt="CSR customer 360 with the AI copilot summary">
</p>

<p align="center">
  <img src="docs/img/mobile-app.png" width="30%" alt="The modular LOB mobile app: adaptive home after activation">
</p>

## The modules

**Core commerce (always deployed)**

| Component | TMF API | Port | What it does |
|---|---|---|---|
| product-catalog | TMF620 | 8081 | Offerings, prices, commitment terms, **categories** (Mobile plans / Broadband / Devices / TV & Add-ons / Top-ups — channels group "my plan" vs extras and keep plan changes like-for-like), and **hard + soft bundles** (TMF620 `bundledProductOfferingOption` cardinality: mandatory components, optional standalone add-ons, and "pick N of M" choice groups enforced at order time), plus **characteristic-conditioned pricing** (TMF620 `prodSpecCharValueUse`: "+2.00/mo when colour = Titanium Edition" — one offering, no SKU per variant; the shop, the cart and the billing run all price the *configured* product, and a pricing rule conditioned on `color:X` runs a **campaign on a colour**). **Product content is a per-tenant seam**: device galleries and colour-variant imagery ride the offering's `attachment` list — from the internal TMF667 store by default, or resolved live from **the operator's own PIM** (`pim-base-url` per tenant, keyed by product name, cached, fail-open) — the channels can't tell the difference. Also serves the **ACP product feed** (`/acp/product_feed`) — the public projection AI shopping agents ingest, per-tenant gated |
| product-ordering | TMF622 | 8082 | Order capture, validation, completion orchestration |
| product-inventory | TMF637 | 8083 | What each customer has, provisioned per order item |
| party-account | TMF632 / TMF666 / TMF669 | 8084 | Individuals, organizations, accounts, party roles. **Customer search** is multi-word term-AND with name ranking, plus a **pg_trgm typo net** (GIN trigram index) that only speaks when strict matching finds nothing — "Solvieg Fjelheim" still finds Solveig, and strict results stay byte-identical (suite #55). **Household billing**: a PERSON can be another person's payer — consent-gated (the dependent requests by email, only the named payer accepts, either side can leave); the payer then orders onto the dependent's line from My page and it bills to the payer with per-person attribution — one family bill, the same payer machinery as B2B, zero org semantics. **Child accounts**: the payer creates a kid's own login (TMF672, customer role only — consent implicit when the payer is the creator), hands over the once-shown credentials, and the kid signs into **their own mobile app** — own My page, own line, honestly bannered "paid for by …" |
| gateway | ODA exposure | 8080 | Single entry point; white-label host → tenant routing |

**Optional components** (leave any out via the [composer](https://kundanvarma.github.io/genalpha-bss/composer.html) — channels adapt)

| Component | TMF API | Port | What it does |
|---|---|---|---|
| product-stock | TMF687 | 8086 | Device shelf: reserve at order, consume at completion |
| payment | TMF676 | 8087 | Authorize/capture behind a PSP adapter (mock PSP in dev) |
| billing | TMF678 | 8088 | Billing runs: recurring + usage + discounts on one bill |
| qualification | TMF679 | 8089 | Serviceability: where fiber-class offerings can be delivered |
| appointment | TMF646 | 8091 | Installer slots, booked at checkout |
| trouble-ticket | TMF621 | 8092 | Support cases, org-scoped for partner agents |
| party-interaction | TMF683 | 8093 | Every touchpoint on the customer timeline |
| communication | TMF681 | 8095 | Event-driven notifications and the **per-tenant email (ESP) seam** — one tenant sends through a SendGrid-shaped provider, another through its own; **delivery receipts** flow back (opened / bounced) and a **bounce suppression** list stops chasing dead addresses. Every minted notification also publishes to the omnichannel TMF683 interaction record, so martech and system messages land on one customer timeline |
| shopping-cart | TMF663 | 8096 | Server-side carts: guest secret-id, claim on login, abandonment events — **plus the ACP `checkout_sessions` lifecycle** (agentic commerce): a session rides a real cart, `complete` charges the delegated payment token and places the TMF622 order with the *caller's* exchanged, commerce-only credential, Idempotency-Key replay-safe |
| usage | TMF635 / TMF677 | 8097 | Mediation intake, rating, allowance meters, overage charges — and **one-time data top-ups**: a completed order carrying a boost-flagged offering extends the buyer's current-month allowance (meter and overage threshold both grow) |
| agreement | TMF651 | 8098 | Commitment periods minted automatically at order completion |
| promotion | TMF671 | 8099 | Promo codes: anonymous validation → redemption → bill discount |
| user-roles | TMF672 | 8100 | Tenant admins manage staff via TMF API over their own IdP — surfaced as the console's **Staff tab** (search an operator, tick the areas they manage). Also the **invitation seam**: a business admin can provision a login for their own people, hardcoded to the customer role — no escalation path |
| geographic-address | TMF673 | 8101 | Address validation + standardization at checkout |
| recommendation | TMF680 | 8102 | Cross-sell with a learning seam: rule-selected candidates, popularity-ranked (a trained model plugs into the same Ranker interface) |
| payment-method | TMF670 | 8103 | Tokenized card vault: save at checkout, pay bills one-click |
| document | TMF667 | 8106 | Content store: tenant logos and offering artwork the channels wear — the **internal PIM** behind the console's artwork upload. The bytes live behind a **ContentStore seam**: in-row Postgres by default (dev/CI, zero config), or object storage by one env var — the **S3 protocol** (AWS/MinIO/R2/GCS-interop, plain SigV4, no SDK) or **Azure Blob's own SharedKey dialect** (real Blob on AKS, not an S3 pretender). Rows keep metadata + storage key; reads hydrate invisibly; the TMF667 surface and attachment URLs never change — suite #54 takes the database receipt (content IS NULL beside an `s3:`/`azure:` key) against MinIO and Azurite |
| campaign | martech | 8108 | **The growth engine, built in — and measured honestly.** Event-triggered journeys (once-per-customer via a unique execution row) with **branch steps** that read live insight segments at send time; **A/B arms** with per-arm stats and an honest z-test verdict (it will tell you the test is inconclusive); **holdout-measured lift** — a control group proves a campaign worked *in currency per exposed customer*, not in vibes — with **revenue attribution** from the converting order's catalog value; **frequency caps and quiet hours** so marketing stays welcome; **{code} promo templating**; and **audience sync** that pushes consented segments to Meta as SHA-256 hashes (never in the clear). Delivery rides the per-tenant **ESP seam** (see communication) under the acting tenant's machine identity. Marketers run all of it from the console's Campaigns / Journeys / Audiences tabs — no separate marketing cloud. Suites #35–39 (`growth`, `esp`, `sales`, `guardrails`, `social`) |
| quote | TMF648 | 8110 | B2B quotes born from intents: the OSS proposal priced, token allowances on line items, acceptance places the order |
| intelligence | AI | 8109 | Any-LLM seam (per-tenant overrides) with **tiered model routing** — the task class lives at the call site (FAST for copy/summaries/narratives, SMART for proposals/judgment, default SMART), and every config value resolves tier-first: different models AND different providers per tier, one tenant, at once (a local openai-compatible endpoint for volume, an Anthropic-dialect API for judgment — proven on the wire by a model-naming mock with a request ledger). Carries the copy assistant + CSR copilot + **Product Advisor** (receipts before advice: top-up attach counted from paged inventory, market price gaps from a per-tenant market-feed seam with a 10% materiality threshold, the LLM narrating but never inventing a number, and adopt-as-draft — every suggestion becomes at most an "In study" offering a product owner must promote) + **Product Copilot** (a product owner CHATS a product into the catalog: the model proposes specs/prices/offerings/bundles as TMF620 payloads, the console validates and shows a human card, and a deterministic executor applies it with the owner's OWN token on confirmation — the model never writes; runs on the keyless stub in CI, any real model by config) + a churn engine that starts as transparent rules across BSS/CSR/assurance data and **learns in production** — feature snapshots accumulate from day one, outcomes label them, and a per-tenant logistic model trains in-service (or immediately from imported operator history) |
| flow | observability | 8111 | **Live Flow** — consumes every `bss.*.events` topic and streams the choreography to a browser (`/flow`); watch components react in real time |
| porting | MNP | 8112 | Keep-your-number **and** leave-with-your-number: port-in/out through a country clearinghouse seam (NRDB in Norway, pluggable per country). Port-in activates on the ported number; port-out ceases the service, releases the number, and records a churn outcome |
| policy | rules | 8113 | **Business rules AND dynamic pricing as data, not code**: eligibility / quantity-cap / incompatibility / verified-identity rules enforced at order time, plus **pricing rules** (percent or amount adjustments, conditioned on segment / cart / verified identity) applied at cart and bill time — author or disable any of them in the console as JSON-logic with **no redeploy** ([how-to guide](docs/product-rules.md)). **Rules are also marketing**: anonymous `price/teaser` (a rule's public face per offering) and `price/indicative` (guest basket pricing over a sanitized, identity-free context) power the storefront's deal merchandising. Pluggable engine seam (JSON-logic today, Drools/CEL swappable); tenant-isolated by RLS; fails open if unreachable |
| knowledge | knowledge base | 8118 | **Articles and FAQ as data, audience-scoped and searchable** — Postgres full-text with **per-tenant language stemming** (the tenant's locale picks the regconfig, GIN-indexed per language: on nova "regning" finds "regningene") and a **pgvector semantic net** beneath it — cosine neighbours that speak only when keyword search is silent ("why is my internet so slow" finds the fair-use article containing neither word; nonsense finds nothing, by the distance ceiling), embeddings behind a keyless-stub / openai-compatible seam; no extra infra, suite #55: customers get the FAQ shelf on the shop's Support page, CSRs add cheat-sheets plus **✨Ask** — a grounded AI answer with named sources, retrieved with the asker's OWN token so the answer can only draw on what they could read themselves — sales get talking points, and **product owners get the how-to-build-products library** (spec→offering→price, bundles, pricing rules, gifting/rollover levers, copilot usage) right in the console where they build. Authored in the console (drafts stay backstage), tenant-isolated by RLS |
| insight | customer insight | 8119 | **First-party personalization, consent first (CDP-ready, not a CDP)**: an anonymous visitor's consent choice gates EVERYTHING — no rows before yes, revocation deletes what was held, and the E2E asserts the negative. Consented browsing becomes interests; the shop home answers "what should this person see?" (hero rail + banner); **experience rules are policy data** (domain `personalization`: banner copy + pinned offering, authored in the console); login **stitches** the browser profile to the customer, by consent — and experience rules can be **chat-authored** (the copilot turns "device browsers see this banner and this pin" into a policy row). On top of the signals sits **the individualized shop**: a signed-in **"For you"** rail ranked from the customer's own data with a governed-AI caption (web + mobile app), **next-hit session reaction** (the last thing you looked at leads the next page — recency beats the all-time favourite), and **"customers who bought this also bought"** market-basket affinity on every product page (aggregate, minimum-support floored). The enterprise **CDP / GA4 plugs in behind a per-tenant analytics seam** — GA4 Data API audience import and Meta Custom-Audience / Lead-Ads sync all ship — but the BSS contributes and consumes profiles, it doesn't warehouse the identity graph. Suites #34, #60–63 |

**Production (OSS)** — the layer below the BSS, thin but real

| Component | TMF API | Port | What it does |
|---|---|---|---|
| service-orchestration | TMF641 / TMF638 / TMF640 / TMF685 | 8104 | Digital orders decompose, activate and complete themselves — **fulfilment decided by catalog category**: network lines draw MSISDNs + SIMs, **partner services (Netflix) mint entitlements** through a pluggable partner seam, security products activate as features, insurance is billing-only. **OCS seam**: the catalog *references* charging (spec characteristic `chargingSpecId` — the rate plan lives in the operator's Online Charging System, never in the BSS); activation provisions the subscriber + buckets there, a plan change swaps the rate plan, and it all fails open — charging reconciliation never blocks activation. **TMF654 Prepay Balance Management** projects the OCS's counters (remaining / used / rollover) to the channels and forwards top-up credits; the bundled `mock-ocs` stands in for Ericsson/Huawei/Matrixx in dev |
| assurance | TMF642 / TMF656 | 8105 | Critical alarms become service problems; the CSR console shows live outages |

**Channels** — one build each, white-labeled per tenant by hostname (logo, name **and brand
color** theme every channel from the tenant manifest)

| Channel | Path | For |
|---|---|---|
| storefront | `/shop` | Self-service: guest browse → configure → cart → checkout → bills → support (React + Vite PWA). **My page recomposes around what the customer holds** (the MyJio idea, same as the mobile app's Home): a dashboard card per line of business — Mobile (plans, **every line with its own number and SIM**, data meter, **one-tap top-up**), Broadband, TV & entertainment, Devices, the bundle with its components nested — plus the **latest bill with a Pay link** and a discovery card driven by **TMF680 recommendations** (category gaps as fallback). **Change plan** is like-for-like (mobile→mobile, broadband→broadband — never a device): a TMF622 `modify` order swaps the plan on the same line, same number, including a bundle's broadband tier; commitments block the change until their window ends. **Data top-ups** bought one-time extend this month's meter. **SIM self-care**: masked ICCID, PUK revealed on request, OTA PIN reset through a pluggable SIM-platform seam. **Devices sell like devices**: product-shot galleries with thumbnails, the hero photo follows the colour pick, phones appear as pictures in the bundle configurator, and an "About this device" table renders the spec's non-configurable facts. **Deals merchandise themselves**: a pricing rule that mentions an offering becomes a product-page banner (anonymous teaser API, audience-scoped with an honest "private customers only" note), an **"add the deal to cart" one-gesture action**, a cart hint offering the missing partner (idempotent), **in-cart colour/storage pickers** for unconfigured device lines, and **indicative guest pricing** (identity claims sanitized server-side — negotiated company terms can never leak or be fished anonymously; sign-in reprices exactly) |
| business-console | `/biz` | **B2B self-care, two faces by role.** The company admin manages their own organization: add people **with a real sign-in minted on the spot** (TMF672 invitation; the party is pinned to the new token subject), order subscriptions for them **and change a member's plan in place** (same line, same number), see every member's live lines, browse **Plans & your company pricing** (list price vs the org's **negotiated price** — a pricing rule conditioned on `organizationId`/`memberCount`, authored as data, applied on the consolidated invoice too), and read the **consolidated company invoice** with per-person line attribution. **Split billing**: every product bills to its **payer** — orders the admin places are payer-stamped to the company at ordering time; anything a member buys themselves stays on their own personal bill. The admin also sets a **Company policy**: a configurable **device allowance** — the company pays a device's monthly charge up to the cap, and the excess lands on the employee's personal bill as its own labelled line ("above company allowance"). An invited **member** signs in to the same channel and gets their **my-page**: their work line, usage meters, SIM self-care (PUK, PIN reset), the "billed to your company" note — and their **personal bill** (self-bought services + device co-pay excess) right below it. **Localized like the storefront**: the tenant manifest drives language and currency — Nova's business console is Norwegian with NOK invoices |
| dealer-console | `/dealer-app` | **The retail partner's desk** (the CSP + external-retail model — think Elkjøp/Power): a clerk whose org holds a dealer agreement sells at the counter (order stamped with the dealer attribution), mints **starter kits** for the shelf (activation code + the SIM in the box + attribution baked in), and watches **commission** accrue pending → earned — or claw back when a customer uses their angrerett. The same `/dealer/v1` machine API serves chains with their own POS (per-partner OAuth2 client, per-partner rate limits at the edge, the chain's own phone riding the sale as context). The **Telesales desk** on the same console serves outbound partners: pull the consented, DNC-washed dial list ("1 reserved excluded" said out loud), record warm and cold offers (a cold prospect's code rides the partner's own SMS), and watch the pipeline in its honest states — offered, confirmed, expired |
| csr-console | `/csr` | Assisted service with **role-scoped powers**: customer 360, ticket queue, AI copilot (`ai:use`), number-porting cutover (`porting:write`), service cease (`service:write`), Stock view (`stock:read`) — a junior agent sees the 360 without any of them |
| admin-console | `/console` | Back office with **role-scoped tabs**: catalog, stock, campaigns, business Rules (with dry-run), porting, AI audit, the **Product Copilot** (chat → proposal card → "Yes — create it"), a **Product advisor tab** (findings with their receipts, "Adopt as draft…" as the row action), an **Operators tab** (operator-as-a-form: five fields mint a tenant on the running fleet), the billing back office (Bill formats, Deliveries, Unapplied cash, Disputes, Dunning), and a **Staff tab** (TMF672) where a tenant admin grants/revokes whole areas per operator — no IdP console needed. Each area appears only for operators holding its staff role |
| mobile-app | `/app` | React Native (Expo): the modular LOB app — adaptive Home, one-tap plans, saved-card bill pay, **SIM self-care (PUK/PIN) and one-tap data top-ups on the line card, partner entitlement codes, locale-aware money from the tenant manifest**, and the **Family card** (household payers accept requests, order onto a dependent's line, mint child accounts; dependents see "paid for by …" and can leave); web today, iOS/Android from the same code |

## Multitenancy (pool model, hardened)

Two operators — **GenAlpha** (`localhost`) and **Nova Telecom** (`*.nova.localhost`) — share one
deployment:

- **Identity**: tenant = verified OIDC issuer. Each tenant is a Keycloak realm in dev; a Cognito
  pool or Entra tenant works identically in production. Machine-to-machine calls use the *acting
  tenant's* credentials.
- **Data**: `tenant_id` on every domain row, enforced twice — in code on every query, and by
  **PostgreSQL Row-Level Security**: services run as restricted roles that see zero rows without
  a session tenant, even for SQL with no predicate at all.
- **Anonymous traffic**: the gateway maps hostname → tenant, so each operator's storefront,
  CSR console and admin console show only their world. Cross-tenant access reads as 404 everywhere.

## Quickstart

Prereqs: JDK 17, Maven, Docker (with compose), ~8GB free memory for the full stack.

```bash
mvn -q package -DskipTests            # images use the host-built jars
docker compose build                  # seconds, not minutes
docker compose up -d                  # ~25 containers; wait for healthy

# demo data (idempotent; order matters) — see ops/README.md
for s in seed_genalpha_one reshape_bundle link_prices seed_stock \
         seed_serviceable_areas seed_usage_allowances seed_agreement_terms \
         seed_promotions seed_resource_pools seed_ai_slice seed_verified_identity seed_nova seed_content \
         seed_device_content seed_color_pricing seed_ocs_charging; do python3 ops/seed/$s.py; done
```

Then browse:

| URL | What |
|---|---|
| http://localhost:8080/shop/ | GenAlpha storefront (self-register, or browse as guest) |
| http://localhost:8080/dealer-app/ | Dealer console — clerks of a signed retail chain (suite #48 shows the flow) |
| http://localhost:8080/shop/ | B2C self-service persona — `kai@bss.local` / `kai` (live line, change plan, SIM PUK/PIN) |
| http://biz.nova.localhost:8080/biz/ | Norwegian B2B persona — `birgit@fjellheim.no` / `birgit` (bedriftskonsollen in Norwegian: Fjellheim AS, people & lines, consolidated 299 kr invoice) |
| http://shop.nova.localhost:8080/shop/ | Norwegian B2C persona — `nils@nova.local` / `nils` (Min side in Norwegian: two plan tiers to switch between, 59 kr datapåfyll, a 299 kr bill with Betal, Mobildata meter) |
| http://localhost:8080/app/ | B2B member on MOBILE — `emil@acme.example` / `emil` (work line, SIM care, plan change; "provided by your company", personal purchases on his own bill) |
| http://localhost:8080/biz/ | Business console (B2B customer admin) — `bianca@acme.example` / `bianca`. Members she invites sign in here too, with the credentials shown at invite time, and get their my-page |
| http://localhost:8080/csr/ | CSR console — `agent-anna` / `agent` (full agent) |
| http://localhost:8080/csr/ | CSR console, junior persona — `jo@bss.local` / `jo` (read + tickets only) |
| http://localhost:8080/console/ | Admin console — `demo` / `demo` (all areas) |
| http://localhost:8080/console/ | Admin console, product persona — `pat@bss.local` / `pat` (product tabs only) |
| http://shop.nova.localhost:8080/shop/ | Nova Telecom's white-label storefront (own realm, own catalog) |

Demo cards: `4242 4242 4242 4242` pays, anything ending `0002` declines. Promo code: `WELCOME10`.
Serviceable fiber postcodes start with `111`, `222` or `333`.

## AI with a real model (optional)

The intelligence component ships with a deterministic `stub` provider, so AI features work with
zero keys and zero network. To run against a real local model:

```bash
docker compose --profile ai up -d ollama
docker exec bss-ollama ollama pull llama3.2:1b       # ~1.3 GB, fits the dev VM
AI_PROVIDER=openai-compatible AI_BASE_URL=http://ollama:11434 AI_MODEL=llama3.2:1b \
  docker compose up -d intelligence
```

The same two variables point at OpenAI, Azure OpenAI, Mistral, Groq or vLLM
(`AI_PROVIDER=openai-compatible`, `AI_BASE_URL=…`, `AI_API_KEY=…`), or at Claude natively
(`AI_PROVIDER=anthropic`, `AI_API_KEY=…`). Every call — including contract misses and retries —
lands in the per-tenant `ai_audit` ledger.

## Verification

```bash
mvn -q clean test -Dapi.version=1.44        # ~250 tests incl. real-Postgres migrations + RLS proofs
cd ops/e2e && npm i playwright && npx playwright install chromium
node storefront_test.js && node guest_test.js && node console_test.js \
  && node csr_test.js && node tenant_test.js && node roles_test.js \
  && node app_test.js && node martech_test.js && node policy_test.js \
  && node pricing_test.js && node bundle_test.js && node demo_test.js \
  && node family_test.js && node family_phase2_test.js && node family_config_test.js \
  && node family_phase3_test.js \
  && node knowledge_test.js && node personalization_test.js && node growth_test.js
```

The storefront suite alone walks ~40 assertions: register → configure a bundle (phone choice,
color, storage) → cart ×2 → serviceability gate → installer slot → pay → order → stock
consumption → payment capture → commitment agreement → usage meters → billing run with overage
and promo discount → pay the bill with the vaulted card → notifications — all through the UI.

## Deploying

`deploy/helm/genalpha-bss` carries the whole stack (both realms, per-service RLS roles, the
second tenant's issuers). Choose modules with the [composer](https://kundanvarma.github.io/genalpha-bss/composer.html):

```bash
helm install genalpha-bss deploy/helm/genalpha-bss -f my-modules.yaml
```

Terraform stacks for EKS and AKS live under `deploy/terraform`.

**The chart is not template-verified — it has run, on three live clusters.** Local k3s, then
**AWS EKS** (Graviton nodes, RDS), then **Azure AKS** (Flexible Server, ACR) — each with the
same images, the same in-cluster Kafka/Keycloak, the same smoke, and billing at **two replicas**
holding a single set of tick leases (the P0 scale-out safety, proven live against a managed
database on both clouds). Each run is one command — `ops/k8s-soak/eks-run.sh up|smoke|down` and
`ops/k8s-soak/aks-run.sh up|smoke|down` — wrapping Terraform, the registry push, managed
Postgres, Helm, the smoke and a verified teardown. **Not one line of application code differs
between the clouds** — every difference (node architecture, VM/region gating, connection caps,
extension allow-lists, TLS) lives in Terraform and two Helm `--set`s, absorbed by the same seams
the app already had. The nine live-run truths each cloud taught — and the cloud-by-cloud
difference table — are in [architecture.md §5](docs/architecture.md) and
[docs/k8s-soak-plan.md](docs/k8s-soak-plan.md). "Any cloud" is now two invoices, not a claim.

## Stack

Java 17 · Spring Boot 3.2 · Spring Security (multi-issuer resource server) · JPA/PostgreSQL
(+ RLS) · Flyway · Kafka (transactional outbox) · Keycloak 26 (dev IdP) · React + Vite ·
Playwright · Helm · Terraform · GitHub Actions.

## CI

`.github/workflows/ci.yml` builds and tests every service on each push. CI is the source of
truth for "it builds and passes".
