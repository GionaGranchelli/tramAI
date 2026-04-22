# TASK-019: Harden Workflow Resume Compatibility

- Status: completed
- Priority: high
- Primary spec: [SPEC-012](../../specs/spec-012-orchestration-and-coordination.md)
- Related ADRs: [ADR-017](../../adr/adr-017.md)
- Last updated: 2026-04-22

## Purpose

Make checkpoint/resume compatibility explicit and safe enough for a stable public contract.

## Scope

- add an explicit workflow definition version or equivalent stable compatibility control
- persist durable compatibility metadata in checkpoints
- replace the weak topology fingerprint with a stronger canonical digest
- include stop-policy-relevant compatibility inputs in the persisted definition metadata
- document exactly when a workflow may resume and when it must fail loudly

## Definition Of Done

- resume safety no longer depends only on a weak structural hash
- checkpoints contain stable compatibility metadata that operators can audit
- incompatible workflow changes fail loudly before resumed execution starts
- tests cover compatible resume, incompatible resume, and intentionally changed definition versions

## Notes

Stable orchestration requires a stronger resume contract than the current prototype fingerprint check.
Implemented in the runtime with explicit `definitionVersion`, SHA-256 structural definition digests, stop-policy-aware compatibility metadata, loud failure on missing or incompatible metadata, and targeted tests covering version and digest mismatch cases.
