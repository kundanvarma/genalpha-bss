#!/usr/bin/env bash
# Demo campaigns reset — a clean Journeys/Campaigns tab every time.
#
# E2E suites leave hundreds of throwaway campaigns/journeys ("Growth campaign
# 178...", "Welcome journey 178..."). The console lists them all, and the API
# has no delete (campaigns only toggle active/paused) — so we clear the campaign
# tables directly. The demo's Scene 4 then creates a fresh campaign live.
#
# Config/infra tables (martech_setting, migrations, outbox) are left untouched.
# Run before a demo:  ops/demo-reset-campaigns.sh
set -eu
DOCKER=${DOCKER:-/opt/homebrew/bin/docker}
PG=bss-postgres

echo "clearing demo campaign data (test debris) ..."
"$DOCKER" exec "$PG" psql -U postgres -d campaign -q -c "
  TRUNCATE journey_enrollment, campaign_execution, marketing_touch, campaign, journey
  RESTART IDENTITY CASCADE;" >/dev/null

echo "done — campaigns/journeys cleared. The console's Journeys & Campaigns tabs"
echo "are empty; create a fresh 'Welcome' campaign live in Scene 4."
