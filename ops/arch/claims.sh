#!/usr/bin/env bash
# THE CLAIMS GATE: the repository proves its own prose.
#
# Every finding that an outside reviewer landed on 23 Sep 2026 had one shape:
# a sentence in a document that the code did not honour, and no tool that
# compared the two.
#
#   "agentic commerce off-by-default"   — the gateway defaulted to `full`
#   "146 / 114 / 97 browser suites"     — there were 227 files on disk
#   "Java 17 · Spring Boot 3.2"         — the POMs were on 3.5.7
#   "accessibility enforced in CI"      — no workflow ran axe
#   a secret gate whose --all mode      — could not see its own structural rule
#
# None of those is a hard bug. Together they are the expensive kind of debt,
# because they are exactly what a buyer's technical due diligence reads first,
# and a claim that turns out to be stale makes every other claim suspect.
#
# The ratchet counts code debt and only lets it fall. THIS counts the distance
# between what we say and what is true, and only lets it be zero.
#
#   ops/arch/claims.sh          check, exit 1 on drift
#   ops/arch/claims.sh --fix    print the corrected lines (never edits for you)
#
# Adding a claim: one CHECK block, one grep, one comparison. If a claim cannot
# be checked by a machine, it does not belong in the README as a fact.
set -u
cd "$(dirname "$0")/../.."

FIX="${1:-}"
FAILED=0

fail() {
  FAILED=$((FAILED + 1))
  echo "claims: DRIFT — $1" >&2
  [ -n "${2:-}" ] && echo "         $2" >&2
  return 0
}

# ---------------------------------------------------------------- suites ----
# The proof runner executes every ops/e2e/*_test.js. That number is a fact;
# every sentence that states it must state the same one.
SUITES=$(find ops/e2e -maxdepth 1 -name '*_test.js' | wc -l | tr -d ' ')
CLAIMED=$(grep -oE '\b[0-9]+ (end-to-end browser|Playwright) suites' README.md | grep -oE '^[0-9]+' | sort -u)
for n in $CLAIMED; do
  [ "$n" = "$SUITES" ] || fail "README says $n suites; ops/e2e holds $SUITES" \
    "fix: sed -i '' 's/\\b$n \\(end-to-end browser\\|Playwright\\) suites/$SUITES \\1 suites/g' README.md"
done
RUN_ALL=$(grep -oE 'all \*\*[0-9]+ suites\*\*' README.md | grep -oE '[0-9]+' || true)
for n in $RUN_ALL; do
  [ "$n" = "$SUITES" ] || fail "README's proof-runner line says $n suites; ops/e2e holds $SUITES"
done

# ------------------------------------------------------------ secure-off ----
# Capabilities that must be DARK unless an operator asks. The application
# default is the production default: a deployment that forgets the variable
# must get the safe answer. Demo opt-in belongs in docker-compose.yml only.
# Raw exposure sends the UNREDACTED prompt to an external model. No tenant may
# carry a literal `true` in a file that ships: an operator who wants it sets the
# environment variable, deliberately, and the AI ledger records that they did.
RAW=$(grep -n '^\s*ai-raw-exposure:\s*true\s*$' infra/tenants/tenants.yml 2>/dev/null || true)
[ -z "$RAW" ] || fail "a tenant ships ai-raw-exposure: true — unredacted prompts leave the process" "$RAW"

for key in agent-commerce; do
  BAD=$(grep -rn "^\s*${key}: \${[A-Z_]*:\(full\|discovery\|on\|true\)}" \
        services/gateway/src/main/resources/application.yml infra/tenants/tenants.yml 2>/dev/null || true)
  [ -z "$BAD" ] || fail "'$key' defaults to an ON value in config — it must default off" "$BAD"
done
# ...and EVERY service that reads the key must carry the demo opt-in.
# infra/tenants/tenants.yml is read by ~41 services, so a ${VAR:default} in
# it resolves PER CONTAINER: a service without the variable silently answers
# differently about the same tenant. That is how flipping this default to
# `off` turned /acp/product_feed dark while the gateway still said `full` —
# the gateway had the variable and product-catalog did not.
READERS=$(grep -rln 'agentCommerce' services/*/src/main/java/ 2>/dev/null \
          | sed 's|services/||; s|/src/main/java.*||' | sort -u)
for svc in $READERS; do
  awk -v s="  $svc:" '
    $0 == s { inside = 1; next }
    /^  [a-z0-9-]+:$/ { inside = 0 }
    inside && /AGENT_COMMERCE: / { found = 1 }
    END { exit !found }' docker-compose.yml \
    || fail "service '$svc' reads agentCommerce but docker-compose.yml gives it no AGENT_COMMERCE" \
            "without it that container resolves \${AGENT_COMMERCE:off} to off and disagrees with the gateway"
done

# ----------------------------------------------------------------- stack ----
BOOT=$(grep -m1 -A2 'spring-boot-starter-parent' services/product-catalog/pom.xml | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
grep -q "Spring Boot $BOOT" README.md \
  || fail "README's Stack line does not say Spring Boot $BOOT (the POM's version)" \
          "fix: update the '## Stack' line in README.md"

CI_JAVA=$(grep -m1 -A3 'setup-java' .github/workflows/ci.yml | grep -oE "java-version: '[0-9]+'" | grep -oE '[0-9]+')
grep -qE "Java $CI_JAVA source" README.md \
  || fail "README does not state Java $CI_JAVA source (what CI actually compiles with)"

# The RUNTIME JDK: the images run whatever the Dockerfiles say, CI compiles with
# something else, and the README states both — so both are read from source.
# The runtime image is exercised by the PR smoke (ops/ci-fleet.sh builds and
# boots the real images), which is what makes "Java N runtime" a tested claim.
RT_JAVA=$(grep -hoE '^FROM eclipse-temurin:[0-9]+' services/*/Dockerfile 2>/dev/null | grep -oE '[0-9]+$' | sort -u)
[ "$(echo "$RT_JAVA" | wc -l | tr -d ' ')" = 1 ] \
  || fail "service Dockerfiles run more than one runtime JDK: $(echo $RT_JAVA)" "one runtime for the fleet, or say which service runs which"
