#!/bin/bash
# THE CLOUD AKS RUN — the EKS run's Azure twin, carrying its lessons in
# from the start (ARM nodes, registry in the substrate, databases as
# resources, no version pins that age into paid support).
# Prereqs: az login done, terraform + helm + kubectl + docker with the
# fleet's images built. Costs sponsorship credits while up — run, soak,
# DESTROY.
#
# Usage:
#   ops/k8s-soak/aks-run.sh up       # terraform apply + ACR push + helm install
#   ops/k8s-soak/aks-run.sh smoke    # port-forward + the same smoke as k3s/EKS
#   ops/k8s-soak/aks-run.sh down     # helm uninstall + terraform destroy (ALWAYS)
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
TF="$REPO/deploy/terraform/azure"
NAME="genalpha-bss"
DB_USER="bssadmin"
DB_PASSWORD="${AKS_DB_PASSWORD:?set AKS_DB_PASSWORD (never committed)}"

# same scope as the EKS run: core commerce + billing x2 + the consoles + cart
IMAGES=(billing flow gateway insight knowledge mock-analytics mock-esp mock-ocs
        mock-pim mock-social party-account policy porting product-catalog
        product-inventory product-ordering product-stock storefront console
        shopping-cart)

case "${1:-}" in
  up)
    echo "== terraform apply (AKS ~5-10 min) =="
    terraform -chdir="$TF" init -upgrade
    terraform -chdir="$TF" apply -auto-approve -var "db_password=$DB_PASSWORD"
    RG="$(terraform -chdir="$TF" output -raw resource_group)"
    az aks get-credentials --resource-group "$RG" --name "$NAME" --overwrite-existing

    echo "== ACR: cross-build amd64 and push (this subscription's VM catalog =="
    echo "== has no ARM sizes; the jars are arch-independent, so this is cheap) =="
    ACR="$(terraform -chdir="$TF" output -raw registry_login_server)"
    az acr login --name "${ACR%%.*}"
    for img in "${IMAGES[@]}"; do
      dockerfile=""
      case "$img" in
        mock-*)     ctx="$REPO/integrations/$img" ;;
        # vite/esbuild is unstable under QEMU: the bundle is built on the
        # HOST (npm run build) and Dockerfile.prebuilt just packages dist/
        storefront) ctx="$REPO/apps/storefront"; dockerfile="$ctx/Dockerfile.prebuilt" ;;
        console)    ctx="$REPO/apps/admin-console" ;;
        *)          ctx="$REPO/services/$img" ;;
      esac
      # one retry: a transient push failure must not kill a 20-image run
      docker buildx build --platform linux/amd64 ${dockerfile:+-f "$dockerfile"} \
        -t "$ACR/bss-java-${img}:latest" --push "$ctx" \
        || { echo "retrying $img after transient push failure"; sleep 10;
             docker buildx build --platform linux/amd64 ${dockerfile:+-f "$dockerfile"} \
               -t "$ACR/bss-java-${img}:latest" --push "$ctx"; }
    done

    echo "== helm install (in-cluster kafka/keycloak; Flexible Server for data) =="
    PGFQDN="$(terraform -chdir="$TF" output -raw postgres_fqdn)"
    helm upgrade --install bss "$REPO/deploy/helm/genalpha-bss" \
      -f "$REPO/deploy/helm/genalpha-bss/values-soak.yaml" \
      -n bss --create-namespace \
      --set services.storefront.enabled=true \
      --set services.console.enabled=true \
      --set services.shopping-cart.enabled=true \
      --set local.postgres.enabled=false \
      --set config.dbHost="$PGFQDN" \
      --set config.dbUsername="$DB_USER" \
      --set config.dbPassword="$DB_PASSWORD" \
      --set config.dbMigrationUsername="$DB_USER" \
      --set config.dbMigrationPassword="$DB_PASSWORD" \
      --set image.prefix="$ACR/bss-java-" \
      --set image.pullPolicy=IfNotPresent
    echo "watch: kubectl -n bss get pods -w"
    ;;
  smoke)
    kubectl -n bss port-forward svc/gateway 8080:8080 >/dev/null 2>&1 &
    kubectl -n bss port-forward svc/keycloak 8085:8080 >/dev/null 2>&1 &
    sleep 5
    NODE_PATH="$REPO/ops/e2e/node_modules" node "$REPO/ops/k8s-soak/smoke.js"
    ;;
  down)
    helm uninstall bss -n bss 2>/dev/null || true
    echo "== terraform destroy (the resource group takes everything with it) =="
    terraform -chdir="$TF" destroy -auto-approve -var "db_password=$DB_PASSWORD"
    echo "destroyed — az group list should not show ${NAME}-rg"
    ;;
  *)
    echo "usage: $0 up|smoke|down" >&2
    exit 2
    ;;
esac
