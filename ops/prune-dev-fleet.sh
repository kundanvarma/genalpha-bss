#!/usr/bin/env bash
# THE PRUNING RUNBOOK: a dev fleet ages — every suite run leaves customers,
# subscriptions, agreements. Retiring the detritus restores every
# aged-fleet assumption at once (billing-run cost, list-client pages,
# console listings). This retires ACTIVE subscriptions owned by anyone who
# is not a named demo persona; nothing is deleted — bills, orders and
# history remain, the products just stop billing. Reversible per product.
set -eu
cd "$(dirname "$0")/.."
export PATH=/opt/homebrew/bin:$PATH

PERSONAS_BSS="kai@bss.local paula@family.example wilma@family.example sonny@family.example emil@acme.example bianca@acme.example alice@family.example demo pat@bss.local jo@bss.local agent-anna"
PERSONAS_NOVA="nils@nova.local norah@nova.local birgit@fjellheim.no demo"

subs_for() {
  local realm="$1"; shift
  local admin
  admin=$(curl -s -X POST "http://127.0.0.1:8085/realms/master/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d 'grant_type=password&client_id=admin-cli&username=admin&password=admin' \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
  for u in "$@"; do
    curl -s "http://127.0.0.1:8085/admin/realms/${realm}/users?username=${u}&exact=true" \
      -H "Authorization: Bearer $admin" \
      | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'] if d else '')"
  done | grep -v '^$'
}

prune_tenant() {
  local tenant="$1" keep="$2"
  local inlist
  inlist=$(echo "$keep" | awk '{printf "'"'"'%s'"'"'," , $0}' | sed 's/,$//')
  docker exec bss-postgres psql -U postgres -d product_inventory -c \
    "UPDATE product SET status='cancelled' WHERE tenant_id='${tenant}' AND status='active' AND owner_party_id NOT IN (${inlist});" | tail -1
}

echo "== resolving persona ids"
KEEP_BSS=$(subs_for bss $PERSONAS_BSS)
KEEP_NOVA=$(subs_for nova $PERSONAS_NOVA)
echo "genalpha keeps $(echo "$KEEP_BSS" | wc -l | tr -d ' ') personas; nova keeps $(echo "$KEEP_NOVA" | wc -l | tr -d ' ')"
echo "== pruning genalpha"; prune_tenant genalpha "$KEEP_BSS"
echo "== pruning nova"; prune_tenant nova "$KEEP_NOVA"
docker exec bss-postgres psql -U postgres -d product_inventory -c \
  "SELECT tenant_id, count(DISTINCT owner_party_id) AS owners, count(*) AS active FROM product WHERE status='active' GROUP BY tenant_id;"
echo "PRUNE COMPLETE — the fleet is young again"
