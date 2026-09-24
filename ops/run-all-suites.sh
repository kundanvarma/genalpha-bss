#!/usr/bin/env bash
# The proof run: every suite, serially, one report. The claim "every suite
# green" becomes a fact with a receipt — or an honest list of what broke.
# The suite count is never written down here: it is whatever ops/e2e/*_test.js
# holds, and ops/arch/claims.sh makes the README say the same number.
#
# Hard-won operational shape (first runs taught all of this):
#  - suites run SERIALLY (known cross-talk under parallelism)
#  - onboarding suites first: a recreated Keycloak forgets dynamic realms
#  - a READINESS GATE before each suite: on a memory-tight VM the OOM killer
#    can take a JVM mid-sweep; restart policies bring it back — the runner
#    waits instead of cascading twenty false failures
#  - the surge controller sleeps for the sweep (it auto-hires workers
#    against suite backlog and races their assertions) and wakes only for
#    the closed-loop suite that tests it
#  - failures get ONE automatic retry pass at the end; attempts are
#    recorded, verdicts stay honest
#
# EXIT STATUS IS THE VERDICT. This script is a gate, not a report: it exits
# 1 when any suite ends red, 2 when the fleet never came ready. It used to
# print the failures and exit 0, which meant any automation that called it
# read a green shell status over a red run — the worst kind of bug, because
# it is silent and it flatters. A retry-pass is reported as FLAKY, never
# folded into the clean count; PROOF_STRICT=1 makes flaky fail too.
#   PROOF_CONTINUE_ON_NOT_READY=1   diagnose against a half-up fleet (never in CI)
set -u
cd "$(dirname "$0")/.."
export PATH=/opt/homebrew/bin:$PATH

# the suites are Playwright — make sure it is installed before we judge all
# of them (a fresh clone has no node_modules; this is idempotent and quick)
if [ ! -d ops/e2e/node_modules/playwright ]; then
  echo "[$(date +%H:%M:%S)] installing Playwright for the suites ..."
  ( cd ops/e2e && npm i playwright >/dev/null 2>&1 && npx playwright install chromium >/dev/null 2>&1 )
fi

RESULTS_DIR="ops/e2e/.proof-run"
mkdir -p "$RESULTS_DIR"
RESULTS="$RESULTS_DIR/results.tsv"
: > "$RESULTS"

PREAMBLE="operator_form_test third_operator_test"
CAP=2100
SUITE_N=0   # ROLLING_RESET (optional env): script run between suites every
            # ROLLING_EVERY suites — a memory-tight VM's relief valve

wait_ready() {
  # the fleet's two front doors: the IdP and the gateway
  local deadline=$((SECONDS + 300))
  while [ $SECONDS -lt $deadline ]; do
    local kc gw
    kc=$(curl -s -m 5 -o /dev/null -w '%{http_code}' -X POST \
      http://127.0.0.1:8085/realms/bss/protocol/openid-connect/token \
      -H 'Content-Type: application/x-www-form-urlencoded' \
      -d 'grant_type=password&client_id=bss-demo&username=demo&password=demo' 2>/dev/null)
    gw=$(curl -s -m 5 -o /dev/null -w '%{http_code}' \
      'http://127.0.0.1:8080/tmf-api/productCatalogManagement/v4/productOffering?limit=1' 2>/dev/null)
    [ "$kc" = "200" ] && [ "$gw" = "200" ] && return 0
    echo "[$(date +%H:%M:%S)] waiting for the fleet (kc=$kc gw=$gw)"
    sleep 10
  done
  # A dead front door is a FAILED RUN, not a footnote. Diagnosing against a
  # half-up fleet is a deliberate act, and it names itself.
  if [ -n "${PROOF_CONTINUE_ON_NOT_READY:-}" ]; then
    echo "[$(date +%H:%M:%S)] fleet never came ready — proceeding anyway (PROOF_CONTINUE_ON_NOT_READY)"
    return 0
  fi
  echo "[$(date +%H:%M:%S)] fleet never came ready after 300s" >&2
  return 1
}

# A suite's downstreams, from ops/e2e/suite-needs.txt: start them (idempotent
# on a fleet that already has them) and wait until each reports healthy. A
# suite that fails because its service was not running is not a finding.
ensure_needs() {
  local needs
  needs=$(awk -v s="$1" '$1==s {$1=""; print}' ops/e2e/suite-needs.txt 2>/dev/null)
  [ -z "$needs" ] && return 0
  # shellcheck disable=SC2086
  docker compose up -d --no-deps $needs >/dev/null 2>&1 || true
  for svc in $needs; do
    local c="bss-$svc" i
    for i in $(seq 1 24); do
      case "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$c" 2>/dev/null)" in
        healthy|running) break ;;
      esac
      sleep 5
    done
  done
}

# infra/tenants/tenants.yml is a git-tracked FIXTURE that the onboarding suites
# (operator_form, third_operator) rewrite through the API — third_operator
# re-onboards "fjord" as a clone of nova and fjord's ai-visibility flips from
# dark to search-only, which made geo_discoverability red on every run. The
# committed file is the truth: restore it after the preamble and at the end.
restore_tenants_fixture() {
  git checkout -- infra/tenants/tenants.yml 2>/dev/null || return 0
  echo "[$(date +%H:%M:%S)] tenants.yml restored from git (the fleet re-reads it within a refresh interval)"
}

