# Production acceptance — the checklist with owners

*Companion to [hardening.md](hardening.md). That runbook says what is proven
on this stack and what a real deployment must add. This page turns the
remainder into an acceptance list: one row per control, who owns it, what
counts as evidence, and where the evidence lives. A production launch is
signed off row by row, never by the phrase "the repo is green".*

Owner codes: **REPO** — proven here, by a suite or a gate that runs on every
change · **OPERATOR** — the deploying operator's platform, credentials and
paperwork · **SHARED** — the repo ships the seam and the operator supplies the
environment-specific half.

## 1. Identity, secrets, transport

| Control | Owner | Evidence required | Where it lives / status |
|---|---|---|---|
| Every `.env.example` name is a Kubernetes Secret (ESO or CSI from AWS Secrets Manager / Azure Key Vault); no dev fixture value reaches a cluster | OPERATOR | Secret inventory reconciled against `.env.example`; `ops/scan-secrets.sh --all` green in CI | secret gate proven on every PR (`security.yml`) |
| Realms and client secrets minted per environment with `ops/onboard-tenant.sh` against a hardened IdP — never the repo realm JSON | OPERATOR | IdP change record; realm export without dev clients | `docs/tenant-onboarding-secrets.md` |
| PUK vault key in KMS with a rotation schedule | OPERATOR | KMS key policy + rotation evidence | seam: `BSS_SIM_PUK_KEY` |
| TLS at the edge; mTLS in the cluster (mesh) | OPERATOR | Ingress cert chain; mesh policy showing STRICT | chart: services speak plain HTTP behind the gateway by design |
| Trusted proxies narrowed to the ingress / LB / mesh CIDR; forwarded headers stripped at the outer edge | SHARED | `GATEWAY_TRUSTED_PROXIES` value per environment; a probe from a pod address is refused | `docs/hardening.md` §trusted proxies; default RFC1918 is demo-only |

## 2. Data stores, brokers, tenancy

| Control | Owner | Evidence required | Where it lives / status |
|---|---|---|---|
| Managed HA Postgres with PITR; migration role separate from RLS runtime roles | OPERATOR | Instance config; a PITR restore rehearsal receipt | chart already separates `dbMigrationUsername` from per-service `dbRole` |
| Row-level security holds on the live catalogue after migration and seed | REPO | `ops/security/rls_check.py` exit 0 on the release environment | runs on every PR against the smoke fleet (`browser-proof.yml`); the release environment run is the operator's receipt |
| Kafka 3 brokers, `replication.factor=3`, `min.insync.replicas=2`; outbox relay tolerates failover | SHARED | Broker config; an outbox drain after a broker restart | outbox pattern proven here; broker HA is the platform's |
| Keycloak ≥2 replicas on its own HA Postgres | OPERATOR | Deployment manifest | — |
| Backups with a tested restore for every database | SHARED | Restore drill receipt (date, dataset, time to restore) | drill scripted and proven on the laptop (`docs/hardening.md` §backups); the production drill is the operator's |
| SigScale OCS (when chosen) on durable storage with a stable node name | REPO | `helm template` shows a StatefulSet with a volume claim | chart renders it (TAR-17); a restart-and-balance-survives test on the target cluster is the operator's receipt |

## 3. Scale, capacity, resilience

| Control | Owner | Evidence required | Where it lives / status |
|---|---|---|---|
| Stateless services at ≥2 replicas; one replica speaks at a time for every scheduled mutator | REPO | Tick locks proven under two live replicas | `docs/hardening.md` §one replica speaks; live k3s soak 2026-07-21 |
| Gateway ≥2 replicas with Redis-shared rate ceilings | REPO | Ceiling holds across replicas | proven; `hardening_test` |
| Capacity test at the operator's subscriber / order / billing concurrency | OPERATOR | `ops/load/loadtest.js` run on target hardware with the operator's numbers | baselines with caveats in `perf-baselines.md`; laptop numbers are not capacity evidence |
| Concurrent billing partitions when the base needs them | REPO (backlog) | Decision recorded against measured run time at target base size | prerequisites built (per-account isolation, unique bill constraint); concurrency is a capacity gate, not a defect |
| Regional DR drill on the real topology | OPERATOR | Drill receipt with RTO/RPO met | not provable on a laptop |

## 4. Operations

| Control | Owner | Evidence required | Where it lives / status |
|---|---|---|---|
| Alerts route to humans (Alertmanager → pager / Slack) | OPERATOR | A test alert received by the on-call route | rules exist (`infra/prometheus/alert-rules.yml`); FIRING pages nobody until routed |
| Distributed tracing (OpenTelemetry) end to end | SHARED | A trace spanning gateway → service → outbox → consumer | backlog; metrics exist, traces do not |
| SLOs written down and measured | OPERATOR | SLO document with the smoke SLO (`#57`) as the regression tripwire | — |
| Immutable release artifacts: per-commit tags, digests, SBOM, signature, provenance | REPO | `cosign verify` and `gh attestation verify` succeed for every image of the release SHA | every image is published to GHCR under its source SHA, signed keyless with cosign and attested with build provenance on every push to main (`ci.yml`, job *Every service image builds*); the SBOM rides the image as a CycloneDX attestation; the digest list is a 400-day artifact |
| Runtime JDK exercised by automated proof | REPO | PR smoke boots the Java 25 images | `browser-proof.yml`; claims gate binds the README to the Dockerfiles |

## 5. Security and compliance assurance

| Control | Owner | Evidence required | Where it lives / status |
|---|---|---|---|
| Independent penetration test with verified remediation — tenant hopping, IDOR, agent/MCP authorization, XSS, SSRF through provider seams, webhook signatures, payment state transitions | OPERATOR | Test report + retest closure | the review's explicit precondition for a live operator |
| Art. 30 processing register and DPIA | OPERATOR | Signed documents | the product's data passport and eraser are built (`privacy.md`, suite `#58`); the register is the operator's |
| Per-tenant retention dials configured for the operator's law | SHARED | `tenants.yml` retention keys per tenant, reviewed | dials exist; values are the operator's |
| PCI-DSS attestation at the reduced (PAN-free) tier | OPERATOR | SAQ / AoC | PAN-free by construction (`docs/hardening.md` §PCI) |
| Agent commerce off unless explicitly enabled per tenant; raw-model exposure off | REPO | claims gate: secure defaults off | proven on every PR (`ops/arch/claims.sh`) |

## How to use this page

Copy the four tables into the launch ticket. Every OPERATOR row needs a
document or a receipt attached; every REPO row is satisfied by pointing at
the CI run of the release SHA; every SHARED row needs both. A row with no
evidence is not "probably fine" — it is open, and the launch waits.

## Honest limits

- This is a checklist, not a certification. The repo cannot prove an
  operator's platform; it can only make the seams explicit and keep its own
  half green on every change.
- One REPO row is still open at the time of writing: distributed tracing.
- Publishing runs only on a push to `main`; a pull request builds and scans
  but cannot sign (no OIDC token), so the first proof of the publish steps is
  the first `main` run after they land — verify it with the two commands
  above before quoting this row.
