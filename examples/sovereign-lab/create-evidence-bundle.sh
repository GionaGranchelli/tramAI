#!/usr/bin/env bash
set -euo pipefail

TIMESTAMP="$(date -u +"%Y-%m-%dT%H%M%SZ")"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_DIR="$ROOT_DIR/evidence-template"
OUT_DIR="$ROOT_DIR/build/evidence-bundles/$TIMESTAMP"

mkdir -p "$OUT_DIR/reports"

cp "$TEMPLATE_DIR"/*.md "$OUT_DIR/"

touch "$OUT_DIR/reports/.gitkeep"

{
  echo "# Sovereign Lab Evidence Bundle"
  echo ""
  echo "Created UTC: $TIMESTAMP"
  echo "Git commit: $(git -C "$ROOT_DIR/../.." rev-parse HEAD 2>/dev/null || echo "unknown")"
  echo "Git branch: $(git -C "$ROOT_DIR/../.." rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")"
  echo ""
  echo "This bundle is a local operator evidence scaffold."
  echo "It does not certify production readiness or define performance guarantees."
} > "$OUT_DIR/README.md"

echo "Created sovereign lab evidence bundle:"
echo "$OUT_DIR"
