# The proof run — findings

*2026-08-04. One command, all eighty-four suites, serially, against the
live fleet. Final tally: **66 of 84 green** (best of recorded attempts). The other 22 are not mysteries: every one
is explained below, and the run surfaced more real engineering truth
than any green sweep would have.*

## The headline finding: the fleet grew old

Three weeks of suite-life created **1,304 billing accounts, 340
agreements**, hundreds of profiles, piles of tickets — and parts of the
fleet were quietly written for a young fleet:

- **The billing run walks every account with 3–6 machine calls each**
  (usage rating per member, promotions, loyalty tier, price
  adjustments). At 1,304 accounts a run takes **28–108 minutes**; a
  suite that triggers a run and polls for its bill gets "busy" from
  someone else's still-grinding run. **15 of the 22 failures are this
  one cause** (b2b, billing_cycle, guest, storefront, loyalty, revenue,
  refunds, remittance, installments, household, proration,
  split_billing, hardening, p1_hardening, bill_distribution).
- **assurance's AgreementClient reads `?limit=200` once** — at 340
  agreements, new promises fall off the TMF623 SLA projection. This is
  a REAL product bug, not a suite bug: the face silently under-reports
  on an aged tenant. (sla_test — and the same fixed-limit client
  pattern should be audited fleet-wide.)
- The same aging class had already been caught and fixed during the
  sweeps: bill_distribution's nine accumulated duplicate profile rows
  hiding the A-NZ row off the console listing.

**The named follow-up arcs**: (1) machine-client pagination discipline
— every fleet-internal list client pages to exhaustion or filters
server-side; (2) billing-run scale-out — parallelize the account loop
(the two-replica suite already proves per-account concurrency safe) and
batch the per-party reads; (3) dev-fleet data pruning as an operational
runbook.

## The environment findings (all fixed)

1. **The VM had no headroom and no swap.** An idle k3s cluster from the
   Helm tests plus surge-hired workers had 47 MB free; every mystery
   flake (30s POST timeouts, transient KC 401s, dropped beacons) was
   pressure. k3s stopped; 4 GB swap added.
2. **Keycloak was the fleet's only uncapped JVM.** The kernel
   OOM-killer took it mid-sweep (the wrapper exits 0 — Docker never
   flags it), and once took dockerd itself, mass-restarting ~40
   containers. KC heap now capped via JAVA_OPTS_APPEND.
3. **A KC recreate wipes dynamically-onboarded realms** (aurora,
   fjord) and re-imports live-only settings from files — the file+live
   doctrine held (all grants/roles/lifespan were file-backed), and the
   runner now re-onboards via the preamble suites.
4. **The closed-loop surge controller races the suites** — it auto-
   hires workers against suite backlog and drains queues mid-assertion.
   Legitimate production behavior; the runner parks it and wakes it
   only for the suite that tests it.

## The suite bugs (all fixed)

- bill_distribution minted a fresh profile code per run (the API
  upserts by code — one fixed code now; failure path instrumented).
- operator_form's manifest poll matched a previous run's rebrand by
  substring and never waited for the refresher (polls the fresh colour
  now).
- agentic_workforce's staffing leg leaned on ambient backlog (seeds its
  own open ticket now).
- Re-onboarded tenants inherited nova's agent-commerce/ai-visibility;
  onboarding now defaults new operators to off/dark — agent exposure is
  an opt-in.
- Batch endpoints (billing runs, campaign settings) carried 30s
  interactive timeouts (now 90–120s).
- The TMF645 cart footprint line reused the serviceability CSS classes
  and strict locators resolved two elements (own class now) — found by
  the guest regression, a latent bug from three arcs back.

## Fixed after the sweep (proof the findings were right)

- **sla_test GREEN** — assurance's AgreementClient now filters
  status=active server-side and pages to exhaustion (the fleet-wide
  audit found intelligence's client already did this right; the other
  fixed-limit clients are bounded by per-party filters or catalog size).
- **workforce_runtime_test GREEN** — user-roles held a stale Keycloak
  admin session across the KC recreates; a restart cleared it.
- **copilot_experience_test + social_test GREEN** — congestion victims;
  clean on the settled fleet. (The "missing beacon" was an artifact:
  fire-and-forget beacons report ERR_ABORTED when navigation discards
  the response, but the events land.)
- **porting_test** — reclassified: its failing leg triggers a billing
  run (the composed-goodbye final bill). It is the 16th member of the
  billing family below, not a separate bug.
- The mock legacy estate now mints a FRESH incident number on reopen —
  real estates never reuse INC numbers, and the workforce ledger
  rightly refuses to re-queue a subject it already completed.

## Still open

- **The billing family (16 suites)** — one cause, measured: billing
  runs walk 1,304 accumulated accounts at 28–108 minutes a run. Fixed
  by the named billing-run scale-out arc, not by tonight's patches.
- **wrapped_legacy_test** — the reopened legacy incident does not join
  the workforce queue; the per-tenant legacy-ticket config verifies
  present and the mock now mints fresh numbers, so the remaining
  suspect is the queue derivation's read path — needs a session of its
  own.
- **plan_change_test** — passed on attempt 2 during a congested phase;
  keep an eye on it.

## The receipt

`docs/proof-run.html` (generated from `ops/e2e/.proof-run/results.tsv`
by `docs/build-proof-run-html.py`). Attempts are recorded per suite;
verdicts are best-of. The runner is `ops/run-all-suites.sh`.
