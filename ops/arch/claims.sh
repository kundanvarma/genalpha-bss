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
  # the suggested fix must actually work on the machine that reads it: BSD sed
  # (this laptop) has no \b, so a word-boundary pattern silently matches
  # nothing and the gate stays red while the fix "succeeds"
  [ "$n" = "$SUITES" ] || fail "README says $n suites; ops/e2e holds $SUITES" \
    "fix: python3 - <<'PY'
import re; p='README.md'; s=open(p).read()
open(p,'w').write(re.sub(r'\\b$n (end-to-end browser|Playwright) suites', r'$SUITES \\1 suites', s))
PY"
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

# ------------------------------------------------------ tenant-registry ----
# infra/tenants/tenants.yml is a git-tracked FIXTURE: the onboarding suites
# rewrite it through the API and run-all-suites.sh restores it from git, so the
# committed file is what the fleet boots from. It governs every tenant's
# issuer, JWKS URI, machine credentials, seam URLs and brand.
#
# Three tenants with GENERATED ids had accumulated in it (sc229066, ib249958,
# parity148420 — shadow_clone, import_base and price_parity each derive an id
# from Date.now()). Nothing could reference them by name; they were residue,
# and they had leaked into docker-compose.yml's OCS_NOTIFY_SECRETS as well.
# Each run of those suites would have added another.
tenants_err=$(mktemp)
tenants_out=$(python3 ops/arch/tenant_registry_check.py 2>"$tenants_err")
tenants_rc=$?
if ! printf '%s' "$tenants_out" | grep -q '^tenant-registry: '; then
  sed 's/^/         /' "$tenants_err" >&2
  fail "the tenant registry check did not run" "no sentinel line on stdout — see above"
elif [ "$tenants_rc" -ne 0 ]; then
  printf '%s\n' "$tenants_out" | sed 's/^/         /' >&2
  fail "the tenant registry declares something nobody decided on" \
       "add it to ops/arch/tenants-declared.txt with a reason, or remove it"
fi
rm -f "$tenants_err"

# --------------------------------------------------------- seed-coverage ----
# A suite that asserts on a fixture no seed in ops/seed/manifest.txt creates
# cannot pass on a fleet seeded from scratch — it can only pass where somebody
# once ran the right script by hand. That is how the laptop reached a state
# nobody could reproduce and the committed proof run sat at 122 of 258 suites.
#
# The order used to live in prose (ops/README.md, 13 of 54 scripts) and in a
# nightly workflow job gated on vars.BROWSER_PROOF_RUNNER, which has never been
# set — so it had never run. Against that 22-script list this check reports 79
# unreachable fixtures. Issue #261.
seedcov_err=$(mktemp)
seedcov_out=$(python3 ops/arch/seed_coverage_check.py 2>"$seedcov_err")
seedcov_rc=$?
if ! printf '%s' "$seedcov_out" | grep -q '^seed-coverage: '; then
  sed 's/^/         /' "$seedcov_err" >&2
  fail "the seed-coverage check did not run" "no sentinel line on stdout — see above"
elif [ "$seedcov_rc" -ne 0 ]; then
  printf '%s\n' "$seedcov_out" | sed 's/^/         /' >&2
  fail "a suite asserts on a fixture the seed path never creates" \
       "add the script to ops/seed/manifest.txt, or the literal to ops/arch/seed-coverage-allow.txt"
fi
rm -f "$seedcov_err"

# ------------------------------------------------------------- proof-run ----
# The gate above checks that the README's suite COUNT matches the number of
# files. It never checked that any of them PASS — which is how the committed
# proof run sat at 122 of 258 suites with 30 failures, dated 24 Sep 2026,
# while every document said "258 browser suites prove the behaviour end to
# end". The evidence itself was the stale claim.
#
# covered and green are RATCHETS in ops/arch/proof-baseline.txt: they may rise
# and never fall. A hard "all green" gate would block every unrelated pull
# request the day one suite goes flaky, and a gate people route around is
# worse than no gate.
#
# SENTINEL, the same lesson as billing and the drift check: a crashed checker
# prints nothing on stdout, and silence is exactly how a pass looks.
proof_err=$(mktemp)
proof_out=$(python3 ops/arch/proof_run_check.py 2>"$proof_err")
proof_rc=$?
if ! printf '%s' "$proof_out" | grep -q '^proof-run: '; then
  sed 's/^/         /' "$proof_err" >&2
  fail "the proof-run check did not run" "no sentinel line on stdout — see above"
