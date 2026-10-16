#!/bin/bash
# Direct, flat form of the cross-process qualification: no function indirection, so the environment
# variable is unambiguously attached to each Gradle invocation.
set -u
cd /home/gionag/tramai-d1
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
CONTAINER=tramai-gov-restart
PORT=55432
DB=gov
DBUSER=postgres
DBPASS=gov
URL="jdbc:postgresql://localhost:$PORT/$DB"
HANDOFF=/tmp/tramai-gov-restart-handoff.properties
EVIDENCE=docs/roadmap/0.7.4/evidence/0.7.4d-materialized-read-model/cross-process-restart.txt
: > "$EVIDENCE"
rm -f "$HANDOFF"

docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" -e POSTGRES_PASSWORD="$DBPASS" -e POSTGRES_DB="$DB" -p "$PORT":5432 postgres:17-alpine > /dev/null 2>&1
for _ in $(seq 1 60); do docker exec "$CONTAINER" pg_isready -U "$DBUSER" >/dev/null 2>&1 && break; sleep 2; done
{
  echo "database identity : $(docker exec "$CONTAINER" psql -U "$DBUSER" -d "$DB" -tAc 'select version()')"
  echo "container id      : $(docker inspect -f '{{.Id}}' "$CONTAINER")  image=$(docker inspect -f '{{.Config.Image}}' "$CONTAINER")"
  echo "port              : $PORT"
} >> "$EVIDENCE" 2>&1

echo "" >> "$EVIDENCE"
echo "=== JVM 1: writer ===" >> "$EVIDENCE"
echo "JAVA_TOOL_OPTIONS='-Dgov.restart.phase=publish -Dgov.restart.url=<db url> -Dgov.restart.user=<user> -Dgov.restart.password=<redacted>' \\" >> "$EVIDENCE"
echo "  ./gradlew --no-daemon --no-build-cache :tramai-security:cleanTest :tramai-security:test --tests '*GovernanceProjectionRestartQualificationTest*'" >> "$EVIDENCE"
export JAVA_TOOL_OPTIONS="-Dgov.restart.phase=publish -Dgov.restart.url=$URL -Dgov.restart.user=$DBUSER -Dgov.restart.password=$DBPASS"
./gradlew --no-daemon --no-build-cache :tramai-security:cleanTest :tramai-security:test \
  --tests '*GovernanceProjectionRestartQualificationTest*' --console=plain > /tmp/final-1.log 2>&1
echo "jvm1_exit=$?" >> "$EVIDENCE"
echo "jvm1 executor args : $(grep -aoE 'Gradle Test Executor [0-9]+' /tmp/final-1.log | tail -1)" >> "$EVIDENCE"
grep -aE "BUILD (SUCCESSFUL|FAILED)" /tmp/final-1.log | tail -1 >> "$EVIDENCE"
echo "--- handoff the writer left for the reader (no credentials) ---" >> "$EVIDENCE"
cat "$HANDOFF" >> "$EVIDENCE" 2>&1

echo "" >> "$EVIDENCE"
echo "=== JVM 2: reader, a separate process ===" >> "$EVIDENCE"
echo "JAVA_TOOL_OPTIONS='-Dgov.restart.phase=load -Dgov.restart.url=<db url> -Dgov.restart.user=<user> -Dgov.restart.password=<redacted>' \\" >> "$EVIDENCE"
echo "  ./gradlew --no-daemon --no-build-cache :tramai-security:cleanTest :tramai-security:test --tests '*GovernanceProjectionRestartQualificationTest*'" >> "$EVIDENCE"
export JAVA_TOOL_OPTIONS="-Dgov.restart.phase=load -Dgov.restart.url=$URL -Dgov.restart.user=$DBUSER -Dgov.restart.password=$DBPASS"
./gradlew --no-daemon --no-build-cache :tramai-security:cleanTest :tramai-security:test \
  --tests '*GovernanceProjectionRestartQualificationTest*' --console=plain > /tmp/final-2.log 2>&1
echo "jvm2_exit=$?" >> "$EVIDENCE"
echo "jvm2 executor args : $(grep -aoE 'Gradle Test Executor [0-9]+' /tmp/final-2.log | tail -1)" >> "$EVIDENCE"
grep -aE "BUILD (SUCCESSFUL|FAILED)" /tmp/final-2.log | tail -1 >> "$EVIDENCE"
unset JAVA_TOOL_OPTIONS

echo "" >> "$EVIDENCE"
echo "=== outcomes ===" >> "$EVIDENCE"
python3 - >> "$EVIDENCE" <<'PY'
import glob, xml.etree.ElementTree as ET
for p in sorted(glob.glob('/home/gionag/tramai-d1/tramai-security/build/test-results/test/*RestartQualification*.xml')):
    x = ET.parse(p).getroot()
    print(f"result: tests={x.get('tests')} failures={x.get('failures')} errors={x.get('errors')} skipped={x.get('skipped')}")
    for tc in x.iter('testcase'):
        state = 'PASSED'
        for c in tc:
            if c.tag == 'skipped': state = 'SKIPPED'
            if c.tag in ('failure', 'error'): state = 'FAILED: ' + (c.get('message') or '')[:240]
        print(f"  {state}  {tc.get('name')}")
PY
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
echo "container removed" >> "$EVIDENCE"
cat "$EVIDENCE"