before_suite() {
  ensure_needs "$1"
  case "$1" in
    closed_loop_test|workforce_runtime_test)
      # both drive the controller itself; agentic_workforce needs it PARKED
      # (its dashboard-hire leg asserts the credentials path)
      docker start bss-worker-controller >/dev/null 2>&1 || true
      sleep 8 ;;
  esac
}
after_suite() {
  case "$1" in
    closed_loop_test|workforce_runtime_test)
      docker stop bss-worker-controller >/dev/null 2>&1 || true
      docker ps --format '{{.Names}}' | grep '^wf-' | xargs -r docker rm -f >/dev/null 2>&1 || true ;;
  esac
}

NOT_READY_STREAK=0

run_one() {
  local name="$1" attempt="${2:-1}"
  if ! wait_ready; then
    # record it as a verdict so the receipt shows WHY, then stop burning
    # forty minutes on a fleet that is plainly down
    printf '%s\t%s\t%s\t%s\t%s\n' "$name" "notready" "0" "-" "$attempt" >> "$RESULTS"
    echo "[$(date +%H:%M:%S)] notready ${name} (attempt $attempt)"
    NOT_READY_STREAK=$((NOT_READY_STREAK + 1))
    if [ "$NOT_READY_STREAK" -ge 3 ]; then
      echo "ABORTED: the fleet failed readiness three times running" >&2
      exit 2
    fi
    return 0
  fi
  NOT_READY_STREAK=0
  before_suite "$name"
  local file="ops/e2e/${name}.js"
  local log="$RESULTS_DIR/${name}.log"
  local start end status
  start=$(date +%s)
  perl -e 'alarm shift; exec @ARGV' "$CAP" node "$file" > "$log" 2>&1
  status=$?
  end=$(date +%s)
  local verdict=pass
  [ $status -ne 0 ] && verdict=fail
  [ $status -eq 142 ] && verdict=timeout
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$verdict" "$((end - start))" "$status" "$attempt" >> "$RESULTS"
  echo "[$(date +%H:%M:%S)] $verdict ${name} ($((end - start))s, attempt $attempt)"
  after_suite "$name"
  # a young, fast fleet can out-run its own per-subject rate limiter when
  # suites go back-to-back — the pacing that slow runs used to provide
  sleep 30
  SUITE_N=$((SUITE_N + 1))
  if [ -n "${ROLLING_RESET:-}" ] && [ $((SUITE_N % ${ROLLING_EVERY:-25})) -eq 0 ]; then
    bash "$ROLLING_RESET" || true
    wait_ready
  fi
}

docker stop bss-worker-controller >/dev/null 2>&1 || true

# collect the debris of dead runs before anything else — see the file header
node ops/e2e/debris_sweep.js || true

for s in $PREAMBLE; do run_one "$s"; done
restore_tenants_fixture
for f in ops/e2e/*_test.js; do
  name=$(basename "$f" .js)
  case " $PREAMBLE " in *" $name "*) continue;; esac
  run_one "$name"
done

# ---- the second chance: transients deserve one retry, honestly labeled ----
FAILED=$(awk -F'\t' '$2 != "pass" { print $1 }' "$RESULTS")
if [ -n "$FAILED" ]; then
  echo "RETRY PASS: $(echo "$FAILED" | wc -l | tr -d ' ') suite(s)"
  for name in $FAILED; do run_one "$name" 2; done
fi

# ---- the verdict ----------------------------------------------------------
# Three buckets, not two. A suite that went red and then green on the retry
# is FLAKY: it is not a failure, and it is not the same fact as a first-pass
# green. Folding it into one number is how a proof run starts lying quietly.
CLEAN=$(awk -F'\t' '$5 == 1 && $2 == "pass" { c[$1] = 1 } END { n = 0; for (s in c) n++; print n }' "$RESULTS")
FLAKY_LIST=$(awk -F'\t' '$2 == "pass" { ok[$1] = 1 } $5 == 1 && $2 != "pass" { bad[$1] = 1 }
                         END { for (s in bad) if (s in ok) print s }' "$RESULTS" | sort)
FAILED_LIST=$(awk -F'\t' '$2 == "pass" { ok[$1] = 1 } { seen[$1] = 1 }
                          END { for (s in seen) if (!(s in ok)) print s }' "$RESULTS" | sort)
TOTAL=$(awk -F'\t' '{ seen[$1] = 1 } END { n = 0; for (s in seen) n++; print n }' "$RESULTS")
FLAKY_N=$(printf '%s' "$FLAKY_LIST" | grep -c . || true)
FAILED_N=$(printf '%s' "$FAILED_LIST" | grep -c . || true)

restore_tenants_fixture
echo "PROOF-RUN COMPLETE: $CLEAN/$TOTAL clean · $FLAKY_N flaky · $FAILED_N failed"
[ "$FLAKY_N" -gt 0 ] && { echo "FLAKY (passed only on retry):"; echo "$FLAKY_LIST"; }
[ "$FAILED_N" -gt 0 ] && { echo "FAILED:"; echo "$FAILED_LIST"; }

# a receipt tied to a commit, so "all green" can be checked instead of believed
printf '{\n  "sha": "%s",\n  "when": "%s",\n  "total": %s,\n  "clean": %s,\n  "flaky": %s,\n  "failed": %s\n}\n' \
  "$(git rev-parse HEAD 2>/dev/null || echo unknown)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  "$TOTAL" "$CLEAN" "$FLAKY_N" "$FAILED_N" > "$RESULTS_DIR/summary.json"

[ "$FAILED_N" -gt 0 ] && exit 1
[ -n "${PROOF_STRICT:-}" ] && [ "$FLAKY_N" -gt 0 ] && exit 1
exit 0
