#!/bin/bash
# Wire the secret gate as a pre-commit hook. Hooks don't travel with a
# clone — every contributor runs this once.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
HOOK="$REPO/.git/hooks/pre-commit"
cat > "$HOOK" <<'EOF'
#!/bin/bash
R="$(git rev-parse --show-toplevel)"
# Run every gate PRESENT ON THIS CHECKOUT. A gate that does not exist on the
# branch you are standing on is not a failure — claims.sh arrived on a feature
# branch and the hook then died on main with "No such file or directory", which
# is exactly how people learn to reach for --no-verify. A missing gate is
# skipped; a failing gate still stops the commit.
for gate in ops/scan-secrets.sh ops/arch/ratchet.sh ops/arch/claims.sh; do
  [ -x "$R/$gate" ] || continue
  "$R/$gate" || exit $?
done
EOF
chmod +x "$HOOK"
echo "install-hooks: pre-commit now runs whichever of ops/scan-secrets.sh, ops/arch/ratchet.sh and ops/arch/claims.sh exist on the current checkout"
