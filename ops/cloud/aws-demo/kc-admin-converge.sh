#!/usr/bin/env bash
# THE ADMIN CREDENTIAL KEYCLOAK ACTUALLY HAS.
#
# KC_BOOTSTRAP_ADMIN_* applies only when Keycloak creates its admin on an EMPTY
# database. Keycloak on the box has a persistent volume, so if .env is ever
# regenerated — install.sh generates passwords whenever .env is absent — every
# service receives the new value and Keycloak keeps the OLD one.
#
# Both halves then look correct: same username, same password, in both
# containers. I compared them by sha256 and they matched. Only Keycloak
# disagreed, and the only visible symptom was operator onboarding returning an
# opaque 500 with `401 invalid_grant` buried in the user-roles log. Onboarding
# and re-branding were dead on the hosted demo for an unknown length of time.
# Issue #257.
#
# This proves the credential and converges it when wrong. The reset puts back
# the value every service already holds in .env — it mints no new secret — and
# it is loud about having done it, because a box whose admin password was not
# what .env said is worth knowing about even after the fix.
#
# Recovery is Keycloak's own designed path. `kc.sh bootstrap-admin user`
# refuses when the username exists ("user with username exists"), so this
# creates a TEMPORARY admin, resets the real one through the admin API, and
# deletes the temporary admin again.
#
#   ops/cloud/aws-demo/kc-admin-converge.sh          # check, converge if needed
#   ops/cloud/aws-demo/kc-admin-converge.sh --check  # check only, exit 1 if wrong
#
# Two things learned writing it, both worth keeping:
#  - the Keycloak image has NO curl and no net tools, so every request is made
#    from the HOST. A first version ran curl inside the container and sat for
#    ten minutes reporting "keycloak never answered".
#  - the host port is 8085; 8080 is the gateway and answers 404 on /realms.
#  - passwords go to curl through a mode-600 file, never an argument, so they
#    do not appear in `ps` for other local users.
set -u

CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

KC=${KC_CONTAINER:-bss-keycloak}
BASE=${KC_BASE:-http://localhost:8085}
DOCKER=docker
$DOCKER ps >/dev/null 2>&1 || DOCKER="sudo docker"

TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT INT TERM
umask 077

env_of() {   # env_of <container> <VAR>  -> value on stdout, never logged
  $DOCKER inspect "$1" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | grep "^$2=" | head -1 | cut -d= -f2-
}

KC_USER=$(env_of "$KC" KC_BOOTSTRAP_ADMIN_USERNAME)
printf '%s' "$(env_of "$KC" KC_BOOTSTRAP_ADMIN_PASSWORD)" > "$TMPD/kcpw"
if [ -z "$KC_USER" ] || [ ! -s "$TMPD/kcpw" ]; then
  echo "kc-admin: FAIL — $KC has no KC_BOOTSTRAP_ADMIN_USERNAME/PASSWORD in its env" >&2
  exit 2
fi

ready() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
  "$BASE/realms/master/.well-known/openid-configuration"; }

token_status() {  # password from a file, so it is not in argv
  curl -s -o /dev/null -w '%{http_code}' --max-time 20 \
    -d grant_type=password -d client_id=admin-cli \
    --data-urlencode "username=$KC_USER" \
    --data-urlencode "password@$TMPD/kcpw" \
    "$BASE/realms/master/protocol/openid-connect/token"
}

echo "kc-admin: waiting for keycloak at $BASE"
for _ in $(seq 1 40); do [ "$(ready)" = "200" ] && break; sleep 10; done
[ "$(ready)" = "200" ] || { echo "kc-admin: FAIL — keycloak never answered on /realms/master" >&2; exit 2; }

ST=$(token_status)
if [ "$ST" = "200" ]; then
  echo "kc-admin: ok — the admin credential in .env is the one keycloak has"
  exit 0
fi

echo "kc-admin: MISMATCH — keycloak's admin password is not the one in .env (token said ${ST:-no-answer})" >&2
echo "          issue #257; onboarding would 500 with 401 invalid_grant" >&2
[ "$CHECK_ONLY" = "1" ] && exit 1

echo "kc-admin: converging — temporary admin, reset, remove"
# NO trailing newline: the password is SET from $(cat file) — which strips it —
# and SENT with --data-urlencode password@file, which does not. A newline in one
# and not the other is a different password, and the symptom is a temp admin
# that exists but whose token request fails.
openssl rand -hex 18 | tr -d '\n' > "$TMPD/tmppw"

