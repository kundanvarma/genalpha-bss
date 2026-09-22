# Engineering conventions

*Adopted 2026-09-22 after a developer's review of the codebase and the
research in [engineering-principles-agent-era.md](engineering-principles-agent-era.md).
Every rule here is paired with the check that enforces it, because a
conventions file improves how an agent finds its way, not how it designs.*

## 1. Types

| Rule | Check |
|---|---|
| A service's public methods take and return **records or domain classes**, never `Map<String, Object>`. | `ops/arch/ratchet.sh`: counts public untyped returns per service; the count may only fall (baseline in `ops/arch/baseline.json`). Pre-commit and edit hook. |
| TM Forum payloads are parsed **once at the wire** into typed DTOs. Polymorphism rides `@type` with Jackson `@JsonTypeInfo` and a `defaultImpl`; unknown fields land in an `extensions` map (`@JsonAnySetter`) so they round-trip. | Mapper per resource; a DTO round-trip test per component. |
| `Characteristic.value` and extension blocks stay open (`Object` / `JsonNode`). That is the edge; nothing behind the mapper touches raw JSON. | Code review; the ratchet catches the leak when it becomes a public return. |
| Domain records are exhaustive: sealed interfaces where a `@type` has finitely many house meanings; `switch` without `default`. | Compiler. |

Migration path: type the **boundary first** (controller ↔ service), then the
services the demos lean on (catalog, ordering, ontology), then ratchet the
rest down arc by arc. Never rewrite a component in one go; every batch ends
with its suites green.

