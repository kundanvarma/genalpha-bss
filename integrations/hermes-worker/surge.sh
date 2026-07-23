#!/usr/bin/env bash
# SURGE STAFFING, dev edition: watch the staffing signal and start/stop
# worker processes to match it — the compose-world analogue of the KEDA
# example. You supply the start command (how a worker process runs);
# this script supplies the WHEN.
#
#   WORKER_START_CMD='env $(cat .env.worker2 | xargs) node my-worker.js' \
#   ./surge.sh
#
# Env: BSS_GATEWAY_URL, BSS_TOKEN_URL, BSS_STAFF_USER/PASS (as hire-worker.sh),
#      SURGE_MAX (local cap, default 3), POLL_SECONDS (default 30).
# The BSS-side crew ceiling (governance maxWorkers) still caps everything:
# a surged worker beyond it is refused at claim time — the operator wins.
set -euo pipefail

GATEWAY="${BSS_GATEWAY_URL:-http://localhost:8080}"
TOKEN_URL="${BSS_TOKEN_URL:-http://localhost:8085/realms/bss/protocol/openid-connect/token}"
STAFF_USER="${BSS_STAFF_USER:-demo}"
STAFF_PASS="${BSS_STAFF_PASS:-demo}"
SURGE_MAX="${SURGE_MAX:-3}"
POLL="${POLL_SECONDS:-30}"
: "${WORKER_START_CMD:?set WORKER_START_CMD to the command that runs ONE worker process}"

declare -a PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

while true; do
  STAFF=$(curl -sf -X POST "$TOKEN_URL" -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "grant_type=password&client_id=bss-demo&username=${STAFF_USER}&password=${STAFF_PASS}" \
    | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])')
  SIGNAL=$(curl -sf "$GATEWAY/ai/v1/workforce/kpis" -H "Authorization: Bearer $STAFF" \
    | python3 -c 'import json,sys;s=json.load(sys.stdin)["staffing"];print(int(s["surge"]), s["backlogDepth"], s["activeWorkers"], s["maxWorkers"])')
  read -r SURGE BACKLOG ACTIVE MAX <<< "$SIGNAL"

  # prune finished processes
  ALIVE=()
  for p in "${PIDS[@]:-}"; do kill -0 "$p" 2>/dev/null && ALIVE+=("$p"); done
  PIDS=("${ALIVE[@]:-}")

  CAP=$SURGE_MAX
  [ "$MAX" -gt 0 ] 2>/dev/null && [ "$MAX" -lt "$CAP" ] && CAP=$MAX

  if [ "$SURGE" = "1" ] && [ "${#PIDS[@]}" -lt "$CAP" ]; then
    echo "SURGE (backlog $BACKLOG, active $ACTIVE): starting worker $(( ${#PIDS[@]} + 1 ))/$CAP"
    bash -c "$WORKER_START_CMD" & PIDS+=($!)
  elif [ "$SURGE" = "0" ] && [ "${#PIDS[@]}" -gt 0 ]; then
    echo "relieved (backlog $BACKLOG): stopping one surged worker"
    kill "${PIDS[-1]}" 2>/dev/null || true
    unset 'PIDS[-1]'
  fi
  sleep "$POLL"
done
