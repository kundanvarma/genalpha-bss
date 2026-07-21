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

**Caveat for scale-out**: the buckets are in-memory per gateway replica.
N gateway replicas ⇒ the effective ceiling is N× the dial. That is still
a real DoS net (each replica defends itself), but for an exact global
ceiling move the buckets to Redis (Spring Cloud Gateway's
`RequestRateLimiter` + Redis is the drop-in) — a P1 item.

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
1. Stateless services: replicas ≥2 via the Helm values — safe NOW
   because of the tick locks (this arc's point).
2. Gateway replicas ≥2 (see the Redis caveat above).
3. The billing run: still a single sweep per tick — fine to ~10⁵
   customers, then partition by customer range (P1, designed not built).
4. Load-test before believing any of it: the 56 suites prove
   correctness, none of them prove throughput (P1).

### Still P1/P2 — do not mistake P0-done for production-done
| | |
|---|---|
| P1 | load tests + baselines; billing-run partitioning; live multi-replica k8s soak; Redis rate-limit buckets; alerting/SLOs; OpenTelemetry tracing |
| P2 | GDPR erasure + export flows; retention as policy rules; PCI assessment; DR drills across regions; penetration test; lawful-intercept interfaces |
