# Task PR #34 — GitHub Artifact Attestations for CI/CD Provenance

## Status
In implementation

## Branch
`feat/artifact-attestations`

## Spec Cross-Reference
SPEC-022: GitHub Artifact Attestations for CI/CD Provenance

## Summary
Add GitHub Artifact Attestations (`actions/attest@v4`) to link the Sovereign Evidence Pack and CycloneDX SBOM to a specific GitHub Actions workflow run. Attestation evidence is captured in an `AttestationEvidenceV1` DTO within the evidence pack for auditor-safe CI/CD provenance verification.

## File Changes

### New files
1. `tramai-sovereign/.../evidence/AttestationEvidenceV1.kt` — Attestation CI/CD provenance DTO
2. `tramai-sovereign/.../evidence/AttestedSubjectV1.kt` — Attested subject DTO (name + sha256)
3. `docs/specs/spec-022-artifact-attestations.md` — Specification
4. `docs/board/tasks/task-pr34-artifact-attestations.md` — Task tracking doc

### Modified files
5. `tramai-sovereign/.../evidence/SovereignEvidencePackV1.kt` — Add `attestation` field (position 11/12)
6. `tramai-sovereign/.../evidence/SovereignEvidencePackWriter.kt` — Add attestation serialization (`serializeAttestation`, `serializeAttestedSubject`)
7. `tramai-sovereign/.../evidence/SovereignEvidencePackGenerator.kt` — Add `attestation` parameter, validation, sanitization
8. `tramai-sovereign/.../SovereignTramai.kt` — Add `attestation` to `evidencePack()` method
9. `.github/workflows/ci.yml` — Add `id-token: write` and `attestations: write` permissions, attest steps for evidence pack and SBOM
10. `docs/modules/tramai-sovereign.md` — Add `attestation` to Evidence Pack field table, update roadmap
11. `docs/specs/spec-020-sovereign-evidence-pack.md` — Add attestation to scope list, DTO definition, generator collection, evidencePack() signature, roadmap table
12. `docs/specs/spec-021-sbom-evidence-linkage.md` — Add SBOM attestation note, update roadmap

### Test files
13. `tramai-sovereign/.../evidence/SovereignEvidencePackWriterTest.kt` — Add attestation serialization tests
14. `tramai-sovereign/.../evidence/SovereignEvidencePackIntegrationTest.kt` — Add attestation subsection integration test

## Implementation Phases

### Phase 1: DTOs

**Scope:** `AttestationEvidenceV1`, `AttestedSubjectV1`

**Acceptance criteria:**
- [ ] `AttestationEvidenceV1` data class created with 7 fields: `schemaVersion`, `provider`, `workflowName`, `workflowRunId`, `repository`, `commitSha`, `attestedSubjects`
- [ ] `AttestedSubjectV1` data class created with 3 fields: `fileName`, `sha256`, `attestationType`
- [ ] Stable field ordering matching data class declaration order

### Phase 2: Evidence Pack Integration

**Scope:** Add `attestation` field to pack, writer, generator, SovereignTramai

**Acceptance criteria:**
- [ ] `SovereignEvidencePackV1.attestation` field added (after supplyChain, before generatedAt, position 11/12)
- [ ] Writer serializes attestation (or null) at correct field position (11/12)
- [ ] `serializeAttestation()` produces deterministic JSON with correct field ordering: schemaVersion, provider, workflowName, workflowRunId, repository, commitSha, attestedSubjects
- [ ] `serializeAttestedSubject()` produces deterministic JSON with correct field ordering: fileName, sha256, attestationType
- [ ] Generator accepts optional `attestation` parameter
- [ ] `SovereignTramai.evidencePack()` forwards `attestation` parameter
- [ ] Backward compatible — existing callers without attestation continue to work

### Phase 3: CI Pipeline Attestation

**Scope:** GitHub Actions workflow updates

**Acceptance criteria:**
- [ ] `zero-egress` job has `id-token: write` and `attestations: write` permissions; `build` job has only `contents: read`
- [ ] Evidence pack attest step: `actions/attest-build-provenance@v2` with `subject-path: build/zero-egress-report/sovereign-evidence-pack-v1.json`
- [ ] Zero-egress report attest step: `actions/attest-build-provenance@v2` with `subject-path: build/zero-egress-report/zero-egress-report.json`
- [ ] SBOM predicate attest step: `actions/attest@v4` with `subject-path` pointing to the evidence pack and `sbom-path` pointing to the CycloneDX JSON
- [ ] Offline harness generates AttestationEvidenceV1 from GITHUB_* env vars when present; falls back to null locally

### Phase 4: Documentation

**Scope:** Spec, task doc, module docs, existing spec updates

**Acceptance criteria:**
- [ ] SPEC-022 documents DTOs, CI design, verification commands, security invariants, non-goals
- [ ] `docs/modules/tramai-sovereign.md` updated with `attestation` field in evidence pack table
- [ ] `docs/specs/spec-020-sovereign-evidence-pack.md` updated with attestation scope, DTO, generator, evidencePack(), roadmap
- [ ] `docs/specs/spec-021-sbom-evidence-linkage.md` updated with SBOM attestation note and roadmap
- [ ] `docs/board/tasks/task-pr34-artifact-attestations.md` created

## Exit Criteria

- [ ] `./gradlew :tramai-sovereign:test --rerun-tasks` green
- [ ] `./gradlew :examples:sovereign-offline-verification:test --rerun-tasks` green
- [ ] Attestation DTOs serialize deterministically
- [ ] CI attest steps run without permission errors
- [ ] `gh attestation verify` succeeds on attested artifacts
- [ ] Evidence pack remains safe for auditors (no secrets, tokens, paths)
- [ ] All documentation reflects the current implementation
