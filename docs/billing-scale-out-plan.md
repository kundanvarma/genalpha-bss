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

*(recorded when it lands)*
