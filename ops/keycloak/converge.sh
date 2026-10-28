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

login() {
  "$ADM" config credentials --config "$CFG" --server "$KC_URL" --realm master \
    --user "${KC_ADMIN_USER:-admin}" --password "${KC_ADMIN_PASSWORD:-admin}" >/dev/null 2>&1
}
for i in $(seq 1 60); do
  login && break
  [ "$i" = 60 ] && { echo "[converge] keycloak never answered at $KC_URL" >&2; exit 1; }
  sleep 5
done

realms=$("$ADM" get realms --config "$CFG" --fields realm --format csv --noquotes | grep -v '^master$')
n=0
for r in $realms; do
  # the master admin token lives a minute; a fleet with many realms outlasts it
  login || { echo "[converge] lost the admin session at realm $r" >&2; exit 1; }
  "$ADM" update "realms/$r" --config "$CFG" -s "passwordPolicy=$POLICY"
  # guessing is throttled (#326): temporary lockout, never permanent
  "$ADM" update "realms/$r" --config "$CFG" -f "$SEC/brute-force.json"
  # account mail (#327): where verify-email and reset-password mail goes. Set only
  # when the fleet names a mail server — the laptop's Mailpit; the box its own (#328)
  if [ -n "${KC_SMTP_HOST:-}" ]; then
    "$ADM" update "realms/$r" --config "$CFG" \
      -s "smtpServer.host=$KC_SMTP_HOST" -s "smtpServer.port=${KC_SMTP_PORT:-25}" \
      -s "smtpServer.from=${KC_SMTP_FROM:-notifications@bss.local}" \
      -s "smtpServer.fromDisplayName=$r" -s 'smtpServer.auth=false' \
      -s 'smtpServer.ssl=false' -s 'smtpServer.starttls=false'
  fi
  # roles a component needs (#354): defined where missing, granted to the machine
  # identity that holds them — never to a person (machine-roles)
  if [ -f "$SEC/machine-roles" ]; then
    grep -vE '^[[:space:]]*(#|$)' "$SEC/machine-roles" | while read -r role holder; do
      "$ADM" create roles -r "$r" --config "$CFG" -s "name=$role" >/dev/null 2>&1 || true
      case "${holder:-}" in
        "") ;;
        composite:*)   # into a composite people hold, where the realm has it
          if "$ADM" get "roles/${holder#composite:}" -r "$r" --config "$CFG" >/dev/null 2>&1; then
            "$ADM" add-roles -r "$r" --config "$CFG" --rname "${holder#composite:}" --rolename "$role" \
              || echo "[converge] $r: could not add $role to ${holder#composite:}" >&2
          fi ;;
        user:*)        # to one named user, where the realm has them
          # the search is fuzzy ("demo" also finds "worker-...-demo-..."): take the user whose
          # username OR email is exactly the name (a login may be stored as its email address)
          name="${holder#user:}"
          # (plain bash: the Keycloak image carries no awk)
          uid=""
          while IFS=, read -r id un em; do
            if [ "$un" = "$name" ] || [ "$em" = "$name" ] || [ "${un%%@*}" = "$name" ]; then uid="$id"; break; fi
          done < <("$ADM" get users -r "$r" --config "$CFG" -q "search=$name" --fields id,username,email \
              --format csv --noquotes 2>/dev/null)
          if [ -n "$uid" ]; then
            "$ADM" add-roles -r "$r" --config "$CFG" --uid "$uid" --rolename "$role" \
              || echo "[converge] $r: could not give $role to $name" >&2
          fi ;;
        *)             # a machine: the client's service account
          if "$ADM" get users -r "$r" --config "$CFG" -q "username=service-account-$holder" \
              --fields id --format csv --noquotes 2>/dev/null | grep -q .; then
            "$ADM" add-roles -r "$r" --config "$CFG" --uusername "service-account-$holder" --rolename "$role"
          fi ;;
      esac
    done
  fi
  for f in "$SEC"/messages-*.json; do
    locale=$(basename "$f" .json); locale=${locale#messages-}
    "$ADM" create "realms/$r/localization/$locale" --config "$CFG" -f "$f" >/dev/null
  done
  n=$((n + 1))
done
echo "[converge] $n realm(s): passwordPolicy=\"$POLICY\", lockout from brute-force.json, mail via ${KC_SMTP_HOST:-nowhere}, machine roles $(grep -cvE '^[[:space:]]*(#|$)' "$SEC/machine-roles" 2>/dev/null || echo 0), messages for $(ls "$SEC"/messages-*.json | sed 's#.*/messages-##; s#\.json##' | tr '\n' ' ')"
