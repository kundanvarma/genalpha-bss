# The Operational Semantic Registry — the ontology as a component

*2026-09-11. The GenAlpha Operational Ontology is the model of the business: what exists, what state it can be in, what can be done to it, under which conditions, by whom, through which component, and what follows. The **Operational Semantic Registry** is its machine-readable representation — the `ontology/` directory in the repo — served, checked, executed and explained by the `ontology` component (`:8160`, `/ontology/v1`). Research and rationale: `docs/ontology-research.md`; the review it answers: Ivan's comments of 2026-09-11 (approve with additions).*

## What it is, in one paragraph

Three layers. TM Forum semantics are cited as **lineage** on every concept and capability (SID entity, TMF Open API resource, ODA component). The GenAlpha **core** is the product's: seven concepts on the first journey (Customer, Subscription, ProductOffering, Service, ProductOrder, Bill, Entitlement), eleven governed actions (ten live, one retired), forty-one typed capabilities across thirteen components. An **operator overlay** (`ontology/tenants/<tenant>/`) may add concepts and actions and may tighten governance, policy and wording on a core action — it may never remove a core precondition (Taranga's overlay adds a price ceiling). Every document is validated against `ontology/schema/*.schema.json` and every reference resolved at load; a dangling capability, concept, component or event fails startup, and the CI test (`RegistryLoadTest`) proves it.

## The rule that governs it

> AI reasons and proposes. Policies govern. Deterministic components execute.

The registry offers agents **actions only** — never generic writes. An action carries its meaning, inputs, typed preconditions, permissions, policy domain, governance (autonomy, approval, audit), the capability that executes it, typed effects, the events it emits, and what settles it. Side effects are references to capabilities, not prose; the conformance suite resolves every one.

## The first journey, end to end

