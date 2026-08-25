# Time compression — a simulated quarter in the sandbox clone

**Status:** T1 SHIPPED (2026-08-25) · T2 dunning + T3 usage on trigger · **Depends on:** shadow-operator clone (shipped), Tvilling base seeding (shipped)

## The research (what the code actually says)

- `BillingRunService.run()` takes **no arguments**: it bills "the current
  period" per account, segment-based, and the fleet's hardest-won invariant —
  **no day is ever billed twice** — is enforced against real wall-clock dates
  (period bookkeeping per account, proration from product `startDate`).
- Dates are woven through the money path, not injected: `OffsetDateTime.now()`
  appears in billing (segments, proration), usage (periods, rerate windows),
  dunning/AR aging, and the shadow-billing cursor. There is **no clock seam**.
- The clone gives us a place where compressed time is HONEST: the sandbox
  cannot touch the outside world, its base is fictional, and its whole purpose
  is "run the quarter before you live it".

## The design decision: a per-tenant clock seam, not parameterized runs

Two candidate shapes were considered:

1. **Parameterize every engine** (`run(asOf)`, `rate(asOf)`, …): every date
   read becomes a parameter, threading through hundreds of call sites — high
   blast radius, and production code paths grow simulation-only branches.
2. **A TenantClock seam**: one small component per service answering
   `now(tenantId)` — wall clock for everyone, `wall + offsetDays` for a
   sandbox tenant whose fleet block carries `clock-offset-days: N`
   (refresher-mutable, so advancing time is a PATCH, no restart). Engines
   swap `OffsetDateTime.now()` for `clock.now(tenant)` **only where the
   date decides money** (billing segments/proration first).

**Decision: the clock seam.** It matches the fleet-file doctrine (a tenant
property, live-mutated), it is sandbox-gated at the source (the refresher
refuses a clock offset on a non-sandbox block — production time is not a
knob), and it compresses ALL of a service's date logic at once instead of
per-endpoint parameters.

## Phases

- **T1 — billing quarter (the core)**: TenantClock in billing;
  `clock-offset-days` in the block (sandbox-only, guard in the refresher);
  onboarding gains `POST /operator/{cloneId}/advanceClock {days}` (writes the
  block) and `POST /operator/{cloneId}/simulateQuarter` = 3 × (advance 30 →
  billing run) returning the three cycles' bill totals as a report with
  assumptions on its face. Suite: 3 cycles over the twin base → each twin
  carries 3 bills, **no day billed twice** across the compressed quarter,
  source tenant untouched, wall intact throughout.
- **T2 — collections**: dunning/AR aging reads the tenant clock → overdue and
  dunning-path questions ("what does a 6-month price rise do to my aged AR")
  become clone-answerable.
- **T3 — usage**: usage periods + wholesale rerate windows on the clock →
  compressed wholesale settlement quarters for the negotiation twin.

## Honesty rules
- The clock NEVER moves for a non-sandbox tenant — enforced where the file is
  read, not where the API is called.
- Every simulated-quarter report states: "dates were compressed; recurring
  charges only — usage-dependent lines reflect the seeded meters, not a lived
  quarter" (until T3 closes that gap).
- Idempotency invariants (sourceRef, no-day-twice) must hold across
  compressed cycles — the suite proves them, not the prose.
