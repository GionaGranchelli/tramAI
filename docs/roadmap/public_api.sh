#!/bin/bash
# Phase 0 Audit - Public API Surface Check
# Since Kotlin defaults to 'public' when no modifier is specified,
# we need a different approach: count top-level declarations

cd /home/gionag/Development/aurora

for mod in tramai-core tramai-engine tramai-structured tramai-observability tramai-orchestration tremai-ollama tramai-openai tramai-anthropic tramai-standalone tramai-spring tramai-testing tramai-scheduler tramai-server tramai-mcp tramai-platform tramai-dashboard; do
  src="$mod/src/main/kotlin"
  if [ ! -d "$src" ]; then continue; fi
  
  echo "=== $mod ==="
  # List public interfaces/classes (no visibility modifier = public in Kotlin)
  echo "Interfaces:"
  grep -rnE '^(public |)(interface )' "$src" --include='*.kt' 2>/dev/null | sed 's/.*kotlin\///' | sort
  echo "Classes:"
  grep -rnE '^(public |)(abstract |open |)(class )' "$src" --include='*.kt' 2>/dev/null | sed 's/.*kotlin\///' | sort
  echo "Annotations:"
  grep -rnE '(annotation class)' "$src" --include='*.kt' 2>/dev/null | sed 's/.*kotlin\///' | sort
  echo "Objects:"
  grep -rnE '^(public |)(object )' "$src" --include='*.kt' 2>/dev/null | sed 's/.*kotlin\///' | sort
  echo "---"
done
