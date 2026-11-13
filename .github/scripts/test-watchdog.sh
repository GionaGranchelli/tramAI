#!/usr/bin/env bash
#
# Guarded test runner for the CI `tests` job.
#
# A hanging suite and a merely slow suite need different treatment, so this carries two independent
# guards:
#
#   inactivity  no observable progress for WATCHDOG_INACTIVITY_SECONDS -> a real stall, abort now
#   ceiling     wall-clock WATCHDOG_CEILING_SECONDS -> a growth margin, abort regardless of progress
#
# Progress is read from Gradle's own test-result tree, which advances as each test executes. Both
# guards dump worker threads first, so the uploaded artifact shows where the run stopped.
#
# Calibration consequence: the ceiling must sit comfortably above the suite's real runtime. If the
# suite grows into the ceiling, the guard silently becomes an assertion about suite duration rather
# than a hang detector, and healthy runs get aborted at random.
set -uo pipefail

CEILING_SECONDS="${WATCHDOG_CEILING_SECONDS:-1200}"
INACTIVITY_SECONDS="${WATCHDOG_INACTIVITY_SECONDS:-270}"
POLL_SECONDS="${WATCHDOG_POLL_SECONDS:-30}"
DUMP_DIR="${WATCHDOG_DUMP_DIR:-/tmp/threaddumps}"
RESULTS_ROOT="${WATCHDOG_RESULTS_ROOT:-.}"
COMMAND="${WATCHDOG_COMMAND:-./gradlew test}"

mkdir -p "$DUMP_DIR"

progress_fingerprint() {
  find "$RESULTS_ROOT" -path '*/build/test-results/*' -type f -printf '%p %s %T@\n' 2>/dev/null |
    sort | md5sum | cut -d' ' -f1
}

abort() {
  echo "::error::$1"
  for P in $(jps -l 2>/dev/null | grep -iE 'GradleWorkerMain|GradleDaemon' | awk '{print $1}'); do
    jstack "$P" >"$DUMP_DIR/worker-$P-$(date +%H%M%S).txt" 2>&1 || true
  done
  kill "$CHILD_PID" 2>/dev/null || true
  wait "$CHILD_PID" 2>/dev/null || true
  exit 1
}

echo "watchdog: ceiling=${CEILING_SECONDS}s inactivity=${INACTIVITY_SECONDS}s poll=${POLL_SECONDS}s results=${RESULTS_ROOT}"

bash -c "exec ${COMMAND}" &
CHILD_PID=$!

started=$(date +%s)
last_progress=$started
fingerprint=$(progress_fingerprint)

while kill -0 "$CHILD_PID" 2>/dev/null; do
  sleep "$POLL_SECONDS"
  now=$(date +%s)
  current=$(progress_fingerprint)
  if [ "$current" != "$fingerprint" ]; then
    fingerprint="$current"
    last_progress="$now"
  fi
  idle=$((now - last_progress))
  elapsed=$((now - started))
  if [ "$idle" -ge "$INACTIVITY_SECONDS" ]; then
    abort "Run tests made no progress for ${idle}s (limit ${INACTIVITY_SECONDS}s) — dumping threads and aborting"
  fi
  if [ "$elapsed" -ge "$CEILING_SECONDS" ]; then
    abort "Run tests reached the ${CEILING_SECONDS}s wall-clock ceiling — dumping threads and aborting"
  fi
done

wait "$CHILD_PID"
exit $?
