# Task PR #33 — CycloneDX SBOM Generation and Evidence Linkage

## Status
In implementation

## Branch
`feat/sbom-evidence-linkage`

## Spec Cross-Reference
SPEC-021: CycloneDX SBOM Generation and Evidence Linkage

## Summary
Add CycloneDX SBOM generation via Gradle plugin, a digest task, a `SupplyChainEvidenceV1` DTO, and integration into the existing Sovereign Evidence Pack. Supply-chain evidence is validated, sanitized, and linked into the evidence pack for auditor-safe deployment attestation.

## File Changes

### New files
1. `tramai-sovereign/.../evidence/SupplyChainEvidenceV1.kt` — Supply-chain evidence DTO
2. `docs/specs/spec-021-sbom-evidence-linkage.md` — Specification
3. `docs/board/tasks/task-pr33-sbom-evidence-linkage.md` — Task tracking doc

### Modified files
4. `gradle/libs.versions.toml` — Add `cyclonedx-bom` plugin declaration
5. `build.gradle.kts` — Apply cyclonedx plugin, configure SBOM output, add `computeSbomDigest` task
6. `tramai-sovereign/.../evidence/SovereignEvidencePackV1.kt` — Add `supplyChain` field
7. `tramai-sovereign/.../evidence/SovereignEvidencePackGenerator.kt` — Add `supplyChain` parameter, digest validation, field sanitization
8. `tramai-sovereign/.../evidence/SovereignEvidencePackWriter.kt` — Add supplyChain serialization, update field count
9. `tramai-sovereign/.../SovereignTramai.kt` — Add `supplyChain` to `evidencePack()` method
10. `examples/sovereign-offline-verification/.../OfflineVerificationMain.kt` — Parse `--sbom-path=` and `--sbom-digest-path=` arguments
11. `.github/workflows/ci.yml` — Add SBOM generation, digest, and artifact uploads (build job only)
12. `docs/modules/tramai-sovereign.md` — Add `supplyChain` to Evidence Pack field table

### Test files
13. `tramai-sovereign/.../evidence/SovereignEvidencePackWriterTest.kt` — Add supply-chain serialization, digest validation, filename sanitization tests
14. `tramai-sovereign/.../evidence/SovereignEvidencePackIntegrationTest.kt` — Add supply-chain subsection integration test

## Implementation Phases

### Phase 1: Gradle Configuration

**Scope:** CycloneDX plugin, SBOM output, digest task

**Acceptance criteria:**
- [ ] `cyclonedx-bom` plugin declared in version catalog
- [ ] Plugin applied to root project
- [ ] SBOM output configured: JSON, schema v1.6, path `build/supply-chain/sbom/tramai-cyclonedx-sbom.json`
- [ ] `computeSbomDigest` task registered, produces `build/supply-chain/sbom/tramai-cyclonedx-sbom.sha256`
- [ ] `./gradlew cyclonedxBom` succeeds
- [ ] `./gradlew computeSbomDigest` succeeds

### Phase 2: SupplyChainEvidenceV1 DTO and Validation

**Scope:** Data class, sanitization, digest validation

**Acceptance criteria:**
- [ ] `SupplyChainEvidenceV1` data class created with 6 fields
- [ ] `sbomSha256` validated against `^sha256:[a-fA-F0-9]{64}$`
- [ ] `sbomFormat`, `sbomSpecVersion`, `sbomFileName`, `generatedBy` sanitized via `EvidenceSafeString`
- [ ] `sbomSha256` NOT passed through `EvidenceSafeString`
- [ ] Invalid digest → `IllegalArgumentException("evidence-unsafe-digest-format")`
- [ ] Path-containing filename → `IllegalArgumentException("evidence-unsafe-identifier")`

### Phase 3: Evidence Pack Integration

**Scope:** Add supplyChain to pack, writer, generator, SovereignTramai

**Acceptance criteria:**
- [ ] `SovereignEvidencePackV1.supplyChain` field added (after auditChain, default null)
- [ ] Writer serializes supplyChain (or null) at correct field position (10/11)
- [ ] Generator validates and sanitizes supplyChain before creating pack
- [ ] `SovereignTramai.evidencePack()` forwards supplyChain parameter
- [ ] Backward compatible — existing callers without supplyChain continue to work

### Phase 4: Offline Example Integration

**Scope:** Add optional SBOM args to OfflineVerificationMain

**Acceptance criteria:**
- [ ] `--sbom-path=` argument parsed (optional)
- [ ] `--sbom-digest-path=` argument parsed (optional)
- [ ] When both present: read digest, validate format, create `SupplyChainEvidenceV1`, pass to `evidencePack()`
- [ ] When absent: evidence pack works without supplyChain (null)

### Phase 5: CI and Documentation

**Scope:** CI steps, spec, task doc, module docs

**Acceptance criteria:**
- [ ] CI generates SBOM and digest in build job
- [ ] CI uploads SBOM JSON and digest as artifacts
- [ ] SPEC-021 documents DTO, security invariants, non-goals
- [ ] `docs/modules/tramai-sovereign.md` updated with supplyChain field

## Exit Criteria

- [ ] `./gradlew :tramai-sovereign:test --rerun-tasks` green
- [ ] `./gradlew :examples:sovereign-offline-verification:test --rerun-tasks` green
- [ ] `./gradlew cyclonedxBom` succeeds
- [ ] `./gradlew computeSbomDigest` succeeds
- [ ] Supply-chain evidence is validated and sanitized before writing
- [ ] Evidence pack remains safe for auditors (no secrets, tokens, paths)