`upgradeSubscription`: Customer → Subscription (TMF637 product) → available upgrades (same family, on sale on the caller's channel, dearer per month) → nine preconditions (active line; offering on sale; visible in channel; a different offering; same family; not a bundle; an upgrade, not a downgrade; no unexpired commitment; deliverable where the customer lives) → permission (the owner themselves, or a role — a customer's token acts on its own line only) → policy domain `order` (the registry's machine identity holds `policy:evaluate` for the pre-check; product-ordering enforces the same rules again at execution) → **TMF622 productOrder with a `modify` item, placed with the caller's own token and channel** → effects: the OCS rate plan (SOM's charging seam), the entitlement server, proration on the next bill run → events from product-ordering, product-inventory, service-orchestration, device-entitlement → a **decision receipt** (`DecisionRecordedEvent` on `bss.ontology.events`, ingested by insight's decision log as decision point `ontology.upgradeSubscription`, with every verdict as evidence and the available upgrades as candidates) → outcome `completed` when the order completes.

A refusal names the failed condition in the action's own words: *"the target must differ from the current offering"*, *"this is not your subscription"*, *"the target must cost more per month than the current plan — otherwise it is a downgrade"*. The same words appear in the console's dry run, the SDK's `Refused` error and the MCP tool's error content.

## The faces

| Face | Where | What |
|---|---|---|
| Registry, read | `GET /ontology/v1`, `/concepts`, `/actions`, `/capabilities`, `/components`, `/schemas` | the merged core + tenant view for the caller's tenant |
| Check | `POST /ontology/v1/actions/{name}/check` | may it happen for these inputs — every condition with its verdict (holds / fails / unknown), permission, policy; nothing changes |
| Execute | `POST /ontology/v1/actions/{name}/execute` | check, then the capability with the caller's token, then the receipt; 422 with the refusal when refused |
| Derived reads | `GET /ontology/v1/subscriptions/{id}/availableUpgrades` | the candidate set of the action |
| Explain | `GET /ontology/v1/explain/{action,concept,journey}/{name}`, `/explain/page/{path}` | the definitions in words — the ? drawer's "Explain this page" draws on it first, then the manual |
| MCP | `POST /ontology/v1/mcp` (JSON-RPC 2.0, MCP 2026-07-28 and 2025-06-18: server/discover, initialize, tools/list, tools/call, resources/list, resources/read) | tools generated from the actions: `list_actions`, `explain`, `available_upgrades`, `check_<action>`, `<action>`; the concepts as resources; a confirm card for picking a benefit; the agent calls with the delegated token of the person it acts for — see *The MCP door at 2026-07-28* below |
| SDK | `ops/ontology/gen-sdk.mjs` → `packages/genalpha-sdk/src/index.ts` | a typed TypeScript client generated from the registry — one interface per concept, `upgradeSubscription()` / `checkUpgradeSubscription()`, `availableUpgrades()`, `explain()`; the suite regenerates and diffs it |
| Self-description | `GET /.well-known/genalpha-component.json` | what the component manages, executes and emits, and its live routes — read from the running application |
| Console | Platform › What the BSS can do | action cards in words, "Explain the journey", "Try a dry run" |

## Identity

Every downstream call carries the **caller's own bearer** and `X-Channel`; the registry reads a customer's line with the customer's rights and places the order as them. The one exception is the policy pre-check: `bss-ontology`, a machine client whose service account holds `policy:evaluate` and nothing else (`ops/seed/realm_ontology_client.py` adds it to a live Keycloak; the realm files carry it for fresh installs). No database: the registry rides in the jar, receipts ride the bus.

## Versioning and change

Concepts, actions and capabilities carry `version` and `introduced`; a retired action carries `deprecated` and `supersededBy` and stays callable until its date. Generated surfaces are contracts: the suite regenerates the SDK and fails when the committed copy differs, and the MCP tool list is derived at request time from the same definitions. A breaking semantic change bumps the version; a tenant overlay cannot change `executes`, `inputs`, `permissions` or `emits` of a core action.

## Conformance — suite #125 (`ops/e2e/ontology_test.js`)

1. Registry loads; typed references resolve; anonymous callers are refused.
2. Every capability route is served by the gateway (checked against its route table); every event an action, concept or component declares exists in that component's Java sources; every role named exists in the realm; the component's self-description agrees with its registry entry.
3. A fresh customer gets a line; the registry lists its available upgrades; same plan, a bogus id, a stranger's line and (after the upgrade) a downgrade are refused in words; executing a refused action is 422 and does nothing.
4. The upgrade executes with the customer's own token: order completed, product repointed with the previous offering remembered for proration, line renamed by SOM, same number.
5. The receipt reaches insight's decision log with the verdicts as evidence and the candidates listed.
6. MCP: initialize, generated tools, a dry run that refuses with the condition, the journey explained in steps, unknown method → -32601.
7. Explain: action, page (productOrder known; the simulator page honestly not yet), and the Taranga overlay adds its guardrail while keeping every core condition.
8. The SDK regenerates identically.

## The second arc — 2026-09-11, later the same day

**Nine governed actions** now: `upgradeSubscription`, `suspendSubscription`, `resumeSubscription`, `replaceSim`, `cancelOrder`, `disputeBill`, `issueCredit`, `requestLaunch`, `approveLaunch`, `holdLaunch` — plus `changeSubscriptionPlan`, kept on record as *retired* (deprecated 2026-09-10, superseded by `upgradeSubscription`): it refuses past its date with the successor named, and is absent from the agents' tools and the SDK. Forty-one capabilities across thirteen components.

**Approval ladder, as Ivan's example.** `issueCredit` is permitted to `billing:admin` and to the `agent` persona; governance says approval is human above 25 (approver role `billing:admin`) and the ceiling is 50. A care agent's credit of 5 executes at once — **as the registry's own identity**, because the action's permission model replaces the API's (`executes.as: registry`), and the receipt names the agent who asked; 30 is **filed** on the workforce approval desk (`POST /ai/v1/workforce/approvals`) with the action, the exact request and the reason, and executes with the approver's own token when they press Approve; 60 is refused by the ceiling before anyone is asked. A finance approver executes directly.

**Runtime self-description.** product-ordering, service-orchestration, product-catalog, product-inventory, billing and device-entitlement each serve `/.well-known/genalpha-component.json` (events declared, routes read from the running application). `GET /ontology/v1/conformance` compares every registry entry with the component's own description; the seven journey components agree, the rest say honestly that they do not describe themselves yet.

**Outcomes measured.** The outcome sweep (`OutcomeSweeper`, every five minutes; `POST /ontology/v1/outcomes/sweep` on demand) reads this tenant's `ontology.*` receipts from the decision log with the machine identity, and for those past the window (90 days in life, `ONTOLOGY_OUTCOME_AFTER_DAYS`; the demo compresses it to 0) reads the subscription and writes the outcome: **retained** (still active on the chosen offering), **changed**, or **lost**.

**Learning contracts on ontology decision points.** The campaign component registers `ontology.<action>` on first contact, so an operator can write the intent — `ops/seed/seed_ontology.py` seeds "retained" for the upgrade and "accepted" for credits, with guardrails. The console's action card shows the contract; the registry executes what the caller chose and never redefines either the ontology or the policy — learning optimises within.

**Context resolver.** `GET /ontology/v1/context/customer/{id}` (and the MCP tool `customer_context`) assembles the person, their subscriptions with what each could become, their lines, bills and decision receipts in one call, walked with the caller's rights; every edge that did not answer is listed, so a customer's own call says "receipts (not allowed to read it)" instead of pretending.

**RDF export.** `GET /ontology/v1/export.ttl` — the registry as Turtle (`ga:` vocabulary: concepts as classes with SID/TMF/ODA lineage, actions with preconditions as blank nodes, capabilities, components, events). Derived; the YAML stays the source of truth.

**The desk on the SDK.** The generator also emits a plain-JS twin (`apps/csr-console/src/sdk/genalpha-sdk.js`); the CSR desk's customer 360 offers *Upgrade options* on an active product, checks the chosen one through the registry (every condition with its verdict, in words) and upgrades through it — the second consumer of "define once, consume everywhere", after the storefront's own change-plan which still posts raw TMF622.

**Machine identity, widened deliberately.** `bss-ontology` holds `policy:evaluate` (pre-check), `insight:read` + `inventory:read` (outcome sweep), `billing:admin` (delegated credits under the threshold) and `workforce:use` (filing approvals). Nothing else runs as the registry.

## The MCP door at 2026-07-28 (#422, 2026-11-02)

The MCP specification of 28 July 2026 drops the session: there is no `initialize` handshake, and every request carries its own protocol version and client capabilities in `params._meta`. The door now speaks both eras on the same endpoint.

- **Two versions, one endpoint.** A modern client sends any request straight away — `server/discover` lists `2026-07-28` and `2025-06-18` — and gets `resultType: "complete"` and the server's identity in every result's `_meta`. Nothing is remembered between requests and no `Mcp-Session-Id` is minted. A 2025-06-18 client still opens with `initialize` and gets exactly the answers it got before. `initialize` answers with the client's version when we speak it; a version we do not speak is answered with 2025-06-18, the newest one a handshake can lead to (2026-07-28 has no handshake).
- **The routing headers must tell the truth.** `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` mirror the body so a gateway can route and rate-limit without reading it. When the door sees them, they must match the body (`Mcp-Name` is the tool name, prompt name or resource URI, Base64 sentinel form decoded); a mismatch is HTTP 400 with JSON-RPC `-32020 HeaderMismatch`, because a gateway that routes on one thing while the server executes another is how a policy gets bypassed. A modern request must carry all three; an unsupported version is 400 with `-32022` and the versions we speak; a modern request without its capabilities is 400 `-32602`.
- **Where to get a token.** `GET /.well-known/oauth-protected-resource/ontology/v1/mcp` (RFC 9728, routed by the gateway) names the tenant's own Keycloak realm as the authorization server — the same issuer that validates the token afterwards — chosen from the gateway's hostname stamp, never from the request body. The MCP endpoint answers a call without a token with 401 and a `WWW-Authenticate` that points to the document.
- **The model is readable.** `resources/list` has one resource per concept of the caller's tenant (core plus overlay), `ontology://concept/Customer` and so on; `resources/read` returns the concept as JSON — its meaning, states, properties and links — so an agent can learn what a thing is before acting on it.
- **A card, not a paragraph.** The dry run `check_pick_benefit` links an MCP Apps card (`ui://genalpha-ontology/pick-benefit-confirm`, `text/html;profile=mcp-app`). A host that renders apps shows the benefit by name, what it costs in Choices and whether it can be picked, with a **Confirm** button that asks the host to call `pick_benefit`. The card is self-contained (no outside script, style or connection) and writes everything it shows as text, never as markup. It is linked from the dry run, not from `pick_benefit`, because a host shows a card when its tool has finished, and on the executing tool that would be after the Choice was already spent. Confirm runs every precondition, permission and policy again with the person's token and writes the receipt: the card decides nothing.

Proof: `McpServerTest` (MockMvc: both versions, the header mismatches, the resources, the metadata per tenant, the card and its link). The browser suite #125 still speaks 2025-06-18 and is unchanged.

## What is not here yet (honest edges)

- **MCP 2026-07-28 is partly implemented.** Not built: `subscriptions/listen` and change notifications, SSE responses (every answer is one JSON object), multi-round-trip requests (`InputRequiredResult`), `ttlMs`/`cacheScope` on list results, `Origin` validation at the door, audience binding of the token to this resource (RFC 8707 — the door accepts any valid token of the tenant, as every component does), scopes in the metadata or the 401 challenge, and `prompts` (the list is empty). A 2025-06-18 batch is still accepted.
- **MCP Apps (SEP-1865) is one card.** Only `pickBenefit` has one. The server does not read the client's `io.modelcontextprotocol/ui` capability before linking the card (the link is `_meta`, harmless to a host that ignores it); the card does not use host context (theme, display mode, locale), resizing or `ui/message`; `pick_benefit` stays visible to the model as well as the app, so an agent on a host can still pick without the click — the click is the host's offer, the action's governance is the guarantee. The card's cost reads `choiceCost` from the catalog offering; when the catalog states none the card says so rather than guessing.
- **The metadata document is served by Spring Security, not by a `permitAll` line.** Since #428 it is declared as a `framework:` entry in `ops/security/public-paths.txt` and measured by `FrameworkSurfaceTest`. Every other component serves Spring's unfilled default too (measured on the live fleet, 3 Nov 2026); only the ontology's is routed by the gateway. See `docs/hardening.md`.

- The registry does not consult a learning contract before executing; the contract is the operator's stated intent and the sweep's vocabulary, not yet a gate. Guardrails that should gate belong in the action's preconditions today.
- Six more components (party-account, agreement, qualification, policy, insight, intelligence) do not self-describe at runtime; conformance for them is against sources.
- The Subscription → Service link is by name (the inventory carries no `realizingService`), the same weak seam the shop and the business console use today; a `realizingService` column plus a back-fill on service activation is the fix, an inventory change.
- Qualification is offering-by-place only, so "eligible for this customer" is approximated by serviceability at the customer's postcode.
- A customer's token cannot see a stranger's objects at all (404 by design), so that refusal reads "could not be read" rather than "not yours".
- **There is no receipt store.** Receipts are published to `bss.ontology.events`; two components consume it by pattern — event-hub (fan-out to TMF688 subscribers) and flow (journey triggers) — and neither is an archive. The ontology service has no migrations, so there is no receipt table anywhere, and the durable record of a governed action is Kafka retention plus whatever a subscriber happened to keep. Issue #234.
- A receipt that fails to send **after** the action completed still cannot be undone. The service now refuses to start when it cannot write receipts at all (`ontology.receipts-required` (`ONTOLOGY_RECEIPTS_REQUIRED`), true by default — the Boot 4 migration proved why: one missing autoconfigure module and every receipt silently vanished while the service reported healthy), and a send that fails anyway is logged at ERROR and counted on `bss.ontology.receipts.dropped`. That makes the loss visible, not impossible; making it impossible needs the store above.

## Tracing (#422)

Each execute is an OpenTelemetry `execute_tool <action>` span; the receipt rows carry its trace id (`action_receipt.trace_id`, outside the hash chain) and the decision carries it as `context.traceId`, so a trace, a receipt and a decision-log row join on one key. No input or refusal wording goes on the span. How to point it at a collector, and its limits: [ai-control-plane-plan.md](ai-control-plane-plan.md#tracing--one-key-for-a-trace-a-ledger-row-a-receipt-and-a-decision-422).

## Agents — the registry of AI actors

Every AI actor of the BSS is declared in `ontology/agents/*.yml` and served by the same registry as the concepts and actions: `GET /ontology/v1/agents`, `/agents/{name}`, `/explain/agent/{name}`, the Turtle export (`ga:Agent`) and the MCP tool `list_agents`. An agent declares its kind (assistant, copilot, classifier, autonomous, external), whose rights it runs with (`caller` = the signed-in person's own token and nothing more, or a machine account), the capabilities it may read, the governed actions it may check and execute, the model use cases it makes (each one governed, metered and audited by the intelligence component), its autonomy (advisory, supervised, policy-autonomous), the contract that bounds it, and what it remembers. The registry refuses an agent that names an unknown capability or action, or that executes what it may not check; the ontology suite checks that every declared model use case exists in the intelligence code.

The name travels: a call carrying `X-GenAlpha-Agent: care-assist` (the generated SDK's `agent` option; the CSR desk sets it) is receipted with `context.agent = care-assist`, the MCP door receipts `external-mcp` unless the client says who it is, and an unknown name is receipted as `<name> (unregistered)` rather than dropped. Ten agents are registered today: care-assist, care-chat, product-copilot, sales-advisor, journey-copilot, knowledge-ask, incident-agent, signal-classifier, hermes-worker, external-mcp.
