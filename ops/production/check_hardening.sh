#!/bin/sh
# Phase 4 gate: refuse to bless a production env that still smells of dev.
set -e
ENV_FILE="${1:-production.env}"
[ -f "$ENV_FILE" ] || { echo "FAIL: $ENV_FILE not found (copy production.env.template)"; exit 1; }
fail=0
if grep -q "CHANGE_ME" "$ENV_FILE"; then echo "FAIL: CHANGE_ME values remain in $ENV_FILE"; fail=1; fi
for bad in "POSTGRES_PASSWORD=postgres" "KEYCLOAK_ADMIN_PASSWORD=admin" "POSTGRES_PASSWORD=$" "KEYCLOAK_ADMIN_PASSWORD=$"; do
  if grep -q "^$bad" "$ENV_FILE"; then echo "FAIL: dev default or empty: $bad"; fail=1; fi
done
pw=$(grep '^POSTGRES_PASSWORD=' "$ENV_FILE" | cut -d= -f2)
[ ${#pw} -ge 16 ] || { echo "FAIL: POSTGRES_PASSWORD shorter than 16 chars"; fail=1; }
kpw=$(grep '^KEYCLOAK_ADMIN_PASSWORD=' "$ENV_FILE" | cut -d= -f2)
[ ${#kpw} -ge 16 ] || { echo "FAIL: KEYCLOAK_ADMIN_PASSWORD shorter than 16 chars"; fail=1; }
[ $fail -eq 0 ] && echo "OK: $ENV_FILE passes the hardening gate" || exit 1
