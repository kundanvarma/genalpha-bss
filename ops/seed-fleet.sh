#!/usr/bin/env bash
# THE SEED PATH, RUNNABLE. An empty fleet to the state the suites assert on.
#
#   ops/seed-fleet.sh            # the whole path (a proof run wants this)
#   ops/seed-fleet.sh smoke      # the small tier the PR gate runs
#   ops/seed-fleet.sh --list     # what would run, in order, without running it
#
# The order lives in ops/seed/manifest.txt, which ops/arch/seed_coverage_check.py
# also reads — one file, so the runner and the gate cannot disagree.
#
# WHY THIS EXISTS. The order used to live in two places and neither was
# runnable: ops/README.md documented 13 of 54 scripts in prose, and
# browser-proof.yml carried a 22-script list inside its nightly job — a job
# gated on vars.BROWSER_PROOF_RUNNER, which has never been set, so every
# scheduled run reports "skipped" and that order had never once executed. The
# laptop's state was accumulated by hand instead, which is why the committed
# proof run sat at 122 of 258 suites since 24 September and could not be
# reproduced. Issue #261.
#
# EXIT STATUS IS THE VERDICT, and that is not decoration: ops/demo-reset.sh
# ends every seed step with `|| echo "(step skipped)"` and then prints "demo
# reset complete", so a failed seed there reads exactly like a pass. This stops
# at the first failure and says which script, because later seeds build on
# earlier ones and a cascade of errors hides the one that mattered.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
MANIFEST="$HERE/seed/manifest.txt"
PY=${PY:-python3}

TIER=full
LIST_ONLY=0
case "${1:-}" in
  smoke)   TIER=smoke ;;
  full|'') TIER=full ;;
  --list)  LIST_ONLY=1 ;;
  *) echo "usage: $(basename "$0") [smoke|full|--list]" >&2; exit 2 ;;
esac
[ "${2:-}" = "--list" ] && LIST_ONLY=1

[ -f "$MANIFEST" ] || { echo "seed: FAIL — no manifest at $MANIFEST" >&2; exit 2; }

# tier, script, args — comments and blank lines dropped
entries() {
  while IFS= read -r raw; do
    line=${raw%%#*}
    line=$(printf '%s' "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
    [ -z "$line" ] && continue
    set -- $line
    t=$1; shift
    [ "$TIER" = "smoke" ] && [ "$t" != "smoke" ] && continue
    printf '%s\n' "$*"
  done < "$MANIFEST"
}

TOTAL=$(entries | grep -c . || true)
if [ "$LIST_ONLY" = "1" ]; then
  echo "seed: $TOTAL script(s) in the '$TIER' tier, in order:"
  entries | nl -ba -w4 -s'  ' | sed 's/^/  /'
  exit 0
fi

cd "$ROOT"

# A seed writes through the gateway into the service it is seeding. If the fleet
# is not up, every script fails the same way and the cascade hides which one
# mattered — so say it once, here. The full tier needs the FULL fleet: surveyed
# on 8 Oct against a 46-of-96 laptop, 37 of 51 passed and all 14 failures were
# services that were simply stopped (knowledge, campaign, sigscale-ocs), not
# script faults.
GW=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "${GATEWAY:-http://localhost:8080}/actuator/health" 2>/dev/null || true)
if [ "$GW" != "200" ]; then
  echo "seed: FAIL — the gateway is not answering (${GATEWAY:-http://localhost:8080} said ${GW:-nothing})." >&2
  echo "      seeds write through it. Start the fleet first: docker compose up -d" >&2
  exit 2
fi

echo "seed: $TOTAL script(s), tier '$TIER'"
N=0; FAILED=""
while IFS= read -r spec; do
  [ -z "$spec" ] && continue
  set -- $spec
  script=$1; shift
  N=$((N + 1))
  printf '  [%2d/%2d] %-30s' "$N" "$TOTAL" "$script"
  start=$(date +%s)
  if out=$("$PY" "ops/seed/$script.py" "$@" 2>&1); then
    printf 'ok   %ss\n' "$(( $(date +%s) - start ))"
  else
    printf 'FAILED\n'
    printf '%s\n' "$out" | tail -12 | sed 's/^/          /'
    FAILED="$script"
    break
  fi
done < <(entries)

if [ -n "$FAILED" ]; then
  echo "seed: FAIL — $FAILED (step $N of $TOTAL). Later seeds build on earlier" >&2
  echo "      ones, so this stops here rather than cascading." >&2
  exit 1
fi
echo "seed: ok — $N script(s) ran, tier '$TIER'"
