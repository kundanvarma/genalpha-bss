# Production hardening — the runbook

*What the P0 arc built and proved on this stack, and what a real deployment
must add that a laptop cannot prove. Companion to
[p0-hardening-plan.md](p0-hardening-plan.md); suite #56
(`ops/e2e/hardening_test.js`) is the proof of the provable half.*

## What is DONE and proven here

### One replica speaks at a time
Every mutating scheduled tick — dunning, bill distribution, campaign
journeys, churn sweeps, porting cutover, commission hardening, order
resume, telesales expiry, cart abandonment — claims a row-lease in its
service's own database before running (`tick_lock` table + `TickGuard`,
the ShedLock algorithm hand-rolled, no new dependency). Scale any of the
six services to N replicas and the ticks still fire once. A crashed
holder's lease expires on its own; nothing wedges. The 27 outbox relays
are deliberately unguarded: at-least-once delivery with eventId dedup at
every consumer is the contract — a duplicate relay is waste, never harm.

**Proven**: two billing replicas against one database, one bill cut, the
mock print house received exactly one copy.

### Rate limits, two rings
The dealer surface keeps its strict per-partner buckets. Everything else
now passes a fleet-wide ceiling — per subject for people, per client for
machines, per IP for anonymous knocks — decode-only, fairness not authz.
Dials: `GLOBAL_RATE_CAPACITY` (default 1200/window) and
`GLOBAL_RATE_WINDOW_MS` (default 60s).

**Scale-out (closed in P1)**: the buckets now live behind a
`RateLimitStore` seam — in-memory with no configuration, shared Redis
buckets when `BSS_GATEWAY_REDIS_URL` is set (the compose default). N
replicas enforce ONE exact ceiling, a restarted gateway still refuses
inside the same window (suite #57 proves the resurrection), and an
unreachable Redis fails OPEN — rate limiting is fairness, never
authorization.

### Backups with a tested restore
`ops/backup.sh` dumps every database and role; `ops/restore-drill.sh`
restores into a throwaway container and verifies databases, rows, and an
optional sentinel. **Run the drill on a schedule, not once** — a backup
that hasn't restored lately is decaying back into a hope. In production,
prefer the managed database's PITR (below) and still run the drill
against it: the drill tests the *procedure*, not just the file.

### The secret gate
`ops/scan-secrets.sh` refuses commits whose staged diff carries a
real-secret shape (Anthropic/OpenAI/AWS/GitHub/Slack keys, private-key
blocks); `ops/install-hooks.sh` wires it as pre-commit. The dev fixtures
in compose and realm files ("billing-secret", admin/admin, partner
tokens) are deliberate stand-ins — every one of them is an env seam
(`.env.example` lists them) and none may carry a real value in git.

## What a REAL deployment must add (a laptop cannot prove these)

### Secrets
- All `.env.example` names become Kubernetes Secrets — External Secrets
  Operator or the CSI driver pulling from AWS Secrets Manager / Azure
  Key Vault; the Helm chart's env-driven values take them as-is.
- Keycloak: dev realm JSONs are fixtures. Per environment, mint realms
  and client secrets with `ops/onboard-tenant.sh` against a hardened
  Keycloak (or a managed IdP) — never import the repo realms.
- The PUK vault key (AES-GCM) moves to KMS; rotate on schedule.

### TLS in transit
Edge TLS terminates at the Ingress (cert-manager + Let's Encrypt or the
cloud LB). Inside the cluster, prefer a mesh (Istio/Linkerd) for mTLS
between services — zero code change, the services already speak plain
HTTP behind the gateway. The alternative (Spring-native TLS per service)
works but costs a cert-distribution story the mesh gives you free.

### HA databases and brokers
- **Postgres**: one managed HA instance (RDS/Aurora Multi-AZ, Azure
  Flexible Server zone-redundant) — image parity matters only for
  pgvector (both clouds support it). Turn on PITR. Point every
  service's `DB_URL` at it; the per-service databases and RLS roles
  migrate themselves (Flyway runs as the owner on boot).
- **Kafka**: MSK / managed Kafka, 3 brokers, `replication.factor=3`,
  `min.insync.replicas=2`. The outbox pattern already tolerates broker
  failover — that is why it exists.
- **Keycloak**: 2+ replicas behind the LB with its own HA Postgres.

### Scale-out, in order
1. Stateless services: replicas ≥2 via the Helm values — safe because of
   the tick locks (P0's point).
2. Gateway replicas ≥2 — buckets shared in Redis since P1, ceilings stay
   exact.
3. The billing run partitions by ACCOUNT since P1: each bill commits
   alone, a crashed run resumes by construction (the bill is the
   checkpoint), and every run is a ledger row (`GET /billingRun`). One
   run speaks at a time — a concurrent trigger gets `{"busy": true}`
   (row-lease with per-account heartbeat; a crash frees it in ~2 min)
   and a unique index makes a double bill impossible at the database.
   What remains for true telco scale is running partitions CONCURRENTLY
   (worker pools over account ranges) — the per-account isolation and
   the constraint this arc built are the prerequisites.
4. Throughput has numbers now — `ops/load/loadtest.js`, baselines with
   stated caveats in [perf-baselines.md](perf-baselines.md), and suite
   #57's smoke SLO as the regression tripwire. Re-baseline on real
   hardware before capacity planning.

### Alerts (P1) and what still pages nobody
`infra/prometheus/alert-rules.yml` evaluates ServiceDown, outbox publish
failures and gateway 5xx over the whole fleet (32 scrape targets).
Prometheus FIRING is not a page: a deployment adds Alertmanager and a
route to a pager/Slack — until then alerts are visible at :9090/alerts.

### Compliance (P2) — built and proven where a laptop can
GDPR is features now, not intentions — see [privacy.md](privacy.md):
the data passport (`GET /privacy/v1/export`, the caller's own token
doing all the reading), erasure with the law's own exceptions (active
contracts refuse 409; bookkeeping categories retained WITH their basis;
profile anonymized in place; login scrubbed at the IdP; immutable
audit rows), and retention as TickGuard-guarded clocks. PCI scope is
verified-then-claimed (token vault, no PAN — SAQ-A-shaped, a QSA
attests the rest). Lawful intercept is stated as a boundary: the BSS
half (warrant-gated disclosure) is shaped, not built. Suite #58.

### Still open — the honest remainder
| | |
|---|---|
| P1.5 | live multi-replica k8s soak (needs the compose stack stopped); OpenTelemetry tracing (agent + collector cost RAM the demo VM lacks); concurrent billing partitions; Alertmanager routing |
| P2.5 | third-party penetration test; regional DR drill on real topology; Art. 30 processing register + DPIA (operator documents); per-tenant retention dials in the registry; warrant-disclosure endpoint when a national format names it |
