# Ontology for GenAlpha — what it is, why it is worth building, how we would build it

*2026-09-11. Research asked for by Ivan, based on Palantir's Ontology, with TM Forum TR326, Totogi's telco ontology and Amdocs aOS as the telecom reference points. It answers two questions: what is the value of an ontology, and why (and how) should GenAlpha build one. It also reads Ivan's proposed architectural principle, "Semantic and Operational by Design", and says where we agree and where we would bend it.*

## The short answer

An ontology, in the sense Palantir and the telecom industry now use the word, is not a data dictionary and not a research artifact. It is the **operational model of the business, made machine-readable**: what exists (objects and their links), what state things can be in, what can be done to them (actions, with their preconditions, permissions, policies and consequences), and who executes it. Its value is that every consumer — an application, an integration, a human on a screen, and above all an AI agent — works against **the business model instead of the implementation**.

GenAlpha should build one, because we are unusually well placed to: almost all of the raw material already exists in the running system (40 TM Forum API families, 204 domain event types, 69 scoped roles, a JSON-logic policy engine, the DecisionPoint seam with its receipts and learning contracts, a capability map). What is missing is the thing that ties them together and can be read by a machine. That is a registry and a generator, roughly five weeks of work, not a semantic-web programme. The first consumer is already live: the "BSS explains itself" help work done today (see the last section) is the ontology principle at its smallest scale.

Ivan's proposed principle — *every business entity, relationship, capability, state, event, policy, decision and permitted action shall be explicitly represented in a machine-understandable operational ontology* — is right in substance and matches, almost clause for clause, what Palantir and TR326 describe. Two bends: the ontology must be **derived from and tested against** the running code (or it rots, exactly as our help tags rotted), and the semantic foundation should be the **TM Forum Open API resource models we already execute** rather than a hand-modelled SID.

## 1. What Palantir means by "Ontology"

Palantir's Foundry Ontology is described as the operational layer of the organisation — a digital twin that binds data to decisions and actions. It has two halves.

**Semantic elements** — the nouns. *Object types* (Customer, Order, Aircraft), each with *properties* and *links* to other object types. Objects are backed by datasets, streams or models; the ontology is the layer where those become business things rather than tables.

**Kinetic elements** — the verbs. *Actions* are the only sanctioned way to write: an action type declares its parameters, its validation and submission criteria, which objects and properties it may change, and its side effects (notifications, webhooks to external systems). *Functions* are typed logic that runs against objects (eligibility, scoring, simulation). *Interfaces* let several object types share a contract. Because writes go through actions, permissions, audit and "what would this do" are properties of the action, not of each application.

**Exposure**. The *Ontology SDK* (OSDK) generates typed clients — `customer.subscriptions()`, `order.approve()` — from the ontology, so an application programs against business concepts and never sees storage. The *Ontology MCP* exposes the same objects and actions to AI agents through the Model Context Protocol, so an agent reads the world through object queries and acts only through declared actions. AIP, the agent platform, layers on top: retrieval context from objects, object queries as tools, logic functions as tools, action tools, and governance (who may run which tool, with what approval).

Three lessons carry over directly:

1. **Actions are the unit of safety.** An agent that can only invoke declared actions cannot do an undeclared thing. Validation and permissions live once, on the action.
2. **The ontology is the contract for every consumer.** SDK, MCP tools, forms, notifications and audit are generated from the same definitions, so they cannot drift apart.
3. **Semantic and kinetic together.** A model of the nouns alone is a data catalogue; only with the verbs does it become operational.

## 2. What the telecom industry is doing with it

**TM Forum TR326 "Operationalizing Ontologies for AI-Native Autonomous Networks" (June 2026).** The Forum's own move from SID/eTOM as documents to a layered semantic architecture: a *Context layer* (a formal ontology held in a knowledge graph), an *Interaction layer* (APIs through which agents read and write the graph) and an *Intelligence layer* (specialised agents under routing and orchestration). Its governing rule is the one that matters for us: the semantic environment holds the facts and the constraints, and *state transitions are executed only by explicitly declared methods*, so every agent action stays inside the world's semantics. The Forum's parallel work — MCP support inside ODA components, the AI-native ODA canvas sandbox, and the earlier TR292 Intent Management Ontology behind TMF921 — points the same way: ODA components become self-describing, and agents talk to them through declared capabilities.