# A UNIQUE name per run. bootstrap-admin refuses an existing username ("user
# with username exists"), so a leftover from an interrupted run would block
# every future recovery — and it cannot be deleted first, because deleting it
# needs the admin token this script does not yet have. A fresh name sidesteps
# the deadlock; strays are swept below once a token exists.
TMP_USER="kc-recover-$(date +%s)-$$"
$DOCKER exec -e TMPPW="$(cat "$TMPD/tmppw")" "$KC" sh -c \
  "/opt/keycloak/bin/kc.sh bootstrap-admin user --no-prompt --optimized \
     --username '$TMP_USER' --password:env TMPPW" 2>&1 \
  | grep -E 'ERROR' >&2 || true

jqp() { python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get(sys.argv[1],"") if isinstance(d,dict) else (d[0].get(sys.argv[1],"") if d else ""))' "$1"; }

TOK=$(curl -s --max-time 25 -d grant_type=password -d client_id=admin-cli \
        --data-urlencode "username=$TMP_USER" \
        --data-urlencode "password@$TMPD/tmppw" \
        "$BASE/realms/master/protocol/openid-connect/token" | jqp access_token)
[ -n "$TOK" ] || { echo "kc-admin: FAIL — no token for $TMP_USER (bootstrap-admin did not create it)" >&2; exit 1; }

UID_A=$(curl -s --max-time 20 -H "Authorization: Bearer $TOK" \
  "$BASE/admin/realms/master/users?exact=true&username=$KC_USER" | jqp id)
[ -n "$UID_A" ] || { echo "kc-admin: FAIL — admin user '$KC_USER' not found in master" >&2; exit 1; }

python3 - "$TMPD/kcpw" > "$TMPD/body.json" <<'PY'
import json,sys
pw=open(sys.argv[1]).read()
json.dump({"type":"password","temporary":False,"value":pw}, sys.stdout)
PY
RC=$(curl -s -o /dev/null -w '%{http_code}' -X PUT --max-time 25 \
  -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  --data-binary "@$TMPD/body.json" \
  "$BASE/admin/realms/master/users/$UID_A/reset-password")
[ "$RC" = "204" ] || { echo "kc-admin: FAIL — reset-password returned $RC" >&2; exit 1; }

# Sweep the recovery admins — STRAYS FIRST, OUR OWN LAST.
# Order matters and getting it wrong is silent: the first version deleted its
# own account at the head of the list, which invalidated the very token it was
# deleting with, and every later DELETE failed while the loop discarded the
# status codes. A stray temp admin left in the master realm is a permanent way
# in, so each delete is now checked.
sweep_user() {   # sweep_user <id> <label>
  code=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE --max-time 20 \
    -H "Authorization: Bearer $TOK" "$BASE/admin/realms/master/users/$1")
  case "$code" in
    204|404) ;;
    *) echo "kc-admin: WARNING — could not remove recovery admin $2 (HTTP $code);" >&2
       echo "          delete it by hand: it can administer the master realm" >&2 ;;
  esac
}

for U_ID in $(curl -s --max-time 20 -H "Authorization: Bearer $TOK" \
      "$BASE/admin/realms/master/users?search=kc-recover-&max=50" \
    | python3 -c "import sys,json;[print(u['id']) for u in json.load(sys.stdin) if u.get('username')!='$TMP_USER']" 2>/dev/null) \
    $(curl -s --max-time 20 -H "Authorization: Bearer $TOK" \
      "$BASE/admin/realms/master/users?exact=true&username=temp-recovery" | jqp id); do
  sweep_user "$U_ID" "stray"
done

# ours last: this delete ends the session the token belongs to
UID_T=$(curl -s --max-time 20 -H "Authorization: Bearer $TOK" \
  "$BASE/admin/realms/master/users?exact=true&username=$TMP_USER" | jqp id)
[ -n "$UID_T" ] && sweep_user "$UID_T" "$TMP_USER"

if [ "$(token_status)" = "200" ]; then
  echo "kc-admin: converged — keycloak's admin password is now the one in .env"
  exit 0
fi
echo "kc-admin: FAIL — still wrong after the reset" >&2
exit 1
