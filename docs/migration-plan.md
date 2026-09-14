# Installed-base migration — the kit the operator runs without us — plan

*2026-09-14. Research in `docs/migration-research.md`. Status: PLANNED,
build starts after the 2026-09-15 demo. Nothing here touches the demo box
until then; every change is additive and behind flags that default off.*

## What we are building, in one paragraph

A migration kit the operator runs inside their own tenancy: templates per
canonical object, a schema profiler that emits a profile and synthetic rows
(the only thing that ever leaves the building), a declarative mapping spec
the operator confirms, a manifest with control totals, staging in the
tenant's own database, dependency-ordered loading through the existing
front doors, row-level rejects with a closed reason enum, an exportable
legacy-id crosswalk, a reconciliation desk with gates and named sign-off,
and the billing rehearsal and base-migration waves reused as the simulate
and cut-over steps. Then the pieces nobody else has: the customer sees
their own migration, legacy numbers work forever, migration on touch
through the bridge, a synthetic twin of the base for rehearsal, and the
same kit run backwards as the exit.

## Research findings (summary; the full note is docs/migration-research.md)

- Self-service migration tooling has converged: template → staging →
  validate/simulate → rejects with reason → upsert on legacy id → re-run.
  Billing adds vault-to-vault cards and term dates on subscriptions;
  telecom adds bill history as documents, never re-rated.
- Failures were status edge cases (dormant, closed, in binding,
  mid-cycle), identity and bill-archive continuity, and slow detection.
  Regulators fined the detection gap as hard as the defect.
- No TM Forum bulk-load API. Sources are DB-level (BSCS, Kenan, BRM, CBS)
  or API-level (Netcracker, Comarch, Cerillion, Tecnotree, Salesforce).
  Prepaid balances often live in a separate charging system.
- GDPR Art. 28 and ekomloven § 3-10 make vendor access to real data a
  contract-and-secrecy matter. The vendor-blind design is the only posture
  in which we are not a processor for the migration.

## Design

### Demo-safety rules (binding for every phase)

- Build on a branch on the laptop. No deploy to the box and no database
  migration there before the demo is over; merge to main after it.
- New columns are nullable. New behaviour sits behind a tenant flag that
  defaults off. The kit is a new service and a new console desk; existing
  flows are unchanged with the flag off.
- The existing suites stay green. The new suite proves migration on a
  synthetic export only.

### Phase 1 — the doors (additive changes to existing services)

1. **Legacy id on every entity.** `externalReference {sourceSystem,
   legacyId}` on party, billing account, product, service, resource,
   agreement (nullable columns, indexed per tenant). Lookup by legacy id
   in the party door and in CSR universal search. This is the crosswalk
   and "both numbers work forever".
2. **Opening financial position door** in billing:
   `POST /tmf-api/customerBillManagement/v4/customerBill/opening` takes
   legacy open invoices as bill records with `origin: legacy`, amount,
   due date, ageing bucket, document reference; balance forward per
   account; dunning stage. Plus a bill-archive index (legacy bill headers
   with a document reference) that the bill list renders and the bridge
   can read through.
3. **Migration birth path** for product, service and resource: created
   directly with lineage (`productOrderItem` empty, `realizingService`
   set), no order, no provisioning trigger, `origin: migration`.
4. **Tenant silence mode.** Flag `migration.silence` on the tenant: no
   journeys, outbound messages, martech events or provisioning for
   parties with `origin: migration` until released per wave. Default off.

### Phase 2 — the kit (new `migration` service + console desk)

5. **Templates and dictionary.** One CSV per canonical object (party,
   organisation, billing account, contact/identity, payment token,
   product, agreement, service, resource, opening position, archive
   index, open work) with a data dictionary and a JSON Lines twin for
   nested characteristics.
6. **Manifest with control totals.** Row count, SHA-256, sums (open
   receivables, monthly recurring, prepaid balances), hash total over
   legacy ids, as-of date, cutover date. Loader refuses on mismatch.
7. **Schema profiler** (runs locally, container or CLI): column names,
   types, null rates, cardinality, value patterns, top values for
   low-cardinality code columns only; synthetic rows from the patterns;
   the **edge-case census** (dormant with balance, closed but billable,
   in binding, mid-cycle, backdated, duplicates) from aggregates.
8. **Mapping spec** (declarative: source column → canonical field →
   transform → value map → rejection rule) with a runner; the assistant
   proposes from the profile only; confidence per line; the operator
   confirms in the desk. Starter packs for BSCS, Kenan, BRM, CBS,
   Salesforce Industries, Odoo from public schema knowledge (starting
   points, not verified mappings). The kit refuses to arm until every
   census category has a named rule.
9. **Staging, validation, levels.** Staging tables in the tenant's own
   database; type, referential and ontology checks (a mobile product
   needs a realizing service with number and SIM; a paused line needs a
   reason; an in-binding agreement needs an end date); dependency levels
   catalog → parties → accounts → identities → tokens →
   products/agreements → services/resources → opening position → archive
   → open work; parallel within a level.