**Totogi Ontology (the former "BSS Magic").** A commercial "executable knowledge graph" for telecom: entities, processes and actions of *other vendors'* BSS stacks are normalised onto SID, ODA, eTOM and 3GPP vocabulary, so that agents and integrations program against the graph rather than each vendor. Their two claims are the ones to remember: invalid actions become *unrepresentable* (an agent cannot express a call the ontology does not allow), and integration cost drops from N² pairwise mappings to N mappings onto the model.

**Amdocs aOS (February 2026).** An agentic operating system whose "Cognitive Core" runs agents on Amdocs' Logical Data Model as a telecom ontology: entities, workflows and policies expressed in one model that the agents reason over. Marketing-heavy, but the shape is the same: ontology in the middle, agents on top, execution through the vendor's own components.

The convergence is the finding. Four independent parties — a general-purpose data platform, the industry standards body, a challenger vendor and an incumbent — arrive at the same architecture: *nouns + verbs + governance in one machine-readable model, exposed to agents through MCP-style tools, with execution reserved for deterministic components*. Ivan's principle is that architecture written down for us.

## 3. Reading Ivan's principle

The document (Revised GenAlpha Architectural Principle: Semantic and Operational by Design) proposes four dimensions — business semantics, business logic, first-class actions, governance — one contract exposed three ways (APIs, SDK, MCP), a runtime knowledge graph beside the ontology, semantic decision context for learning, and nine supporting principles. Assessment, clause by clause:

| Proposal | Verdict | Note |
|---|---|---|
| Ontology as a core layer, not an AI add-on | Agree | The help gap found today shows why: the *same* missing description hurts humans, docs and agents alike. |
| SID as the primary semantic foundation | Bend | SID is a data model on paper. The executable, SID-derived shape we already run is the TMF Open API resource model (TMF620/637/641/…); build the nouns from those and cite SID entities as the lineage. |
| Business logic in the ontology (eligibility, pricing, lifecycle, credit, compatibility, entitlement) | Agree, with a boundary | Represent *that* a rule applies and *where it runs* (policy domain, component); do not re-implement the rules in the ontology. The policy engine and the catalog remain the executors. |
| Actions as first-class, with meaning, inputs, preconditions, outcomes, side effects, policies, permissions, ODA capability, API, events | Agree fully | This is the heart of it and the thing we lack. Every field listed maps to something we already have: roles, policy domains, event types, gateway routes, components. |
| Governance: allowed actor, limits, human approval thresholds, audit | Agree | We already have the approval desk, learning-contract autonomy levels and decision receipts. The ontology names them per action. |
| "AI reasons and proposes. Policies govern. Deterministic capabilities execute." | Agree — already our rule | Copilot → proposal → policy → component is how the product copilot, launch governance and the DecisionPoint seam work today. |
| One contract → API + SDK + MCP | Agree | Generate the SDK and the MCP tool descriptions from the registry; never hand-write them. |
| Runtime knowledge graph | Bend | No graph database. The TMF resources with their `href` links *are* the graph; what an agent needs is a context resolver that walks them (customer → subscriptions → products → services → balances) and the decision log for the "why". |
| Self-describing components ("what I manage, what it means, states, actions, policies, events, how to invoke me") | Agree strongly | Make it a well-known endpoint per service and a conformance test; this is what keeps the ontology honest. |
| "Every … shall be explicitly represented" | Bend the wording | *Explicit* must not mean *hand-written*. Derive what can be derived (types, links, states, events, routes, roles) and hand-author only meaning, preconditions and consequences. |

## 4. The value, concretely for GenAlpha

**Agents act on business capabilities, safely.** Today an agent that wants to upgrade a line has to know that it is a TMF622 product order with a `modify` action item whose `product.id` is the current product and whose `productOffering.id` is the target, that the policy service must be consulted with domain `order`, that the OCS plan flips on activation, and that the entitlement server must be told. With an `upgradeSubscription` action in the ontology the agent sees one capability with preconditions ("subscription active, offering eligible for this customer"), permissions, and the events it will produce. It cannot compose an invalid call, and the refusal comes with a reason.

**One chokepoint for policy before execution.** Ivan's issueCredit example — allowed actor, maximum amount, human approval above a threshold, required conditions, mandatory audit — is exactly the shape of our launch envelopes and learning contracts. Putting it on the action means humans in the console, the CSR desk, an integration and an agent are governed by the same declaration.

