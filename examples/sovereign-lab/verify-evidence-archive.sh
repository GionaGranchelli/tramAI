#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <evidence-bundle-archive.tar.gz>" >&2
  exit 2
fi

ARCHIVE="$1"

if [[ ! -f "$ARCHIVE" || ! -r "$ARCHIVE" ]]; then
  echo "Evidence archive must be a readable regular file: $ARCHIVE" >&2
  exit 1
fi

for command in sha256sum python3 tar mktemp; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing $command; cannot verify evidence archive" >&2
    exit 1
  fi
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFIER="$SCRIPT_DIR/verify-evidence-bundle.sh"

if [[ ! -f "$VERIFIER" || ! -r "$VERIFIER" ]]; then
  echo "Missing readable evidence bundle verifier at: $VERIFIER" >&2
  exit 1
fi

CHECKSUM="$ARCHIVE.sha256"

if [[ ! -f "$CHECKSUM" || ! -r "$CHECKSUM" ]]; then
  echo "Evidence archive checksum sidecar missing or unreadable: $CHECKSUM" >&2
  exit 1
fi

ARCHIVE_ABS="$(cd "$(dirname "$ARCHIVE")" && pwd)/$(basename "$ARCHIVE")"
ARCHIVE_NAME="$(basename "$ARCHIVE_ABS")"

if [[ ! "$ARCHIVE_NAME" =~ ^[A-Za-z0-9._-]+\.tar\.gz$ ]]; then
  echo "Evidence archive name must be archive-safe and end with .tar.gz: $ARCHIVE_NAME" >&2
  exit 1
fi

# Validate sidecar format and hash ourselves — never trust sha256sum -c
# with untrusted sidecar content that could name arbitrary paths.
# Accepted formats:
#   <64-hex-sha>  <archive-name>
#   <64-hex-sha> *<archive-name>   (sha256sum -b binary mode)

LINE_COUNT="$(wc -l < "$CHECKSUM" | tr -d ' ')"

if [[ "$LINE_COUNT" != "1" ]]; then
  echo "Evidence archive checksum sidecar must contain exactly one line: $CHECKSUM" >&2
  exit 1
fi

CHECKSUM_LINE="$(cat "$CHECKSUM")"

read -r EXPECTED_SHA EXPECTED_NAME EXTRA_FIELD <<< "$CHECKSUM_LINE"

if [[ -n "${EXTRA_FIELD:-}" ]]; then
  echo "Evidence archive checksum sidecar must contain exactly a SHA-256 digest and archive filename" >&2
  exit 1
fi

if [[ -z "${EXPECTED_SHA:-}" || -z "${EXPECTED_NAME:-}" ]]; then
  echo "Evidence archive checksum sidecar must contain a SHA-256 digest and archive filename" >&2
  exit 1
fi

# Support sha256sum -b binary-mode marker: *filename
if [[ "$EXPECTED_NAME" == \** ]]; then
  EXPECTED_NAME="${EXPECTED_NAME#\*}"
fi

if [[ ! "$EXPECTED_SHA" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "Evidence archive checksum sidecar must start with a valid SHA-256 hex digest" >&2
  exit 1
fi

if [[ "$EXPECTED_NAME" != "$ARCHIVE_NAME" ]]; then
  echo "Evidence archive checksum sidecar must reference $ARCHIVE_NAME, got: $EXPECTED_NAME" >&2
  exit 1
fi

ACTUAL_SHA="$(sha256sum "$ARCHIVE_ABS" | awk '{print $1}')"

if [[ "${EXPECTED_SHA,,}" != "${ACTUAL_SHA,,}" ]]; then
  echo "Evidence archive checksum mismatch for $ARCHIVE_NAME" >&2
  echo "Expected: $EXPECTED_SHA" >&2
  echo "Actual:   $ACTUAL_SHA" >&2
  exit 1
fi

# Inspect tar entries safely before extraction
BUNDLE_NAME="$(
  python3 - "$ARCHIVE_ABS" <<'PY'
import pathlib
import sys
import tarfile

archive = pathlib.Path(sys.argv[1])

def fail(message: str) -> None:
    print(f"Evidence archive verification failed: {message}", file=sys.stderr)
    sys.exit(1)

try:
    tar = tarfile.open(archive, "r:gz")
except Exception as exc:
    fail(f"archive is not a readable .tar.gz file: {exc}")

with tar:
    members = tar.getmembers()

    if not members:
        fail("archive is empty")

    top_levels = set()

    for member in members:
        name = member.name

        if name == "":
            fail("archive contains empty entry name")

        normalized = pathlib.PurePosixPath(name)

        if normalized.is_absolute():
            fail(f"archive entry must not be absolute: {name}")

        if any(part in ("", ".", "..") for part in normalized.parts):
            fail(f"archive entry must be a safe relative path: {name}")

        top_levels.add(normalized.parts[0])

        if member.issym():
            fail(f"archive entry must not be a symlink: {name}")

        if member.islnk():
            fail(f"archive entry must not be a hardlink: {name}")

        if not (member.isfile() or member.isdir()):
            fail(f"archive entry must be a regular file or directory: {name}")

    if len(top_levels) != 1:
        fail(f"archive must contain exactly one top-level bundle directory, found: {sorted(top_levels)}")

    print(next(iter(top_levels)))
PY
)"

# Extract into a temporary directory with safe flags
TMP_ROOT="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

tar \
  --no-same-owner \
  --no-same-permissions \
  -xzf "$ARCHIVE_ABS" \
  -C "$TMP_ROOT"

EXTRACTED_BUNDLE="$TMP_ROOT/$BUNDLE_NAME"

if [[ ! -d "$EXTRACTED_BUNDLE" ]]; then
  echo "Extracted evidence bundle directory not found: $EXTRACTED_BUNDLE" >&2
  exit 1
fi

# Verify the extracted bundle
bash "$VERIFIER" "$EXTRACTED_BUNDLE"

echo "Evidence archive verified: $ARCHIVE_ABS"
