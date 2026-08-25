#!/bin/sh
# Phase 5 — the backup/restore drill: dump the billing database, restore it
# into a SCRATCH database, and prove a bill survives the round trip. The
# live database is never written. Run it on a schedule; a backup that has
# never been restored is a hope, not a backup.
set -e
PG=${PG_CONTAINER:-bss-postgres}
SRC=${DRILL_SOURCE_DB:-billing}
SCRATCH=drill_restore_$$
echo "— dumping $SRC —"
docker exec $PG pg_dump -U postgres -d $SRC -F c -f /tmp/drill.dump
SIZE=$(docker exec $PG sh -c 'ls -la /tmp/drill.dump | awk "{print \$5}"')
echo "dump: $SIZE bytes"
echo "— restoring into scratch '$SCRATCH' —"
docker exec $PG createdb -U postgres $SCRATCH
docker exec $PG pg_restore -U postgres -d $SCRATCH --no-owner /tmp/drill.dump 2>/dev/null || true
LIVE=$(docker exec $PG psql -U postgres -d $SRC -tAc "select count(*) from customer_bill" 2>/dev/null || echo 0)
RESTORED=$(docker exec $PG psql -U postgres -d $SCRATCH -tAc "select count(*) from customer_bill" 2>/dev/null || echo 0)
echo "bills live=$LIVE restored=$RESTORED"
docker exec $PG dropdb -U postgres $SCRATCH
docker exec $PG rm -f /tmp/drill.dump
[ "$LIVE" = "$RESTORED" ] && [ "$LIVE" != "0" ] \
  && echo "OK DRILL: every bill survived the round trip — the backup is real" \
  || { echo "FAIL: restored $RESTORED of $LIVE bills"; exit 1; }
