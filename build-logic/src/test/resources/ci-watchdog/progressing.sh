#!/usr/bin/env bash
# Synthetic progress: advances the test-result tree the way Gradle does as tests execute.
for i in 1 2 3 4 5 6 7 8; do
  mkdir -p "$WATCHDOG_RESULTS_ROOT/build/test-results/test"
  echo "class $i" >>"$WATCHDOG_RESULTS_ROOT/build/test-results/test/results.bin"
  sleep 1
done
