#!/bin/bash
cd /home/gionag/Development/aurora

echo "=== EXISTING DOCUMENTATION CHECK ==="
echo ""

echo "=== Spec files ==="
find docs/specs/ -name '*.md' 2>/dev/null | sort || echo "(none found)"

echo ""
echo "=== ADR files ==="
find docs/adr/ -name '*.md' 2>/dev/null | sort || echo "(none found)"

echo ""
echo "=== Top-level docs ==="
ls docs/*.md 2>/dev/null || echo "(none found)"

echo ""
echo "=== docs/ directory tree ==="
find docs/ -type f -name '*.md' 2>/dev/null | sort

echo ""
echo "=== Module READMEs (should be NO for all) ==="
for mod in tramai-core tramai-engine tramai-structured tramai-observability tramai-orchestration tramai-ollama tramai-openai tramai-anthropic tramai-standalone tramai-spring tramai-testing tramai-bom tramai-scheduler tramai-server tramai-mcp tramai-platform tramai-dashboard; do
  if [ -f "$mod/README.md" ]; then
    echo "  $mod: YES"
  fi
done
echo "  (all silent = no READMEs exist)"
