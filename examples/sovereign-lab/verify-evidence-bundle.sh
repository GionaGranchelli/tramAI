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
import re
import sys
from datetime import datetime

bundle_dir = pathlib.Path(sys.argv[1]).resolve()
manifest_path = bundle_dir / "manifest.json"

def fail(message: str) -> None:
    print(f"Evidence bundle verification failed: {message}", file=sys.stderr)
    sys.exit(1)

def reject_symlinks() -> None:
    for path in bundle_dir.rglob("*"):
        if path.is_symlink():
            relative = path.relative_to(bundle_dir).as_posix()
            fail(f"bundle must not contain symlinks: {relative}")

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

def validate_iso_timestamp(value: str, context: str) -> None:
    """Actual datetime parsing, not just regex."""
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            fail(f"{context}: timestamp must include timezone offset")
    except (ValueError, TypeError):
        fail(f"{context}: timestamp is not valid ISO-8601: {value}")

reject_symlinks()

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

# Every actual file in the bundle (except manifest.json) must appear in files[]
existing_paths = set()
for path in bundle_dir.rglob("*"):
    if not path.is_file():
        continue
    relative = path.relative_to(bundle_dir).as_posix()
    if relative == "manifest.json":
        continue
    if os.path.isabs(relative) or ".." in pathlib.PurePosixPath(relative).parts:
        fail(f"bundle file must be a safe relative path: {relative}")
    existing_paths.add(relative)

unmanifested = existing_paths - set(files_by_path.keys())
if unmanifested:
    fail(
        "bundle contains files missing from manifest: "
        + ", ".join(sorted(unmanifested))
    )

# ── runtime-evidence section ──────────────────────────────────────
# Optional section: can be absent entirely. If present, validate.

RUNTIME_EVIDENCE_DIR = "runtime-evidence"
runtime_evidence_dir = bundle_dir / RUNTIME_EVIDENCE_DIR

# Known runtime evidence files and their expected event types
EVIDENCE_FILES = {
    "policy-decisions.jsonl": "policy.decision",
    "approval-decisions.jsonl": "approval.decision",
    "provider-routing.jsonl": "provider.route",
    "tool-permissions.jsonl": "tool.permission",
}

# Allowed decision kinds per event type
ALLOWED_DECISION_KINDS = {
    "policy.decision": {"ALLOW", "DENY", "REQUIRE_APPROVAL"},
    "approval.decision": {"APPROVED", "DENIED"},
    "provider.route": {"SELECTED", "FALLBACK", "BLOCKED"},
    "tool.permission": {"ALLOW", "DENY", "REQUIRE_APPROVAL"},
}

# Expected source component per event type
EXPECTED_SOURCE_COMPONENTS = {
    "policy.decision": "policy-engine",
    "approval.decision": "approval-control-plane",
    "provider.route": "provider-router",
    "tool.permission": "policy-engine",
}

# Metadata allowlists per event family
POLICY_METADATA_KEYS = {
    "providerName", "modelName", "toolName", "classification",
    "classificationSource", "riskLevel", "fallbackProviderName",
    "attr_cacheReuse", "attr_fallbackReason",
}
APPROVAL_METADATA_KEYS = {
    "approvalVersion", "reasonDigest", "reasonLength",
    "outboxStatus", "eventKeyDigest",
}
ROUTING_METADATA_KEYS = {
    "requestedModelDigest", "selectedProviderDigest", "selectedModelDigest",
    "previousProviderDigest", "previousModelDigest", "routeIndex",
    "attempt", "fallbackReason",
}
TOOL_PERMISSION_METADATA_KEYS = {
    "toolName", "enforcementPoint", "riskLevel", "classification",
    "classificationSource",
}
EVIDENCE_METADATA_KEYS = {
    "policy.decision": POLICY_METADATA_KEYS,
    "approval.decision": APPROVAL_METADATA_KEYS,
    "provider.route": ROUTING_METADATA_KEYS,
    "tool.permission": TOOL_PERMISSION_METADATA_KEYS,
}

SHA256_DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
REASON_CODE_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$")

# Strict key sets — reject unknown fields at every level
ROOT_KEYS = {
    "schemaVersion", "eventId", "eventType", "workflowRunId",
    "correlationId", "actor", "createdAt", "source", "decision",
    "digests", "metadata",
}
SOURCE_KEYS = {"component", "module"}
DECISION_KEYS = {"kind", "reasonCode"}
DIGEST_KEYS = {"subjectDigest", "payloadDigest"}

# Optional fields that can be null
NULLABLE_ROOT_FIELDS = {"workflowRunId", "correlationId", "actor"}

def check_valid_string(value, name, context) -> None:
    """Check a value is a non-empty string (or None/NULL for nullable fields)."""
    if value is None:
        return
    if not isinstance(value, str) or not value.strip():
        fail(f"{context}: {name} must be a non-empty string, got {repr(value)}")

