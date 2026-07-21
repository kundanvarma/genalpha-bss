# Performance baselines

*First captured 2026-07-21 with `ops/load/loadtest.js` (plain-Node
closed-loop driver, 10 workers, 15 s per scenario, keep-alive HTTP through
the gateway). Re-capture with:*

```bash
GLOBAL_RATE_CAPACITY=1000000 docker compose up -d gateway   # lift the wide ring
node ops/load/loadtest.js --seconds 15 --workers 10
docker compose up -d gateway                                # restore the dial
```

## The honest caveat, first

These numbers come from **one laptop** (Apple Silicon, Colima VM) running
the ENTIRE fleet — ~30 JVMs each capped at 224 MB heap, Postgres, Kafka,
Keycloak, Redis and every mock — while the load driver competed for the
same cores. They are **floor numbers on shared hardware**, not capacity
claims. On real infrastructure (one service per node-slice, an unshared
database, warm JIT) every figure below should improve substantially.
What the baselines are FOR is regression: if a future change halves one
of these, suite #57's smoke SLO trips and the change explains itself.

## Baseline (2026-07-21, 10 workers, 15 s)

| Scenario | What it exercises | req/s | p50 | p95 | p99 | errors |
|---|---|---|---|---|---|---|
| catalog | anonymous browse: gateway → catalog → Postgres | 678.6 | 8.4 ms | 38.9 ms | 110.8 ms | 0 |
| shop | storefront page: gateway → static channel | 5,861.7 | 1.4 ms | 3.4 ms | 5.9 ms | 0 |
| bills | authenticated read: gateway → JWT validation → billing → RLS-scoped Postgres | 451.0 | 16.0 ms | 52.4 ms | 97.7 ms | 0 |

Roughly 100,000 requests answered across the three scenarios with zero
errors, through the full security stack (the bills scenario pays for JWT
signature validation and row-level security on every request — that is
the honest price of the tenancy model, ~8 ms of p50 over anonymous).

## What is deliberately NOT here yet

- **Write-path throughput** (orders/sec, bills-cut/sec): order placement
  fans out through ordering → SOM → inventory → events; a meaningful
  number needs isolated hardware or it measures laptop contention, not
  the system. The billing run's own pace is visible per-run in its
  ledger (`GET /billingRun` — accounts, bills, duration).
- **Usage/CDR ingestion rates**: rating at telco volume lives behind the
  OCS seam by design; load-testing the mock would flatter nobody.

## The smoke SLO

Suite #57 runs a short burst (5 workers, 8 s) and asserts only
`catalog p95 < 1500 ms` — ten × headroom over baseline, a tripwire for
"something broke", never a benchmark.
