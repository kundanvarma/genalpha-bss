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

echo; echo "== 3/3  product imagery + plan comparison specs =="
"$PY" "$HERE/seed/seed_demo_images.py" || echo "  (imagery step skipped — see ops/seed/seed_demo_images.py)"
"$PY" "$HERE/seed/seed_plan_compare.py" || echo "  (plan-compare step skipped — see ops/seed/seed_plan_compare.py)"
"$PY" "$HERE/seed/seed_lifecycle_characteristics.py" || echo "  (lifecycle-characteristics step skipped — see ops/seed/seed_lifecycle_characteristics.py)"
"$PY" "$HERE/seed/seed_wholesale_partners.py" || echo "  (wholesale-partners step skipped — see ops/seed/seed_wholesale_partners.py)"
"$PY" "$HERE/seed/seed_wholesale_access_products.py" || echo "  (wholesale-access-products step skipped — see ops/seed/seed_wholesale_access_products.py)"
"$PY" "$HERE/seed/seed_wholesale_coverage.py" || echo "  (wholesale-coverage step skipped — see ops/seed/seed_wholesale_coverage.py)"

echo; echo "demo reset complete — the stage is clean and curated."
