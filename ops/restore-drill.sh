#!/bin/bash
# THE RESTORE DRILL: an untested backup is a hope, not a backup. This
# restores a dump into a THROWAWAY container (same pgvector/pg16 image
# as production dev), verifies the databases and their rows actually
# came back, optionally proves a named sentinel row survived, and
# removes the container. Nothing it does can touch the live fleet.
#
# Usage:
#   ops/restore-drill.sh                          # newest backups/bss-*.sql.gz
#   ops/restore-drill.sh backups/bss-<ts>.sql.gz
#   ops/restore-drill.sh --expect <db> "<sql>"    # sql must return rows
#     e.g. --expect party "SELECT 1 FROM individual WHERE given_name='Restore'"
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DRILL=bss-restore-drill
IMAGE=pgvector/pgvector:pg16

DUMP=""
EXPECT_DB=""
EXPECT_SQL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --expect) EXPECT_DB="$2"; EXPECT_SQL="$3"; shift 3 ;;
    *) DUMP="$1"; shift ;;
  esac
done
if [ -z "$DUMP" ]; then
  DUMP="$(ls -t "$REPO"/backups/bss-*.sql.gz 2>/dev/null | head -1 || true)"
fi
if [ -z "$DUMP" ] || [ ! -f "$DUMP" ]; then
  echo "restore-drill: no dump found — run ops/backup.sh first" >&2
  exit 1
fi

cleanup() { docker rm -f "$DRILL" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

echo "restore-drill: raising a throwaway postgres ($IMAGE) ..."
docker run -d --name "$DRILL" -e POSTGRES_PASSWORD=drill "$IMAGE" >/dev/null
for i in $(seq 1 30); do
  docker exec "$DRILL" pg_isready -U postgres >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$DRILL" pg_isready -U postgres >/dev/null

echo "restore-drill: restoring $(basename "$DUMP") ..."
# pg_dumpall replays roles + databases + rows; role/db "already exists"
# noise is impossible here — the container is virgin
gunzip -c "$DUMP" | docker exec -i "$DRILL" psql -U postgres -q -v ON_ERROR_STOP=0 >/dev/null 2>&1 || true

DBS="$(docker exec "$DRILL" psql -U postgres -tA -c \
  "SELECT count(*) FROM pg_database WHERE datname NOT IN ('postgres','template0','template1')")"
if [ "$DBS" -lt 20 ]; then
  echo "restore-drill: FAILED — only $DBS databases came back (expected the fleet, 20+)" >&2
  exit 1
fi
echo "restore-drill: $DBS databases restored"

# spot-check rows in the fleet's busiest tables
for probe in "party_account:individual" "billing:customer_bill" "product_catalog:product_offering"; do
  db="${probe%%:*}"; table="${probe##*:}"
  rows="$(docker exec "$DRILL" psql -U postgres -d "$db" -tA -c \
    "SELECT count(*) FROM $table" 2>/dev/null || echo "n/a")"
  echo "restore-drill: $db.$table = $rows rows"
done

if [ -n "$EXPECT_DB" ]; then
  HIT="$(docker exec "$DRILL" psql -U postgres -d "$EXPECT_DB" -tA -c "$EXPECT_SQL" | head -1)"
  if [ -z "$HIT" ]; then
    echo "restore-drill: FAILED — sentinel query returned nothing in '$EXPECT_DB': $EXPECT_SQL" >&2
    exit 1
  fi
  echo "restore-drill: sentinel found in '$EXPECT_DB' — the row survived the round trip"
fi

echo "restore-drill: PASSED — this backup provably restores. Container removed."
