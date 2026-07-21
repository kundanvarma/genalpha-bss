#!/bin/bash
# Wire the secret gate as a pre-commit hook. Hooks don't travel with a
# clone — every contributor runs this once.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
HOOK="$REPO/.git/hooks/pre-commit"
cat > "$HOOK" <<'EOF'
#!/bin/bash
exec "$(git rev-parse --show-toplevel)/ops/scan-secrets.sh"
EOF
chmod +x "$HOOK"
echo "install-hooks: pre-commit now runs ops/scan-secrets.sh on every commit"