**Worked example: product-catalog (22 Sep).** 35 → 0 public `Map<String, Object>` returns, wire unchanged (TMF620 CTK, suites #119/#131 green).
- One record per resource, verdict or receipt, plain names (`LaunchDecision`, `ReadinessItem`, `CheckProductConfiguration`, `PriceView`); shared wire atoms once (`Money`, `Quantity`, `TimePeriod`, `EntityRef`, `NameValue`).
- Key order = component declaration order, pinned with `@JsonPropertyOrder`; `@JsonInclude(NON_NULL)` only where the map used to leave the key off, per-component where a record mixes both; `@JsonProperty("@type")` for TMF markers.
- Open edge stays open: `@JsonAnyGetter @JsonAnySetter Map<String, Object> extensions` on wire records (unknown fields round-trip), `Object` for characteristic values, `JsonNode` for a polymorphic input (string | ref | list), `List<Map<String, Object>>` for the standard's own open blocks (spec values, terms, price conditions).
- Request bodies are records too (`GovernanceRequest`, `ProductConfigurationRequest`) with `@JsonIgnoreProperties(ignoreUnknown = true)` and an `EMPTY` for an optional body; a lenient `@JsonCreator` keeps old parsing (a bare date in `TimePeriod`).
- A mutable stored blob (`GovernanceState`, the `governance_json` column) is a plain class with public fields plus `extensions`, unwrapped into the view with `@JsonUnwrapped`; a `DtoRoundTripTest` (pure Jackson, no context) pins bytes and order, and runs in seconds.

**Worked example: ontology (22 Sep).** 25 → 0 (plus product-ordering's descriptor), wire unchanged (#125, CSR/Home suites, generated SDK identical). What the registry *declares* stays `JsonNode` (actions, agents, `effects`/`emits` copied into a receipt); what the service *computes* is a record (`Check`, `Verdict`, `ExecuteReceipt`, `CustomerContext`, `Recommendation`, `Explanation`, `ConformanceResult`).
- A response written path by path (execute: refused, filed, done) gets a private mutable `Draft` in the service and one `receipt()` that freezes it; a record enriched in stages (ranking, then decision id) grows by `with`-style copies (`ranked(…)`, `decided(…)`), never by mutation.
- A verdict keeps its domain form (`Boolean ok`) and writes its wire form (`verdict: holds|fails|unknown`) from a `@JsonProperty` getter with `@JsonIgnore` on the component; evaluation context (`resolved`) is `@JsonIgnore`d, so the same record serves the check, the receipt and the MCP result.
- `@JsonUnwrapped` puts a check's keys beside the action name (`ActionCheck`); one `@JsonPropertyOrder` is the union of every path's order and `NON_NULL` leaves off what a path never wrote — where paths disagreed, the common path wins and the deviation is in the report, not the code. Protocol shapes the server owns (MCP tool schema, JSON-RPC envelope) are records too; `Map.of(...)` had been randomising their key order.

**Worked example: intelligence (22 Sep).** 70 → 0, wire unchanged (TMF915 CTK, copilot/CSR/workforce/control-plane suites, snapshot diff of 59 endpoints: 25 byte-identical, 21 values-only, 11 key-order-only where `Map.of` had been random, 2 model-authored).
- A store that keeps the caller's document **verbatim** (TMF915 alarm/rule/contract rows) types it as `ObjectNode`, never a record; the projections the service computes (`AiModelView`, `AiModelContractView`) are records rendered to the same tree (`valueToTree`) so one TMF630 filter/`fields=` mechanic serves both halves.
- What a **model** wrote stays `JsonNode` inside the record (`CopilotReply.proposal`, `forecast`) with `@JsonAnyGetter extensions` for keys the model adds; the parse/normalise code keeps working on trees and converts once at the record boundary. Foreign documents from another component (`ticketById`, `processFlow`, `vocSummary`) are `JsonNode` too.
- A request whose whole body is pasted into a prompt (customer summary, ticket reply, wrap-up) is a `JsonNode` body — an open console document — not a record with fifty optional fields; validation ports as `path(...)` checks.
- A record enriched in stages grows by copies (`PriceSimReportView.saved(id,name)`, `withLines(kept)`, `KnowledgeAnswer.cached(true)`); a `Map<String, Record>` keyed by a dynamic name (`byKind`, `workerTypes`) is a keyed collection, not an untyped return.

**Worked example: billing (22 Sep).** 43 → 0 (raw-map bodies 16 → 0), wire unchanged (37 endpoints snapshotted: 31 byte-identical, 6 key-order-only where `Map.of` had been random; TMF678 CTK). Money is the entity's `BigDecimal` as stored, never re-scaled — `0.00` stays `0.00`, a stored `49.90` stays `49.90`; the `DtoRoundTripTest` pins the scale, and a value read back from a stored JSON blob (credited lines) is parsed as `BigDecimal`, not through a `Double`.
- An event that is the API view plus a few keys (`DunningStepReachedEvent` = case + `step` + `billNo`, `InstallmentPaidEvent` = plan + `billNo` + `paidAmount`) is a record with the view `@JsonUnwrapped` — and the unwrapped component must be named FIRST in `@JsonPropertyOrder`, or Jackson writes the added keys before the view's. Event-only maps (`promiseView`, `letterOf` internals) that no public method returns stay maps: events are a standing rule, and a map that serialises identically is not debt.
- Absent-vs-explicit-null on a PATCH (`customizationId: null` clears the row) cannot ride `Optional` — Jackson gives `Optional.empty()` for an absent creator parameter too — so the field is a `JsonNode`: absent → Java null (leave alone), JSON null → `NullNode` (clear).
- A handler with two honest answers is a sealed interface, not a map with different keys: `BillingRunResult` (`Receipt` | `Busy`), `ChannelConsentResult` (row | `Withdrawn`), `DunningRow` (`DunningCase` | `CollectionCase`), rehearsal rows (`Priced` | `Missing`). The foreign catalog documents the run prices from (`offering`, `price`) became `JsonNode` on the client interface; the run's arithmetic reads `path(...)`/`decimalValue()` and the test mocks build trees with `valueToTree`.

**Worked example: insight (22 Sep).** 50 → 0 (raw-map bodies 15 → 0), wire unchanged (82 endpoints snapshotted on two tenants against a zero noise floor: 69 byte-identical, 13 key-order-only where `Map.of` had been random; suites #social-lead/listening/meta/publish, audience activation/async/native, landing, prospect-reach green; the rest blocked on stopped components, not on insight).
- A **user-authored document inside a typed envelope** stays `JsonNode`: the audience criteria tree (`AudienceView.criteria`, `AudienceRequest.criteria` — a JSON string or an object, both accepted as before), a desk preset's `values`, a desk event's `props`, a connector's `config`, a decision's `candidates`/`context`/`evidence` as the publishing service wrote them. The envelope (`id`, `name`, `population`, `@type`) is typed; the service evaluates the document, never re-shapes it; a parse failure degrades to a `TextNode`, as the map path degraded to the raw string.
- A block that is **absent until it exists and then written whole, nulls included** (a decision's `outcome`/`outcomeValue`/`outcomeAt`) is a nullable nested record `@JsonUnwrapped` in its slot of `@JsonPropertyOrder`: Jackson skips a null unwrapped property and writes every key of a present one — `NON_NULL` on the three fields would have dropped a null `outcomeValue` the ontology's sweeper and the CSR desk read today. A receipt that re-labels a row (`@type: DecisionReceipt` in the row's own slot, then the sentences) is the row `@JsonUnwrapped` FIRST plus `receipt`, built from a `with`-style copy (`asReceipt()`).
- One `@JsonPropertyOrder` per suggestion is the union of every kind's facts (`form, fields, stopField, count, features, opened, actions, people`) with `NON_NULL`, and the one-click fix is a sealed `SuggestedAction` (`Preset` | `Http` with a `JsonNode` body) so `decide()` switches on the kind instead of reading `action.get("kind")`. A per-JVM-random `new LinkedHashMap<>(Map.of(...))` seed is not "pinned" by wrapping — put the keys in order.
- The pure-Jackson `DtoRoundTripTest` caught a real slip on the first run (a factory passing `prospectId` into the `email` slot of a five-string record): positional record constructors are where a typo lands now, so every `of(...)`/`with(...)` factory gets an exact-bytes assertion, not only the wire shapes.

**Worked example: quote (22 Sep).** 41 → 0 (raw-map bodies 18 → 0), wire unchanged (64 endpoints snapshotted against a zero noise floor: 55 byte-identical, 4 key-order-only where `Map.of` had been random, 5 scale-only on stored lines; TMF648/TMF699 have no CTK — cpq-rules, order-contract, pricing-esign, sales, lead-scoring, pipeline-board, social-lead, CDP-engagement suites green; b2b/telesales/opportunity-pipeline blocked on stopped business-console and party-interaction, not on quote).
- A **stored JSON column of lines** (`quote.items`) is a `List<QuoteItem>` record with `extensions`, not a `List<Map>`: rows an older JVM wrote in `Map.of`'s random key order parse into the record and re-write in the pinned order, and money inside the blob comes back as the `BigDecimal` that was stored — so the wire now shows `90.000000` where the `Map` read had shown a `Double`'s `90.0`. Numerically identical, textually not: report it as scale-only, never re-scale to hide it, and pin the write side's scale (`setScale` at computation) only as its own deliberate change.
- A list the map **left off when empty** (a deal's `items`/`activities`) is `@JsonInclude(NON_EMPTY)` on that component alone; a nested block the map left off when *null* (`owner`, `salesOpportunity`) is a nullable record with `NON_NULL` — the two are different rules and one annotation does not serve both.
- The foreign documents a quote is priced from (intent, offerings, price, allowances) and the receipts it gets back (order, agreement) are `JsonNode` on the client; the CDP's one small answer (`LeadSignal`) is a record with a `NONE` sentinel so fail-soft stays typed; what quote *sends* downstream (`ProductOrderRequest`, `AgreementRequest`, `NarrativeContext`) is a record too, so the outbound TMF shapes are pinned by the same test.

**Worked example: usage (22 Sep).** 34 → 0 (raw-map bodies 27 → 0), wire unchanged (125 endpoints snapshotted against a two-run noise floor: 92 byte-identical, 33 key-order-only — `Map.of` had been random, and a stored TMF635 document used to keep its posted key order under the overlay; the 15 remaining diffs are the noise floor's own list growth and DB member order. Suites usage_policy and ocs green; TMF635 2022/2022, TMF677 60/60, TMF654 837/837; journey_* blocked on stopped campaign, configurable_channels on the stopped app, storefront at the appointment slot grid — none on usage).
- A **TMF document stored verbatim and answered with an overlay** (TMF635 usage and specification, the TMF654 task echoes) comes in as `ObjectNode` and goes out as a record with the derived keys declared and the rest in `@JsonAnyGetter extensions` — declared keys first, stored keys after; the map had kept the posted order with overrides in place, so this is the one key-order change the report owns. A tier table whose shape is ours (`Tier`) is a typed list both ways, stored as the same JSON array.
- A **seam's projection is the house record on the adapter interface** (`OcsClient.subscribersOf` → `List<OcsSubscriber>`): the generic adapter parses the stand-in's JSON straight into it (`ignoreUnknown`), the vendor adapter maps its own document (SigScale's TMF637 product, a `JsonNode`) into it; consumers (`PrepayBucketView`, the running-low translation) never see a vendor shape.
- The **internal door and the public machine seam do not share a body record when only one may name the tenant**: `SpendThresholdNotification` (internal, `tenantId` honoured) and `SpendChargeRequest` (gateway-facing, the caller's tenant only) — one record would have let a public caller switch tenant from the body. Absent-vs-null on a cap or a limit rides `JsonNode`, as in billing.
- The snapshot's own writes must be **rows the CTK accepts** (a `reserveBalance` without `relatedParty` failed 12 kit assertions across three runs — two of them written by the old image): take the kit's noise floor before the change too, and delete only what the snapshot itself wrote.

**Worked example: device-commerce (22 Sep).** 31 → 0 (raw-map bodies 7 → 0), wire unchanged (91 endpoints snapshotted against a zero noise floor: 49 byte-identical, 41 key-order-only where `Map.of` had written `relatedParty` at random, 1 value-order-only — a refusal's model list from `Set.of`; suites device_commerce, installments, bnpl_recurring, psp_bnpl_settlement, psp_bnpl_remittance, device_model green; install_slot_rollback and device_entitlement blocked on stopped appointment and device-entitlement, not on device-commerce).
- A **seam's answers are typed on the interface** (`FinancingProvider` → `FinancingQuote`, `EarlySettlementQuote`, `FinancingSettlement`): one record per answer shared by every driver (operator book | mock bank | BNPL), a static factory per model (`writeOff(…)`, `bank(…)`, `delegated(…)`) so each path writes only its own facts, `@JsonPropertyOrder` the union of the paths, `NON_NULL` per component; the service stamps its own keys last by copy (`forAgreement(id, currency)`).
- A **receipt is the row `@JsonUnwrapped` FIRST plus the facts of the act** (`SettleReceipt`, `SwapReceipt`, `WithdrawalReceipt`), and an idempotent replay returns the same record with the facts null — `NON_NULL` leaves them off, so a replay serialises as the bare row it always was; facts that must be written whole with nulls (`WithdrawalReceipt.AgreementFacts`) are a nullable nested `@JsonUnwrapped` record, as in insight.
- **Randomness leaks into values, not only keys**: an estimate's note listing defects from a `Map.of`, a refusal naming models from a `Set.of` — the snapshot flags them structural though no key moved. Put such tables in declaration order (`LinkedHashMap`, `List.of`) and report the sentence change as value-order-only; a body that a record refuses (`HttpMessageNotReadableException`) keeps the 400-with-message contract through the exception handler.

**Worked example: campaign (22 Sep).** 28 → 0 (raw-map bodies 14 → 0), wire unchanged (116 endpoints snapshotted against a two-run noise floor: 85 byte-identical, 22 key-order-only where `Map.of`/`Map.copyOf` had been random, 3 value-order-only — the lifecycle refusal's `Set.of` list, 2 structural = the noise floor's own holdout hash and the tenant's growing portfolio; 22 suites green, 4 blocked on stopped intelligence/process, 2 pre-existing failures the old image fails identically).
- **The decision seam's record is the event AND the answer**: `DecisionRecord.view()` is what `DecisionRecordedEvent` carries (keys the decision log and the ontology's outcome sweep read) and what a dry run answers, re-labelled by a `with`-style copy (`asDryRun()` = `@type: DecisionDryRun`, `decisionId` null under `NON_NULL` on that one component) and `@JsonUnwrapped` FIRST under `dryRun: true`. `Map.copyOf` in `DecisionRequest` had been shuffling the logged `context` per JVM — a copy that lands in a log is `Collections.unmodifiableMap(new LinkedHashMap<>(…))`.
- **A view that is another view re-labelled** (a learning contract = the decision point's keys under `@type: LearningContract`, then `contract`) is the inner record `@JsonUnwrapped` with a `labelled(type)` copy; the two honest shapes of the contract (the tenant's row | the defaults marked as such) are a sealed pair (`ContractView.Stored` | `Defaults`), each with its own `@JsonPropertyOrder`, never one record with half its keys null.
- **A stored JSON ledger of the service's own rows** (the tuning log) is a `List<TuneEntry>` both ways — rows an older image wrote parse into the record (`ignoreUnknown`, `NON_NULL` on the keys only some rows carry: `z`, `threshold`, `decisionId`) and re-write byte-identical; the policy behind the seam still reads the rows as its open context (`convertValue(rows, STEP_LIST)`), so the record boundary is the ledger, not the policy.

## 2. Modules and size

| Rule | Check |
|---|---|
| A Java class does one thing and stays under ~400 lines; a service package has a `controller`, `service`, `mapper`, `dto`, `entity`, `repository` split. | Review; ArchUnit rules where a component has them. |
| A front-end file stays under **300 lines**; a React component renders one concern. | `ops/arch/ratchet.sh`: front-end file line counts may only fall from baseline. |
| No file grows past what an agent can read in one pass; split before adding. | Same ratchet. |

## 3. Data, events, tenancy

| Rule | Check |
|---|---|
| Every table carries `tenant_id`; every table gets a row-level-security migration in `db/migration-postgresql`. | `PostgresMigrationTest` per component; RLS suite. |
| Flyway versions are **shared** between `db/migration` and `db/migration-postgresql`. | Flyway refuses duplicates; `mvn clean` first. |
| Every state change publishes a domain event through the **outbox** in the same transaction; consumers are idempotent. | Suite per arc asserts the event; the martech sweep after any event change. |
| No cross-component database access. A component reads another only through its API or its events. | ArchUnit `noClasses().dependOnClassesThat().resideInPackage("..other.entity..")` where present; review. |

## 4. Channels and UI

| Rule | Check |
|---|---|
| Channels ask, never compute: pricing from the catalog's configurator, eligibility from policy, actions from the ontology. | Suite #131 pattern; review. |
| Every front end sends `X-Channel`; every agent surface sends `X-GenAlpha-Agent`. | Gateway refuses what a channel cannot see; suite #118. |
| React for channels; **no new vanilla-JS files**. The vanilla consoles are migrated desk by desk: split into files first (classic scripts in load order, so the shared globals keep working), then mount React islands with `createRoot` into the existing ids, one desk at a time, deleting the old desk when the new one is green. | Ratchet on file size; suite per desk. |
| Screens speak operator language: names, never keys or UUIDs; healthy states stay quiet; every page has a goal line and a ? drawer. | Screenshot + look before handover; `console_home_test` asserts no UUID text on Home. A no-UUID assertion on every console page is a follow-up. |
| Accessibility: keyboard operable, visible focus, colour never the only signal. | Suite spot checks. |

## 5. AI

| Rule | Check |
|---|---|
| Copilots propose a card; a human presses Create. Agents act only through registered ontology actions with `check` and `execute` under the caller's token, and every act writes a receipt. | Suite #125; the decision log. |
| Every model call is metered and logged in the AI ledger; a stub provider runs every suite without a key. | AI audit page; suites run with `AI_PROVIDER=stub`. |
| Prompts and contracts live in code with the component, versioned; no prompt in a front end. | Review. |

## 6. Seams and vendors

| Rule | Check |
|---|---|
| Anything vendor- or country-specific sits behind a seam: one interface, one adapter per vendor, a stand-in in the fleet, selection per tenant in `tenants.yml`. | Every seam has a `mock-*` container and a suite that runs against it. |
| Fail soft on a seam: a quiet vendor never blocks the customer's action; the gap is logged and reconciled later. | Suite asserts the action completes with the stand-in down where that is the contract. |

## 7. Documents and proof

| Rule | Check |
|---|---|
| Every arc has `docs/<arc>.md` ending in **Honest limits**. | Review at arc end. A `--docs` check on the ratchet is a follow-up; older docs use varying headings. |
| A feature exists when its numbered suite is green through the gateway with a real token. | `ops/run-all-suites.sh`. |
| README rows, the capability map and the architecture diagrams follow every shipped component. | Review at arc end. |
| Decisions that lock a design get an ADR in `docs/adr/`. | Review. |

## 8. What we deliberately do not do

- No business logic in front ends, no shared database, no free-form pricing formulas, no prompt-only guardrails.
- No rewrite arcs. Debt is paid down inside feature arcs under the ratchet.
- No "regenerate instead of maintain" for anything with a suite: regeneration is a regression surface, the suite is the contract.
