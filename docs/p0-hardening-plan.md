# P0 production hardening — plan

*2026-07-20. The production-readiness assessment named the gap plainly: the
architecture is right, the operations are not. This arc closes the P0 items —
the ones that must be true before ANY production deployment — and proves what
can be proven on this stack. What can only be proven on real infrastructure
(managed HA databases, a service mesh) gets an honest runbook instead of a
pretend proof.*

## The P0 list, and what "done" means for each

### 1. One replica speaks at a time (tick locks)

**The problem.** Nine scheduled ticks mutate the world: dunning notices,
bill distribution to print/Peppol partners, campaign journey sends, churn
sweeps, porting cutovers, commission hardening, order resume, telesales offer
expiry, cart abandonment. Scale any of those services to two replicas and
every tick fires twice — two dunning letters, two paper bills, two campaign
emails. This is the #1 blocker to horizontal scaling. (The 27 outbox relays
are NOT on this list: at-least-once delivery with eventId dedup at every
consumer is the contract, and a second relay's `delete` of an already-relayed
row is a no-op — duplication there is waste, never harm.)

**The fix.** The lock is a row. A `tick_lock` table in each service's own
database and a ~70-line `TickGuard` (the ShedLock algorithm, hand-rolled in
the house style — no new dependency): claim by atomic UPDATE-then-INSERT with
a lease, release on completion, expire on crash. Claim and release run in
`REQUIRES_NEW` transactions so a unique-violation on the claim can never
poison the tick's own transaction. Six services: billing, campaign,
intelligence, porting, service-orchestration, shopping-cart.

**The proof.** Suite #56 runs billing as TWO replicas
(`docker compose run --no-deps -d billing` — same network, same database, no
compose surgery), cuts a bill for the print channel while both replicas tick
every 3 seconds, and asserts the mock print house received EXACTLY ONE copy.
The receiver counts, not the sender.

### 2. Rate limiting fleet-wide

**The problem.** Only `/dealer/v1/**` is throttled. Every other path is an
unmetered DoS surface.

**The fix.** The partner filter grows a second, fleet-wide bucket: every
request through the gateway is counted per subject (the token's `sub`), per
client (`azp`) for machines, per IP for anonymous knocks — decode-only,
fairness not authz, exactly as before. Default generous (1200/min — a ceiling
against runaways, not a tax on browsing), env-swappable
(`GLOBAL_RATE_CAPACITY`), the dealer surface keeps its strict partner bucket.

**The proof.** Suite #56 swaps the gateway to a small ceiling, bursts
anonymous requests into a 429 with `Retry-After`, shows an authenticated
caller (a different bucket) still passes, and swaps back.

### 3. Backups WITH a tested restore

**The problem.** No backup procedure, no restore drill. An untested backup
is a hope, not a backup.

**The fix.** `ops/backup.sh` — `pg_dumpall` of every database in the fleet
to `backups/` (git-ignored), pruned to the newest 14. `ops/restore-drill.sh` —
restores the newest dump into a THROWAWAY `pgvector/pg16` container, verifies
databases and row counts, optionally proves a named sentinel row survived,
and removes the container. The drill is the deliverable; the dump is a
by-product.

**The proof.** Suite #56 creates a sentinel customer through the API, runs
the backup, runs the drill, and asserts the sentinel is present in the
restored copy — a restore that has actually happened, not a script that
should work.

### 4. Secrets discipline as tooling

**The problem.** Dev fixtures (client secrets, admin/admin, partner tokens)
live in compose and realm files. Fine for a demo stack that must clone-and-run;
disqualifying if a real secret ever lands beside them. The standing manual
check (`git diff --cached | grep -c sk-ant`) is a ritual, not a control.

**The fix.** `ops/scan-secrets.sh` — scans the staged diff for real-secret
shapes (Anthropic/OpenAI/AWS/GitHub key prefixes, private-key blocks) and
refuses the commit; `ops/install-hooks.sh` wires it as a pre-commit hook.
`.env.example` documents every injectable value the compose stack accepts —
the per-tenant `${ENV:default}` seams already built are the injection points.
Production secrets never live in files; the runbook says where they do live.

### 5. The runbook for what a laptop cannot prove

`docs/hardening.md` — the honest production checklist: external secrets in
Kubernetes (External Secrets Operator / CSI, per-environment realm minting
via `ops/onboard-tenant.sh`), TLS in transit (mesh sidecar or cert-manager),
managed HA Postgres and a 3-broker Kafka (the exact values to change), the
backup/restore operation, the two rate-limit dials, the tick-lock behavior
under scale — and the P1/P2 items that remain, so nobody mistakes P0-done
for production-done.

## Order of work

1. TickGuard + `tick_lock` migration × 6 services (billing V20, campaign V14,
   intelligence V7, porting V3, SOM V21, shopping-cart V4 — versions checked
   across BOTH migration dirs), guard all 9 ticks.
2. Gateway global bucket + unit tests.
3. Ops scripts + `.env.example` + hardening runbook.
4. Rebuild, suite #56, regressions (bill distribution, dealer channel,
   billing cycle at minimum).
5. README + book + manual, commit, push.

## Shipped

Suite #56 (`ops/e2e/hardening_test.js`) passed end-to-end on its first run:

- **Two replicas, one paper bill** — a second billing replica raised with
  `docker compose run --no-deps -d billing`, both schedulers ticking every
  3 seconds against one database; the bill cut during the race arrived at the
  mock print house exactly once, and `tick_lock` showed both named leases.
- **A restore that happened** — the sentinel customer created minutes before
  the dump was found again inside the throwaway restore container
  (`restore-drill.sh --expect party_account …`).
- **The wide ring** — with the ceiling dialed to 15, anonymous knock #16 met
  `429 + Retry-After` while an authenticated caller (its own `sub` bucket)
  passed untouched; the dial returned to 1200 with one `compose up`.

TickGuard landed in six services (billing V20, campaign V14, intelligence V7,
porting V3, SOM V21, shopping-cart V4) guarding all nine mutating ticks; the
27 outbox relays stay deliberately unguarded (at-least-once + eventId dedup
is their contract). Gateway unit tests cover both rings. `docs/hardening.md`
is the runbook for what a laptop cannot prove; `.env.example` and the secret
gate (`ops/scan-secrets.sh` + pre-commit hook) close the P0 list.