elif [ "$proof_rc" -ne 0 ]; then
  printf '%s\n' "$proof_out" | sed 's/^/         /' >&2
  fail "the proof run regressed" "coverage or green count fell below ops/arch/proof-baseline.txt"
else
  printf '%s\n' "$proof_out" | head -1 | sed 's/^/claims:   /'
fi
rm -f "$proof_err"

# ------------------------------------------------------ securityconfig-drift ----
# Tokens are validated in EVERY component rather than at the gateway, which
# takes 39 copies of SecurityConfig — and 39 copies is 39 chances to drift.
# CLAUDE.md has carried "a drift check across the copies is a follow-up" since
# the hardening arc; this is it. Four invariants per copy (an authenticated
# terminal rule, the component validates its own tokens, stateless sessions, a
# multi-issuer resolver that consults the registry) plus the fleet's whole
# anonymous surface pinned to ops/security/public-paths.txt, so a new public
# path is a deliberate edit instead of a line in a diff nobody reads.
#
# SENTINEL, same lesson as billing above: a crashed checker prints nothing on
# stdout, and silence is exactly how a pass looks. The gate hangs on evidence
# that the check RAN, not merely that it said nothing.
drift_err=$(mktemp)
drift_out=$(python3 ops/security/security_config_drift.py 2>"$drift_err")
drift_rc=$?
if ! printf '%s' "$drift_out" | grep -q '^drift: \(clean\|FAIL\)'; then
  sed 's/^/         /' "$drift_err" >&2
  fail "the SecurityConfig drift check did not run" \
       "no sentinel line on stdout — see the output above"
elif [ "$drift_rc" -ne 0 ]; then
  printf '%s\n' "$drift_out" | sed 's/^/         /' >&2
  fail "the SecurityConfig copies have drifted" \
       "a new anonymous path belongs in ops/security/public-paths.txt, in the same commit"
