#!/bin/bash
# 0.7.4d — cross-process durability, run rather than asserted.
#
# Two JVMs, one database. The writer publishes and records its own process id, the digest of what it
# published and the timestamp of that write; the reader, in a separate process, opens the row the writer
# left through the store's load path and compares all three. The reader migrates nothing, clears nothing
# and publishes nothing.
#
# This harness owns everything it touches, and fails closed on everything it does not:
#   - the database is created for this run, on a loopback port Docker chooses, in a container whose ID
#     this execution captures from `docker run`, so a database this script did not start can never be
#     reached or modified;
#   - cleanup addresses that captured ID and nothing else. A container that existed before this run, or
#     one this run refused to touch, is never removed, because ownership is not established by a name;
#   - startup, readiness and both phases are each required, and the script exits nonzero unless both
#     phases report the expected result;
#   - each phase is checked for the outcome that phase must produce: the reader run must pass the reader
#     test and skip the writer test, and the writer run the reverse, which the second phase proves by
#     requiring the test that passed in the first phase to be the one skipped now;
#   - cleanup runs from an EXIT trap, so a failure still removes what this run created;
#   - the configuration reaches both JVMs through environment variables only. Nothing secret goes into
#     JAVA_TOOL_OPTIONS or any other JVM argument, because the JVM echoes those into its own output.
#
# Requirements: docker, and a JDK for the Gradle build.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

IMAGE=${GOV_RESTART_IMAGE:-postgres:17-alpine}
NAME="tramai-gov-restart-$$"
DB="gov_restart_$$"
DBUSER=postgres
DBPASS="$(openssl rand -hex 16)"
WORK="$(mktemp -d)"
HANDOFF="$WORK/handoff.properties"
RESULTS="$ROOT/tramai-security/build/test-results/test"
EVIDENCE="$ROOT/docs/roadmap/0.7.4/evidence/0.7.4d-materialized-read-model/cross-process-restart.txt"

CONTAINER_ID=""
cleanup() {
  status=$?
  # Exactly the container this execution created, addressed by the ID docker returned. An empty ID means
  # this run created nothing, so there is nothing of ours to remove.
  if [ -n "$CONTAINER_ID" ]; then
    docker rm -f "$CONTAINER_ID" >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
  exit "$status"
}
trap cleanup EXIT

# Ownership, before anything else: never remove a container this run did not create.
if docker inspect "$NAME" >/dev/null 2>&1; then
  echo "refusing to run: container $NAME already exists and is not mine to remove" >&2
  exit 1
fi

CONTAINER_ID="$(docker run -d --name "$NAME" -e POSTGRES_PASSWORD="$DBPASS" -e POSTGRES_DB="$DB" \
  -p 127.0.0.1::5432 "$IMAGE")"
PORT="$(docker port "$CONTAINER_ID" 5432/tcp | head -1 | sed 's/.*://')"
if [ -z "$PORT" ]; then
  echo "refusing to run: the database did not publish a port" >&2
  exit 1
fi

ready=0
for _ in $(seq 1 60); do
  if docker exec "$CONTAINER_ID" pg_isready -U "$DBUSER" >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
if [ "$ready" != 1 ]; then
  echo "refusing to run: the database never became ready" >&2
  docker logs "$CONTAINER_ID" 2>&1 | tail -20 >&2
  exit 1
fi

URL="jdbc:postgresql://127.0.0.1:$PORT/$DB"
run_phase() {
  local phase="$1" log="$2"
  GOV_RESTART_PHASE="$phase" GOV_RESTART_URL="$URL" GOV_RESTART_USER="$DBUSER" \
    GOV_RESTART_PASSWORD="$DBPASS" GOV_RESTART_HANDOFF="$HANDOFF" \
    ./gradlew --no-daemon --no-build-cache :tramai-security:cleanTest :tramai-security:test \
    --tests '*GovernanceProjectionRestartQualificationTest*' --console=plain > "$log" 2>&1
}

