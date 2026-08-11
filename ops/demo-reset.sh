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

echo; echo "== 3/3  product imagery =="
"$PY" "$HERE/seed/seed_demo_images.py" || echo "  (imagery step skipped — see ops/seed/seed_demo_images.py)"

echo; echo "demo reset complete — the stage is clean and curated."
