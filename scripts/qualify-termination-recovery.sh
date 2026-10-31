#!/bin/bash
# 0.7.4g — R5, an interrupted rebuild, run against the real database rather than argued about.
#
# One JVM runs GovernanceProjectionTerminationQualificationTest, which spawns two child JVMs of its own
# and SIGKILLs each at a chosen point:
#
#   (B) kill -9 while the publish transaction is open — the child pauses after the header upsert has
#       executed on the server and before any fact or decision row is written, so the only states
#       PostgreSQL can leave are the previously committed projection or the fully committed new one.
#   (A) kill -9 after the transaction committed and publish() returned — the child reports "publish
#       returned" and never hands the outcome to a caller; the row must still be there, and a retry of
#       the same publish must report ALREADY_CURRENT without writing.
#
# This harness owns everything it touches, and fails closed on everything it does not:
#   - the database is created for this run, on a loopback port Docker chooses, in a container whose ID
#     this execution captures from `docker run`, so a database this script did not start can never be
#     reached or modified;
#   - cleanup addresses that captured ID and nothing else; a container that existed before this run is
#     never removed, because ownership is not established by a name;
#   - the test result is read from this checkout's own results directory: exactly one test case, and it
#     must have PASSED. A skipped case (missing configuration) or a failed one exits nonzero, so the
#     qualification cannot pass by qualifying nothing;
#   - the three verdict lines the test prints (R5-A, R5-B, R5-CONTROL) must all appear, so a run that
#     silently reached only one of the cases fails;
#   - cleanup runs from an EXIT trap, so a failure still removes what this run created;
#   - configuration reaches every JVM through environment variables only.
#
# Requirements: docker, a JDK, and JAVA_HOME pointing at it (the script exports its own default when
# JAVA_HOME is unset).
#
# Usage:
#   scripts/qualify-termination-recovery.sh
#   GOV_TERM_IMAGE=postgres:17-alpine scripts/qualify-termination-recovery.sh
#
# It writes ../tramai-security/build/qualification/termination-recovery.txt (build output, not tracked)
# and prints it. Expected wall time: about a minute, most of it the Gradle compile.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}

IMAGE=${GOV_TERM_IMAGE:-postgres:17-alpine}
NAME="tramai-gov-termination-$$"
DB="gov_termination_$$"
DBUSER=postgres
DBPASS="$(openssl rand -hex 16)"
WORK="$(mktemp -d)"
RESULTS="$ROOT/tramai-security/build/test-results/test"
EVIDENCE="$ROOT/tramai-security/build/qualification/termination-recovery.txt"
LOG="$WORK/jvm.log"

CONTAINER_ID=""
cleanup() {
  status=$?
  if [ -n "$CONTAINER_ID" ]; then
    docker rm -f "$CONTAINER_ID" >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
  exit "$status"
}
trap cleanup EXIT

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

mkdir -p "$(dirname "$EVIDENCE")"
: > "$EVIDENCE"
{
  echo "database identity : $(docker exec "$CONTAINER_ID" psql -U "$DBUSER" -d "$DB" -tAc 'select version()')"
  echo "container         : id=$CONTAINER_ID  name=$NAME  image=$IMAGE  status=$(docker inspect -f '{{.State.Status}}' "$CONTAINER_ID")"
  echo "database          : $DB (created for this run, on loopback port $PORT chosen by docker)"
  echo "cleanup           : by container id only; a container not created by this run is never removed"
  echo "configuration     : environment variables (GOV_TERM_URL, _USER, _PASSWORD); no credential in any JVM argument"
  echo "kill mechanism    : Process.destroyForcibly() from the test JVM = SIGKILL; death by signal is recorded as exit 137"
  echo "child JVMs        : the test's own class, launched with this module's test runtime classpath"
  echo ""
  echo "=== qualification ==="
  echo "GOV_TERM_URL/JAVA_HOME set; ./gradlew --no-daemon --no-build-cache :tramai-security:cleanTest :tramai-security:test --tests '*GovernanceProjectionTerminationQualificationTest*'"
} >> "$EVIDENCE"

if GOV_TERM_URL="$URL" GOV_TERM_USER="$DBUSER" GOV_TERM_PASSWORD="$DBPASS" \
  ./gradlew --no-daemon --no-build-cache -Dkotlin.compiler.execution.strategy=in-process \
  :tramai-security:cleanTest :tramai-security:test \
  --tests '*GovernanceProjectionTerminationQualificationTest*' --console=plain > "$LOG" 2>&1; then
  echo "gradle_exit=0" >> "$EVIDENCE"
else
  echo "gradle_exit=nonzero" >> "$EVIDENCE"
  tail -30 "$LOG" >> "$EVIDENCE" 2>&1 || true
  cat "$EVIDENCE"
  echo "qualification FAILED: the test JVM exited nonzero" >&2
  exit 1
fi

grep -aE "BUILD (SUCCESSFUL|FAILED)" "$LOG" | tail -1 >> "$EVIDENCE" || true

# The outcome this run must produce, from this checkout's own results directory: one case, and it
# passed. A skipped case means the configuration never reached the test, which is not a pass. The three
# verdict lines are read from the test's captured stdout: they are the record that each case was
# actually reached, and Gradle does not echo test output to the console.
python3 - "$RESULTS" <<'PY' >> "$EVIDENCE"
import glob, os, sys, xml.etree.ElementTree as ET
results = sys.argv[1]
files = sorted(glob.glob(os.path.join(results, '*TerminationQualification*.xml')))
if len(files) != 1:
    sys.exit(f'expected exactly one termination qualification result in {results}, found {len(files)}')
root = ET.parse(files[0]).getroot()
states = {}
for case in root.iter('testcase'):
    state = 'PASSED'
    for child in case:
        if child.tag == 'skipped':
            state = 'SKIPPED'
        elif child.tag in ('failure', 'error'):
            state = 'FAILED'
    states[case.get('name')] = state
print('  results: ' + '  '.join(f'{n}={states[n]}' for n in sorted(states)))
if len(states) != 1:
    sys.exit(f'expected one test case, got {states}')
if 'PASSED' not in states.values():
    sys.exit(f'the qualification did not pass: {states}')
captured = ''.join(so.text or '' for so in root.iter('system-out'))
for verdict in ('R5-B:', 'R5-A:', 'R5-CONTROL:'):
    lines = [line.strip() for line in captured.splitlines() if verdict in line]
    if not lines:
        sys.exit(f'verdict line missing: {verdict} - the run did not reach that case')
    print('  ' + lines[-1])
PY

echo "" >> "$EVIDENCE"
echo "QUALIFICATION PASSED: a SIGKILL inside the publish transaction left the previous projection or the" >> "$EVIDENCE"
echo "fully committed new one with header, facts and decisions agreeing on the digest; a SIGKILL after commit" >> "$EVIDENCE"
echo "left the new projection readable with no second version, and a retry reported ALREADY_CURRENT." >> "$EVIDENCE"
cat "$EVIDENCE"
