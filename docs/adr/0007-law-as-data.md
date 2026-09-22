# 0007 — Law as data: statutory packs a tenant cannot undercut

**Status:** accepted, 2026 (collections ladder, usage caps and migration notice shipped 2026-08; recorded as an ADR 2026-09-22)

## Context

Dunning fees, reminder counts, notice periods, roaming spend caps and
penalty-free exit rights are set by statute per country, and a tenant's
commercial policy sits on top of them. If the law's numbers are ordinary
form fields, a tenant can misconfigure itself into an unlawful ladder; if
they are hard-coded, a second country is a code change.

## Decision

- Country rules ship as **statutory packs**: data keyed by country, loaded
  per tenant from the countries it serves (`tenants.yml` served-countries).
- A per-tenant policy (`dunningPolicy`, usage thresholds, migration
  `noticeDays`) is validated **against the pack at write time**. A policy
  that undercuts the floor is refused with a 4xx naming the rule, not
  warned about.
- Examples pinned today: the Norway collections pack (14-day reminder-fee
  gate, fee cap, two fee-bearing reminders, one-month enforcement-notice
  clock, minimum actionable floor); no recurring charges during a nonpayment
  suspension; the EEA roaming financial limit (≈€50 ex-VAT, warn at 80 %,
  cut at 100 %); the content-services cap floor and mandatory categories for
  minors; 30 days' notice before a detrimental migration, with penalty-free
  exit in-binding.
- In the console the statutory floor renders **read-only**; the law's
  numbers are never form fields.
- Zero-rating per app is absent on purpose (unlawful in the EEA); free-data
  promotions are application-agnostic meters.
- Norway is the built-in reference geography; other countries follow the
  same adapter-seam doctrine (ADR 0006).

## Consequences

- Costs: a pack must be researched and written per country before a tenant
  there can go live; a lawful-but-unusual tenant policy needs a pack change,
  not a console edit; the packs carry legal interpretations that a real
  engagement must have counsel confirm.
- Buys: an operator cannot configure itself into an unlawful ladder; a
  regulator's question has one place to look; a second market is data.

## Enforced by

`collections_test.js` (the pack refuses an undercutting policy; MRC stops
during suspension; emergency calls stay reachable), `usage_policy_test.js`,
`base_migration_test.js` (notice gate is a 409, not advice).

## Related

`docs/architecture.md` "Collections is orchestration ... shaped by statute",
"Usage policy", "Base migration"; `docs/collections-dunning-plan.md`,
`docs/usage-policy-plan.md`, `docs/base-migration-plan.md`,
`docs/markets/`.
