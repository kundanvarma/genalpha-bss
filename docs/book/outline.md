# Verify Everything
### A Build Memoir — how one person and an AI built a complete telecom BSS, one proof at a time

> Working title. Alternates: *The Composable Machine* · *One Deployment, Two Operators* ·
> *The Invisible Made Visible*.

---

## What this book is

This is the story of building **genalpha-bss** — a vendor-neutral, multi-tenant telecom
Business Support System, plus a thin OSS layer and an AI platform, from an empty repository to
thirty composable ODA components, four channels, and eleven end-to-end test suites that all pass
in a real browser. It was built by one person working with an AI pair, over an intense arc, with
a single discipline holding the whole thing together: **nothing is real until it's proven, end to
end, against the real thing.**

It is a *memoir*, not a manual. There is a reference book to be written about how the components
fit together; this is not that book. This is about the decisions, the dead ends, the war stories,
and the method — and about what it means that a system this size was built the way it was.

Three threads run through every chapter:

1. **Vendor-neutral by conviction.** Any OIDC identity provider, any PostgreSQL, any
   Kafka-protocol broker, any LLM, any PSP, any number-porting clearinghouse. Nothing
   operator-specific is hardcoded, ever. The book is partly an argument for *seams* as the unit of
   trustworthy software.
2. **Verify everything.** Official TM Forum conformance kits. Real-Postgres migration tests.
   Row-level-security proofs that connect as the restricted role and demonstrate zero visibility.
   Every feature driven through a real browser. The discipline is what made building at this speed,
   with an AI, *trustworthy* rather than reckless.
3. **Honest about what's mocked.** A thin SOM. A mock PSP. A shaped-not-connected NRDB. The book
   never pretends a stand-in is the real thing — and argues that naming the seam honestly is what
   makes a demo credible instead of naive.

And underneath all three, the meta-thread: **this was built with an AI as the executing partner.**
Not autocomplete — a collaborator that wrote services, argued architecture, made mistakes, and got
caught by the tests. The memoir is, quietly, a field report on a new way of building software.

**Who it's for:** telco architects and BSS/OSS people who want to see the whole thing built
honestly; engineers curious about AI-assisted building at real scale; and anyone who has watched a
"platform" demo and wondered what was actually underneath.

**On the jargon:** this is a book about a TM Forum ODA BSS, so it can't avoid the vocabulary —
catalog, ordering, assurance, TMF620, ODA, CTK. The book's rule is that it never assumes the reader
already knows a term: each chapter explains the one or two standards it needs *as the story reaches
them*, in plain English, and **Appendix C** collects the full "what each TMF component means"
reference for anyone who wants the whole map at once. You should be able to read this having never
heard of TM Forum and come out fluent.

---

## The shape of the story (five acts)

- **Act I — The First Cut.** Why build a BSS at all, and the discipline that would define
  everything.
- **Act II — Scale, and the Multitenant Reckoning.** Many components; the architectural fork of
  one deployment serving two operators; making it visible through channels.
- **Act III — The Intelligence Turn.** Adding AI as a first-class citizen — vendor-neutral,
  honest, and verified — and learning where AI belongs and where it doesn't.
- **Act IV — Autonomy Accelerated.** Recreating a TM Forum Catalyst: the 5G AI slice, the
  self-healing network, and agents talking to agents. The crescendo.
- **Act V — Making It Real, Making It Legible.** Hardening for the real world; number portability;
  and the turn from *building* the system to *seeing* it.
- **Coda — The Method and the Meta.** What it means that it was built this way.

---

## Act I — The First Cut

### Chapter 1 — The lock-in that started it
*The scene:* Why a telecom Business Support System, and why build one at all. The problem the
product play answers: BSS/OSS is where operators are most locked in, and "composable ODA" is a
promise the industry makes but rarely ships end to end. The decision to build vendor-neutral from
line one — his own company runs one IdP stack; the product must never depend on it.
*Covers:* what a BSS actually is (catalog, ordering, inventory, party, billing); TM Forum ODA and
Open APIs as the vocabulary; the bet that *neutrality* is the differentiator.
*The lesson:* constraints chosen early (any-IdP, any-DB, any-broker) are cheap on day one and
priceless on day one hundred.
*Receipts:* the core five — product-catalog (TMF620), ordering (TMF622), inventory (TMF637),
party-account (TMF632/666/669), gateway.

