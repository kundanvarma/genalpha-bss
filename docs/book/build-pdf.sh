#!/usr/bin/env bash
# Rebuild Verify-Everything.pdf from book.html.
# Playwright's Chromium lives in ops/e2e/node_modules, so we run the renderer there.
set -euo pipefail
export PATH=/opt/homebrew/bin:$PATH
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT/ops/e2e"
cp "$ROOT/docs/book/make-pdf.mjs" ./_make-pdf.mjs
trap 'rm -f ./_make-pdf.mjs' EXIT
node ./_make-pdf.mjs "$ROOT"
echo "PDF: $ROOT/docs/book/Verify-Everything.pdf"
