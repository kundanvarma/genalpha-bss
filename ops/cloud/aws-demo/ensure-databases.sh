#!/usr/bin/env bash
# Every database the fleet needs, present (#380).
#
# infra/postgres/init-databases.sql runs only when Postgres initialises an EMPTY
# data directory. The box keeps its volume across deploys, so a component added
# since the box was first built has no database there, and it crash-loops with
# `database "<x>" does not exist` while the install reports success. Seen on
# 28 Oct 2026 with benefit-allowance.
#
# This creates, as the owning `postgres` role, each database the init script
# names that the server lacks — and nothing else. A component's own first
# migration creates its runtime role and row-level security, exactly as on a
# fresh box. Idempotent; says what it created.
#
#   ops/cloud/aws-demo/ensure-databases.sh            # create what is missing
#   ops/cloud/aws-demo/ensure-databases.sh --check    # say what is missing, exit 1 if any
set -euo pipefail
cd "$(dirname "$0")/../../.."

SQL="${DB_LIST_FILE:-infra/postgres/init-databases.sql}"
PG="${PG_CONTAINER:-bss-postgres}"
CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

psql() { docker exec "$PG" psql -U postgres -v ON_ERROR_STOP=1 -tAc "$1"; }

# Postgres answering, not merely running
for _ in $(seq 1 60); do
  docker exec "$PG" pg_isready -U postgres >/dev/null 2>&1 && break
  sleep 2
done
docker exec "$PG" pg_isready -U postgres >/dev/null 2>&1 || { echo "ensure-databases: $PG is not answering" >&2; exit 1; }

wanted=$(sed -nE 's/^[[:space:]]*CREATE DATABASE[[:space:]]+([a-z_][a-z0-9_]*)[[:space:]]*;.*/\1/p' "$SQL")
[ -n "$wanted" ] || { echo "ensure-databases: no CREATE DATABASE lines in $SQL" >&2; exit 1; }
have=$(psql "SELECT datname FROM pg_database")

missing=()
for db in $wanted; do
  grep -qx "$db" <<<"$have" || missing+=("$db")
done

total=$(wc -w <<<"$wanted" | tr -d ' ')
if [ ${#missing[@]} -eq 0 ]; then
  echo "ensure-databases: all $total databases present"
  exit 0
fi
if [ $CHECK -eq 1 ]; then
  echo "ensure-databases: MISSING ${missing[*]}" >&2
  exit 1
fi
for db in "${missing[@]}"; do
  # the names come from our own init script and are matched to [a-z_][a-z0-9_]* above
  psql "CREATE DATABASE $db" >/dev/null
  echo "ensure-databases: created $db"
done
echo "ensure-databases: $total databases present (${#missing[@]} created)"
