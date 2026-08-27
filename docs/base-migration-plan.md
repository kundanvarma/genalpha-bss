# Base migration — moving the installed base without breaking the law or the bills — plan

*2026-08-27. The price-rise rehearsal service models revenue, margin,
churn, and even drafts the customer letter — then executes nothing.
There is no engine to sunset a legacy plan, reprice a cohort, or
auto-migrate an age-banded or promo plan when its condition lapses.
Operators do this today with spreadsheets and one-off batch scripts;
no TMF standard covers it; the law prescribes its shape precisely.
This arc turns the simulator into the front half of an
execute-something pipeline.*

## Research findings

- **The legal shape is crisp** (EECC Art. 105; Norwegian ekomloven
  2024): any contract change needs **written notice at least one
  month** before effect, explaining the change and stating the right
  to cancel; a change **not exclusively to the subscriber's benefit**
  grants a **penalty-free exit** — explicitly including customers
  still in binding. Consumer binding is capped at 12 months in
  Norway (24 under EECC generally). The UK regulator has banned
  inflation-linked mid-contract rises in new contracts — treat
  index clauses as a per-jurisdiction policy flag, never a default.
  Promo plans whose roll-off was disclosed at sale trigger no new
  exit right — but a courtesy heads-up is universal practice.
- **Industry practice = waves + mapping + opt-out.** The T-Mobile US
  legacy migrations (~8M subscribers) staged by billing cycle with a
  published source→target map and an opt-out window. The cautionary
  tale is Vodafone UK's £4.6M fine: the killers were billing-state
  edge cases and complaint-handling capacity, not the plan math.
  Standard mitigations: simulate first, pilot cohort, monitor
  complaints and billing exceptions, keep a rollback.
- **Age-banded plans have two honest strategies**, both live in this
  market: auto-adjust on birthday (an age-gated discount that
  attaches/detaches — the detach is a detrimental change, so the
  notice regime applies) or **lapse-to-grandfather** (eligibility
  checked at sale only, customer keeps the plan until they act).
  Both must be expressible.
- **TMF fit**: there is no bulk-order API. The idiomatic shape is a
  migration-campaign resource of our own that emits **one TMF622
  `modify` order per subscriber** — which reuses the existing
  proration-safe plan-change path untouched. Source→target mapping
  hangs off TMF620 offering ids; retiring the source offering
  (catalog lifecycle) is step one of any sunset. Events follow the
  house envelope on the bus.
- **Repo recon**: everything but the engine exists — the simulation +
  rehearsal services (revenue/margin/churn + drafted letter), per-order
  plan change with proration, shadow billing (a per-wave
  reconciliation oracle: expected vs billed delta), party birth
  dates, promotions with duration months, and the growth/journey
  engine for notice delivery.

## Design

### The plan and the matrix (new `base-migration` service)

`migration_plan` — per tenant: matrix rows
`{sourceOfferingId → targetOfferingId, characteristic carry-over,
deltaClass: beneficial | neutral | detrimental}`; eligibility filters
(in-binding treatment: `defer-to-expiry | exclude |
migrate-with-free-exit`, segment excludes, explicit grandfather
list); trigger `bulk | age-threshold | promo-expiry`; jurisdiction
pack (`noticeDays` = 30 for NO/EEA, exit-right applicability by
deltaClass, notice content requirements). Plan states
`draft → simulated → armed → running → done | paused`.

**The rehearsal gate is hard**: a plan cannot arm without an attached
simulation run from the pricing simulator (revenue/margin/churn +
letter draft). Simulate → pilot → scale is codified, not advised.

### Per-customer execution

State machine `scheduled → noticed → exit-window → order-emitted →
migrated | exited | failed | rolled-back`. The engine enforces
`orderDate ≥ noticeSentAt + noticeDays` as a gate, not a convention.
Notice delivery rides the growth/journey engine off
`CustomerMigrationScheduledEvent` (with a save-offer hook in the
journey — the notice moment is also a retention moment); the exit
choice lands back as an event and parks the customer as `exited`
with the penalty-free flag set for downstream billing. Each
migration stores a pre-migration snapshot; rollback emits the
inverse modify order.

### Waves and the circuit breaker

Cohorts default to billing-cycle grouping (natural stagger), plus
`maxOrdersPerDay` and an error-rate circuit breaker that pauses the
wave when order failures or billing exceptions cross a threshold.
Per-wave reconciliation report: expected delta (from the simulation)
vs billed delta (from shadow billing) — the Vodafone lesson made
executable.

### Rule triggers

- **Age threshold**: a birthday scheduler over party birth dates;
  per-rule strategy `auto-migrate` (notice queued at
  birthday − noticeDays) or `lapse-to-grandfather` (flag only).
- **Promo expiry**: promotions with duration roll off to the mapped
  target with a courtesy notice (no exit right — disclosed at sale;
  the notice template says so honestly).

### Faces

Console migration desk: plan builder over the catalog (pick source
offerings, map targets, see affected-base counts live), simulation
attach + arm button (disabled until simulated), wave monitor with
failure drill-down, reconciliation view. Selfcare: the notice renders
the change, the effective date, and — when applicable — the one-click
penalty-free exit, as the law requires.

### Events

`MigrationPlanArmedEvent`, `MigrationWaveStarted/Paused`,
`CustomerMigrationScheduled/Noticed/Completed/Failed/RolledBack`,
`MigrationExitExercisedEvent` — wired to insight traits, campaign
topics, and bridge mappings per the standing rule (an exercised exit
is churn ground truth; a completed migration updates plan traits).

### Proof

`base_migration_test.js`: sunset a legacy plan — out-of-binding
cohort noticed, gate holds until noticeDays pass (test clock),
orders emitted, billing reflects the new plan; in-binding customer
deferred to expiry; detrimental delta exposes the exit and an
exercised exit terminates penalty-free; failed order trips the
breaker; rollback restores the snapshot plan. Age rule migrates on
birthday under `auto-migrate` and only flags under grandfather.

## Phases

- **P1** — plan + matrix + per-customer modify orders, manual cohort,
  events.
- **P2** — notice gating via journeys, exit-right handling,
  jurisdiction pack.
- **P3** — age + promo-expiry triggers, grandfather strategy.
- **P4** — wave scheduler, circuit breaker, shadow-billing
  reconciliation, console desk polish.

Docs upkeep on ship: manual + architecture + README per house rule.
