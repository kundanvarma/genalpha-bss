#!/usr/bin/env bash
# Converge every realm's sign-in rules (#320) from infra/keycloak/security/, through
# Keycloak's own admin CLI. Idempotent: run on every fleet start and box install
# (the keycloak-converge one-shot service). A realm keeps every existing password —
# a policy is checked when a password is SET — so this is safe to re-run.
set -euo pipefail
KC_URL=${KC_URL:-http://keycloak:8080}
SEC=${SECURITY_DIR:-/security}
ADM=/opt/keycloak/bin/kcadm.sh
CFG=/tmp/kcadm.config
POLICY=$(tr -d '\n' < "$SEC/password-policy")

for i in $(seq 1 60); do
  if "$ADM" config credentials --config "$CFG" --server "$KC_URL" --realm master \
       --user "${KC_ADMIN_USER:-admin}" --password "${KC_ADMIN_PASSWORD:-admin}" >/dev/null 2>&1; then
    break
  fi
  [ "$i" = 60 ] && { echo "[converge] keycloak never answered at $KC_URL" >&2; exit 1; }
  sleep 5
done

realms=$("$ADM" get realms --config "$CFG" --fields realm --format csv --noquotes | grep -v '^master$')
n=0
for r in $realms; do
  "$ADM" update "realms/$r" --config "$CFG" -s "passwordPolicy=$POLICY"
  # guessing is throttled (#326): temporary lockout, never permanent
  "$ADM" update "realms/$r" --config "$CFG" -f "$SEC/brute-force.json"
  for f in "$SEC"/messages-*.json; do
    locale=$(basename "$f" .json); locale=${locale#messages-}
    "$ADM" create "realms/$r/localization/$locale" --config "$CFG" -f "$f" >/dev/null
  done
  n=$((n + 1))
done
echo "[converge] $n realm(s): passwordPolicy=\"$POLICY\", lockout from brute-force.json, messages for $(ls "$SEC"/messages-*.json | sed 's#.*/messages-##; s#\.json##' | tr '\n' ' ')"
