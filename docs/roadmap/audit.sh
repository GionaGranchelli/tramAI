#!/bin/bash
# Phase 0 Module Audit Script
# Run from repo root: cd /home/gionag/Development/aurora && bash docs/roadmap/audit.sh

MODULES=(
  tramai-core tramai-engine tramai-structured tramai-observability
  tramai-orchestration tramai-ollama tramai-openai tramai-anthropic
  tramai-standalone tramai-spring tramai-testing tramai-bom
  tramai-scheduler tramai-server tramai-mcp tramai-platform tramai-dashboard
)

echo "=== TRAMAI PHASE 0 AUDIT ==="
echo "Date: $(date)"
echo "Repo: $(pwd)"
echo ""

for mod in "${MODULES[@]}"; do
  src="$mod/src/main/kotlin"
  test="$mod/src/test/kotlin"
  
  echo "=== MODULE: $mod ==="
  
  if [ ! -d "$src" ]; then
    echo "  SKIP: no src dir"
    echo ""
    continue
  fi
  
  # Source files
  src_files=$(find "$src" -name '*.kt' 2>/dev/null | wc -l)
  echo "  src_files: $src_files"
  
  # Test files
  test_files=$(find "$test" -name '*.kt' 2>/dev/null | wc -l)
  echo "  test_files: $test_files"
  
  # Top-level declarations (Kotlin: public is default)
  total_decls=$(grep -rnE '^(public |internal |protected |private |)(abstract |open |)(class |interface |object |annotation |enum )|^fun |^val |^var ' "$src" --include='*.kt' 2>/dev/null | grep -v '/build/' | wc -l)
  echo "  total_decls: $total_decls"
  
  # Internal/private declarations
  internal_count=$(grep -rnE '^(internal |private )' "$src" --include='*.kt' 2>/dev/null | grep -E '(class|interface|object|annotation|fun|val|var)' | grep -v '/build/' | wc -l)
  echo "  internal_decls: $internal_count"
  
  # Kotlin files
  kotlin_files=$(find "$src" -name '*.kt' 2>/dev/null | sort)
  echo "  kotlin_files:"
  echo "$kotlin_files" | sed 's/^/    /'
  
  # Packages (directories)
  echo "  packages:"
  find "$src" -type d 2>/dev/null | sort | sed 's/.*kotlin\///' | sed 's/^/    /'
  
  # Tramai internal dependencies
  deps=$(grep -rn '^import dev\.tramai' "$src" --include='*.kt' 2>/dev/null | sed 's/.*import dev.tramai.//' | sed 's/\..*//' | sort -u | tr '\n' ' ')
  echo "  tramai_deps: $deps"
  
  # Lines of code
  loc=$(find "$src" -name '*.kt' -exec cat {} + 2>/dev/null | wc -l)
  echo "  loc: $loc"
  
  # Gradle build file exists?
  build=$(test -f "$mod/build.gradle.kts" && echo "YES" || echo "NO")
  echo "  build_gradle: $build"
  
  # Has readme?
  readme=$(test -f "$mod/README.md" && echo "YES" || echo "NO")
  echo "  readme: $readme"
  
  echo ""

  # Check test compilation
  if [ "$test_files" -gt "0" ]; then
    test_result=$(./gradlew ":$mod:test" 2>&1 | tail -3)
    echo "  test_result: $test_result"
  else
    echo "  test_result: N/A (no tests)"
  fi
  
  echo ""
  echo "---"
  echo ""
done
