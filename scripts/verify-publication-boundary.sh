#!/usr/bin/env bash
# Verifies the published-artifact boundary for test fixtures (0.7.4h1).
#
# Publishes every publishable module into a clean, isolated Maven repository and audits the repository
# itself — not the build configuration and not the generated metadata alone — for fixture leakage:
#
#   * no *-test-fixtures.jar anywhere in the repository
#   * no testFixtures* variant in any published .module
#   * no test-fixtures dependency in any published .pom
#   * every jar module still publishes its main, sources and javadoc artifacts
#   * platform modules (java-platform) still publish their POM
#
# Usage: scripts/verify-publication-boundary.sh [repo-dir]
# Exit code: 0 when the boundary holds, 1 otherwise.
set -uo pipefail

REPO="${1:-build/publication-boundary-repo}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

rm -rf "$REPO"
echo "publishing every publishable module into $REPO"
if ! ./gradlew publishAllPublicationsToTramaiRemoteRepository --no-daemon --console=plain \
      "-PtramaiPublishSnapshotUrl=file://$ROOT/$REPO" > /tmp/publication-boundary-publish.log 2>&1; then
  echo "FAIL: publication itself failed; see /tmp/publication-boundary-publish.log"
  grep -aE 'What went wrong|FAILED' /tmp/publication-boundary-publish.log | head -5
  exit 1
fi

python3 - "$ROOT/$REPO" <<'PY'
import glob
import json
import os
import sys

repo = sys.argv[1]
# java-platform modules publish a POM by design; a missing jar there is not a defect.
PLATFORM = {"tramai-bom"}

modules = sorted(
    {os.path.basename(os.path.dirname(os.path.dirname(p)))
     for p in glob.glob(f"{repo}/dev/tramai/*/*/")}
)
if not modules:
    print("FAIL: nothing was published, so nothing was audited")
    raise SystemExit(1)

problems = []
for name in modules:
    files = [os.path.basename(f)
             for d in glob.glob(f"{repo}/dev/tramai/{name}/*/")
             for f in glob.glob(d + "*")]
    jars = [f for f in files if f.endswith(".jar")]
    if name in PLATFORM:
        if not any(f.endswith(".pom") for f in files):
            problems.append(f"{name}: platform module published no POM")
        continue
    if any("test-fixtures" in f for f in files):
        problems.append(f"{name}: fixture jar published")
    if not any(not f.endswith(("-sources.jar", "-javadoc.jar", "-test-fixtures.jar")) for f in jars):
        problems.append(f"{name}: no main jar")
    if not any(f.endswith("-sources.jar") for f in jars):
        problems.append(f"{name}: no sources jar")
    if not any(f.endswith("-javadoc.jar") for f in jars):
        problems.append(f"{name}: no javadoc jar")

fixture_variants = []
for path in glob.glob(f"{repo}/dev/tramai/*/*/*.module"):
    metadata = json.load(open(path))
    for variant in metadata.get("variants", []):
        names = [variant.get("name", "")] + [f.get("name", "") for f in variant.get("files", [])]
        if any("testfixtures" in n.lower() or "test-fixtures" in n.lower() for n in names):
            fixture_variants.append(f"{path}: {variant.get('name')}")

fixture_poms = [p for p in glob.glob(f"{repo}/dev/tramai/*/*/*.pom")
                if "test-fixtures" in open(p, errors="replace").read().lower()
                or "testfixtures" in open(p, errors="replace").read().lower()]

print(f"modules published: {len(modules)}; fixture vars/jars/poms: "
      f"{len(fixture_variants)}/{len([p for p in glob.glob(f'{repo}/**/*', recursive=True) if 'test-fixtures' in p])}"
      f"/{len(fixture_poms)}")
for problem in problems + fixture_variants + fixture_poms:
    print("  FAIL:", problem)
print("RESULT:", "PASS" if not (problems or fixture_variants or fixture_poms) else "FAIL")
raise SystemExit(1 if (problems or fixture_variants or fixture_poms) else 0)
PY
