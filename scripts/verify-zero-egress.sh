#!/usr/bin/env bash
set -euo pipefail

# Zero-egress verification harness
# Builds the offline example, runs it inside Docker --network=none,
# and validates the verification report.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

REPORT_DIR="${REPO_ROOT}/build/zero-egress-report"
rm -rf "$REPORT_DIR"
mkdir -p "$REPORT_DIR"
chmod 0777 "$REPORT_DIR"
trap '' EXIT

echo "=== Building application distribution ==="
cd "$REPO_ROOT"
./gradlew :examples:sovereign-offline-verification:installDist --quiet

echo "=== Building Docker image ==="
cd "$REPO_ROOT"
docker build \
  -f examples/sovereign-offline-verification/Dockerfile \
  -t tramai-sovereign-offline-verification:local \
  .

echo "=== Running container with --network=none ==="
docker run --rm \
  --network=none \
  --volume "$REPORT_DIR:/out" \
  tramai-sovereign-offline-verification:local

REPORT_FILE="$REPORT_DIR/zero-egress-report.json"
if [[ ! -f "$REPORT_FILE" ]]; then
  echo "FAIL: Report file not found at $REPORT_FILE"
  exit 1
fi

echo "=== Validating report ==="
python3 -c "
import json, sys

with open('$REPORT_FILE') as f:
    report = json.load(f)

assertions = [
    (report.get('schemaVersion') == 1, 'schemaVersion != 1'),
    (report.get('deploymentMode') == 'OFFLINE', 'deploymentMode != OFFLINE'),
    (report.get('runtimeBuildSucceeded') == True, 'runtimeBuildSucceeded != true'),
    (report.get('loopbackProviderInvocationSucceeded') == True, 'loopbackProviderInvocationSucceeded != true'),
    (report.get('loopbackProviderInvocationCount', 0) >= 1, 'loopbackProviderInvocationCount < 1'),
    (report.get('externalTcpProbeBlocked') == True, 'externalTcpProbeBlocked != true'),
    (report.get('externalDnsProbeBlocked') == True, 'externalDnsProbeBlocked != true'),
    (report.get('artifactVerificationReceiptCount') == 1, 'artifactVerificationReceiptCount != 1'),
    (report.get('auditChainValid') == True, 'auditChainValid != true'),
]

all_pass = True
for passed, msg in assertions:
    if not passed:
        print(f'FAIL: {msg}')
        all_pass = False

if not all_pass:
    print(f'Full report: {json.dumps(report, indent=2)}')
    sys.exit(1)

print('All assertions passed')
print(f'Report: {json.dumps(report, indent=2)}')
"

echo ""
echo "ZERO_EGRESS_HARNESS_GREEN"
