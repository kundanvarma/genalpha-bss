#!/usr/bin/env bash
# HIRE A DIGITAL WORKER — the day-1 bootstrap.
#
# Mints a login on the tenant's own IdP (TMF672) and grants the
# digital-worker badge. The badge IS the opt-in: no badge, no workforce;
# revoke it on the console (Staff tab) and the worker is unemployed within
# a token lifetime. Works for ANY MCP-speaking agent runtime — Hermes is
# the one we document end to end.
#
# Usage:
#   ./hire-worker.sh [worker-name]
# Env (defaults are the local dev stack):
#   BSS_GATEWAY_URL   http://localhost:8080
#   BSS_TOKEN_URL     http://localhost:8085/realms/bss/protocol/openid-connect/token
#   BSS_STAFF_USER    demo        (needs roles:admin)
#   BSS_STAFF_PASS    demo
set -euo pipefail

GATEWAY="${BSS_GATEWAY_URL:-http://localhost:8080}"
TOKEN_URL="${BSS_TOKEN_URL:-http://localhost:8085/realms/bss/protocol/openid-connect/token}"
STAFF_USER="${BSS_STAFF_USER:-demo}"
STAFF_PASS="${BSS_STAFF_PASS:-demo}"
NAME="${1:-hermes}"
EMAIL="worker-${NAME}-$(date +%s)@bss.local"

say() { printf '%s\n' "$*" >&2; }

say "→ staff token (${STAFF_USER})"
STAFF=$(curl -sf -X POST "$TOKEN_URL" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=password&client_id=bss-demo&username=${STAFF_USER}&password=${STAFF_PASS}" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])')

say "→ minting the worker login (TMF672): ${EMAIL}"
MINTED=$(curl -sf -X POST "$GATEWAY/tmf-api/rolesAndPermissionsManagement/v4/user" \
  -H "Authorization: Bearer $STAFF" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"givenName\":\"Hermes\",\"familyName\":\"Worker\"}")
WORKER_ID=$(printf '%s' "$MINTED" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')
WORKER_PASS=$(printf '%s' "$MINTED" | python3 -c 'import json,sys;print(json.load(sys.stdin)["temporaryPassword"])')

say "→ granting the digital-worker badge (this also sheds the walk-in customer defaults)"
curl -sf -X POST "$GATEWAY/tmf-api/rolesAndPermissionsManagement/v4/permission" \
  -H "Authorization: Bearer $STAFF" -H 'Content-Type: application/json' \
  -d "{\"user\":{\"id\":\"$WORKER_ID\"},\"userRole\":{\"name\":\"digital-worker\"}}" > /dev/null

cat > .env.worker <<ENV
# The digital worker's badge — hand these to the agent runtime.
# Revoke on the console (Staff → the worker → revoke digital-worker) to fire.
BSS_GATEWAY_URL=$GATEWAY
BSS_TOKEN_URL=$TOKEN_URL
BSS_WORKER_USERNAME=$EMAIL
BSS_WORKER_PASSWORD=$WORKER_PASS
ENV

say ""
say "HIRED. Badge written to .env.worker (shown once — treat as a secret):"
say "  BSS_WORKER_USERNAME=$EMAIL"
say ""
say "Next (Hermes): merge config.snippet.yaml into ~/.hermes/config.yaml,"
say "copy skills/ into your Hermes skills directory, and let the cron run."
