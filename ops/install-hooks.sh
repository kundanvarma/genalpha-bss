#!/bin/bash
# Wire the secret gate as a pre-commit hook. Hooks don't travel with a
# clone — every contributor runs this once.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
HOOK="$REPO/.git/hooks/pre-commit"
cat > "$HOOK" <<'EOF'
#!/bin/bash
R="$(git rev-parse --show-toplevel)"
"$R/ops/scan-secrets.sh" || exit $?
exec "$R/ops/arch/ratchet.sh"
EOF
chmod +x "$HOOK"
echo "install-hooks: pre-commit now runs ops/scan-secrets.sh and ops/arch/ratchet.sh on every commit"
