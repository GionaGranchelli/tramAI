# PR #38 — Auto-load Release Bundle Implementation Plan

> **Goal:** Automatically load `build/sovereign-release/release-artifacts-v1.json` into `ReleaseBundleEvidenceV1` and include it in the generated evidence pack.

**Architecture:** The loader lives in tramai-sovereign with manual JSON parsing (no heavy JSON dependency — matches the project's existing manual JSON pattern in `SovereignEvidencePackWriter`). The offline verification harness reads the manifest via `--release-bundle-manifest=` CLI arg, loads it, and passes to `SovereignEvidencePackGenerator.generate(..., releaseBundle = ...)`.

---

## Files Changed

| File | Action | Reason |
|------|--------|--------|
| `tramai-sovereign/src/main/kotlin/.../evidence/ReleaseBundleEvidenceLoader.kt` | CREATE | JSON parser for release-artifacts-v1.json → ReleaseBundleEvidenceV1 |
| `examples/.../offline/OfflineVerificationMain.kt` | MODIFY | Add --release-bundle-manifest arg |
| `scripts/verify-zero-egress.sh` | MODIFY | Pass `$TRAMAI_RELEASE_BUNDLE_MANIFEST` as `--release-bundle-manifest=` |
| `build.gradle.kts` | MODIFY | Make prepareSovereignEvidenceBundle pure; add verifySovereignEvidencePackContainsReleaseBundle |
| `.github/workflows/ci.yml` | MODIFY | Add release manifest steps to zero-egress job |
| `tramai-sovereign/src/test/kotlin/.../evidence/ReleaseBundleEvidenceLoaderTest.kt` | CREATE | Loader unit tests |
| `tramai-sovereign/src/test/kotlin/.../evidence/SovereignEvidencePackIntegrationTest.kt` | MODIFY | Add integration test for release bundle |

---

## Quick-start sequence

1. Create loader + write unit tests
2. Update OfflineVerificationMain to accept `--release-bundle-manifest=`
3. Update verify-zero-egress.sh
4. Make prepareSovereignEvidenceBundle pure + add verification task
5. Update CI
6. Add integration test
7. Run full validation

---

## Phase 1: ReleaseBundleEvidenceLoader

**File:** `tramai-sovereign/src/main/kotlin/dev/tramai/sovereign/evidence/ReleaseBundleEvidenceLoader.kt`

Manual JSON parser for `release-artifacts-v1.json`. Since tramai-sovereign has no JSON library dependency, parse the known deterministic format character-by-character (matching the project's manual approach in `SovereignEvidencePackWriter`).

Validation rules (matching the spec error codes):
- File missing → `release-bundle-evidence-missing`
- Invalid JSON → `release-bundle-evidence-invalid-json`
- schemaVersion != 1 → `release-bundle-evidence-unsupported-schema-version`
- artifacts field missing → `release-bundle-evidence-missing-artifacts`
- artifacts list empty → `release-bundle-evidence-empty-artifacts`
- Missing/invalid field → `release-bundle-evidence-invalid-artifact-entry`
- Blank fileName / path traversal → `release-bundle-evidence-unsafe-file-name`
- Invalid digest format → `release-bundle-evidence-invalid-digest-format`
- sizeBytes <= 0 → `release-bundle-evidence-invalid-size`
- extension != "jar" → `release-bundle-evidence-unsupported-extension`
- Duplicate fileName → `release-bundle-evidence-duplicate-file-name`
- Duplicate coordinate → `release-bundle-evidence-duplicate-coordinate`

API:
```kotlin
object ReleaseBundleEvidenceLoader {
    @JvmStatic
    fun load(path: Path): ReleaseBundleEvidenceV1
}
```

## Phase 2: OfflineVerificationMain

Add to `OfflineVerificationMain.kt`:
- Parse `--release-bundle-manifest=` argument
- Load via `ReleaseBundleEvidenceLoader.load(...)` 
- Pass to `tramai.evidencePack(releaseBundle = loadedReleaseBundle, ...)`

## Phase 3: verify-zero-egress.sh

If `TRAMAI_RELEASE_BUNDLE_MANIFEST` is set, mount the manifest file into the Docker container and pass `--release-bundle-manifest=` to the application.

## Phase 4: build.gradle.kts

1. Remove `dependsOn(":prepareCycloneDxBom", ":prepareSovereignReleaseArtifacts")` from `prepareSovereignEvidenceBundle`
2. Add `verifySovereignEvidencePackContainsReleaseBundle` task:
   - Reads `build/zero-egress-report/sovereign-evidence-pack-v1.json`
   - Checks `"releaseBundle"` is not null
   - Fails with `sovereign-evidence-pack-missing-release-bundle`

## Phase 5: CI (ci.yml)

Zero-egress job becomes:
```
prepareSovereignReleaseArtifacts
verifySovereignReleaseManifest
verify-zero-egress.sh (with TRAMAI_RELEASE_BUNDLE_MANIFEST)
prepareSovereignEvidenceBundle
verifySovereignEvidenceBundleReleaseManifest
```

## Phase 6: Tests

- Loader unit tests: 18+ test cases covering all error codes
- Integration test: generate evidence pack with release bundle, assert `"releaseBundle"` present
