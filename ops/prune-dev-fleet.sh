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

# Quarantine non-persona USAGE too: unrated ('received') usage rows of junk
# parties are what the billing run grinds through on a fresh period — one
# pathological account (aff-solo-1784817883752, recorded) once hung a worker
# 15 minutes. Marking junk rows 'rated' stops them billing; nothing deleted.
# Persona usage is KEPT received — loyalty's meter derives from it.
quarantine_usage() {
  local keep="$1"
  local inlist
  inlist=$(echo "$keep" | awk '{printf "'"'"'%s'"'"'," , $0}' | sed 's/,$//')
  docker exec bss-postgres psql -U postgres -d usage -c \
    "UPDATE usage_record SET status='rated' WHERE status='received' AND (owner_party_id IS NULL OR owner_party_id='' OR owner_party_id NOT IN (${inlist}));" | tail -1
}

echo "== resolving persona ids"
KEEP_BSS=$(subs_for bss $PERSONAS_BSS)
KEEP_NOVA=$(subs_for nova $PERSONAS_NOVA)
echo "genalpha keeps $(echo "$KEEP_BSS" | wc -l | tr -d ' ') personas; nova keeps $(echo "$KEEP_NOVA" | wc -l | tr -d ' ')"
echo "== pruning genalpha"; prune_tenant genalpha "$KEEP_BSS"
echo "== pruning nova"; prune_tenant nova "$KEEP_NOVA"
echo "== quarantining non-persona usage"; quarantine_usage "$(printf '%s\n%s' "$KEEP_BSS" "$KEEP_NOVA")"
# Retire sweep-minted advisor drafts: every proof sweep ADOPTS market findings
# into 'In study' offerings, growing the catalog until suites' fixed list pages
# age out (the 2026-08-13 sweep cascade: "no plan in catalog"). Drafts are
# advisor proposals — re-mintable any time; retiring them keeps list pages young.
echo "== retiring sweep-minted 'In study' drafts"
docker exec bss-postgres psql -U postgres -d product_catalog -c \
  "UPDATE product_offering SET lifecycle_status='Retired' WHERE lifecycle_status='In study';" | tail -1
docker exec bss-postgres psql -U postgres -d product_inventory -c \
  "SELECT tenant_id, count(DISTINCT owner_party_id) AS owners, count(*) AS active FROM product WHERE status='active' GROUP BY tenant_id;"
echo "PRUNE COMPLETE — the fleet is young again"
