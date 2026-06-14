# Task PR #32 — Sovereign Evidence Pack

## Status
In review (PR #32)

## Branch
`feat/sovereign-evidence-pack`

## Spec Cross-Reference
SPEC-020: Sovereign Evidence Pack

## Summary
Add a deterministic Sovereign Evidence Pack V1 that summarizes the security-relevant state of a sovereign TramAI deployment without leaking prompts, payloads, tokens, secrets, stack traces, or filesystem paths. Designed for CI attestation, enterprise security review, and future air-gap validation evidence chains.

## File Changes

### New files
1. `tramai-sovereign/.../evidence/SovereignEvidencePackV1.kt` — Top-level evidence DTO
2. `tramai-sovereign/.../evidence/ArtifactEvidenceV1.kt` — Artifact summary DTO
3. `tramai-sovereign/.../evidence/EvidenceSafeString.kt` — Identifier sanitizer
4. `tramai-sovereign/.../evidence/ZeroEgressEvidenceV1.kt` — Zero-egress evidence DTO
5. `tramai-sovereign/.../evidence/AuditChainEvidenceV1.kt` — Audit chain evidence DTO
6. `tramai-sovereign/.../evidence/SovereignEvidencePackWriter.kt` — Deterministic JSON writer
7. `tramai-sovereign/.../evidence/SovereignEvidencePackGenerator.kt` — Evidence collection API
8. `docs/specs/spec-020-sovereign-evidence-pack.md`
9. `docs/board/tasks/task-pr32-sovereign-evidence-pack.md`

### Modified files
10. `tramai-sovereign/.../SovereignTramai.kt` — Add `evidencePack()` method, store profile
11. `examples/sovereign-offline-verification/.../OfflineVerificationMain.kt` — Generate evidence pack
12. `.github/workflows/ci.yml` — Upload evidence pack artifact
13. `docs/modules/tramai-sovereign.md` — Add evidence pack section
14. `docs/security/SECURITY-MODEL.md` — Add evidence pack boundary

### Test files
15. `tramai-sovereign/.../evidence/SovereignEvidencePackWriterTest.kt` — Unit tests
16. `tramai-sovereign/.../evidence/SovereignEvidencePackIntegrationTest.kt` — Integration tests

## Implementation Phases

### Phase 1: DTOs and Writer

**Scope:** Data classes, JSON serializer

**Acceptance criteria:**
- [ ] All 4 DTOs created with stable field ordering (VerifiedModelEvidenceV1 removed)
- [ ] EvidenceSafeString sanitizer rejects unsafe identifiers before DTO construction
- [ ] Writer produces deterministic JSON with full control-character escaping
- [ ] No prompts, paths, secrets, tokens in output

### Phase 2: Generator and SovereignTramai Integration

**Scope:** Evidence collection API

**Acceptance criteria:**
- [ ] `SovereignEvidencePackGenerator.generate()` collects deployment mode, models, providers, zones, settings, receipts
- [ ] `SovereignTramai.evidencePack()` method calls generator with current state
- [ ] Optional zero-egress and audit-chain subsections supported

### Phase 3: Example Integration

**Scope:** Evidence pack generation in offline verification harness

**Acceptance criteria:**
- [ ] OfflineVerificationMain generates evidence pack after zero-egress verification
- [ ] Evidence pack written to `build/sovereign-evidence/sovereign-evidence-pack-v1.json`

### Phase 4: CI and Documentation

**Scope:** GitHub Actions upload, doc updates

**Acceptance criteria:**
- [ ] CI uploads evidence pack as build artifact
- [ ] Spec, task doc, module docs, security model updated

## Exit Criteria

- [ ] `./gradlew test --rerun-tasks` green
- [ ] `./gradlew :tramai-sovereign:test --rerun-tasks` green
- [ ] `./gradlew :examples:sovereign-offline-verification:test --rerun-tasks` green
- [ ] Evidence pack JSON is deterministic and safe for auditors
- [ ] PR body documents security invariants and non-goals
