# 0019 — Hosted demo: the same Compose fleet on one box, Caddy TLS, one hostname per tenant

**Status:** accepted, 2026-09-06 (install kit and the vendor's own demo tenant)

## Context

Prospects and partners need to click through the system without a laptop
in the room. The Helm chart has run on EKS and AKS, but a managed cluster
for a demo is cost and ceremony, and a demo fleet that differs from the
laptop fleet is a second thing to debug. The demo must be rebuildable from
the repo and must not be precious.

## Decision

- The hosted demo is **the same `fleet.sh demo` slice** the laptop runs,
  as Docker Compose on **one EC2 instance** (`r6i.2xlarge`, Ubuntu, 120 GB),
  installed by one script (`ops/cloud/aws-demo/install.sh`).
- **Caddy** in front with automatic HTTPS; the security group opens only
  22 (own IPs), 80 and 443 — Compose publishes many ports, the group is the
  fence.
- **One hostname per tenant** (`shop.`, `csr.`, `console.`, `biz.` under
  the tenant's domain; `*.taranga.no` A-record to the Elastic IP), so the
  gateway's hostname-to-tenant rule (ADR 0003) works unchanged; the PSP
  mocks' approve pages get public hosts so redirect flows return.
- The vendor's own demo tenant (Taranga, EN/NOK, Norway rails) is the
  default face; other demo tenants are added by seed, not by build.
- **Deploy only on request.** The box is not a CI target: a build that is
  green on the laptop stays there until someone asks for it on the box,
  and never during a demo window (plans carry "no deploy to the box before
  the demo").
- Nothing on the box is precious: rebuild from the repo in about 40
  minutes rather than repair.

## Consequences

- Costs: one always-on instance (a nightly stop is not yet set); a box that
  lags the laptop by arcs, which each memory note must track ("not on the
  box yet"); the memory wall of one host decides which components are in
  the demo slice.
- Buys: what a prospect clicks is what the suites prove; no cluster to
  operate for a demo; a broken box is a 40-minute script, not an incident.

## Enforced by

`ops/cloud/aws-demo/install.sh` and `gen-caddyfile.sh` are the only way
the box is built; the deploy list in each arc's notes; review.

## Related

`ops/cloud/aws-demo/README.md`, `docs/demo-script.md`,
`docs/migration-plan.md` "Demo-safety rules", `README.md` Quickstart
(Taranga row).
