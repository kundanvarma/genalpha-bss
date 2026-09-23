#!/usr/bin/env bash
# THE THIRD OPERATOR IN AN AFTERNOON: stand up a NEW operator (an MVNO)
# on the running deployment — a realm, a tenant block, a seeded catalog.
# No image is rebuilt, and since 2026-09-23 no fleet restart either.
#
# ONE IMPLEMENTATION, TWO DOORS. This script used to clone the template
# realm itself, in inline python, beside a second copy of the same logic in
# user-roles' TenantOnboardingService. The two drifted, and the drift was a
# security hole: the script's clone kept the template's 24 literal client
# secrets, so every onboarded operator held valid machine credentials in
# every other operator's realm. The script now asks the service to do the
# work the console's operator form does — same clone, same freshly
# generated per-tenant client secret, same "no template user travels"
# rule — so there is nothing left to drift.
#
# Usage: ops/onboard-tenant.sh <id> "<Brand Name>" <locale> <currency> [#color]
set -euo pipefail
export PATH="/opt/homebrew/bin:$PATH"
ID="$1"; NAME="$2"; LOCALE="${3:-en}"; CURRENCY="${4:-EUR}"; COLOR="${5:-#B85C38}"
cd "$(dirname "$0")/.."

GATEWAY="${GATEWAY_BASE:-http://localhost:8080}"
KEYCLOAK="${KEYCLOAK_BASE:-http://localhost:8085}"
# the HOST operator mints operators; a hosted one runs only its own
HOST_REALM="${HOST_REALM:-bss}"
HOST_USER="${HOST_USER:-demo}"
HOST_PASSWORD="${HOST_PASSWORD:-demo}"

echo "== 1/3 host operator: a staff token with roles:admin from realm '$HOST_REALM'"
TOK=$(curl -sS -X POST "$KEYCLOAK/realms/$HOST_REALM/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=bss-demo&username=$HOST_USER&password=$HOST_PASSWORD" \
  | python3 -c "import json,sys;print(json.load(sys.stdin).get('access_token',''))")
[ -n "$TOK" ] || { echo "no host-operator token from realm '$HOST_REALM' — is Keycloak up?"; exit 1; }

echo "== 2/3 operator: realm with its OWN client secrets, registry block, starter catalog"
BODY=$(python3 -c "
import json, sys
print(json.dumps({'id': sys.argv[1], 'name': sys.argv[2], 'locale': sys.argv[3],
                  'currency': sys.argv[4], 'color': sys.argv[5]}))" \
  "$ID" "$NAME" "$LOCALE" "$CURRENCY" "$COLOR")
RECEIPT=$(curl -sS -X POST "$GATEWAY/onboarding/v1/operator" \
  -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" -d "$BODY")
echo "$RECEIPT" | python3 -c "
import json, sys
try:
    r = json.load(sys.stdin)
except Exception:
    print('   onboarding refused — is user-roles up on the gateway?'); sys.exit(1)
if not r.get('id'):
    print('   onboarding refused: ' + json.dumps(r)[:400]); sys.exit(1)
print(f\"   realm, registry block and starter catalog for '{r['id']}' in {r.get('seconds', '?')}s\")"

echo "== 3/3 fleet: the rest of the components adopt the newborn on their refresh tick"
# the onboarding service refreshes its own registry immediately; every other
# component re-reads the shared file within BSS_TENANTS_REFRESH_MS (15s)
for i in $(seq 1 20); do
  STAFF=$(curl -sS -X POST "$KEYCLOAK/realms/$ID/protocol/openid-connect/token" \
    -d "grant_type=password&client_id=bss-demo&username=demo&password=demo" \
    | python3 -c "import json,sys;print(json.load(sys.stdin).get('access_token',''))" || true)
  if [ -n "$STAFF" ] && curl -sf -o /dev/null -H "Authorization: Bearer $STAFF" \
      "$GATEWAY/tmf-api/productCatalogManagement/v4/productOffering?limit=1"; then
    break
  fi
  sleep 5
done
[ -n "${STAFF:-}" ] || { echo "the newborn realm never issued a staff token"; exit 1; }
echo "   '$ID' serves its own catalog through the gateway"
# The read path above only needs the tenant in the registry. The MACHINE
# path needs its SECRET too, and a re-onboard of an existing id rotates
# that secret out from under every component's cached registry entry and
# cached token. Both heal on the next refresh tick — outwait it rather
# than hand back an operator whose first order 502s.
SETTLE=$(( ${BSS_TENANTS_REFRESH_MS:-15000} / 1000 + 6 ))
echo "   waiting ${SETTLE}s for every component to re-read the registry"
sleep "$SETTLE"
echo ""
echo "OPERATOR '$NAME' ($ID) IS LIVE — realm, registry, catalog. Storefront host: shop.$ID.localhost"
echo "Its machine clients answer to a secret generated for THIS operator alone; the value"
echo "lives in this operator's infra/tenants/tenants.yml block and is printed nowhere."
