#!/usr/bin/env bash
set -euo pipefail

# ──────────────────────────────────────────────
# Sovereign Lab Evidence Archive Signature Verifier
#
# Verifies a detached signature over the archive checksum sidecar.
# Signature is over <archive>.tar.gz.sha256, not the archive itself.
# Then runs the existing archive verifier.
#
# Usage:
#   verify-evidence-archive-signature.sh <archive.tar.gz> <public-key.pem>
#
# Exit codes:
#   0 - Signature valid, archive verified
#   1 - Verification failure
#   2 - Usage error
# ──────────────────────────────────────────────

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <evidence-bundle-archive.tar.gz> <public-key.pem>" >&2
  exit 2
fi

ARCHIVE="$1"
PUBLIC_KEY="$2"
CHECKSUM="$ARCHIVE.sha256"
SIGNATURE="$ARCHIVE.sha256.sig"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARCHIVE_VERIFIER="$SCRIPT_DIR/verify-evidence-archive.sh"

# ── Dependency check ──

for command in openssl; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing $command; cannot verify evidence archive signature" >&2
    exit 1
  fi
done

# ── Input validation ──

if [[ ! -f "$ARCHIVE" || ! -r "$ARCHIVE" ]]; then
  echo "Archive must be a readable regular file: $ARCHIVE" >&2
  exit 1
fi

if [[ ! -f "$CHECKSUM" || ! -r "$CHECKSUM" ]]; then
  echo "Checksum sidecar missing or unreadable: $CHECKSUM" >&2
  echo "Run package-evidence-bundle.sh first to create the archive and checksum." >&2
  exit 1
fi

if [[ ! -f "$SIGNATURE" || ! -r "$SIGNATURE" ]]; then
  echo "Detached signature missing or unreadable: $SIGNATURE" >&2
  echo "The signature file must be at $SIGNATURE" >&2
  exit 1
fi

if [[ ! -f "$PUBLIC_KEY" || ! -r "$PUBLIC_KEY" ]]; then
  echo "Public key must be a readable regular file: $PUBLIC_KEY" >&2
  exit 1
fi

if [[ ! -f "$ARCHIVE_VERIFIER" || ! -r "$ARCHIVE_VERIFIER" ]]; then
  echo "Missing readable archive verifier at: $ARCHIVE_VERIFIER" >&2
  exit 1
fi

# ── Verify detached signature over checksum sidecar ──
# The signature is over the .sha256 sidecar, not the archive itself.
# This keeps the trust object small and human-inspectable.

if ! openssl dgst -sha256 \
  -verify "$PUBLIC_KEY" \
  -signature "$SIGNATURE" \
  "$CHECKSUM" 2>/dev/null; then
  echo "Evidence archive signature verification FAILED for $SIGNATURE" >&2
  exit 1
fi

# ── Run existing archive verifier ──
# This checks SHA-256 match, sidecar format, tar entry safety, extraction, bundle verification.

bash "$ARCHIVE_VERIFIER" "$ARCHIVE"

echo "Evidence archive signature verified: $SIGNATURE"
