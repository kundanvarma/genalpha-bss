# Live k8s soak — plan

*2026-07-21. The oldest recorded backlog item: the Helm chart has been
template-verified since it shipped, never run against a live cluster —
because one laptop cannot carry the compose fleet and Kubernetes at once.
Today it trades: compose stops, k3s (already installed in the Colima VM,
deliberately stopped) starts, and the chart proves itself on real
Kubernetes — including the P0 claim that matters most there: two replicas
of billing against one database, ticks firing once.*

## Scope — the prepared core-commerce soak, plus the replica proof

`values-soak.yaml` (prepared earlier) disables everything outside core
commerce to fit 16 GB. On top of it this soak:

- enables **billing at `replicas: 2`** — a one-line chart addition
  (`services.<name>.replicas` overriding the global default) makes
  per-service scaling possible; billing is where the tick locks live.
- runs the smoke through a **port-forwarded gateway** with the fleet's
  own seeds, so the proof is requests served, not pods merely Ready.

## The sequence (checkpointed, reversible)

1. **Free the disk**: `docker builder prune -af` (~20 GB reclaimable on
   the 40 GB Colima data disk).
2. **Stop compose** (`docker compose stop` — containers and volumes kept;
   the fleet resumes exactly where it left off).
3. **Reset k3s state**: the previous attempt left a crashlooping deploy
   in etcd — wipe `/var/lib/rancher/k3s/server/db` for a factory-fresh
   cluster before `systemctl start k3s`.
4. **Import images**: k3s has its own containerd store; `docker save`
   the enabled `bss-java-*` images into it. Public images (pgvector,
   kafka, keycloak) pull from the network as usual.
5. **Deploy**: `helm install bss ... -f values-soak.yaml
   --set services.billing.enabled=true --set services.billing.replicas=2`.
6. **Soak**: all pods Ready and STAY Ready (no restarts over the
   window); billing 2/2.
7. **Prove with requests**: port-forward gateway:8080 + keycloak:8085,
   seed the catalog, then: token minted, offerings listed, an order
   placed and acknowledged, and `tick_lock` in the k8s postgres showing
   claimed leases — two billing replicas, one set of ticks.
8. **Receipts**: `kubectl get pods` + restart counts + the tick_lock
   rows, captured into this doc's close-out.
9. **Restore**: `helm uninstall`, stop k3s, `docker compose start`, and
   a regression suite green against the restored compose fleet — the
   soak must leave no trace.

## What this soak is NOT

Not an HA drill (one k3s node), not a load test (the laptop is the
bottleneck), not the cloud deployment (EKS/AKS Terraform remains
deploy-time). It is the missing existence proof: the chart, the images,
the seeds and the security stack running on real Kubernetes, with the
scale-out safety P0 built demonstrated live on it.

## Shipped — 2026-07-21, the chart's first live cluster

**The trade worked**: compose stopped, k3s started factory-fresh (the
crashlooped leftover wiped), and the discovery that repaid the whole
arc's setup cost — Colima's k3s runs with `--docker`, sharing Docker's
runtime, so every locally built image was already visible: the feared
image-import step does not exist on this rig.

**What the preparation caught** (the point of doing it):
- the chart's postgres was `postgres:16-alpine` — the knowledge
  service's pgvector migration would have crashlooped; fixed to
  `pgvector/pgvector:pg16` (compose parity).
- both realm files had drifted from `infra/keycloak` — `ops/sync-helm-files.sh`
  caught it, as designed.
- ordering hard-depends on product-stock — the prepared core-commerce
  scoping had excluded it; the soak's first order came back
  `502 product-stock is unreachable` and the scoping learned a fact no
  template render could have taught it.

**What ran**: 21 pods (core commerce + billing at `replicas: 2` via the
new per-service override) — all Ready after the expected init-order
restart dance (k8s has no depends_on; crash-until-postgres-answers is
the mechanism, 2 restarts each, then stable).

**What was proven with requests** (`ops/k8s-soak/smoke.js` through
port-forwarded gateway/keycloak on the fleet's own ports, so issuers
validate unchanged):
- a staff token from the in-cluster Keycloak (imported realms),
- an offering AUTHORED through the gateway (spec + price + offering into
  the k8s postgres) and served back in the listing,
- an order placed and acknowledged (the SOM is outside the soak scope —
  acknowledgement is the honest finish line),
- and the P0 claim, live on Kubernetes: **two billing replicas, one
  speaker** — `tick_lock` in the k8s postgres shows `dunning` and
  `bill-distribution` both leased by a single replica id:

```
       name        |              locked_by               | held
-------------------+--------------------------------------+------
 dunning           | a6ca988e-7c76-4c31-a495-82639503bf20 | f
 bill-distribution | a6ca988e-7c76-4c31-a495-82639503bf20 | f
```

**Stability**: `SOAK-STABLE: 10 minutes, zero new restarts across all
21 pods` — restart counts at T+10 byte-identical to the post-boot
baseline, every pod 1/1 throughout the window.

**Restored**: helm uninstall, k3s stopped, compose restarted, regression
green — the soak left no trace on the demo fleet.
