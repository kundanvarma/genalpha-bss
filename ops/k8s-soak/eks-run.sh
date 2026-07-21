#!/bin/bash
# THE CLOUD EKS RUN — the k3s soak's bigger sibling, on real AWS.
# Prereqs: aws credentials configured (aws sts get-caller-identity works),
# terraform + helm + kubectl installed, docker running with the fleet's
# images built. COSTS REAL MONEY while up (~$9-10/day: EKS control plane,
# 2× t3.large, NAT, RDS db.t4g.small) — run, soak, DESTROY.
#
# Usage:
#   ops/k8s-soak/eks-run.sh up       # terraform apply + ECR push + helm install
#   ops/k8s-soak/eks-run.sh smoke    # port-forward + the same smoke as k3s
#   ops/k8s-soak/eks-run.sh down     # helm uninstall + terraform destroy (ALWAYS)
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
TF="$REPO/deploy/terraform/aws"
REGION="${AWS_REGION:-eu-central-1}"
NAME="genalpha-bss"
DB_PASSWORD="${EKS_DB_PASSWORD:?set EKS_DB_PASSWORD (never committed; used for RDS + helm)}"

# the same core-commerce scope the k3s soak proved, billing at 2 replicas
IMAGES=(billing flow gateway insight knowledge mock-analytics mock-esp mock-ocs
        mock-pim mock-social party-account policy porting product-catalog
        product-inventory product-ordering product-stock storefront console
        shopping-cart)

account() { aws sts get-caller-identity --query Account --output text; }
registry() { echo "$(account).dkr.ecr.$REGION.amazonaws.com"; }

case "${1:-}" in
  up)
    echo "== terraform apply (EKS ~15-20 min) =="
    terraform -chdir="$TF" init -upgrade
    terraform -chdir="$TF" apply -auto-approve -var "db_password=$DB_PASSWORD"
    aws eks update-kubeconfig --name "$NAME" --region "$REGION"

    echo "== ECR: one repo per image, then push =="
    aws ecr get-login-password --region "$REGION" \
      | docker login --username AWS --password-stdin "$(registry)"
    for img in "${IMAGES[@]}"; do
      aws ecr describe-repositories --repository-names "bss-java-$img" --region "$REGION" \
        >/dev/null 2>&1 \
        || aws ecr create-repository --repository-name "bss-java-$img" --region "$REGION" >/dev/null
      docker tag "bss-java-$img:latest" "$(registry)/bss-java-$img:latest"
      docker push "$(registry)/bss-java-$img:latest"
    done

    echo "== databases: create the fleet's DBs on RDS =="
    RDS="$(terraform -chdir="$TF" output -raw rds_address)"
    # RDS sits in private subnets — run psql from inside the cluster
    kubectl run initdb --rm -i --restart=Never --image=postgres:16 \
      --env PGPASSWORD="$DB_PASSWORD" -- \
      psql -h "$RDS" -U postgres -d postgres \
      < "$REPO/infra/postgres/init-databases.sql" || true

    echo "== helm install (in-cluster kafka/keycloak; RDS for data) =="
    helm upgrade --install bss "$REPO/deploy/helm/genalpha-bss" \
      -f "$REPO/deploy/helm/genalpha-bss/values-soak.yaml" \
      -n bss --create-namespace \
      --set local.postgres.enabled=false \
      --set config.dbHost="$RDS" \
      --set config.dbPassword="$DB_PASSWORD" \
      --set config.dbMigrationPassword="$DB_PASSWORD" \
      --set image.prefix="$(registry)/bss-java-" \
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
    echo "== terraform destroy (leaves NOTHING billable) =="
    terraform -chdir="$TF" destroy -auto-approve -var "db_password=$DB_PASSWORD"
    for img in "${IMAGES[@]}"; do
      aws ecr delete-repository --repository-name "bss-java-$img" \
        --region "$REGION" --force >/dev/null 2>&1 || true
    done
    echo "destroyed — verify in the console that nothing lingers"
    ;;
  *)
    echo "usage: $0 up|smoke|down" >&2
    exit 2
    ;;
esac
