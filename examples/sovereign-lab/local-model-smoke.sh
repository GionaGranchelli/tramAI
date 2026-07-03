#!/usr/bin/env bash
set -euo pipefail

: "${TRAMAI_LOCAL_BASE_URL:=http://localhost:11434/v1}"
: "${TRAMAI_LOCAL_API_KEY:=local-dev}"
: "${TRAMAI_LOCAL_MODEL:=qwen2.5:7b}"

echo "=== Sovereign Lab Local Model Smoke ==="
echo ""
echo "Endpoint:  $TRAMAI_LOCAL_BASE_URL"
echo "Model:     $TRAMAI_LOCAL_MODEL"
echo ""

TRAMAI_ENABLE_LOCAL_MODEL_TEST=true \
TRAMAI_LOCAL_BASE_URL="$TRAMAI_LOCAL_BASE_URL" \
TRAMAI_LOCAL_API_KEY="$TRAMAI_LOCAL_API_KEY" \
TRAMAI_LOCAL_MODEL="$TRAMAI_LOCAL_MODEL" \
  ./gradlew verifySovereignLabLocalModel

echo ""
echo "Local model invocation proof complete."
