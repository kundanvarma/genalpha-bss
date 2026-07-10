#!/bin/sh
# The Helm chart carries copies of the realm and DB-init files (Helm cannot
# read outside the chart). Run this after editing the originals in infra/.
set -e
cd "$(dirname "$0")/.."
cp infra/keycloak/bss-realm.json deploy/helm/genalpha-bss/files/bss-realm.json
cp infra/postgres/init-databases.sql deploy/helm/genalpha-bss/files/init-databases.sql
echo "helm chart files synced from infra/"