fi
rm -f "$drift_err"
# ------------------------------------------------------- jackson3-deprecated ----
# The Jackson 2 -> 3 migration renamed the string accessors, and the old names
# were kept as DEPRECATED rather than removed, so every one of them compiles,
# passes its tests and behaves identically. That is exactly why eight of them
# survived the migration and were found later by CodeQL rather than by the
# build: nothing anywhere said no. Spring 7 did the same to the 422 matcher
# when HTTP renamed the status to Unprocessable Content.
DEPRECATED=$(grep -rnE '\.textNode\(|\.asText\(\)|JsonNode::asText|isUnprocessableEntity\(\)' \
    --include='*.java' services/*/src 2>/dev/null || true)
[ -z "$DEPRECATED" ] || fail "a deprecated Jackson 2 or Spring 6 accessor is back" \
    "use stringNode(), asString() and isUnprocessableContent(); found:
$DEPRECATED"

# --------------------------------------------------------- boot4-modules ----
# Spring Boot 4 split autoconfiguration into one module per technology. A
# service that uses a technology directly but does not depend on its module
# COMPILES AND STARTS — the autoconfiguration is simply absent. How that shows
# up depends on how the bean is reached, and only one of the three is loud:
#   bss-bridge injected KafkaTemplate       -> context failed, crash loop (seen)
#   flow declared @KafkaListener            -> SILENT; no listener container,
#                                              the component consumed nothing
#   ontology looked it up via ObjectProvider-> SILENT; receipts never published
# Two of those three would have shipped. Hence a gate: if the main sources
# reach for a technology, the pom must carry its module.
for d in services/*/; do
  svc=$(basename "$d")
  if grep -rlq 'org.springframework.kafka' "$d/src/main/java" 2>/dev/null; then
    grep -q 'spring-boot-kafka' "$d/pom.xml" \
      || fail "service '$svc' uses the Kafka API but its pom has no spring-boot-kafka" \
              "Boot 4 ships no Kafka autoconfiguration without it — a listener silently never subscribes"
  fi
  if grep -rlq 'org.springframework.web.client.RestClient' "$d/src/main/java" 2>/dev/null; then
    grep -q 'spring-boot-restclient' "$d/pom.xml" \
      || fail "service '$svc' uses RestClient but its pom has no spring-boot-restclient" \
              "Boot 4 ships no RestClient.Builder without it"
  fi
  if [ -n "$(find "$d/src/main/resources" -path '*db/migration*' -name '*.sql' 2>/dev/null | head -1)" ]; then
    grep -q 'spring-boot-flyway' "$d/pom.xml" \
      || fail "service '$svc' ships Flyway migrations but its pom has no spring-boot-flyway" \
              "Boot 4 runs no migrations without it — the schema is simply never created"
  fi
done

# ------------------------------------------------------------ wire-shape ----
# Jackson 3 (Spring Boot 4) flipped two defaults that are visible on the wire:
# SORT_PROPERTIES_ALPHABETICALLY and FAIL_ON_NULL_FOR_PRIMITIVES both became
# ENABLED. The first re-orders every TM Forum body (@type first, then a-z);
# the second throws on a payload that leaves a number out, which TM Forum
# payloads do by design. Spring Boot overrides neither, so every component
# pins both itself — and a component that forgets answers in a different
# shape from all the others, which is exactly the drift nobody notices until
# a consumer does. Hence a gate, not a convention.
for yml in services/*/src/main/resources/application.yml; do
  svc=$(echo "$yml" | sed 's|services/||; s|/src/.*||')
  grep -q '^      sort-properties-alphabetically: false$' "$yml" \
    || fail "service '$svc' does not pin spring.jackson.mapper.sort-properties-alphabetically: false" \
            "without it Jackson 3 re-orders that component's TM Forum bodies alphabetically"
  grep -q '^      fail-on-null-for-primitives: false$' "$yml" \
    || fail "service '$svc' does not pin spring.jackson.deserialization.fail-on-null-for-primitives: false" \
            "without it a sparse payload that omits a number throws instead of reading 0"
done
# -------------------------------------------------------- merge-markers ----
# A conflict marker that reached a commit. This is here because one did: a
# `git stash pop` left "<<<<<<< Updated upstream" in docs/adr/README.md and it
# was committed to main without anyone noticing, because nothing lints a
# markdown table and the eye skims a diff it believes it understands. Cheap to
# check, and silent otherwise.
MARKERS=$(grep -rlE '^(<<<<<<< |>>>>>>> |={7}$)' --include='*.java' --include='*.js' --include='*.mjs' \
          --include='*.md' --include='*.yml' --include='*.yaml' --include='*.sh' --include='*.sql' \
          --exclude-dir=node_modules --exclude-dir=target --exclude-dir=.git . 2>/dev/null \
          | grep -v 'ops/arch/claims.sh' || true)
[ -z "$MARKERS" ] || fail "a merge conflict marker is committed" "$MARKERS"

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
# Every suite a document NAMES must exist: a renamed suite must not leave a
# stale name behind as a "proof" (the semantic-maturity table cites suites).
for f in $(grep -ohE '`[a-z0-9_]+_test`' docs/ctk-conformance.md docs/capability-map.md 2>/dev/null | tr -d '`' | sort -u); do
  [ -f "ops/e2e/$f.js" ] || fail "docs cite suite '$f' but ops/e2e/$f.js does not exist"
done
# ...and a suite named by PATH, anywhere. A document that cites `ops/e2e/x.js`
# as its proof is making the strongest claim in this repository, so the file it
# names must be on disk — in EVERY document, not the two that happened to be
# checked first.
for p in $(grep -rhoE 'ops/e2e/[a-z0-9_]+\.js' README.md CLAUDE.md docs/*.md 2>/dev/null | sort -u); do
  [ -f "$p" ] || fail "a document cites '$p' as its proof, and that file does not exist"
done
# A suite NUMBER is prose — there is no registry — but it can never be larger
# than the number of suites there are. #129 landed on "a suite count nobody
# added up"; this is the same arithmetic one rung down.
for n in $(grep -rhoE 'suite #[0-9]+' README.md CLAUDE.md docs/*.md 2>/dev/null | grep -oE '[0-9]+' | sort -un); do
  [ "$n" -le "$SUITES" ] || fail "a document cites suite #$n; ops/e2e holds $SUITES suites" \
    "a suite number above the count cannot name a suite that exists"
done
# ...and where a suite DECLARES its own number in its header, the document that
# cites it must agree. This is as close to a registry as the tree has: it stops a
# document renumbering a suite on its own, which is how BR-11 nearly shipped a
# renamed #240 while the file still said #239. It cannot catch two FILES claiming
# one number (nine pairs already do) — that is named in docs/billing-revenue-desk.md.
disagree=$(python3 - <<'PY'
import glob, os, re
pat = re.compile(r'`?ops/e2e/([a-z0-9_]+)\.js`?\s*\((?:suite\s*)?#(\d+)\)')
for doc in ['README.md', 'CLAUDE.md'] + sorted(glob.glob('docs/*.md')):
    try:
        text = open(doc, encoding='utf-8').read()
    except OSError:
        continue
    for name, cited in pat.findall(text):
        path = f'ops/e2e/{name}.js'
        if not os.path.exists(path):
            continue                      # the existence check above owns this
        own = re.search(r'[Ss]uite #(\d+)', open(path, encoding='utf-8').read(1200))
        if own and own.group(1) != cited:
            print(f"{doc} cites {name} as #{cited}; {path} declares #{own.group(1)}")
PY
)
if [ -n "$disagree" ]; then
  while IFS= read -r line; do
    [ -n "$line" ] && fail "$line" "change the document or the suite's own header — not one of them"
  done <<EOF
$disagree
EOF
fi

# ------------------------------------------- the Billing & Revenue arc ----
# docs/billing-revenue-desk.md makes six structural claims a reader has every
# reason to trust and no way to check: the eight bill situations and their
# precedence, the four channel modules that read them and own no clock, the six
# destinations in lifecycle order, how many pages sit under the department, and
# nav.js sitting exactly on the front-end ceiling. Each is read off the source.
#
# The helper's SENTINEL is required, not optional: a checker that crashes prints
# nothing on stdout, and "no drift" is exactly how a pass looks. Hanging the
# gate on evidence that the checks RAN is the lesson of every stale claim here.
billing_err=$(mktemp)
billing_drift=$(python3 ops/arch/billing_claims.py 2>"$billing_err")
# grep -q, never `x=$(grep -c ...|| echo 0)`: grep -c PRINTS 0 and EXITS 1 on no
# match, so the `||` appends a second 0 and the string never equals "0" — the
# guard then believed every crash had run its checks. Found by crashing it.
if grep -q 'billing-claims: checked [1-9]' "$billing_err"; then
  billing_ran=yes
else
  billing_ran=no
fi
if [ "$billing_ran" = no ]; then
  # show what it said instead — a traceback is the answer to "why no sentinel"
  sed 's/^/         /' "$billing_err" >&2
fi
rm -f "$billing_err"
if [ "$billing_ran" = no ]; then
  fail "ops/arch/billing_claims.py produced no sentinel — it did not run its checks" \
       "an empty stdout from a crashed checker reads exactly like a pass"
elif [ -n "$billing_drift" ]; then
  while IFS= read -r line; do
    [ -n "$line" ] && fail "$line"
  done <<EOF
$billing_drift
EOF
fi

# A suite's downstreams are DATA (ops/e2e/suite-needs.txt) and the proof runner
# starts them with ONE `docker compose up`. A single name that is not a service
# makes that whole command fail — quietly, because the runner swallows it
# (`|| true`) so a fleet that is already up is not an error. The suite then runs
# against a stopped downstream and reports a bug that is not there. Found on
# 25 Sep with four bad names across two lines: "party" for party-account,
# "accounting" for revenue, "risk" for intelligence.
badneeds=$(python3 ops/arch/suite_needs_check.py)
[ -z "$badneeds" ] || fail "ops/e2e/suite-needs.txt names services docker-compose.yml does not define: $badneeds" \
  "fix: use the compose service name — docker compose config --services"

# react and react-dom are ONE version or the app is a blank page: React refuses
# to render a mismatched pair (error #527). Dependabot bumps them as two PRs,
# and merging one half blanked the CSR console and the mobile app on 25 Sep.
for lock in apps/*/package-lock.json; do
  mismatch=$(python3 - "$lock" <<'PY'
import json, sys
p = json.load(open(sys.argv[1])).get("packages", {})
r = (p.get("node_modules/react") or {}).get("version")
d = (p.get("node_modules/react-dom") or {}).get("version")
print(f"react {r} but react-dom {d}" if r and d and r != d else "")
PY
)
  [ -z "$mismatch" ] || fail "$lock pins $mismatch — React refuses a mismatched pair (blank page); bump both together"
done

# ------------------------------------- no JSON box on a commercial page ----
# docs/engineering-conventions.md §4 says a page a commercial user opens never
# carries a raw `jsontext` field, names how many are left, and says the ratchet
# holds the line. All three halves are machine-checked here, because a rule with
# a remembered number is the shape every stale claim on 23 Sep had: the metric
# must exist, the baseline must PIN it (an unpinned metric compares against
# nothing and can never go red), and the number in the prose is read off the
# tree by the ratchet itself, never typed.
# Hung on the METRIC, not on the sentence: deleting the conventions row would
# otherwise switch this whole block off in silence, which is the same failure
# one rung up. A metric with no rule behind it is drift too.
if grep -q 'commercialJsonBoxes' ops/arch/ratchet.sh; then
  grep -q 'Never a JSON box on a page a commercial user opens' docs/engineering-conventions.md \
    || fail "ops/arch/ratchet.sh counts commercialJsonBoxes but docs/engineering-conventions.md §4 states no such rule" \
            "a metric nobody wrote down is a number, not a convention"
  grep -q '"commercialJsonBoxes"' ops/arch/baseline.json \
    || fail "ops/arch/baseline.json does not pin commercialJsonBoxes" \
            "an unpinned metric compares against nothing — the rule reads enforced and is not"
  BOXES=$(ops/arch/ratchet.sh --metric commercialJsonBoxes 2>/dev/null || echo unreadable)
  grep -qE '[0-9]+ JSON box(es)? remain' docs/engineering-conventions.md \
    || fail "conventions §4 carries the JSON-box rule but states no count" \
            "write 'N JSON boxes remain' in the Check column so the number can be checked"
  # anywhere the count is stated, not only in the conventions file
  CLAIMED_BOXES=$(grep -rhoE '[0-9]+ JSON box(es)? remain' README.md CLAUDE.md docs/*.md 2>/dev/null | grep -oE '^[0-9]+' | sort -u)
  for n in $CLAIMED_BOXES; do
    [ "$n" = "$BOXES" ] || fail "a document says $n JSON boxes remain on commercial pages; the console has $BOXES" \
      "fix: ops/arch/ratchet.sh --metric commercialJsonBoxes is the source; change the number in docs/engineering-conventions.md"
  done
fi

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
every sellable spec names a cfs|cfs_check.py
catalog and orchestrator agree|cfs_check.py
signed keyless with cosign|cosign sign
attested with build provenance|attest-build-provenance
CLAIMS

# --------------------------------------------------------------- verdict ----
if [ "$FAILED" -gt 0 ]; then
  echo "" >&2
  echo "claims: $FAILED claim(s) drifted from the code. Change the code or change the claim." >&2
  exit 1
fi
echo "claims: clean — $SUITES suites, $CTK_ROWS CTKs certified, Spring Boot $BOOT, Java $CI_JAVA source / $RT_JAVA runtime, secure defaults off, every gate can fail"
exit 0
