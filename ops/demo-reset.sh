#!/usr/bin/env bash
# One-command demo reset — run before every demo for a clean, curated stage.
#   1. curate the storefront catalog (retire E2E-test debris; keep 21 real products)
#   2. clear campaign/journey debris (fresh Journeys & Campaigns tabs)
#   3. refresh product imagery (real device photos if present locally, else tiles)
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
PY=${PY:-/usr/bin/python3}

echo "== 1/3  storefront catalog =="
"$PY" "$HERE/demo-reset-catalog.py"

echo; echo "== 2/3  campaigns / journeys =="
bash "$HERE/demo-reset-campaigns.sh"

echo; echo "== 3/3  demo fixtures (imagery, plan comparison, wholesale) =="
# NOT `|| echo "(step skipped)"` any more. Every one of these steps used to end
# that way, so a failed seed printed one quiet line and the script still said
# "demo reset complete" and exited 0 — the exact shape this repository forbids:
# a script that prints failures and exits 0 reads as a pass to everything
# downstream, including the person about to demo. Found while building the seed
# path for #261.
FAILED=""
for s in seed_demo_images seed_plan_compare seed_lifecycle_characteristics \
         seed_wholesale_partners seed_wholesale_access_products \
         seed_wholesale_coverage seed_novafibre_owner; do
  printf '  %-34s' "$s"
  if out=$("$PY" "$HERE/seed/$s.py" 2>&1); then
    echo "ok"
  else
    echo "FAILED"
    printf '%s\n' "$out" | tail -8 | sed 's/^/      /'
    FAILED="$FAILED $s"
  fi
done
if [ -n "$FAILED" ]; then
  echo >&2
  echo "demo reset INCOMPLETE — these seeds failed:$FAILED" >&2
  echo "The stage is NOT ready. Fix them before demoing." >&2
  exit 1
fi

echo; echo "demo reset complete — the stage is clean and curated."
