#!/usr/bin/env bash
set -euo pipefail

: "${TRAMAI_LOCAL_BASE_URL:=http://localhost:11434/v1}"
: "${TRAMAI_LOCAL_API_KEY:=local-dev}"
: "${TRAMAI_LOCAL_MODEL:=qwen2.5:7b}"
: "${TRAMAI_LOCAL_BENCHMARK_WARMUP:=1}"
: "${TRAMAI_LOCAL_BENCHMARK_CALLS:=3}"

echo "=== Sovereign Lab Local Model Benchmark ==="
echo ""
echo "Endpoint:       $TRAMAI_LOCAL_BASE_URL"
echo "Model:          $TRAMAI_LOCAL_MODEL"
echo "Warmup calls:   $TRAMAI_LOCAL_BENCHMARK_WARMUP"
echo "Measured calls: $TRAMAI_LOCAL_BENCHMARK_CALLS"
echo ""

TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK=true \
TRAMAI_LOCAL_BASE_URL="$TRAMAI_LOCAL_BASE_URL" \
TRAMAI_LOCAL_API_KEY="$TRAMAI_LOCAL_API_KEY" \
TRAMAI_LOCAL_MODEL="$TRAMAI_LOCAL_MODEL" \
TRAMAI_LOCAL_BENCHMARK_WARMUP="$TRAMAI_LOCAL_BENCHMARK_WARMUP" \
TRAMAI_LOCAL_BENCHMARK_CALLS="$TRAMAI_LOCAL_BENCHMARK_CALLS" \
  ./gradlew benchmarkSovereignLabLocalModel

echo ""
echo "Benchmark report:"
echo "examples/spring-sovereign-starter/build/reports/sovereign-lab/local-model-benchmark/benchmark.json"
