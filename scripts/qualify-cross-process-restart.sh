#!/bin/bash
# 0.7.4d — cross-process durability, run rather than asserted.
#
# Two JVMs, one database. The writer publishes and records its own process id, the digest of what it
# published and the timestamp of that write; the reader, in a separate process, opens the row the writer
# left through the store's load path and compares all three. The reader migrates nothing, clears nothing
# and publishes nothing.
#
# This harness owns everything it touches, and fails closed on everything it does not:
#   - the database is created for this run, on a loopback port Docker chooses, in a container named for
#     this process, so a database this script did not start can never be reached or modified;
#   - a container or database that already exists is refused rather than removed, because ownership is
#     not established by a name;
#   - startup, readiness and both phases are all required, and the script exits nonzero unless both
#     phases report the expected result, so a failed run cannot end green;
#   - cleanup runs from an EXIT trap, so a failure still removes what this run created;
#   - the configuration reaches both JVMs through environment variables only. Nothing secret goes into
#     JAVA_TOOL_OPTIONS or any other JVM argument, because the JVM echoes those into its own output.
#
# Requirements: docker, and a JDK for the Gradle build.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

IMAGE=${GOV_RESTART_IMAGE:-postgres:17-alpine}
CONTAINER="tramai-gov-restart-$$"
DB="gov_restart_$$"
DBUSER=postgres
DBPASS="$(openssl rand -hex 16)"
WORK="$(mktemp -d)"
HANDOFF="$WORK/handoff.properties"
EVIDENCE="$ROOT/docs/roadmap/0.7.4/evidence/0.7.4d-materialized-read-model/cross-process-restart.txt"

cleanup() {
  status=$?
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$WORK"
  exit "$status"
}
trap cleanup EXIT

# Ownership, before anything else: never remove a container this run did not create.
if docker inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "refusing to run: container $CONTAINER already exists and is not mine to remove" >&2
  exit 1
fi

docker run -d --name "$CONTAINER" -e POSTGRES_PASSWORD="$DBPASS" -e POSTGRES_DB="$DB" \
  -p 127.0.0.1::5432 "$IMAGE" >/dev/null
PORT="$(docker port "$CONTAINER" 5432/tcp | head -1 | sed 's/.*://')"
if [ -z "$PORT" ]; then
  echo "refusing to run: the database did not publish a port" >&2
  exit 1
fi

ready=0
for _ in $(seq 1 60); do
  if docker exec "$CONTAINER" pg_isready -U "$DBUSER" >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
if [ "$ready" != 1 ]; then
  echo "refusing to run: the database never became ready" >&2
  docker logs "$CONTAINER" 2>&1 | tail -20 >&2
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

: > "$EVIDENCE"
{
  echo "database identity : $(docker exec "$CONTAINER" psql -U "$DBUSER" -d "$DB" -tAc 'select version()')"
  echo "container id      : $(docker inspect -f '{{.Id}}' "$CONTAINER")  image=$IMAGE  status=$(docker inspect -f '{{.State.Status}}' "$CONTAINER")"
  echo "database          : $DB (created for this run, on loopback port $PORT chosen by docker)"
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

# Success means the reader passed and the writer-only test was skipped: anything else is a failure.
echo "" >> "$EVIDENCE"
echo "=== outcomes ===" >> "$EVIDENCE"
python3 - "$EVIDENCE" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
target = sys.argv[1]
lines = []
ok = False
for p in sorted(glob.glob('/home/gionag/tramai-d1/tramai-security/build/test-results/test/*RestartQualification*.xml')) + \
         sorted(glob.glob('tramai-security/build/test-results/test/*RestartQualification*.xml')):
    x = ET.parse(p).getroot()
    passed, skipped, failed = [], [], []
    for tc in x.iter('testcase'):
        state = 'PASSED'
        for c in tc:
            if c.tag == 'skipped': state = 'SKIPPED'
            if c.tag in ('failure', 'error'):
                state = 'FAILED: ' + (c.get('message') or '')[:240]
                failed.append(tc.get('name'))
        (passed if state == 'PASSED' else skipped if state == 'SKIPPED' else failed).append(tc.get('name'))
        lines.append(f"  {state}  {tc.get('name')}")
    lines.insert(0, f"result: tests={x.get('tests')} failures={x.get('failures')} errors={x.get('errors')} skipped={x.get('skipped')}")
    if len(passed) == 1 and len(skipped) == 1 and not failed:
        ok = True
    break
with open(target, 'a') as f:
    f.write("\n".join(lines) + "\n")
if not ok:
    print("the reader phase did not report exactly one pass and one skip", file=sys.stderr)
    sys.exit(1)
PY

echo "QUALIFICATION PASSED: writer published, a separate process read the same row" >> "$EVIDENCE"
cat "$EVIDENCE"
