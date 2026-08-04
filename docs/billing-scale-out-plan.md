# Billing-run scale-out — the run that outgrew its loop — plan

*2026-08-04. The proof run's principal finding, measured: the billing
run walks 1,304 accumulated accounts strictly serially at 3–6 machine
calls each (usage rating per member, promotions, loyalty tier, price
adjustments) — 28 to 108 minutes per run. Sixteen suites fail on
"busy" from someone else's still-grinding run. The fix was named by
the fleet's own history: suite #56 already runs billing as TWO
REPLICAS against the same account list and proves exactly-one-bill —
per-account concurrency has been safe by design since the P0
hardening. The run just never used that safety inside itself.*

## The design

Parallelize the account loop with a bounded worker pool:

- Each account already bills in its OWN REQUIRES_NEW transaction with
  isolated failure handling (one account down, the run walks on) and
  the bill itself as the idempotency checkpoint — the loop body moves
  onto worker threads unchanged.
- **The two real traps, handled**: the lazy price/unit caches become
  ConcurrentHashMaps (they were method-local HashMaps), and every
  worker task binds the RLS tenant via try-with-resources
  `TenantContext.actAs(tenantId)` — the same pattern the SLA listener
  uses — so per-connection `app.tenant_id` holds on pool threads.
- The coordinator thread awaits the pool in short slices, extending
  the TickGuard heartbeat and writing throttled progress from atomic
  counters — the ledger row stays live, crash-resume semantics
  untouched (a killed run's survivors are re-skipped by the bill
  checkpoint exactly as before).
- Concurrency is a dial: `bss.billing.run-concurrency` (default 8,
  env `BILLING_RUN_CONCURRENCY`); 1 restores the serial loop.

Expected: ~8–12× on an I/O-bound loop — the 28–108 minute run returns
to suite-patience territory (~2–5 minutes at today's 1,304 accounts).

## The proof

**Suite #85 `billing_scale_test.js`**: trigger a full run on the aged
fleet and assert it COMPLETES within 300 seconds (against a measured
28-minute floor before), with the ledger row carrying its counts; then
the real proof — the sixteen billing-family suites from the proof run,
re-run serially. Regressions: #56 (two replicas, exactly-one-bill) and
#57 (kill mid-run, resume) — the crash-resume guarantees must survive
the pool.

## Shipped

**2026-08-05 (overnight), suite #85 green — and an honest boundary.**
The pool landed as designed: 1,243 accounts skip-checked in 2.3
seconds (from a measured 28–108 minutes), the ledger row, heartbeat
and exactly-one-bill checkpoint all proven to survive the worker
threads, concurrency as a dial. Two more layers of the disease fell
out during the payoff runs and were fixed the same night:

- **The 400ms dev pacing knob was baked into the running container**
  — 1,304 × 400ms ≈ 8.7 minutes of pure sleep per serial run, a third
  of the original disease; the parallel path no longer honors it.
- **Billing's machine clients had no read timeouts** — one aged
  account's pathological usage rating hung a worker fifteen minutes;
  a RestClientCustomizer now bounds every call at 60s so a poisoned
  account fails ALONE and the run walks on. (The pathological rating
  itself — party aff-solo-1784817883752 — is recorded for the usage
  service and the pruning runbook.)

**The boundary, stated plainly**: runs that hit the bill checkpoint
(the common case — suites re-billing the current period) are now
seconds. Runs over a FRESH period bypass the checkpoint entirely and
grind the full rating chain for all 1,304 aged accounts to
mostly-EMPTY outcomes — the pool cuts that 4–8× but minutes is still
beyond a suite's 30-second patience, so the billing-family suites
remain starved on the aged fleet. The next honest cut is not more
concurrency: it is BATCHING (a bulk usage-rating call instead of
1,304 singles) or the dev-fleet pruning runbook (1,304 accounts of
suite detritus back to ~50 real personas — which fixes every
aged-fleet finding at once). Named, scoped, next.
