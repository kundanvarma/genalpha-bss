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

## The cloud run — EKS, same day

The k3s soak's bigger sibling ran hours later on real AWS
(`ops/k8s-soak/eks-run.sh`: Terraform apply → ECR push → RDS database
init → Helm install → smoke → destroy). Account bootstrapped with a $10
budget alert BEFORE anything billable existed; total session cost ≈ a
few dollars against sign-up credits.

**What the first live cloud run caught** (three more stack truths no
template could):
- **EKS module v20 grants the creator no cluster access by default** —
  the apply succeeded and `kubectl` was locked out;
  `enable_cluster_creator_admin_permissions = true` is now in the stack.
- **Architecture is part of the image contract**: Apple-Silicon-built
  images are arm64; the stack's t3.large nodes were x86 —
  ImagePullBackOff with "no match for platform". The stack now runs
  **Graviton (t4g.large, `AL2023_ARM_64_STANDARD`)** — native arm64 and
  ~30% cheaper than the t3 it replaced.
- **EKS 1.31 had aged into extended support** (6× control-plane price);
  the default is now 1.33 with a comment explaining the trap.

**What ran and held**: 22 pods Ready (core commerce + billing×2 +
storefront + admin console) on 2× t4g.large; the smoke green through a
port-forwarded gateway — token, authored offering, acknowledged order —
against **RDS** this time; `tick_lock` in RDS showing both billing ticks
leased by ONE replica id; the storefront and admin console browsable
from a laptop, served from Frankfurt; and the same stability standard:
**10 minutes, zero new restarts** across all baseline pods (one pod —
shopping-cart — deliberately added mid-window after the storefront's
add-to-cart found the scope's edge: an honest 500, not a bug).

**Torn down, verified**: `eks-run.sh down` destroyed all 57 Terraform
resources; the post-destroy sweep across eu-central-1 answered zero on
every billable axis — EKS clusters 0, EC2 instances 0, RDS instances
AND snapshots 0, NAT gateways 0, elastic IPs 0, load balancers 0, EBS
volumes 0, custom VPCs 0, ECR repositories 0 (one repo — shopping-cart,
added mid-session for the storefront's cart — survived the scripted
cleanup and was deleted by hand; the script's list now includes it).
The $10 budget alarm stays armed for free as the watchdog; Cost
Explorer the next day is the closing receipt. Total session cost: a few
dollars against sign-up credits.

Also from this session: a `terraform.tfstate` (which stores sensitive
variables in cleartext) briefly reached a public commit — remediated
within minutes by a history rewrite, an immediate RDS password rotation
and a release roll; the secret gate now refuses the file CLASS
(`*.tfstate`, `.terraform/`) outright, because a scanner that only
knows shapes misses containers. The AKS twin of this run remains on the
ledger, waiting on Azure credits.