### Chapter 2 — Proof or it didn't happen
*The scene:* the first real decision that shaped everything — how do you know a thing works? The
answer that became doctrine: the official **TM Forum Conformance Test Kits**, and every feature
driven through a *real browser*, not a mocked unit test.
*Covers:* CTK conformance for the original five (TMF620, 622, 632, 637, 666) with zero failures;
Playwright suites as the source of truth; real-Postgres migration tests (H2-in-Postgres-mode is
close but not identical, and "close" is where the bugs live).
*The lesson:* verification isn't overhead — it's the thing that lets you move fast *and* trust the
result, especially when an AI is writing the code.
*Receipts:* the CTK-conformance commits; the E2E suite scaffolding; the first "ALL CHECKS PASSED".

### Chapter 3 — The pair
*The scene:* introducing the working method — a human holding intent and judgment, an AI holding
execution and breadth. What it felt like: describing a component and watching it appear; catching
the AI's plausible-but-wrong answer with a test; the rhythm of build → verify → commit.
*Covers:* how the collaboration actually worked; where the AI was strong (breadth, boilerplate,
wiring) and where it needed the human (architecture calls, honesty about scope, taste); the
guardrails (tests as the arbiter).
*The lesson:* AI-assisted building is only as trustworthy as the verification around it. The
discipline of Chapter 2 is what made the speed of this chapter safe.
*Receipts:* the cadence of the commit history itself.

---

## Act II — Scale, and the Multitenant Reckoning

### Chapter 4 — One by one
*The scene:* the long middle — adding component after component, each a real TMF domain, each
verified before the next. Stock, payment, billing, qualification, appointment, tickets,
interactions, communication, cart, usage, agreements, promotions, roles, address, recommendation,
payment vault, document. The catalog of a real BSS, built in a marathon.
*Covers:* how a new component slots in (the composable pattern); the seams (mock PSP behind a
`PspAdapter`, any-IdP behind an admin-client interface); the transactional outbox and event
envelope that let components stay decoupled.
*The lesson:* a platform is not one big thing; it's many small things that agree on a few
contracts (the event envelope, the tenant, the gateway).
*Receipts:* the run of component commits; the composer (`docs/composer.html`) that turns the whole
catalog into a pick-list with dependency rules.

### Chapter 5 — Two operators, one deployment
*The scene:* the biggest architectural fork in the book. Multitenancy: silo (a stack per customer)
or pool (one stack, tenant_id everywhere)? The decision, made deliberately: **pool**, with three
reinforcing walls — a verified OIDC issuer *is* a tenant, `tenant_id` on every row, and PostgreSQL
Row-Level Security so a service literally cannot see another tenant's rows even with a
predicate-free query.
*Covers:* issuer-per-tenant identity; RLS with restricted roles and a `__system__` escape hatch;
hostname → tenant routing for anonymous traffic; per-tenant machine identity so service-to-service
calls carry the *acting tenant's* credentials.
*The lesson:* isolation you can *prove* (the RLS test that connects as the restricted role and sees
nothing) beats isolation you merely *assert*. Defense in depth means the app-layer check and the
database both have to fail before data leaks.
*Receipts:* GenAlpha and Nova Telecom running side by side; the tenant-isolation E2E suite; the
RLS proof tests.

### Chapter 6 — The war stories of scale
*The scene:* the things that cost real time — told honestly, because a memoir that only shows the
clean path is a lie. The 12GB VM that OOM-killed the stack. The Netty DNS cache in the gateway that
pointed a service name at a re-assigned container IP so the *wrong* service answered — three
separate incidents before the permanent fix. The machine-token caches holding tokens signed by a
dead realm key after a Keycloak restart.
*Covers:* the debugging arc of each; the permanent fixes (a DNS resolver bean; heap caps across
~30 JVMs; restart-the-machine-identity-services runbook).
*The lesson:* the gotchas that cost a day each are the real curriculum. Write them down or pay for
them twice.
*Receipts:* the DnsConfig fix; the memory file of dev-environment gotchas.