def validate_runtime_evidence():
    if not runtime_evidence_dir.exists():
        return  # Optional section — absent is valid

    if not runtime_evidence_dir.is_dir():
        fail("runtime-evidence must be a directory")

    # Reject nested directories
    for path in runtime_evidence_dir.iterdir():
        if path.is_dir():
            fail(f"runtime-evidence contains unexpected directory: {path.name}")

    # Collect present files and track for minimum files check
    present_files = set()
    for path in runtime_evidence_dir.iterdir():
        if path.is_file():
            present_files.add(path.name)

    if not present_files:
        fail("runtime-evidence directory must contain at least one known JSONL file")

    # Track global event ID uniqueness across all files
    seen_event_ids = set()

    # Each known file that IS present must be non-empty and in manifest.json files[]
    for filename, expected_event_type in EVIDENCE_FILES.items():
        file_path = runtime_evidence_dir / filename
        if not file_path.exists():
            continue  # File is optional — may be absent independently

        if not file_path.is_file():
            fail(f"runtime-evidence/{filename} must be a file")

        file_size = file_path.stat().st_size
        if file_size == 0:
            fail(f"runtime-evidence/{filename} must contain at least one record")

        # Verify each JSONL line
        record_count = 0
        with file_path.open("r", encoding="utf-8") as f:
            line_num = 0
            for line_num, line in enumerate(f, 1):
                stripped = line.strip()
                if not stripped:
                    continue  # Skip empty lines

                record_count += 1

                # Must be valid JSON
                try:
                    record = json.loads(stripped)
                except json.JSONDecodeError as exc:
                    fail(f"runtime-evidence/{filename} line {line_num}: invalid JSON: {exc}")

                if not isinstance(record, dict):
                    fail(f"runtime-evidence/{filename} line {line_num}: root must be an object")

                # Strict key set: reject unknown fields at root
                for key in record:
                    if key not in ROOT_KEYS:
                        fail(
                            f"runtime-evidence/{filename} line {line_num}: "
                            f"unknown root field '{key}'. Allowed: {sorted(ROOT_KEYS)}"
                        )

                # schemaVersion
                sv = record.get("schemaVersion")
                if sv != "runtime-evidence.v1":
                    fail(f"runtime-evidence/{filename} line {line_num}: unsupported schemaVersion: {sv}")

                # eventType must match file
                et = record.get("eventType")
                if et != expected_event_type:
                    fail(
                        f"runtime-evidence/{filename} line {line_num}: "
                        f"eventType '{et}' does not match expected '{expected_event_type}'"
                    )

                # eventId must be non-blank string
                eid = record.get("eventId")
                if not isinstance(eid, str) or not eid.strip():
                    fail(f"runtime-evidence/{filename} line {line_num}: eventId must be a non-blank string")

                # Global event ID uniqueness
                if eid in seen_event_ids:
                    fail(f"runtime-evidence/{filename} line {line_num}: duplicate eventId '{eid}'")
                seen_event_ids.add(eid)

                # createdAt must be valid ISO-8601 (actual parsing)
                cat = record.get("createdAt")
                if not isinstance(cat, str):
                    fail(f"runtime-evidence/{filename} line {line_num}: createdAt must be a string")
                validate_iso_timestamp(cat, f"runtime-evidence/{filename} line {line_num}: createdAt")

                # Optional nullable root fields — check string|None
                for field in NULLABLE_ROOT_FIELDS:
                    val = record.get(field)
                    if val is not None and not isinstance(val, str):
                        fail(f"runtime-evidence/{filename} line {line_num}: {field} must be a string or null")

                # source — strict key set
                src = record.get("source")
                if not isinstance(src, dict):
                    fail(f"runtime-evidence/{filename} line {line_num}: source must be an object")
                for key in src:
                    if key not in SOURCE_KEYS:
                        fail(
                            f"runtime-evidence/{filename} line {line_num}: "
                            f"unknown source field '{key}'. Allowed: {sorted(SOURCE_KEYS)}"
                        )
                expected_component = EXPECTED_SOURCE_COMPONENTS.get(expected_event_type)
                actual_component = src.get("component")
                if expected_component and actual_component != expected_component:
                    fail(
                        f"runtime-evidence/{filename} line {line_num}: "
                        f"source.component must be '{expected_component}', got '{actual_component}'"
                    )
                src_module = src.get("module")
                if src_module is not None and not isinstance(src_module, str):
                    fail(f"runtime-evidence/{filename} line {line_num}: source.module must be a string or null")

                # decision — strict key set
                dec = record.get("decision")
                if not isinstance(dec, dict):
                    fail(f"runtime-evidence/{filename} line {line_num}: decision must be an object")
                for key in dec:
                    if key not in DECISION_KEYS:
                        fail(
                            f"runtime-evidence/{filename} line {line_num}: "
                            f"unknown decision field '{key}'. Allowed: {sorted(DECISION_KEYS)}"
                        )
                kind = dec.get("kind")
                allowed_kinds = ALLOWED_DECISION_KINDS.get(expected_event_type, set())
                if kind not in allowed_kinds:
                    fail(
                        f"runtime-evidence/{filename} line {line_num}: "
                        f"unsupported decision.kind '{kind}'. Allowed: {','.join(sorted(allowed_kinds))}"
                    )
                # reasonCode format check — family-specific allowlists
                reason_code = dec.get("reasonCode")
                if reason_code is not None:
                    if not isinstance(reason_code, str):
                        fail(
                            f"runtime-evidence/{filename} line {line_num}: "
                            f"decision.reasonCode must be a string or null"
                        )
                    if expected_event_type == "approval.decision":
                        _APPROVAL_REASON_CODES = {"approval-approved", "approval-denied"}
                        if reason_code not in _APPROVAL_REASON_CODES:
                            fail(
                                f"runtime-evidence/{filename} line {line_num}: "
                                f"decision.reasonCode must be one of "
                                f"{sorted(_APPROVAL_REASON_CODES)} "
                                f"for approval.decision, got: {reason_code}"
                            )
                    elif expected_event_type == "provider.route":
                        _ROUTING_REASON_CODES = {
                            "provider-selected", "provider-fallback", "provider-blocked",
                        }
                        if reason_code not in _ROUTING_REASON_CODES:
                            fail(
                                f"runtime-evidence/{filename} line {line_num}: "
                                f"decision.reasonCode must be one of "
                                f"{sorted(_ROUTING_REASON_CODES)} "
                                f"for provider.route, got: {reason_code}"
                            )
                    elif expected_event_type == "tool.permission":
                        if not REASON_CODE_RE.match(reason_code):
                            fail(
                                f"runtime-evidence/{filename} line {line_num}: "
                                f"decision.reasonCode must match "
                                f"^[a-zA-Z0-9][a-zA-Z0-9._-]{{0,127}}$ "
                                f"for tool.permission, got: {reason_code}"
                            )
                    else:  # policy.decision — use format regex
                        if not REASON_CODE_RE.match(reason_code):
                            fail(
                                f"runtime-evidence/{filename} line {line_num}: "
                                f"decision.reasonCode must match "
                                f"^[a-zA-Z0-9][a-zA-Z0-9._-]{{0,127}}$"
                            )

                # digests — strict key set
                dig = record.get("digests")
                if not isinstance(dig, dict):
                    fail(f"runtime-evidence/{filename} line {line_num}: digests must be an object")
                for key in dig:
                    if key not in DIGEST_KEYS:
                        fail(
                            f"runtime-evidence/{filename} line {line_num}: "
                            f"unknown digests field '{key}'. Allowed: {sorted(DIGEST_KEYS)}"
                        )
                sd = dig.get("subjectDigest", "")
                if not SHA256_DIGEST_RE.match(str(sd)):
                    fail(f"runtime-evidence/{filename} line {line_num}: subjectDigest must match sha256:<64 hex>")
                pd = dig.get("payloadDigest", "")
                if not SHA256_DIGEST_RE.match(str(pd)):
                    fail(f"runtime-evidence/{filename} line {line_num}: payloadDigest must match sha256:<64 hex>")

                # metadata allowlist
                meta = record.get("metadata", {})
                if not isinstance(meta, dict):
                    fail(f"runtime-evidence/{filename} line {line_num}: metadata must be an object")
                allowed_meta = EVIDENCE_METADATA_KEYS.get(expected_event_type, set())
                for key in meta:
                    if key not in allowed_meta:
                        fail(
                            f"runtime-evidence/{filename} line {line_num}: "
                            f"metadata key '{key}' is not allowlisted for {expected_event_type}"
                        )
                    val = meta[key]
                    if not isinstance(val, str):
                        fail(
                            f"runtime-evidence/{filename} line {line_num}: "
                            f"metadata value for '{key}' must be a string"
                        )

                # ── Family-specific metadata value validation ────────
                if expected_event_type == "approval.decision":
                    # reasonDigest and eventKeyDigest must be valid sha256 digests
                    for dk in ("reasonDigest", "eventKeyDigest"):
                        dv = meta.get(dk)
                        if dv is not None:
                            if not SHA256_DIGEST_RE.match(str(dv)):
                                fail(
                                    f"runtime-evidence/{filename} line {line_num}: "
                                    f"metadata '{dk}' must match sha256:<64 hex>, got: {dv}"
                                )
                    # reasonLength must be a non-negative integer
                    rl = meta.get("reasonLength")
                    if rl is not None:
                        try:
                            rl_int = int(rl)
                            if rl_int < 0:
                                raise ValueError()
                        except (ValueError, TypeError):
                            fail(
                                f"runtime-evidence/{filename} line {line_num}: "
                                f"metadata 'reasonLength' must be a non-negative integer, got: {rl}"
                            )
                    # approvalVersion must be a non-negative integer
                    av = meta.get("approvalVersion")
                    if av is not None:
                        try:
                            av_int = int(av)
                            if av_int < 0:
                                raise ValueError()
                        except (ValueError, TypeError):
                            fail(
                                f"runtime-evidence/{filename} line {line_num}: "
                                f"metadata 'approvalVersion' must be a non-negative integer, got: {av}"
                            )
                elif expected_event_type == "provider.route":
                    # All five *Digest fields must be valid sha256 digests
                    for dk in ("requestedModelDigest", "selectedProviderDigest",
                               "selectedModelDigest", "previousProviderDigest",
                               "previousModelDigest"):
                        dv = meta.get(dk)
                        if dv is not None:
                            if not SHA256_DIGEST_RE.match(str(dv)):
                                fail(
                                    f"runtime-evidence/{filename} line {line_num}: "
                                    f"metadata '{dk}' must match sha256:<64 hex>, got: {dv}"
                                )
                    # routeIndex must be non-negative integer
                    ri = meta.get("routeIndex")
                    if ri is not None:
                        try:
                            ri_int = int(ri)
                            if ri_int < 0:
                                raise ValueError()
                        except (ValueError, TypeError):
                            fail(
                                f"runtime-evidence/{filename} line {line_num}: "
                                f"metadata 'routeIndex' must be a non-negative integer, got: {ri}"
                            )
                    # attempt must be non-negative integer
                    at = meta.get("attempt")
                    if at is not None:
                        try:
                            at_int = int(at)
                            if at_int < 0:
                                raise ValueError()
                        except (ValueError, TypeError):
                            fail(
                                f"runtime-evidence/{filename} line {line_num}: "
                                f"metadata 'attempt' must be a non-negative integer, got: {at}"
                            )
                    # fallbackReason must be in routing allowlist
                    fb = meta.get("fallbackReason")
                    if fb is not None:
                        ALLOWED_ROUTING_REASONS = {
                            "provider-failure", "streaming-startup-failure",
                            "circuit-breaker-open", "model-registry-blocked",
                            "policy-blocked", "no-route",
                        }
                        if fb not in ALLOWED_ROUTING_REASONS:
                            fail(
                                f"runtime-evidence/{filename} line {line_num}: "
                                f"metadata 'fallbackReason' must be one of "
                                f"{sorted(ALLOWED_ROUTING_REASONS)}, got: {fb}"
                            )
                elif expected_event_type == "tool.permission":
                    # toolName must be non-blank
                    tn = meta.get("toolName")
                    if not isinstance(tn, str) or not tn.strip():
                        fail(
                            f"runtime-evidence/{filename} line {line_num}: "
                            f"metadata 'toolName' is required and must be non-blank"
                        )
                    # enforcementPoint must be one of the three tool enforcement points
                    ep = meta.get("enforcementPoint")
                    _TOOL_ENFORCEMENT_POINTS = {
                        "BEFORE_TOOL_EXPOSURE", "BEFORE_TOOL_EXECUTION",
                        "BEFORE_TOOL_RESULT_REINJECTION",
                    }
                    if ep not in _TOOL_ENFORCEMENT_POINTS:
                        fail(
                            f"runtime-evidence/{filename} line {line_num}: "
                            f"metadata 'enforcementPoint' must be one of "
                            f"{sorted(_TOOL_ENFORCEMENT_POINTS)}, got: {ep}"
                        )
                    # riskLevel, if present, must be one of the allowed values
                    rl = meta.get("riskLevel")
                    if rl is not None:
                        _ALLOWED_RISK_LEVELS = {"LOW", "MEDIUM", "HIGH", "CRITICAL"}
                        if rl not in _ALLOWED_RISK_LEVELS:
                            fail(
                                f"runtime-evidence/{filename} line {line_num}: "
                                f"metadata 'riskLevel' must be one of "
                                f"{sorted(_ALLOWED_RISK_LEVELS)}, got: {rl}"
                            )

        # Record count check: must contain at least one record
        if record_count == 0:
            fail(f"runtime-evidence/{filename} must contain at least one JSON record")

    # Reject unknown files in runtime-evidence/
    for path in runtime_evidence_dir.iterdir():
        if not path.is_file():
            continue
        if path.name not in EVIDENCE_FILES:
            fail(f"runtime-evidence contains unknown file: {path.name}")

validate_runtime_evidence()

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