# The outcome a phase must produce, read from this checkout's own results directory: no other path, and
# no other combination of pass and skip is accepted. Given the previous phase's passing test, it must now
# be the skipped one - that is what makes the roles swap rather than merely "some test passed".
check_outcome() {
  python3 - "$RESULTS" "$1" "$2" <<'PY'
import glob, os, sys, xml.etree.ElementTree as ET

results, previous, current = sys.argv[1], sys.argv[2], sys.argv[3]
files = sorted(glob.glob(os.path.join(results, '*RestartQualification*.xml')))
if len(files) != 1:
    sys.exit(f'expected exactly one restart qualification result in {results}, found {len(files)}')
states = {}
for case in ET.parse(files[0]).getroot().iter('testcase'):
    state = 'PASSED'
    for child in case:
        if child.tag == 'skipped':
            state = 'SKIPPED'
        elif child.tag in ('failure', 'error'):
            state = 'FAILED'
    states[case.get('name')] = state
passed = sorted(n for n, s in states.items() if s == 'PASSED')
print('  results: ' + '  '.join(f'{n}={states[n]}' for n in sorted(states)))
if len(states) != 2 or len(passed) != 1:
    sys.exit(f'expected two tests with exactly one passing, got {states}')
if previous and os.path.exists(previous):
    before = open(previous).read().strip()
    if states.get(before) != 'SKIPPED':
        sys.exit(f'{before} passed in the previous phase and must be skipped now, was {states.get(before)}')
    if passed[0] == before:
        sys.exit(f'the same test passed twice ({before}): the phases did not change roles')
open(current, 'w').write(passed[0])
PY
}

: > "$EVIDENCE"
{
  echo "database identity : $(docker exec "$CONTAINER_ID" psql -U "$DBUSER" -d "$DB" -tAc 'select version()')"
  echo "container         : id=$CONTAINER_ID  name=$NAME  image=$IMAGE  status=$(docker inspect -f '{{.State.Status}}' "$CONTAINER_ID")"
  echo "database          : $DB (created for this run, on loopback port $PORT chosen by docker)"
  echo "cleanup           : by container id only; a container not created by this run is never removed"
  echo "configuration     : environment variables (GOV_RESTART_PHASE, _URL, _USER, _PASSWORD, _HANDOFF); no credential in any JVM argument"
  echo ""
  echo "=== JVM 1: writer ==="
  echo "GOV_RESTART_PHASE=publish ./gradlew --no-daemon --no-build-cache :tramai-security:cleanTest :tramai-security:test --tests '*GovernanceProjectionRestartQualificationTest*'"
} >> "$EVIDENCE"

if run_phase publish "$WORK/jvm1.log"; then
  echo "jvm1_exit=0" >> "$EVIDENCE"
else
  echo "jvm1_exit=nonzero (writer phase failed)" >> "$EVIDENCE"
  tail -12 "$WORK/jvm1.log" >> "$EVIDENCE" 2>&1 || true
  echo "qualification FAILED at the writer phase" >&2
  exit 1
fi
grep -aE "BUILD (SUCCESSFUL|FAILED)" "$WORK/jvm1.log" | tail -1 >> "$EVIDENCE" || true
echo "writer outcome    :" >> "$EVIDENCE"
if ! check_outcome "" "$WORK/writer-passed" >> "$EVIDENCE"; then
  echo "the writer phase did not produce the required outcome" >&2
  exit 1
fi
echo "--- handoff the writer left for the reader (no credentials) ---" >> "$EVIDENCE"
cat "$HANDOFF" >> "$EVIDENCE" 2>/dev/null || { echo "the writer left no handoff" >> "$EVIDENCE"; exit 1; }

{
  echo ""
  echo "=== JVM 2: reader, a separate process ==="
  echo "GOV_RESTART_PHASE=load ./gradlew --no-daemon --no-build-cache :tramai-security:cleanTest :tramai-security:test --tests '*GovernanceProjectionRestartQualificationTest*'"
} >> "$EVIDENCE"

if run_phase load "$WORK/jvm2.log"; then
  echo "jvm2_exit=0" >> "$EVIDENCE"
else
  echo "jvm2_exit=nonzero (reader phase failed)" >> "$EVIDENCE"
  tail -12 "$WORK/jvm2.log" >> "$EVIDENCE" 2>&1 || true
  echo "qualification FAILED at the reader phase" >&2
  exit 1
fi
grep -aE "BUILD (SUCCESSFUL|FAILED)" "$WORK/jvm2.log" | tail -1 >> "$EVIDENCE" || true
echo "reader outcome    :" >> "$EVIDENCE"
if ! check_outcome "$WORK/writer-passed" "$WORK/reader-passed" >> "$EVIDENCE"; then
  echo "the reader phase did not produce the required outcome" >&2
  exit 1
fi

echo "" >> "$EVIDENCE"
echo "QUALIFICATION PASSED: the writer published, a separate process read the same row, and the two phases" >> "$EVIDENCE"
echo "produced opposite outcomes - $(cat "$WORK/writer-passed") in the writer phase, $(cat "$WORK/reader-passed") in the reader phase." >> "$EVIDENCE"
cat "$EVIDENCE"
