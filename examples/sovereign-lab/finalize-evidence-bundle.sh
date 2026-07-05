#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <evidence-bundle-dir>" >&2
  exit 2
fi

BUNDLE_DIR="$1"
MANIFEST="$BUNDLE_DIR/manifest.json"

if [[ ! -d "$BUNDLE_DIR" ]]; then
  echo "Evidence bundle directory does not exist: $BUNDLE_DIR" >&2
  exit 1
fi

if [[ ! -f "$MANIFEST" ]]; then
  echo "Missing manifest.json at: $MANIFEST" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "Missing python3; cannot finalize manifest.json" >&2
  exit 1
fi

python3 - "$BUNDLE_DIR" <<'PY'
import hashlib
import json
import os
import pathlib
import sys
from datetime import datetime, timezone

bundle_dir = pathlib.Path(sys.argv[1]).resolve()
manifest_path = bundle_dir / "manifest.json"

def fail(message: str) -> None:
    print(f"Evidence bundle finalization failed: {message}", file=sys.stderr)
    sys.exit(1)

def require_inside_bundle(candidate: pathlib.Path, relative: str) -> None:
    try:
        candidate.relative_to(bundle_dir)
    except ValueError:
        fail(f"path escapes bundle directory: {relative}")

def file_sha256_and_size(path: pathlib.Path) -> tuple[str, int]:
    hasher = hashlib.sha256()
    size = 0
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            size += len(chunk)
            hasher.update(chunk)
    return hasher.hexdigest(), size

try:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
except Exception as exc:
    fail(f"manifest.json is not valid JSON: {exc}")

if manifest.get("schemaVersion") != 1:
    fail("manifest.json must declare schemaVersion 1")

if manifest.get("bundleType") != "sovereign-lab-evidence-bundle":
    fail("manifest.json must declare bundleType sovereign-lab-evidence-bundle")

claim_boundary = manifest.get("claimBoundary")
if not isinstance(claim_boundary, dict):
    fail("manifest.json must contain claimBoundary object")

expected_claims = {
    "localEvidenceScaffold": True,
    "certifiesProductionReadiness": False,
    "definesPerformanceGuarantees": False,
    "runsLocalModel": False,
    "runsBenchmark": False,
    "validatesEvidenceTruth": False,
}

for key, expected in expected_claims.items():
    actual = claim_boundary.get(key)
    if actual is not expected:
        fail(f"claimBoundary.{key} must be {expected}, got {actual}")

required_files = manifest.get("requiredFiles")
if not isinstance(required_files, list) or not required_files:
    fail("manifest.json must contain non-empty requiredFiles array")

# Validate all required files exist
for relative in required_files:
    if not isinstance(relative, str):
        fail("requiredFiles entries must be strings")

    if os.path.isabs(relative) or ".." in pathlib.PurePosixPath(relative).parts:
        fail(f"requiredFiles entry must be a safe relative path: {relative}")

    candidate = (bundle_dir / relative).resolve()
    require_inside_bundle(candidate, relative)

    if relative == "manifest.json":
        if not candidate.is_file():
            fail("required manifest.json is missing")
        continue

    if not candidate.exists():
        fail(f"required file missing: {relative}")

# Digest all bundle files except manifest.json
def relative_bundle_files() -> list[str]:
    paths = []
    for path in bundle_dir.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(bundle_dir).as_posix()
        if relative == "manifest.json":
            continue
        if os.path.isabs(relative) or ".." in pathlib.PurePosixPath(relative).parts:
            fail(f"bundle file must be a safe relative path: {relative}")
        paths.append(relative)
    return sorted(paths)

files = []

for relative in relative_bundle_files():
    candidate = (bundle_dir / relative).resolve()
    require_inside_bundle(candidate, relative)

    sha256, size_bytes = file_sha256_and_size(candidate)

    files.append({
        "path": relative,
        "sha256": sha256,
        "sizeBytes": size_bytes,
    })

manifest["finalizedUtc"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H%M%SZ")
manifest["finalizer"] = "examples/sovereign-lab/finalize-evidence-bundle.sh"
manifest["files"] = files

manifest_path.write_text(
    json.dumps(manifest, indent=2, sort_keys=False) + "\n",
    encoding="utf-8",
)

print(f"Evidence bundle finalized: {bundle_dir}")
PY
