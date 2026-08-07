#!/usr/bin/env bash
# The proof run: every suite, serially, one report. The claim "eighty-four
# suites, all green" becomes a fact with a receipt — or an honest list of
# what broke.
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
set -u
cd "$(dirname "$0")/.."
export PATH=/opt/homebrew/bin:$PATH

# the suites are Playwright — make sure it is installed before we judge 87
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
  echo "[$(date +%H:%M:%S)] fleet never came ready — proceeding anyway"
}

before_suite() {
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

run_one() {
  local name="$1" attempt="${2:-1}"
  wait_ready
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
}

docker stop bss-worker-controller >/dev/null 2>&1 || true

for s in $PREAMBLE; do run_one "$s"; done
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

# final verdict per suite = best attempt
PASS=$(awk -F'\t' '{ if ($2 == "pass") ok[$1] = 1 } END { n = 0; for (s in ok) n++; print n }' "$RESULTS")
TOTAL=$(awk -F'\t' '{ seen[$1] = 1 } END { n = 0; for (s in seen) n++; print n }' "$RESULTS")
echo "PROOF-RUN COMPLETE: $PASS/$TOTAL passed"
awk -F'\t' '{ v[$1] = ($2 == "pass") ? "pass" : v[$1] "x" } END { for (s in v) if (v[s] != "pass") print s }' "$RESULTS"
