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
  echo "Missing python3; cannot verify manifest.json" >&2
  exit 1
fi

python3 - "$BUNDLE_DIR" <<'PY'
import hashlib
import json
import os
import pathlib
import sys

bundle_dir = pathlib.Path(sys.argv[1]).resolve()
manifest_path = bundle_dir / "manifest.json"

def fail(message: str) -> None:
    print(f"Evidence bundle verification failed: {message}", file=sys.stderr)
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

for relative in required_files:
    if not isinstance(relative, str):
        fail("requiredFiles entries must be strings")
    if os.path.isabs(relative) or ".." in pathlib.PurePosixPath(relative).parts:
        fail(f"requiredFiles entry must be a safe relative path: {relative}")
    candidate = (bundle_dir / relative).resolve()
    require_inside_bundle(candidate, relative)
    if not candidate.exists():
        fail(f"required file missing: {relative}")

files = manifest.get("files")
if not isinstance(files, list) or not files:
    fail("manifest.json must contain non-empty files array")

files_by_path = {}
for entry in files:
    if not isinstance(entry, dict):
        fail("files entries must be objects")

    path = entry.get("path")
    sha256 = entry.get("sha256")
    size_bytes = entry.get("sizeBytes")

    if not isinstance(path, str):
        fail("files[].path must be a string")
    if not isinstance(sha256, str) or len(sha256) != 64:
        fail(f"files entry for {path} must contain a 64-character sha256")
    if not all(ch in "0123456789abcdef" for ch in sha256):
        fail(f"files entry for {path} must contain lowercase hex sha256")
    if not isinstance(size_bytes, int) or size_bytes < 0:
        fail(f"files entry for {path} must contain non-negative integer sizeBytes")

    if os.path.isabs(path) or ".." in pathlib.PurePosixPath(path).parts:
        fail(f"files[].path must be a safe relative path: {path}")

    if path in files_by_path:
        fail(f"duplicate files metadata entry for path: {path}")

    files_by_path[path] = entry

for relative in required_files:
    if relative == "manifest.json":
        continue

    if relative not in files_by_path:
        fail(f"files metadata missing for required file: {relative}")

# Verify every files[] entry matches actual file contents
for path, entry in files_by_path.items():
    candidate = (bundle_dir / path).resolve()
    require_inside_bundle(candidate, path)

    if not candidate.is_file():
        fail(f"files entry is not a file: {path}")

    actual_sha256, actual_size = file_sha256_and_size(candidate)

    if entry["sha256"] != actual_sha256:
        fail(f"sha256 mismatch for {path}")

    if entry["sizeBytes"] != actual_size:
        fail(f"sizeBytes mismatch for {path}")

if "manifest.json" in files_by_path:
    fail("manifest.json must not include its own digest entry")

print(f"Evidence bundle verified: {bundle_dir}")
PY
