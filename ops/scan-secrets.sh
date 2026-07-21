#!/bin/bash
# THE SECRET GATE: refuse any commit whose staged diff carries a
# real-secret shape. The dev fixtures in this repo (client secrets like
# "billing-secret", admin/admin, partner tokens like "genalpha-bank-token")
# are deliberate, documented stand-ins — this gate hunts the shapes REAL
# credentials have, which must never land in git:
#
#   sk-ant-...        Anthropic API keys
#   sk-...48+ chars   OpenAI-style keys
#   AKIA...           AWS access key ids
#   ghp_/gho_/ghs_    GitHub tokens
#   xox[bap]-         Slack tokens
#   BEGIN ... PRIVATE KEY
#
# Wire it as a pre-commit hook with ops/install-hooks.sh. To scan the
# working tree instead of the staged diff: ops/scan-secrets.sh --all
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

PATTERNS='sk-ant-[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9]{48,}|AKIA[0-9A-Z]{16}|gh[pos]_[A-Za-z0-9]{30,}|xox[bap]-[A-Za-z0-9-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----'

# STRUCTURAL refusals: some FILES are secret containers no matter what
# shapes they hold — terraform state stores every sensitive variable in
# cleartext (learned the hard way: a tfstate with the RDS password
# reached a public commit and forced a history rewrite + rotation).
STATE_FILES="$(git diff --cached --name-only | grep -E '\.tfstate(\.backup)?$|(^|/)\.terraform/' || true)"
if [ -n "$STATE_FILES" ]; then
  echo "scan-secrets: REFUSED — terraform state must never be committed:" >&2
  echo "$STATE_FILES" >&2
  exit 1
fi

if [ "${1:-}" = "--all" ]; then
  HITS="$(git grep -nE "$PATTERNS" -- . 2>/dev/null || true)"
  WHERE="the working tree"
else
  HITS="$(git diff --cached | grep -nE "^\+.*($PATTERNS)" || true)"
  WHERE="the staged diff"
fi

if [ -n "$HITS" ]; then
  echo "scan-secrets: REFUSED — a real-secret shape is in $WHERE:" >&2
  echo "$HITS" | head -10 >&2
  echo "scan-secrets: remove it (and rotate it — it may already be compromised)." >&2
  exit 1
fi
echo "scan-secrets: clean — no real-secret shapes in $WHERE"