10. **Loader** through the front doors as the registered agent
    `migration-kit` on channel `migration`, idempotent on legacy id,
    resumable run ledger, rejects per object with a closed reason enum,
    crosswalk export `(sourceSystem, legacyId) → genalphaId`. Every
    loaded customer is a receipted decision (`migration.load`) with an
    outcome sweep at 30 days (called, bill matched, churned).
11. **Migration desk** in the console: run list, scorecards per domain
    (source, loaded, rejected, delta, sum checks against tolerance), the
    billing rehearsal on the staged base, bill diff bucketed accept /
    review / block, sample panel (random N + outliers + high value) with
    named sign-off, gates load → counts → bill diff → sample → go-live,
    each with approver and evidence hash. Waves and the circuit breaker
    from base-migration reused; breaker also listens to signal
    intelligence (complaint classification).

### Phase 3 — beyond current practice

12. **"Your account moved"** page in the shop: old and new bill for the
    same period side by side, old number next to new, "something looks
    wrong" opens a case tagged `migration` that feeds the scorecard.
13. **Migrate on touch** via `bss-bridge`: a legacy adapter seam
    (`mock-legacy` in the fleet) pulls a customer across on their own
    event (bill cycle, renewal, care contact, first login); waves become
    the sweep for the never-touched.
14. **Synthetic twin**: profile → synthetic population with the
    operator's shape → full rehearsal in a rehearsal tenant with zero
    real rows.
15. **Read-through history**: the bridge answers the bill door for
    legacy periods from the operator's archive; ages out after two cycles
    or a configured horizon.
16. **Exit kit**: the loader's export direction, the whole base or one
    customer in the canonical format with the crosswalk.
17. **Identity re-link on first login**: Keycloak required action;
    match on a keyed hash of the national id (BankID/eID) or a one-time
    code to the migrated number; legacy-store check while legacy is up;
    staff federated, never migrated.

### Faces

- Console: Migration desk (runs, mapping confirm, scorecards, gates,
  waves). Human language throughout: reason codes rendered as sentences,
  never keys.
- CSR: search by legacy id; a migrated customer's card shows origin and
  the legacy number.
- Shop: "Your account moved" page; bills list includes legacy archive
  rows.
- Ontology: agent `migration-kit`; actions `migration.load`,
  `migration.release`; capabilities for the opening-position door;
  events `CustomerMigratedEvent`, `MigrationWaveReleasedEvent`.
- Docs: `docs/migration-kit.md`, manual chapter, README row, capability
  map row.

### Proof

Suite #130 `migration_test.js`: a synthetic BSCS-shaped export of a few
hundred customers (generated, never real) → profile with census → mapping
from the starter pack → manifest → staging with deliberate bad rows →
rejects with reasons → re-run clean → crosswalk → rehearsal matched
within tolerance with two exceptions by name → gates signed → wave one
released with silence lifted → the customer signs in, sees "your account
moved" with both bills, the CSR finds them by the legacy number → exit
export round-trips. Existing suites unchanged and green.

## Phases and estimate

| Phase | Items | Effort | Demo impact |
|---|---|---|---|
| 1 doors | 1–4 | one evening | none: nullable columns, flag off |
| 2 kit | 5–11 | two evenings | none: new service and desk |
| 3 beyond | 12–17 | two to three evenings | none: shop page and bridge seam only appear for migrated tenants |

Order of build: 1 → 2 → 10 → 5–9 → 11 → 12 → 14 → 13 → 15 → 16 → 17.

## Backlog

| # | Item | Phase | Status |
|---|---|---|---|
| M1 | externalReference on party, account, product, service, resource, agreement + lookup | 1 | planned |
| M2 | Opening financial position door + bill-archive index | 1 | planned |
| M3 | Migration birth path for product/service/resource | 1 | planned |
| M4 | Tenant silence mode | 1 | planned |
| M5 | Templates + dictionary + JSON Lines twin | 2 | planned |
| M6 | Manifest with control totals | 2 | planned |
| M7 | Schema profiler + synthetic rows + edge-case census | 2 | planned |
| M8 | Mapping spec + runner + starter packs | 2 | planned |
| M9 | Staging, validation (incl. ontology checks), levels | 2 | planned |
| M10 | Loader agent, run ledger, rejects, crosswalk, receipts + outcome sweep | 2 | planned |
| M11 | Migration desk: scorecards, rehearsal, gates, waves, VoC breaker | 2 | planned |
| M12 | "Your account moved" page + migration case tag | 3 | planned |
| M13 | Migrate on touch via bridge + mock-legacy | 3 | planned |
| M14 | Synthetic twin rehearsal | 3 | planned |
| M15 | Read-through bill history | 3 | planned |
| M16 | Exit kit (export direction) | 3 | planned |
| M17 | Identity re-link on first login | 3 | planned |
| M18 | Suite #130, docs/migration-kit.md, manual chapter, README, capability map | all | planned |
| M19 | Deploy to the demo box after the demo; re-seed Taranga unchanged | ops | after demo |

Not in scope: no-freeze dual-write via change data capture; verified
vendor mappings (need an operator's data dictionary); migrating rated
usage history.