### Chapter 7 — Making it visible
*The scene:* a BSS with no faces is just APIs. Building the channels — one build of each,
white-labeled per tenant by hostname. The storefront, the CSR console, the admin/back-office
console, and the modular mobile app (inspired by a certain super-app that puts mobile, TV,
broadband and more in one place, composed by what the customer owns).
*Covers:* channels as thin, fail-soft clients over the same APIs; brand color, name and logo from
the tenant manifest (the same storefront in teal for GenAlpha, purple for Nova); the CSR 360;
content and imagery (TMF667) so the channels aren't grey.
*The lesson:* white-labeling is more than a logo — it's proof the neutrality claim reaches all the
way to the glass.
*Receipts:* the four channels; the two branded storefronts side by side (`docs/img/storefront-*`);
the beauty pass.

---

## Act III — The Intelligence Turn

### Chapter 8 — AI, but on our terms
*The scene:* the moment AI stops being a buzzword and becomes a component — and the immediate
question: whose AI? The answer, consistent with everything before it: a vendor-neutral **LLM
seam**. Any model, chosen by configuration. A deterministic stub as the default so the whole stack
and its tests run with zero keys.
*Covers:* the `LlmAdapter` seam (stub / OpenAI-compatible / Anthropic); always-on PII redaction;
a per-tenant audit ledger under RLS — the page a DPO asks for in the first meeting.
*The lesson:* "any model" has to be *proven*, not asserted — and proving it against a real model
(a tiny local Llama, then Claude) caught real bugs the stub never would.
*Receipts:* the intelligence component; the live Ollama and Anthropic runs; the audit viewer.

