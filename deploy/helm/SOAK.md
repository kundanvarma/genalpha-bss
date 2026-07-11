# Live k8s soak — running the chart on real Kubernetes

`helm template` proves the chart renders; this proves it *runs*. Verified on
k3s (Colima `docker+k3s`, 16 GB VM) with the **core-commerce slice** — the full
32-service stack won't fit next to a k3s control plane in 16 GB, and the slice
also exercises the composability model (`enabled: false`).

`values-soak.yaml` disables everything outside core, leaving gateway +
product-catalog / -ordering / -inventory + party-account, plus the chart's
postgres / kafka / keycloak.

```bash
# 0. one VM can't hold compose AND k3s — stop compose first (volumes persist)
docker compose down

# 1. clean k3s (Colima docker+k3s shares one containerd at /run/containerd)
colima restart
kubectl delete ns bss --ignore-not-found

# 2. load the core images into containerd's k8s.io namespace (k3s ≠ dockerd store)
mkdir -p ~/k8s-soak
for s in gateway product-catalog product-ordering product-inventory party-account; do
  docker save bss-java-$s:latest -o ~/k8s-soak/$s.tar
  colima ssh -- sudo ctr -a /run/containerd/containerd.sock -n k8s.io images import ~/k8s-soak/$s.tar
done

# 3. deploy the core slice
kubectl create namespace bss
helm install genalpha-bss deploy/helm/genalpha-bss -f deploy/helm/genalpha-bss/values-soak.yaml -n bss

# 4. smoke: gateway health, realm import, token, catalog CRUD, in-cluster DNS
kubectl port-forward -n bss svc/gateway 18080:8080 &
kubectl port-forward -n bss svc/keycloak 18085:8080 &
# GET /actuator/health -> 200 ; realm bss -> 200 ; demo token mints ;
# catalog GET/POST through the gateway -> 200/201 ; ordering pod reaches
# http://product-catalog:8080 in-cluster (service-to-service DNS).

# 5. tear down, stop k3s, bring compose back
helm uninstall genalpha-bss -n bss && kubectl delete ns bss
colima ssh -- sudo /usr/local/bin/k3s-killall.sh   # k3s and compose can't share the VM
docker compose up -d
```

**Result (2026-07-11):** all 8 pods Ready; Keycloak imported both realms; OIDC
tokens validated across services; the gateway routed to services; Flyway
migrated a fresh cluster Postgres and catalog CRUD worked end to end; the
ordering pod resolved and reached product-catalog over cluster DNS. Placing an
order returns 502 in the core-only slice **by design** — the order path needs
product-stock, which is `enabled: false` here (NXDOMAIN), demonstrating the
dependency model. Add stock/payment to the slice for a green order path.
