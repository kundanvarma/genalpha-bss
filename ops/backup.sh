#!/bin/bash
# BACKUP THE FLEET: every database in the one Postgres, one dump file.
# pg_dumpall carries roles (the per-service app users) and all ~30
# databases; gzip keeps it honest on disk; the newest 14 are kept.
#
# A backup is a HOPE until ops/restore-drill.sh has proven it restores.
# The pair ships together on purpose.
#
# Usage: ops/backup.sh            (writes backups/bss-<UTC timestamp>.sql.gz)
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
BACKUP_DIR="$REPO/backups"
KEEP=14
STAMP="$(date -u +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/bss-$STAMP.sql.gz"

mkdir -p "$BACKUP_DIR"

if ! docker exec bss-postgres true 2>/dev/null; then
  echo "backup: bss-postgres is not running — nothing to back up" >&2
  exit 1
fi

echo "backup: dumping every database and role from bss-postgres ..."
docker exec bss-postgres pg_dumpall -U postgres | gzip > "$OUT"

SIZE="$(du -h "$OUT" | cut -f1)"
DBS="$(gunzip -c "$OUT" | grep -c '^\\\\connect ' || true)"
echo "backup: wrote $OUT ($SIZE, $DBS databases)"

# prune: newest $KEEP stay
ls -t "$BACKUP_DIR"/bss-*.sql.gz 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do
  rm -f "$old" && echo "backup: pruned $(basename "$old")"
done

echo "backup: done — now prove it with ops/restore-drill.sh"
