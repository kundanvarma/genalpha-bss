# P1 hardening — plan

*2026-07-21. P0 made scale-out safe; P1 makes it fast and observable, and
proves it. Same rule as before: build what a laptop can prove, write down
honestly what it cannot.*

## 1. The billing run learns to be interrupted

**The problem.** `BillingRunService.run()` is one `@Transactional` sweep:
the whole book of business in memory, every bill in a single transaction.
One failure rolls back everything; at telco scale the transaction would
never commit at all. This was named in the production-readiness assessment
as the second blocker after tick locks.

**The fix — partition by account, resume by construction.**
- Each account's bill commits in its OWN transaction (`REQUIRES_NEW` per
  account). A failure on one account is recorded and skipped — the other
  bills stand.
- The existing idempotency check (`existsByTenantIdAndOwnerPartyIdAnd
  PeriodStart`) becomes the resume marker: a crashed run's re-trigger
  continues exactly where it stopped, because every billed account skips.
  The partition key is the account; the checkpoint is the bill itself.
- A `billing_run` ledger row per run (started/finished, accounts done,
  bills created, skipped, failed, status running → completed; a newer run
  marks a stale `running` row `superseded`) — so ops can SEE a run's
  progress and what a crash left behind. `GET /billingRun` lists them.
- A dev pacing knob (`bss.billing.run-account-delay-ms`, default 0) so a
  suite can hold the run open long enough to kill it mid-flight —
  the same dev-clock pattern every other suite-facing timing uses.

**The proof (suite #57).** Fifteen customers, the run paced slow, billing
RESTARTED mid-run — then the run triggered again. Every customer ends with
exactly ONE bill for the period: the bills cut before the crash survived
(per-account commits), none were cut twice (the resume marker), and the
ledger shows the superseded run beside the completed one.

## 2. Rate-limit buckets move to Redis (exact ceilings under replicas)

**The problem** (named in P0's caveat): the gateway's buckets are in-memory
per replica — N gateways means N× the ceiling, and a restart forgets every
window.

**The fix.** A `RateLimitStore` seam in the gateway: in-memory by default
(dev unchanged), Redis-backed when `REDIS_URL` is set — `INCR` +
`PEXPIRE`-on-first-knock, the TTL is the Retry-After. One `redis:7-alpine`
container joins compose. Both rings ride the same store.

**The proof.** Trip the ceiling, RESTART the gateway, and the refusal must
still stand inside the same window — an in-memory bucket would have
forgotten; shared state survives its holder, which is exactly the
multi-replica property.

## 3. A load harness and honest baselines

**The problem.** Fifty-six suites prove correctness; none measure
throughput. "Can it scale?" deserves numbers, even laptop numbers.

**The fix.** `ops/load/loadtest.js` — a plain-Node closed-loop driver
(C workers, D seconds, per-request latencies): anonymous catalog browse,
authenticated bill reads, the storefront page. Reports RPS and
p50/p95/p99. Baselines land in `docs/perf-baselines.md` with their
caveats stated (one laptop, ~30 JVMs at 224 MB heap each — these are
floor numbers, not capacity claims). Suite #57 runs a short burst and
asserts only the generous smoke SLO (catalog p95 under 1.5 s at modest
concurrency) — a regression tripwire, not a benchmark.

## 4. Alerts exist (Prometheus rules)

**The problem.** Metrics are scraped, dashboards exist, but NOTHING alerts:
a dead service or a failing outbox is a silent dashboard nobody is looking
at.

**The fix.** `infra/prometheus/alert-rules.yml`: ServiceDown (up == 0),
OutboxPublishFailures (`bss_events_failed_total` increasing),
GatewayErrorRate (5xx ratio), BillDistributionStalled. Wired via
`rule_files`; the suite asserts the rules are LOADED and evaluating
(`/api/v1/rules`). Routing alerts somewhere (Alertmanager → pager) is a
deployment concern — the runbook says so.

## Deferred, with reasons written down

- **OpenTelemetry tracing**: full-fleet `-javaagent` + collector + UI
  costs RAM the 16 GB demo VM does not have with 30 JVMs resident, and
  rebuilding every image belongs with the k8s soak. Stays on the list.
- **Live multi-replica k8s soak**: still requires stopping the compose
  stack (one laptop cannot carry both) — unchanged named trigger.

## Order of work

1. Billing run: ledger table (V21), per-account commits, knob, controller
   GET; rebuild + unit tests.
2. Gateway: RateLimitStore seam + Redis impl + compose redis; unit tests.
3. ops/load harness + baseline capture into docs/perf-baselines.md.
4. Prometheus rules + compose wiring.
5. Suite #57 (crash-resume, restart-surviving 429, smoke SLO, rules
   loaded), regressions, docs (README/book/manual/hardening.md), commit.

## Shipped

Suite #57 (`ops/e2e/p1_hardening_test.js`) green end-to-end — and its four
failed attempts on the way were the best auditors of the arc:

- **Attempt 1 found the racing runs.** The retry loop superseded a live
  run while the crashed run's orphaned thread kept walking — two runs on
  the same period, saved from double-billing by timing alone. Two fixes:
  `POST /billingRun` now takes the same row-lease the ticks take (a
  second trigger answers `{"busy": true}`; the lease HEARTBEATS per
  account via `TickGuard.extend`, so a crash frees it in ~2 minutes) and
  a unique index makes one-bill-per-account-per-period a database
  constraint, not a convention (existing data verified clean first).
- **Attempt 2 found the stale path.** A recreated billing container has
  a new address; the gateway's pooled connections heal on their own
  clock. The suite now waits for the PATH before knocking.
- **Attempt 3 was beaten by the product.** The final "restore" check
  knocked anonymously into the ip bucket the load burst had filled —
  and Redis buckets SURVIVE gateway recreation, which is the feature.
  The suite now sweeps its own rate keys.
- **Attempt 4 outlived its tokens.** Keycloak access tokens last 5
  minutes; the suite runs longer. Every long phase now mints fresh.
- Also fixed en route: an orphaned run limping to its own finish line
  could overwrite its `superseded` verdict with `completed` — history
  now stays written.

The proven results: 12 customers, billing killed mid-run, resume to
EXACTLY one bill each with the ledger showing superseded beside
completed; the smoke SLO at 1,253 req/s p95 8.9 ms (SLO 1500 ms);
a fresh gateway process refusing knock #1 with 429 out of shared Redis
buckets; three alert rules loaded and evaluating over 32 scrape targets.
Baselines in [perf-baselines.md](perf-baselines.md). `billing_cycle`
regression green against the new busy semantics.
