#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <finalized-evidence-bundle-dir>" >&2
  exit 2
fi

BUNDLE_DIR="$1"

if [[ ! -d "$BUNDLE_DIR" ]]; then
  echo "Evidence bundle directory does not exist: $BUNDLE_DIR" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFIER="$SCRIPT_DIR/verify-evidence-bundle.sh"

if [[ ! -f "$VERIFIER" || ! -r "$VERIFIER" ]]; then
  echo "Missing readable evidence bundle verifier at: $VERIFIER" >&2
  exit 1
fi

if ! command -v gzip >/dev/null 2>&1; then
  echo "Missing gzip; cannot create deterministic archive" >&2
  exit 1
fi

# Verify before packaging.
bash "$VERIFIER" "$BUNDLE_DIR"

BUNDLE_ABS="$(cd "$BUNDLE_DIR" && pwd)"
BUNDLE_NAME="$(basename "$BUNDLE_ABS")"

if [[ ! "$BUNDLE_NAME" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "Evidence bundle directory name must be archive-safe: $BUNDLE_NAME" >&2
  exit 1
fi

ARCHIVE_ROOT="$SCRIPT_DIR/build/evidence-archives"
mkdir -p "$ARCHIVE_ROOT"
ARCHIVE="$ARCHIVE_ROOT/$BUNDLE_NAME.tar.gz"

rm -f "$ARCHIVE" "$ARCHIVE.sha256"

# Package from the parent so extraction recreates the bundle folder.
BUNDLE_PARENT="$(dirname "$BUNDLE_ABS")"

# Pipe through gzip -n for deterministic output (no timestamp embedded).
tar \
  --sort=name \
  --mtime='UTC 1970-01-01' \
  --owner=0 \
  --group=0 \
  --numeric-owner \
  -cf - \
  -C "$BUNDLE_PARENT" \
  "$BUNDLE_NAME" | gzip -n > "$ARCHIVE"

# Write checksum with a relative path for easy verification.
(cd "$ARCHIVE_ROOT" && sha256sum "$BUNDLE_NAME.tar.gz") > "$ARCHIVE.sha256"

echo "Evidence bundle archive created: $ARCHIVE"
echo "Evidence bundle archive checksum: $ARCHIVE.sha256"
