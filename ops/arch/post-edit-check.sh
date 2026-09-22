#!/bin/bash
# Claude Code PostToolUse hook (Edit|Write): a check the agent runs after every edit.
# Reads the tool input JSON on stdin, syntax-checks JS, runs the architecture ratchet for the touched area.
set -uo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
F=$(python3 -c 'import json,sys; d=json.load(sys.stdin); print((d.get("tool_input") or {}).get("file_path",""))' 2>/dev/null || true)
[ -z "$F" ] && exit 0
case "$F" in
  *.js|*.mjs|*.cjs) node --check "$F" 2>&1 | head -5 || exit 2 ;;
esac
case "$F" in
  "$REPO"/services/*/src/main/java/*|"$REPO"/apps/*) "$REPO/ops/arch/ratchet.sh" --quick "$F" || exit 2 ;;
esac
exit 0