grep -qE "Java $RT_JAVA runtime image" README.md \
  || fail "README does not state Java $RT_JAVA runtime image (what services/*/Dockerfile actually run)"

# ------------------------------------------------------------------ CTK ----
# "N official TM Forum CTKs" in the README is the number of rows in the
# scorecard's Certified table — a certification is a row with a date and a
# receipt there, not a sentence here. The scorecard also carries the current,
# grown-dataset status; the README must not read as if that were zero too.
# a kit is one TMF API: TMF640 has two collections (v4 and R18.5) and counts once
CTK_ROWS=$(awk '/^## Certified/{f=1;next} /^## /{f=0} f && /^\| \*?\*?[a-z]/' docs/ctk-conformance.md | grep -oE 'TMF[0-9]+' | sort -u | wc -l | tr -d ' ')
CTK_CLAIMED=$(grep -oE '\b[0-9]+ official TM Forum CTKs' README.md | grep -oE '^[0-9]+' | sort -u)
for n in $CTK_CLAIMED; do
  [ "$n" = "$CTK_ROWS" ] || fail "README says $n official TM Forum CTKs; docs/ctk-conformance.md certifies $CTK_ROWS" \
    "fix: the Certified table is the source; change the README number"
done
grep -qiE "CTKs certified" README.md \
  || fail "README must say the CTKs are CERTIFIED (each on the dataset of its day), not that they pass now — see docs/ctk-conformance.md's drift note"

# ------------------------------------------------------------ gates bite ----
# A gate that cannot fail is worse than no gate: it is believed. Every script
# we call a gate must have a path that exits non-zero — in shell, or in an
# embedded interpreter under `set -e` (the ratchet fails via sys.exit(2), and
# an earlier version of THIS check called that a missing gate; verify a gate
# by running it against a deliberately broken fixture, not by reading it).
for gate in ops/run-all-suites.sh ops/scan-secrets.sh ops/arch/ratchet.sh ops/arch/claims.sh; do
  [ -f "$gate" ] || { fail "gate $gate is missing"; continue; }
  grep -qE '(^|[^_a-z])(exit [1-9]|sys\.exit\([1-9]|process\.exit\([1-9])' "$gate" \
    || fail "$gate has no non-zero exit path — it cannot fail, so it is not a gate"
done

# ------------------------------------------------- a fresh install works ----
# Keycloak's KEYCLOAK_ROLE.DESCRIPTION is varchar(255). A longer one imports
# fine on a laptop whose realm was imported months ago and never fails again —
# and refuses to start on an EMPTY database, which is what a new contributor,
# a fresh box and CI all have. `communication:admin` in nova sat at 270 chars
# and broke the first CI run that ever booted Keycloak from nothing.
python3 - <<'PY' || FAILED=$((FAILED + 1))
import json, glob, sys
bad = []
for f in sorted(glob.glob('infra/keycloak/*realm*.json')):
    d = json.load(open(f))
    rs = d.get('roles', {}) or {}
    groups = [rs.get('realm') or []] + list((rs.get('client') or {}).values())
    for roles in groups:
        for r in roles or []:
            if len(r.get('description') or '') > 255:
                bad.append(f"{f}: role '{r.get('name')}' description is "
                           f"{len(r['description'])} chars (Keycloak's column is varchar(255))")
for b in bad:
    print(f"claims: DRIFT — {b}", file=sys.stderr)
sys.exit(1 if bad else 0)
PY

# --------------------------------------------------- CI runs what we claim ---
# If a document says a check is enforced in CI, a workflow must invoke it.
while IFS='|' read -r phrase invocation; do
  [ -z "$phrase" ] && continue
  # CLAUDE.md counts too: it is the densest collection of claims in the repo
  if grep -rqi "$phrase" README.md CLAUDE.md docs/*.md 2>/dev/null; then
    # a workflow may run the thing directly, or run a list that names it
    grep -rqi "$invocation" .github/workflows/ ops/e2e/smoke-suites.txt 2>/dev/null \
      || fail "docs claim \"$phrase\" but no workflow (or smoke list) runs '$invocation'"
  fi
done <<'CLAIMS'
accessibility enforced in ci|a11y_test
the architecture ratchet is green in ci|ratchet.sh
the chart is template-verified|kubeconform
runs on every pull request against the smoke fleet|rls_check.py
CLAIMS

# --------------------------------------------------------------- verdict ----
if [ "$FAILED" -gt 0 ]; then
  echo "" >&2
  echo "claims: $FAILED claim(s) drifted from the code. Change the code or change the claim." >&2
  exit 1
fi
echo "claims: clean — $SUITES suites, $CTK_ROWS CTKs certified, Spring Boot $BOOT, Java $CI_JAVA source / $RT_JAVA runtime, secure defaults off, every gate can fail"
exit 0
