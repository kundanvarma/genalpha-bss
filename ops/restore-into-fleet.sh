#!/bin/bash
# RESTORE A DUMP INTO A WORKING FLEET — the operation ops/restore-drill.sh does
# NOT prove.
#
# The drill restores into a throwaway container and checks the rows came back.
# That proves the BYTES are good. It does not prove a fleet can run on them, and
# on 7 Oct 2026 a real restore onto the hosted box showed it cannot, not without
# the step below:
#
#   pg_dumpall carries ROLES, and roles carry PASSWORDS. Restoring a laptop dump
#   onto another machine replaces every per-service login with the SOURCE
#   machine's password, while the services keep using their OWN .env. Every one
#   of them then dies on "password authentication failed for user …" — and what
#   you actually read in the log is Hibernate's
#       Unable to determine Dialect without JDBC metadata
#   which points at JPA for what is a credentials problem. That cost an hour.
#
# So this restores, and then realigns every role to the password the container
# that uses it is actually configured with — read from the container's own
# environment, so there is one source of truth and no list to maintain here.
#
# Usage:
#   ops/restore-into-fleet.sh backups/bss-<ts>.sql.gz
#   ops/restore-into-fleet.sh                 # newest backup
#   COMPOSE="docker compose -f docker-compose.yml -f docker-compose.cloud.yml" \
#     ops/restore-into-fleet.sh …             # on the hosted box
#
# THIS DESTROYS THE CURRENT DATABASE. It asks first unless FORCE=1.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"
COMPOSE="${COMPOSE:-docker compose}"
PG=bss-postgres

DUMP="${1:-}"
[ -n "$DUMP" ] || DUMP="$(ls -t "$REPO"/backups/bss-*.sql.gz 2>/dev/null | head -1 || true)"
[ -n "$DUMP" ] && [ -f "$DUMP" ] || { echo "restore: no dump given and none in backups/" >&2; exit 1; }

say() { echo "[restore] $*"; }

if [ "${FORCE:-0}" != "1" ]; then
  echo "[restore] This REPLACES every database in $PG with $DUMP."
  printf '[restore] Type the word restore to continue: '
  read -r answer
  [ "$answer" = "restore" ] || { echo "[restore] stopped"; exit 1; }
fi

say "stopping the fleet"
$COMPOSE stop >/dev/null 2>&1 || true

say "replacing the postgres volume"
$COMPOSE rm -f postgres >/dev/null 2>&1 || true
VOL="$(docker volume ls -q | grep -m1 -E 'postgres-data|pgdata' || true)"
[ -n "$VOL" ] && docker volume rm "$VOL" >/dev/null 2>&1 || true

say "starting a clean postgres"
$COMPOSE up -d postgres >/dev/null 2>&1
for _ in $(seq 1 60); do
  docker exec "$PG" pg_isready -U postgres >/dev/null 2>&1 && break
  sleep 5
done
docker exec "$PG" pg_isready -U postgres >/dev/null 2>&1 || { echo "[restore] postgres never came up" >&2; exit 1; }

say "restoring $DUMP"
gunzip -c "$DUMP" | docker exec -i "$PG" psql -U postgres -q >/tmp/restore-psql.log 2>&1 || true
DBS="$(docker exec "$PG" psql -U postgres -tAc \
  "select count(*) from pg_database where datistemplate=false" | tr -d ' ')"
say "databases restored: $DBS"
[ "${DBS:-0}" -gt 1 ] || { echo "[restore] the dump produced no databases — see /tmp/restore-psql.log" >&2; exit 1; }

# THE STEP THE DRILL DOES NOT COVER.
say "realigning role passwords with each service's own configuration"
$COMPOSE up -d >/dev/null 2>&1
sleep 10
n=0
for c in $(docker ps -a --format '{{.Names}}' | grep '^bss-'); do
  env="$(docker inspect "$c" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null || true)"
  u="$(printf '%s\n' "$env" | grep -m1 '^DB_USERNAME=' | cut -d= -f2- || true)"
  p="$(printf '%s\n' "$env" | grep -m1 '^DB_PASSWORD=' | cut -d= -f2- || true)"
  [ -n "${u:-}" ] && [ -n "${p:-}" ] || continue
  docker exec "$PG" psql -U postgres -q -c "ALTER USER \"$u\" WITH PASSWORD '$p'" >/dev/null 2>&1 && n=$((n+1))
done
say "roles realigned: $n"

say "restarting the fleet so every pool reconnects"
$COMPOSE restart >/dev/null 2>&1 || true

say "done. Watch it come up with: ops/fleet.sh status"
say "A restore is not proven until the gateway serves the catalogue AND"
say "ops/security/rls_check.py passes — row-level security policies live in the"
say "dump, so a restore that loses them loses the tenant wall silently."
