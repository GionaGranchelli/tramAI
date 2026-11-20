#!/usr/bin/env bash
# Verifies the published-artifact boundary for test fixtures (0.7.4h1).
#
# Two claims, in order:
#   1. the release gate audits every publishable module's generated metadata and POM (fail-closed), and
#      writes the exact module set it audited to build/publication-boundary/publishable-modules.txt;
#   2. every module in that set appears in a clean, isolated Maven repository with metadata and a POM,
#      and the repository serves no fixture artifact, variant or exported fixture dependency.
#
# Step 2 is why the manifest exists: enumerating the repository and finding no fixtures proves nothing
# if the modules never published at all.
#
# Usage: scripts/verify-publication-boundary.sh [repo-dir] [--audit-only]
# Exit code: 0 when the boundary holds, 1 otherwise.
set -uo pipefail

REPO="${REPO_DIR:-build/publication-boundary-repo}"
AUDIT_ONLY=0
for argument in "$@"; do
  case "$argument" in
    --audit-only) AUDIT_ONLY=1 ;;
    *) REPO="$argument" ;;
  esac
done
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1
MANIFEST="build/publication-boundary/publishable-modules.txt"

rm -f "$MANIFEST"
echo "step 1/2: auditing generated metadata and POM for every publishable module"
if ! ./gradlew verifyNoPublishedTestFixtures --no-daemon --console=plain > /tmp/publication-boundary-gate.log 2>&1; then
  echo "FAIL: verifyNoPublishedTestFixtures rejected the generated metadata"
  grep -aE 'unexpected test-fixtures|FAILED|What went wrong' /tmp/publication-boundary-gate.log | head -8
  exit 1
fi
if [ ! -s "$MANIFEST" ]; then
  echo "FAIL: $MANIFEST was not written, so the expected module set is unknown"
  exit 1
fi
EXPECTED=$(grep -c . "$MANIFEST")
echo "  audited modules: $EXPECTED"

if [ "$AUDIT_ONLY" -eq 1 ]; then
  # Reuses the repository already on disk. Used by the orphan-artifact control, which plants a file
  # after publication and must not have it wiped by a republish.
  echo "step 2/2: auditing $REPO (publication skipped)"
else
  echo "step 2/2: publishing into $REPO and auditing the repository against that set"
  # Only a publishing run resets the repository; --audit-only must inspect what is there.
  rm -rf "$REPO"
  if ! ./gradlew publishAllPublicationsToTramaiRemoteRepository --no-daemon --console=plain \
        "-PtramaiPublishSnapshotUrl=file://$ROOT/$REPO" > /tmp/publication-boundary-publish.log 2>&1; then
    echo "FAIL: publication itself failed; see /tmp/publication-boundary-publish.log"
    grep -aE 'What went wrong|FAILED' /tmp/publication-boundary-publish.log | head -5
    exit 1
  fi
fi

python3 - "$ROOT/$REPO" "$MANIFEST" <<'PY'
import glob
import json
import os
import sys

repo, manifest = sys.argv[1], sys.argv[2]
# java-platform modules publish a POM by design; a missing jar there is not a defect.
PLATFORM = {"tramai-bom"}

expected = [line.strip() for line in open(manifest) if line.strip()]
if not expected:
    print("FAIL: the audited module manifest is empty")
    raise SystemExit(1)

published = {os.path.basename(os.path.dirname(os.path.dirname(p)))
             for p in glob.glob(f"{repo}/dev/tramai/*/*/")}

problems = []
missing = sorted(set(expected) - published)
unexpected = sorted(published - set(expected))
if missing:
    problems.append(f"{len(missing)} module(s) the gate audited were never published: {missing[:8]}")
if unexpected:
    problems.append(f"{len(unexpected)} module(s) published outside the audited set: {unexpected[:8]}")

for name in sorted(set(expected) & published):
    version_dirs = glob.glob(f"{repo}/dev/tramai/{name}/*/")
    files = [os.path.basename(f) for d in version_dirs for f in glob.glob(d + "*")]
    jars = [f for f in files if f.endswith(".jar")]
    # Required for every audited module: without the metadata and the POM there is nothing a consumer
    # resolves, and an absent file must not pass as "no leak".
    if not any(f.endswith(".module") for f in files):
        problems.append(f"{name}: no published .module metadata")
    if not any(f.endswith(".pom") for f in files):
        problems.append(f"{name}: no published POM")
    if name in PLATFORM:
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
    variants = metadata.get("variants") or []
    if not variants:
        fixture_variants.append(f"{path}: no variants in the published metadata")
    for variant in variants:
        names = [variant.get("name", "")] + [f.get("name", "") for f in variant.get("files", [])]
        if any("testfixtures" in n.lower() or "test-fixtures" in n.lower() for n in names):
            fixture_variants.append(f"{path}: {variant.get('name')}")

fixture_poms = []
for path in glob.glob(f"{repo}/dev/tramai/*/*/*.pom"):
    text = open(path, errors="replace").read().lower()
    if "test-fixtures" in text or "testfixtures" in text:
        fixture_poms.append(path)

fixture_files = [p for p in glob.glob(f"{repo}/**/*", recursive=True) if "test-fixtures" in p]
print(f"expected modules: {len(expected)}; published: {len(published)}; "
      f"fixture variants/files/poms: {len(fixture_variants)}/{len(fixture_files)}/{len(fixture_poms)}")
for problem in problems + fixture_variants + fixture_poms + fixture_files:
    print("  FAIL:", problem)
ok = not (problems or fixture_variants or fixture_poms or fixture_files)
print("RESULT:", "PASS" if ok else "FAIL")
raise SystemExit(0 if ok else 1)
PY