**The BSS explains itself.** Kundan's idea from today: an AI-native BSS should be able to explain its own functionality and how to use it. With the ontology, "what can I do on this page, what happens if I do it, who may do it, what will it emit" is *generated* from the definitions rather than written by hand and left to rot. Today's fix (manual + page shelf → Ask) is the same idea powered by prose; the ontology is the structured version. It is a genuine differentiator: incumbents document; we describe.

**Learning becomes business-aware.** The decision log already records context → decision → action → outcome. What it lacks is *names*: a decision is currently "campaign treatment id X". With the ontology every decision and outcome is typed against a business concept, so the learning contracts and the journey tuner optimise "conversion of upgradeSubscription offered to a churn-risk customer", not "arm 2 of journey 17".

**Define once, consume everywhere — and stop drift.** The concrete evidence of drift is small but telling: four help tags pointed at pages that do not exist, two console pages had no help at all, and no document was indexed anywhere. A registry with a conformance test (every declared action resolves to a live route; every emitted event is declared; every console page has a description) is the mechanism that makes drift a failing test rather than a discovery.

**N² → N for the seams.** Every BYO seam (OCS, PSP, carrier, registry, CMS, field service, SM-DP+, RCS) already maps a provider onto our model; the ontology makes that mapping explicit and documented, which is what Totogi sells to operators who do not have a BSS like this.

**Positioning.** Totogi puts an ontology *over* other vendors' BSS; Amdocs puts one *inside* a closed stack; Palantir has the general machinery but no telecom semantics. GenAlpha would be the open, vendor-neutral BSS whose ontology and execution are one thing, traceable to TM Forum, and testable against the running components. Ivan's line for the deck is the right one: *APIs make BSS programmable. Ontology makes BSS understandable. AI makes BSS intelligent.*

## 5. What we already have (the raw material)

| Ontology dimension | Already in GenAlpha | Where |
|---|---|---|
| Object types, properties, links | TMF resources served by 40 API families over 69 gateway routes; `href`/`relatedParty`/`productOffering` links | gateway routes, service OpenAPI, entities |
| States | Lifecycle enums per resource (offering: inStudy→inDesign→inTest→active→retired; order, bill, ticket, journey states) | entities + console human-language pages |
| Events | 204 domain event types on `bss.*.events` topics, outbox-relayed | every service `events/` package |
| Permissions | 69 scoped realm roles (`catalog:approve`, `wholesale:admin`, `ai:use`…), gateway + method security | Keycloak realm, controllers |
| Policies | JSON-logic policy engine with domains (order, pricing, gifting, launch envelopes…) | `policy` service |
| Decisions and their "why" | DecisionPoint seam, decision log with receipts, learning contracts with objective, guardrails, allowed actions, autonomy | insight + campaign |
| Capabilities → components | Capability map: business capability → TMF tech capability → component → proof suite | `docs/capability-map.md` |
| Levers | Product-spec characteristics the platform acts on (`chargingSpecId`, `sliceProfile`, `volte`, `zeroRatedApps`…) | catalog + SOM + entitlement |
| Agent exposure | ACP + MCP agent channel over catalog/cart/ordering; copilot tool registries | catalog, gateway, intelligence |
| Prose description | Operator's Manual (48 chapters), help articles per page, `PAGE_GOALS` | docs/manual, knowledge base, console |

Everything on the left exists. Nothing on the right is linked to anything else by machine.

## 6. Proposal: the GenAlpha Operational Ontology

A registry and a generator over what exists, in five slices. No triple store, no OWL editor; a TM Forum-aligned RDF export can be added later for TR326 conformance without changing the source of truth.

**Slice 1 — the registry (about a week).** `ontology/` in the repo: YAML per concept and per action, validated by a schema, served by a small `ontology` endpoint (from the intelligence service, or its own ODA component). A concept lists its TMF resource, SID lineage, states and links. An action lists meaning, inputs, preconditions, permissions (role), policy domain, component and route, emitted events, side effects, autonomy level and the human approval rule. Start with about twelve actions across four concepts: Subscription (activate, upgrade, downgrade, suspend, resume, terminate, swapSim), Product offering (create, price, requestLaunch, approve, hold, unlaunch, retire), Bill (dispute, credit, resend), Customer (verifyIdentity, issueCredit). A **conformance test** asserts every action's route is a live gateway route, every declared event exists in code, and every console page maps to at least one concept or action.

