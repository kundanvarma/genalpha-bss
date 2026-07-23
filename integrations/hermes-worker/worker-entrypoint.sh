#!/usr/bin/env bash
# The worker's morning routine: render config from env, then work shifts on
# a clock. One-shot shifts (hermes --yolo -z) are the container's cron —
# simple, restartable, and a crashed shift loses nothing (claims are
# leases; they free themselves).
#
# Required env: BSS_WORKER_USERNAME, BSS_WORKER_PASSWORD  (the badge)
#               WORKER_AI_PROVIDER, WORKER_AI_MODEL        (the brain)
# Optional:     WORKER_AI_BASE_URL, WORKER_AI_API_KEY,
#               BSS_GATEWAY_URL, BSS_TOKEN_URL,
#               WORK_INTERVAL_SECONDS (default 900), WORKER_JOB (care|back-office|generalist)
set -euo pipefail

: "${BSS_WORKER_USERNAME:?a worker without a badge never boots}"
: "${BSS_WORKER_PASSWORD:?a worker without a badge never boots}"
: "${WORKER_AI_PROVIDER:?a worker without a brain never boots}"
: "${WORKER_AI_MODEL:?a worker without a brain never boots}"

GATEWAY="${BSS_GATEWAY_URL:-http://gateway:8080}"
TOKEN_URL="${BSS_TOKEN_URL:-http://keycloak:8080/realms/bss/protocol/openid-connect/token}"
INTERVAL="${WORK_INTERVAL_SECONDS:-900}"
JOB="${WORKER_JOB:-care}"

mkdir -p /root/.hermes
cat > /root/.hermes/config.yaml <<CFG
model:
  default: "${WORKER_AI_MODEL}"
  provider: "${WORKER_AI_PROVIDER}"
$( [ -n "${WORKER_AI_BASE_URL:-}" ] && echo "  base_url: \"${WORKER_AI_BASE_URL}\"" )
tool_loop_guardrails:
  hard_stop_enabled: true
mcp_servers:
  bss:
    command: node
    args: ["/opt/bss-mcp/server.js"]
    env:
      BSS_GATEWAY_URL: "${GATEWAY}"
      BSS_TOKEN_URL: "${TOKEN_URL}"
      BSS_WORKER_USERNAME: "${BSS_WORKER_USERNAME}"
      BSS_WORKER_PASSWORD: "${BSS_WORKER_PASSWORD}"
    tools:
      include: [workforce_list_tasks, workforce_claim, workforce_complete,
                workforce_escalate, care_ticket_get, care_ticket_resolve,
                backoffice_unapplied_cash, backoffice_list_bills,
                backoffice_apply_payment, request_approval]
CFG
if [ -n "${WORKER_AI_API_KEY:-}" ]; then
  case "$WORKER_AI_PROVIDER" in
    anthropic) echo "ANTHROPIC_API_KEY=${WORKER_AI_API_KEY}" > /root/.hermes/.env ;;
    *)         echo "OPENAI_API_KEY=${WORKER_AI_API_KEY}"    > /root/.hermes/.env ;;
  esac
  chmod 600 /root/.hermes/.env
fi

CARE_PROMPT='Work the care queue as the badged digital worker: follow the care-triage skill exactly, using only the bss MCP tools. At most 3 ticket tasks this run. If the queue has no ticket tasks, reply QUEUE EMPTY and stop.'
CASH_PROMPT='Work the AR queue as the badged digital worker: follow the cash-matching skill exactly, using only the bss MCP tools. At most 3 unapplied-cash tasks this run. If none, reply QUEUE EMPTY and stop.'

echo "worker ${BSS_WORKER_USERNAME} on shift: job=${JOB} brain=${WORKER_AI_PROVIDER}/${WORKER_AI_MODEL} every ${INTERVAL}s"
while true; do
  case "$JOB" in
    care)        hermes --yolo -t bss --skills care-triage   -z "$CARE_PROMPT" || echo "shift errored (lease will free itself)";;
    back-office) hermes --yolo -t bss --skills cash-matching -z "$CASH_PROMPT" || echo "shift errored (lease will free itself)";;
    *)           hermes --yolo -t bss --skills care-triage   -z "$CARE_PROMPT" || true
                 hermes --yolo -t bss --skills cash-matching -z "$CASH_PROMPT" || echo "shift errored";;
  esac
  sleep "$INTERVAL"
done
