#!/usr/bin/env bash
set -euo pipefail

TIMESTAMP="${TRAMAI_EVIDENCE_BUNDLE_TIMESTAMP:-$(date -u +"%Y-%m-%dT%H%M%SZ")}"

if [[ ! "$TIMESTAMP" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "Invalid TRAMAI_EVIDENCE_BUNDLE_TIMESTAMP: $TIMESTAMP" >&2
  echo "Use only letters, numbers, dot, underscore, or hyphen." >&2
  exit 1
fi
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_DIR="$ROOT_DIR/evidence-template"
OUT_DIR="$ROOT_DIR/build/evidence-bundles/$TIMESTAMP"

mkdir -p "$OUT_DIR/reports"

for template in "$TEMPLATE_DIR"/*.md; do
  name="$(basename "$template")"
  if [[ "$name" == "README.md" ]]; then
    continue
  fi
  cp "$template" "$OUT_DIR/$name"
done

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

REQUIRED_FILES=(
  "README.md"
  "MANIFEST.md"
  "command-log.md"
  "environment.md"
  "run-log.md"
  "approval-flow.md"
  "restart-proof.md"
  "jdbc-persistence.md"
  "no-cloud-proof.md"
  "benchmark.md"
  "reports/.gitkeep"
)

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  value="${value//$'\t'/\\t}"
  printf '%s' "$value"
}

GIT_COMMIT="$(git -C "$ROOT_DIR/../.." rev-parse HEAD 2>/dev/null || echo "unknown")"
GIT_BRANCH="$(git -C "$ROOT_DIR/../.." rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")"
GIT_COMMIT_JSON="$(json_escape "$GIT_COMMIT")"
GIT_BRANCH_JSON="$(json_escape "$GIT_BRANCH")"
TIMESTAMP_JSON="$(json_escape "$TIMESTAMP")"

{
  cat <<EOF
{
  "schemaVersion": 1,
  "bundleType": "sovereign-lab-evidence-bundle",
  "createdUtc": "$TIMESTAMP_JSON",
  "generator": "examples/sovereign-lab/create-evidence-bundle.sh",
  "repository": {
    "commit": "$GIT_COMMIT_JSON",
    "branch": "$GIT_BRANCH_JSON"
  },
  "claimBoundary": {
    "localEvidenceScaffold": true,
    "certifiesProductionReadiness": false,
    "definesPerformanceGuarantees": false,
    "runsLocalModel": false,
    "runsBenchmark": false,
    "validatesEvidenceTruth": false
  },
  "requiredFiles": [
EOF

  first=true
  for required_file in "${REQUIRED_FILES[@]}"; do
    if [[ "$first" == true ]]; then
      first=false
    else
      printf ',\n'
    fi
    printf '    "%s"' "$(json_escape "$required_file")"
  done

  cat <<EOF

  ],
  "files": [
EOF

  sha256_file() {
    local file="$1"
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum "$file" | awk '{print $1}'
      return
    fi
    if command -v shasum >/dev/null 2>&1; then
      shasum -a 256 "$file" | awk '{print $1}'
      return
    fi
    echo "Missing sha256sum or shasum; cannot compute digest for $file" >&2
    exit 1
  }

  size_bytes() {
    local file="$1"
    if stat -c%s "$file" >/dev/null 2>&1; then
      stat -c%s "$file"
      return
    fi
    if stat -f%z "$file" >/dev/null 2>&1; then
      stat -f%z "$file"
      return
    fi
    echo "Unable to determine size for $file" >&2
    exit 1
  }

  file_first=true
  for required_file in "${REQUIRED_FILES[@]}"; do
    if [[ "$required_file" == "manifest.json" ]]; then
      continue
    fi
    file_path="$OUT_DIR/$required_file"
    if [[ ! -f "$file_path" ]]; then
      echo "Required evidence file missing before manifest generation: $required_file" >&2
      exit 1
    fi
    digest="$(sha256_file "$file_path")"
    size="$(size_bytes "$file_path")"
    if [[ "$file_first" == true ]]; then
      file_first=false
    else
      printf ',\n'
    fi
    printf '    {\n'
    printf '      "path": "%s",\n' "$(json_escape "$required_file")"
    printf '      "sha256": "%s",\n' "$(json_escape "$digest")"
    printf '      "sizeBytes": %s\n' "$size"
    printf '    }'
  done

  cat <<EOF

  ]
}
EOF
} > "$OUT_DIR/manifest.json"