**Slice 2 — self-describing components (a few days).** Each service exposes `GET /.well-known/genalpha-component.json`: what it manages, states, actions it executes, policies it consults, events it emits, how to invoke it. The registry aggregates these; the conformance test compares declaration with reality.

**Slice 3 — the Ontology MCP (about a week).** An MCP server generated from the registry: one tool per action (description = the action's meaning, preconditions and consequences in words), read tools per concept (object query by id, walk links), and a `explain` tool that returns the definition. Proof: the product copilot and the care chat use `upgrade_subscription` and are refused, with the reason, when a precondition or a learning-contract guardrail fails. The existing AI budget, kill-switch and audit apply unchanged.

**Slice 4 — the BSS explains itself, structurally (a few days).** The `?` drawer and "Explain this page" draw first on the ontology (this page manages these concepts; these actions are available to you; this is what each does and emits), then on the manual. The manual chapters and help articles gain generated "actions on this page" blocks. Knowledge gaps that are really ontology gaps (a page with no concept) become build tasks rather than writing tasks.

**Slice 5 — SDK and context resolver (about a week).** A generated TypeScript SDK for the apps (`customer.subscriptions()`, `subscription.availableUpgrades()`, `subscription.upgrade(offer)`) that maps to the TMF calls, and a context resolver that assembles a customer's sub-graph for an agent in one call, with the decision log's receipts attached as the "why".

Hard lines: the ontology is tested against the running system or it is not merged; no hand-modelled duplicate of SID; actions are the only writes offered to agents; RL never appears on a buyer roadmap (the ontology serves explainability and safety first, learning second).

## 7. The first proof, done today: the BSS explains itself

The trigger was Kundan asking the help drawer on the Simulator page "how to use the simulator" and getting nothing. The trace found four stacked causes: the page had no help article; keyword search is AND-shaped so "how to use the simulator" matched no article with "simulator" but without "use"; Ask searched the same articles with no page context and gave up with a canned line; and the Operator's Manual, which does explain the simulator, was not indexed anywhere. Four other help tags pointed at pages that do not exist.

What changed: the manual is now loaded into the knowledge base per tenant as 85 published sections tagged by chapter and by console page; Ask is page-aware (the page's own shelf is retrieved alongside the question, and a content-words pass catches the AND miss); the drawer shows the page's goal when no article exists, has an "Explain this page" button, and tells the truth about the AI kill-switch and the budget instead of printing "no answer"; the dead tags are fixed and the two simulator pages have how-tos. The same question now returns numbered steps with the source named.

That is the ontology principle at prose scale: description lives beside the thing described, is retrieved by where the person is, and is verified by a suite. Slice 4 above turns the prose into structure.

## Built — 2026-09-11

Ivan reviewed this note the same day (approve, with additions: registry named apart from the ontology, versioning, a core-plus-tenant extension model, typed action semantics, a future-safe graph rule, the ontology/policy/learning boundary, and one journey proved end to end instead of twelve actions). All of it went into the build: the `ontology` component, the `ontology/` registry, suite #125 and the generated SDK — see `docs/ontology-seam.md`.

## Sources

- Palantir, Foundry Ontology overview, Ontology SDK, Ontology MCP and AIP architecture pages (palantir.com/docs/foundry/ontology/overview, palantir.com/docs/foundry/ontology-sdk, AIP documentation), read 2026-09-10.
- TM Forum, TR326 *Operationalizing Ontologies for AI-Native Autonomous Networks* v1.0.0 (June 2026) — abstract and secondary summaries; the member PDF was not reachable. TM Forum Inform, "Towards an AI-native ODA" (2026). TM Forum TR292 *Intent Management Ontology* v1.0.0.
- Totogi, telco ontology product pages and the "BSS Magic" material (2025–2026).
- Amdocs, aOS and Cognitive Core announcement (February 2026); secondary coverage.
- Ivan Hagen, *Revised GenAlpha Architectural Principle: Semantic and Operational by Design* (2026-09-10).
- GenAlpha repo: `docs/capability-map.md`, `docs/decision-log.md`, `docs/launch-governance.md`, `docs/contextual-help.md`, gateway routes, realm roles, event catalogue (counted 2026-09-11).