### Chapter 9 — Where AI belongs, and where it doesn't
*The scene:* the discovery that shaped the AI platform — language work versus structured work. The
campaign copy assistant and the CSR copilot are LLM jobs (a human always edits before it ships).
The churn scorer is emphatically *not* — it's transparent rules on structured facts a marketer can
verify.
*Covers:* copy assistant; CSR copilot (360 summary + reply drafts, with no credentials of its own,
so it can never widen an agent's view); the copilot's "verify before acting" honesty; the
contract-and-retry discipline that keeps a sloppy model from breaking a campaign.
*The lesson:* put the LLM where the work product is language; put rules where the truth is
structured. Knowing the difference is most of the value.
*Receipts:* the copy assistant, copilot, and the churn scorer's rule set.

### Chapter 10 — The engine that learns
*The scene:* the churn scorer grows up. Rules are day one; the requirement was that it *learn* —
become production-quality in a few months of running, or immediately from imported history. The
insight that made it real: start collecting the training data *before* anyone decides to do ML.
*Covers:* daily feature snapshots that accumulate from day one; labeled outcomes (including a port-
out, later, as the strongest signal); a per-tenant logistic model trained in-service; the
churn-signal → campaign-trigger loop that closes with the martech engine.
*The lesson:* the hard part of ML isn't the model, it's the data pipeline and the labels — build
those first, and the model is almost an afterthought.
*Receipts:* the churn learning provision; the martech loop (signal → journey → message).

---

## Act IV — Autonomy Accelerated

### Chapter 11 — The Catalyst
*The scene:* the DTW moment — watching a TM Forum Catalyst demo (Qvantel, Nokia, and others) sell a
5G AI slice end to end, and deciding to recreate its *pattern* on genalpha-bss. The premise starts
with physics: a sub-10-millisecond round trip can't be served from a distant cloud, so AI compute
moves to the edge and rides an SLA-backed slice.
*Covers:* the story being recreated; why the BSS/OSS boundary is where the differentiation lives;
the honest framing that this is a PoC with modelled pieces and a real *shape*.
*The lesson:* the most interesting product problems live at the seams between vendors — and a demo
is only as persuasive as it is honest about which parts are real.
*Receipts:* `docs/poc-ai-slice.html`; the ninth E2E suite.

### Chapter 12 — Intent, feasibility, and the network that upsells
*The scene:* business intent in, autonomous feasibility out — no human between the ask and the
answer. And the twist: the network doesn't just say yes, it *proposes more than the customer asked
for*.
*Covers:* a TMF921-shaped intent; the physics rule ("a sub-20ms round trip cannot be served from
regional cloud — physics, not policy") that makes low-latency intents feasible only where an edge
GPU pool covers the place; the edge-AI upsell originating on the network side.
*The lesson:* "autonomous" earns the word only when the system reasons and proposes, not just
validates.
*Receipts:* the intent API on the SOM; the feasibility + upsell E2E assertions.

### Chapter 13 — Pricing the future in tokens
*The scene:* the quote — the commercial half of the intent loop, where a network proposal becomes a
priced offer, and AI is metered in *tokens*.
*Covers:* the TMF648 quote born from an intent; token-based pricing rated by the usage engine (an
included allowance, then per million); an AI-drafted narrative; acceptance handing straight into
product ordering.
*The lesson:* new capabilities need new commercial models — and a BSS that can already meter usage
can meter AI tokens without inventing anything.
*Receipts:* the quote component; the "Stadium 5G Slice" + "Edge AI Inferencing" catalog.

### Chapter 14 — The system heals itself
*The scene:* mid-match, a fibre cut. And the system works out the fix on its own.
*Covers:* the slice provisioned on a fibre path; a critical alarm; assurance re-homing every service
on the failed path to the edge, restoring the SLA, filing the ITSM ticket, and closing the problem —
no human in the loop.
*The lesson:* self-healing is the payoff of an event-driven, loosely-coupled design — the same
choreography that makes the system hard to *see* is what lets it repair itself.
*Receipts:* the assurance self-heal; the fibre-cut → edge → resolved E2E.

### Chapter 15 — Agents talking to agents
*The scene:* the connective tissue. Four vendors' worth of workflow — an AI sales agent, a BSS, a
vendor OSS, an ITSM platform — interoperating as one loop, over standardized agent-to-agent
plumbing (MCP and friends).
*Covers:* natural-language intent capture; an MCP server exposing the lead-to-order loop as tools
any agent can hold; Claude driving the whole commercial loop from a plain-language ask; the BSS as
just another tenant-scoped machine principal.
*The lesson:* the agentic future runs on *standardized tools and identity*, not bespoke
integrations — the same neutrality argument, one layer up.
*Receipts:* `integrations/mcp-server`; the live "ask Claude to sell and provision a slice" run.

---

## Act V — Making It Real, Making It Legible

### Chapter 16 — Money, identity, and the things a real deployment trips on
*The scene:* the difference between a demo and a product is the boring correctness. Hardening the
commercial core.
*Covers:* payment idempotency (a retried authorization must never double-charge); real
authorize/capture/refund with settlement references; a strong-customer-authentication hook; a
pluggable Stripe adapter behind the same seam. BankID/Vipps step-up for offerings that require a
verified identity — enforced by a claim, not a mechanism, so BankID → MitID → itsme is a brokering
change, not code.
*The lesson:* the unglamorous properties (idempotency, step-up, capture-actually-moves-money) are
what separate "impressive" from "deployable."
*Receipts:* the PSP hardening; the BankID step-up gate; the tenth E2E suite.

### Chapter 17 — Keep your number, leave with your number
*The scene:* number portability — and the moment a marketing promise ("keep your number") became a
real checkout option. Then the other direction: leaving.
*Covers:* the country-aware clearinghouse seam (NRDB for Norway, under Nkom's rules; a mock for
dev; pluggable per country); port-in that activates on the ported number instead of a pool draw,
with provisioning *waiting* for the cutover; port-out that ceases the service, releases the number,
and — the loop closing — records a churn outcome the model learns from. The service-cease flow the
BSS had been missing.
*The lesson:* a feature isn't done at the API; it's done when the channel offers it and the whole
lifecycle — including *leaving* — is honest.
*Receipts:* the porting component; the full port-in-then-port-out E2E; the storefront "keep your
number" checkbox.

### Chapter 18 — Running on real Kubernetes
*The scene:* `helm template` proves the chart renders; this proves it *runs*. The soak.
*Covers:* the core-commerce slice on k3s (because the full stack won't fit beside a control plane
in 16GB — and that constraint exercised the composability model); the gotchas (k3s and compose
can't share the VM; images must be imported into containerd's k8s.io namespace because k3s doesn't
use the docker store); the smoke test through the gateway.
*The lesson:* IaC that's only ever been templated is a hypothesis. Run it once on real Kubernetes
and the hypothesis becomes a fact — and you learn two gotchas you'd otherwise hit in production.
*Receipts:* `deploy/helm/SOAK.md`; `values-soak.yaml`.

### Chapter 19 — The invisible made visible
*The scene:* the realization that the system's most differentiating property — the loosely-coupled,
event-driven choreography — is the one thing a viewer can't *see*. And the two attempts to fix
that: first an architecture view (components lighting up), then the pivot to what a non-technical
person actually wants — a **business process**, narrated in plain English, with a token marching
through the stages.
*Covers:* Live Flow — a Kafka-to-SSE stream of every business event; a BPM-cockpit process view
that reconstructs live instances and narrates each step ("a customer placed an order; the ordering
component captured it and published an 'order created' event onto the bus"); clickable steps and
per-instance drill-down; the path lighting up as it runs.
*The lesson:* if the value of your architecture is invisible, build the thing that makes it
visible — it's worth as much as a feature. And the difference between showing the *architecture*
and showing the *process* is the difference between impressing an engineer and convincing a buyer.
*Receipts:* the flow component; `docs/img/live-flow-process.png`; the "watch a test run live"
sessions.

---

## Coda — The Method and the Meta

### Chapter 20 — What it means that it was built this way
*The scene:* stepping back. Thirty components, four channels, eleven E2E suites, a Catalyst-grade
PoC, an AI platform, a k8s-validated chart — built by one person and an AI, over a compressed arc,
with every feature proven in a real browser.
*Covers:* the three threads revisited — neutrality as conviction, verify-everything as the enabling
discipline, honesty-about-mocks as the thing that keeps a demo credible. And the meta: what
AI-assisted building at this scale actually felt like, where it broke, and why the verification
discipline was the load-bearing wall — the AI could move fast precisely *because* the tests would
catch it.
*The lesson:* the future of software building isn't "AI writes the code." It's "a human holds
intent and judgment, an AI holds execution and breadth, and a ruthless verification discipline
makes the partnership trustworthy." This whole system is the existence proof.
*Receipts:* the repository itself — every claim in this book has a commit, a test, or a screenshot
behind it. That's the point.

### Appendix A — The gotchas, collected
Every debugging war story, distilled to the symptom, the cause, and the permanent fix. The
curriculum nobody teaches: Netty DNS caching, machine-token dead keys, k3s/containerd namespaces,
Keycloak realm-import replacing built-in scopes, RLS-owner-vs-restricted-role, the compose
`replace(count=1)` that put an env var on the wrong service.

### Appendix B — How to run it yourself
From an empty machine to two branded operators serving traffic, seeded, with Live Flow open. So the
reader can stop reading and start watching.

### Appendix C — The TM Forum vocabulary, plain-English → [`tmf-reference.md`](tmf-reference.md)
What every TMF component *means*: for each of the ~34 TM Forum Open APIs genalpha-bss implements
(TMF620 Catalog, TMF622 Ordering, TMF641 Service Ordering, TMF921 Intent, and the rest), what the
industry standard **is**, what it's **for**, and how genalpha-bss uses it — plus plain-English
definitions of TM Forum, ODA, CTK, and BSS-vs-OSS. Written so a reader who has never touched TM
Forum can follow the whole book; grouped to mirror the acts. **Drafted.**

*Editorial note:* the TMF vocabulary is introduced twice, on purpose. Each chapter explains the one
or two standards it needs *in the flow of the story* (Chapter 1 explains TMF620/622 as it builds
them; Chapter 14 explains TMF642/656 as the network alarms fire). Appendix C is the collected
reference for readers who want the whole map at once, or who read out of order. The book never
makes the reader already know what a "TMF679" is before it's needed.

---

## Notes for writing

- **Voice:** first person, Kundan's. Technical but story-first. The AI pair is a present character,
  not a tool footnote.
- **Every chapter earns its receipts.** The rule that made the system trustworthy is the rule that
  makes the book trustworthy: no claim without a commit, a test, or a screenshot.
- **Show the mess.** The OOM nights, the DNS bug's three incidents, the flow view built wrong then
  rebuilt right. A memoir that only shows the clean path teaches nothing.
- **The thin-stand-in ethic.** Whenever the book describes a mocked seam (SOM, PSP, NRDB), it says
  so plainly and then says exactly what a real deployment plugs in. That honesty *is* the argument.
- **Chapters are ~15–25 pages.** Twenty chapters + three appendices ≈ a 350–400 page book.
