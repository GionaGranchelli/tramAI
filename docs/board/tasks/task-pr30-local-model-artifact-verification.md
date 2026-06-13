# Task PR #30 — Local-Model Artifact Manifest and Byte-Level Verification

## Status
**Merged** (squash commit onto master expected via PR #30)

## Branch
`feat/local-model-artifact-verification`

## Spec Cross-Reference
SPEC-018: Local Model Artifact Verification

## Summary
Implement a local-model artifact verification layer that proves declared local artifact bytes match an approved cryptographic manifest before sovereign runtime use. Reuses the existing `RegisteredModel.artifactDigest` extension point.

## File Changes

### New files
1. `tramai-core/.../LocalModelArtifactManifestV1.kt` — Manifest + file DTOs with aggregate digest computation
2. `tramai-core/.../VerifiedLocalModelArtifact.kt` — Immutable verification receipt
3. `tramai-core/.../ModelArtifactVerifier.kt` — SPI + NoOp default + settings
4. `tramai-security/.../FileSystemModelArtifactVerifier.kt` — Strict streaming filesystem verifier
5. `docs/board/tasks/task-pr30-local-model-artifact-verification.md` — this file

### Modified files
6. `tramai-sovereign/.../SovereignTramai.kt` — Add builder methods + build-time verification for LOCAL routes
7. `tramai-core/.../RegisteredModel.kt` — Add Javadoc clarifying `artifactDigest` meaning (canonical manifest digest)
8. `docs/modules/tramai-security.md` — Add verification section
9. `docs/modules/tramai-sovereign.md` — Add builder API section
10. `docs/specs/spec-018-local-model-artifact-verification.md` — this spec

### Test files
11. `tramai-core/.../LocalModelArtifactManifestV1Test.kt` — Manifest validation tests
12. `tramai-security/.../FileSystemModelArtifactVerifierTest.kt` — Filesystem hardening tests
13. `tramai-sovereign/.../SovereignTramaiArtifactVerificationTest.kt` — Runtime integration tests

## Implementation Phases

### Phase 1: Core Contracts (tramai-core)

**Scope:** Domain models, SPI, manifest canonical bytes, validation utility

**Acceptance criteria:**
- [ ] Extract `validateField()` as a package-level utility function (reused by RegisteredModel, LocalModelArtifactManifestV1, LocalModelArtifactFileV1)
- [ ] `LocalModelArtifactManifestV1` created with required `schemaVersion`, identity fields, artifact list, `canonicalBytes(): ByteArray` (UTF-8 encoded, no hashing)
- [ ] `LocalModelArtifactFileV1` created with relative path, size, digest; validates no absolute/traversal paths
- [ ] Manifest `init` validates: schemaVersion=1, non-blank IDs, non-empty artifacts, no duplicate paths (case-sensitive)
- [ ] File DTO `init` validates: non-empty relative path, no absolute/`../` traversal, no control chars, non-negative size
- [ ] `canonicalBytes()` produces deterministic UTF-8 byte output sorted by relativePath
- [ ] `schemaVersion` is required (no default) to prevent aggregate-digest drift
- [ ] `VerifiedLocalModelArtifact` data class with registryEntryId, manifestDigest, verifiedAt, artifactCount, totalSizeBytes
- [ ] `ModelArtifactVerifier` interface with `suspend fun verify(RegisteredModel): VerifiedLocalModelArtifact?`
- [ ] `NoOpModelArtifactVerifier` object (returns `null`, matching NoOpModelRegistry pattern)
- [ ] `ModelArtifactVerificationSettings` data class with `enabled=false`, `requireDigestForLocalModels=false`
- [ ] Manifest unit tests pass (10+ scenarios including canonicalBytes, empty artifacts, duplicate paths)
- [ ] `./gradlew :tramai-core:test --rerun-tasks` green

### Phase 2: Filesystem Verifier (tramai-security)

**Scope:** Strict streaming filesystem model artifact verifier

**Acceptance criteria:**
- [ ] `FileSystemModelArtifactVerifier` created in `tramai.security.verification` package
- [ ] Takes `allowedRootDirectories: Set<Path>`, `manifests: Map<String, LocalModelArtifactManifestV1>`, `clock: Clock`
- [ ] No auto-discovery of manifests — receives parsed map at construction
- [ ] Verifies: manifest lookup → identity drift check → aggregate digest check (SHA-256 of `manifest.canonicalBytes()`) → file-by-file streaming SHA-256
- [ ] Filesystem hardening: rejects missing files, dir substitution, size mismatches, byte tampering
- [ ] **Symlink policy**: resolves symlinks; rejects only if resolved path escapes allowed roots (allows symlinks within root)
- [ ] Parent-chain symlink detection via `path.toRealPath() != path.toAbsolutePath().normalize()`
- [ ] Streaming hashing with 64KB buffer; no full memory load for large files
- [ ] `Math.addExact()` for `totalSizeBytes` to prevent integer overflow
- [ ] Fixed safe exception codes only (no raw filesystem paths in messages)
- [ ] UTF-8 encoding explicitly declared for canonical bytes
- [ ] Filesystem hardening tests pass (12+ scenarios)
- [ ] `./gradlew :tramai-security:test --rerun-tasks` green

### Phase 3: Sovereign Runtime Binding (tramai-sovereign)

**Scope:** Builder API extension, build-time verification for LOCAL routes

**Acceptance criteria:**
- [ ] `SovereignTramai.Builder` stores nullable `modelArtifactVerifier: ModelArtifactVerifier?` (not NoOp sentinel)
- [ ] `SovereignTramai.Builder.modelArtifactVerifier(verifier)` method added (matches existing delegation pattern)
- [ ] `SovereignTramai.Builder.modelArtifactVerificationSettings(settings)` method added
- [ ] Defaults: `null`, `ModelArtifactVerificationSettings(enabled = false)`
- [ ] `build()`: when enabled + verifier configured → use `runBlocking` to:
  - Iterate `primaryModelRoutes`
  - Lookup each via `modelRegistry.findApprovedModel(providerName, modelName)`
  - Check trust zone from `profile.providerZones`
  - For LOCAL zone: call `modelArtifactVerifier.verify(registeredModel)`, reject on failure
- [ ] `requireDigestForLocalModels=true`: rejects LOCAL models without `artifactDigest`
- [ ] CLOUD-zone models: skipped (existing behavior preserved)
- [ ] Verification receipts stored on `SovereignTramai`, exposed via `verificationReceipts(): List<VerifiedLocalModelArtifact>`
- [ ] No `verifyAll()` on runtime — receipts are build-time only
- [ ] Verification disabled: backward-compatible behavior preserved
- [ ] Sovereign runtime integration tests pass (8 scenarios)
- [ ] `./gradlew :tramai-sovereign:test --rerun-tasks` green

### Phase 4: Documentation

**Scope:** Module docs, spec status update

**Acceptance criteria:**
- [ ] `docs/modules/tramai-security.md` updated with verification section
- [ ] `docs/modules/tramai-sovereign.md` updated with builder API section
- [ ] SPEC-018 status set to `implemented` after merge
- [ ] Task doc status set to `Merged` after merge

## Exit Criteria

- [ ] All 3 test suites green: `tramai-core`, `tramai-security`, `tramai-sovereign`
- [ ] Root test suite green: `./gradlew test --rerun-tasks`
- [ ] Local model with valid manifest: runtime builds
- [ ] Local model without digest when verification required: runtime rejects
- [ ] Local model with unknown manifest: runtime rejects
- [ ] Local model with modified bytes: runtime rejects before provider invocation
- [ ] Cloud model without local artifact manifest: existing behavior preserved
- [ ] Verification disabled: backward-compatible behavior preserved
- [ ] No raw filesystem paths in exception messages
- [ ] Streaming hashing for large files (no full memory load)
