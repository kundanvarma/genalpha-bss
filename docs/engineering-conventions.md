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
