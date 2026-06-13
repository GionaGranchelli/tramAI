# SPEC-018: Local Model Artifact Verification

**Status:** implemented (PR #30)
**PR:** #30
**Branch:** feat/local-model-artifact-verification

## Problem Statement

The approved-model registry (SPEC-017) validates configured provider–model identity and declared registry revision metadata. However, it does not cryptographically prove the deployed model bytes on disk.

A declared `RegisteredModel` with `artifactDigest = sha256:abc...` is trusted as configured. The framework cannot yet prove:

> *"The local artifact file(s) on this filesystem match the approved cryptographic manifest."*

## Scope

Implement a local model artifact verification layer that:

1. Defines a versioned multi-file manifest contract (`LocalModelArtifactManifestV1`)
2. Specifies the meaning of `RegisteredModel.artifactDigest` (canonical manifest digest, hashed at the security layer)
3. Adds a `ModelArtifactVerifier` SPI in `tramai-core`
4. Provides a strict `FileSystemModelArtifactVerifier` in `tramai-security`
5. Binds verification to sovereign runtime build-time using `runBlocking` (single-block at startup)
6. Produces an immutable `VerifiedLocalModelArtifact` receipt per verified model, stored on `SovereignTramai`

## Non-Goals

This PR does NOT implement:
- Model downloads or remote registry synchronization
- Cloud attestation, TPM integration, or GPU detection
- Docker-image verification or Kubernetes admission control
- Background filesystem watcher or periodic re-attestation
- Ollama API introspection or automatic model-server launch
- Zero-egress network harness or offline runtime composition
- Proving an external inference server loaded the verified bytes

### Known Limitation: TOCTOU Gap

Artifact verification happens once at startup (build time). A file replaced between verification and first inference, or between inferences, is not detected. This is a conscious trade-off for startup-only verification. Deferred: periodic re-attestation and filesystem watchers.

## Core Contracts (tramai-core)

### Package-level field validation utility

Extract the `validateField` logic from `RegisteredModel` into a package-level function:

```kotlin
internal fun validateField(fieldName: String, value: String) {
    require(value.isNotBlank()) { "$fieldName must not be blank" }
    require(value == value.trim()) { "$fieldName must not have surrounding whitespace" }
    require(value.length <= 256) { "$fieldName must be at most 256 characters" }
    require(value.none(Char::isISOControl)) { "$fieldName must not contain control characters" }
}
```

Used by `RegisteredModel`, `LocalModelArtifactManifestV1`, and `LocalModelArtifactFileV1`.

### LocalModelArtifactManifestV1

```kotlin
class LocalModelArtifactManifestV1(
    val schemaVersion: Int,  // NO default — required to prevent aggregate-digest drift
    val registryEntryId: String,
    val providerId: String,
    val modelName: String,
    val revision: String,
    artifacts: List<LocalModelArtifactFileV1>,
) {
    val artifacts: List<LocalModelArtifactFileV1> =
        java.util.Collections.unmodifiableList(java.util.ArrayList(artifacts))

    init {
        require(schemaVersion == 1) { "Schema version must be 1" }
        validateField("registryEntryId", registryEntryId)
        validateField("providerId", providerId)
        validateField("modelName", modelName)
        validateField("revision", revision)
        require(this.artifacts.isNotEmpty()) { "At least one artifact file is required" }
        val paths = this.artifacts.map { it.relativePath }
        require(paths.distinct().size == paths.size) {
            "Duplicate artifact paths (case-sensitive comparison)"
        }
    }

    /**
     * Returns the canonical UTF-8 byte representation of this manifest
     * for use by the security layer's aggregate digest computation.
     */
    fun canonicalBytes(): ByteArray {
        val sb = StringBuilder()
        sb.append("schemaVersion=").append(schemaVersion).append('\n')
        sb.append("registryEntryId=").append(registryEntryId).append('\n')
        sb.append("providerId=").append(providerId).append('\n')
        sb.append("modelName=").append(modelName).append('\n')
        sb.append("revision=").append(revision).append('\n')
        sb.append("artifact_count=").append(artifacts.size).append('\n')
        artifacts.sortedBy { it.relativePath }.forEach { artifact ->
            sb.append("  relativePath=").append(artifact.relativePath).append('\n')
            sb.append("  sizeBytes=").append(artifact.sizeBytes).append('\n')
            sb.append("  digest=").append(artifact.digest.value).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
```

### LocalModelArtifactFileV1

```kotlin
data class LocalModelArtifactFileV1(
    val relativePath: String,
    val sizeBytes: Long,
    val digest: ModelArtifactDigest,
) {
    init {
        validateField("relativePath", relativePath)
        require(relativePath.isNotEmpty()) { "Path must not be empty" }
        require(!relativePath.startsWith("/")) { "Path must be relative (no Unix absolute paths)" }
        require(!relativePath.startsWith("\\\\")) { "Path must be relative (no UNC paths)" }
        require(relativePath.length < 2 || relativePath[1] != ':') {
            "Path must be relative (no Windows drive prefixes)"
        }
        val normalized = relativePath.replace('\\\\', '/')
        require(!normalized.startsWith("..")) { "Path must not traverse upward" }
        require(!normalized.contains("/../") && !normalized.endsWith("/..")) {
            "Path must not contain upward traversal"
        }
        require(!normalized.contains("/./") && !normalized.startsWith("./") && normalized != ".") {
            "Path must not contain self-reference segments"
        }
        require(!normalized.contains("//")) {
            "Path must not contain empty segments (double slashes)"
        }
        require(normalized.none(Char::isISOControl)) { "Path must not contain control characters" }
        require(normalized == relativePath) {
            "Path must use forward-slash separator consistently"
        }
        require(sizeBytes >= 0) { "sizeBytes must not be negative" }
    }
}
```

### VerifiedLocalModelArtifact

```kotlin
data class VerifiedLocalModelArtifact(
    val registryEntryId: String,
    val manifestDigest: ModelArtifactDigest,
    val modelName: String,
    val verifiedAt: Instant,
    val artifactCount: Int,
    val totalSizeBytes: Long,  // computed with Math.addExact() to prevent overflow
)
```

### ModelArtifactVerifier SPI

```kotlin
interface ModelArtifactVerifier {
    /**
     * Verifies that all artifact files declared in the manifest for
     * [registeredModel] match their expected digests and sizes.
     *
     * @throws IllegalArgumentException / IllegalStateException with fixed
     *   reason codes on any verification failure.
     */
    suspend fun verify(registeredModel: RegisteredModel): VerifiedLocalModelArtifact?
}

object NoOpModelArtifactVerifier : ModelArtifactVerifier {
    override suspend fun verify(registeredModel: RegisteredModel): VerifiedLocalModelArtifact? = null
}
```
Returns `null` to match the `NoOpModelRegistry.findApprovedModel()` returning `null` pattern. The `verify()` method has a nullable return type on the `NoOp` path.

### ModelArtifactVerificationSettings

```kotlin
data class ModelArtifactVerificationSettings(
    val enabled: Boolean = false,
    val requireDigestForLocalModels: Boolean = true,
)
```

`requireDigestForLocalModels` defaults to `true` for secure default. Once an operator opts into verification (`enabled = true`), the secure default provides registry-pinned verification. Set to `false` for transitional mode: per-file bytes verified against the supplied manifest, but the registry does not cryptographically pin that manifest.

## RegisteredModel.artifactDigest Canonical Meaning

`RegisteredModel.artifactDigest` stores the SHA-256 of the **canonical UTF-8 byte representation** of the `LocalModelArtifactManifestV1`, not the digest of any single file.

The digest is computed in the security layer (not in tramai-core) by hashing `manifest.canonicalBytes()` with `MessageDigest.getInstance("SHA-256")`.

Canonical input (UTF-8 encoded):

```
schemaVersion=1
registryEntryId=<id>
providerId=<id>
modelName=<name>
revision=<rev>
artifact_count=<N>
<sorted by relativePath:>
  relativePath=<path>
  sizeBytes=<N>
  digest=<digest>
  ... (repeat per artifact)
```

## ModelArtifactVerifier SPI (tramai-core)

Defined in tramai-core for dependency reasons (tramai-sovereign depends on tramai-core, not tramai-security). Implementation in tramai-security.

```kotlin
interface ModelArtifactVerifier {
    suspend fun verify(registeredModel: RegisteredModel): VerifiedLocalModelArtifact?
}
```

`NoOpModelArtifactVerifier` returns `null` (not configured).

## FileSystemModelArtifactVerifier (tramai-security)

```kotlin
class FileSystemModelArtifactVerifier(
    private val allowedRootDirectories: Set<Path>,
    private val manifests: Map<String, LocalModelArtifactManifestV1>,
    private val clock: Clock = Clock.systemUTC(),
) : ModelArtifactVerifier
```

### Manifest Loading

The `manifests: Map<String, LocalModelArtifactManifestV1>` is populated at build time by the consumer. The expected source is a JSON file per registry entry, loaded by the application and deserialized. `FileSystemModelArtifactVerifier` does NOT auto-discover or load manifest files — it receives the parsed map.

The application is responsible for:
1. Defining a manifest JSON file for each LOCAL model (e.g., `manifests/registry-entry-id.json`)
2. Loading and deserializing it to `LocalModelArtifactManifestV1`
3. Constructing `FileSystemModelArtifactVerifier` with the map

Future work may add auto-discovery from a convention-based directory.

### Verification Algorithm

For each `verify(registeredModel)` call:

1. **Lookup manifest** by `registeredModel.registryEntryId` in `manifests`
2. **Manifest identity drift check**: manifest's `registryEntryId`, `providerId`, `modelName`, `revision` must match the registered model
3. **Aggregate digest check**: SHA-256 of `manifest.canonicalBytes()` must equal `registeredModel.artifactDigest.value`
4. **Filesystem hardening**: for each artifact file path, try against each `allowedRootDirectory`:
   - Resolve `root.resolve(relativePath)` and check it exists under the root (no traversal)
   - Reject symlinks in the artifact path OR its parent chain by comparing `path.toRealPath()` with `path.toAbsolutePath().normalize()`
   - Reject missing files
   - Reject directories substituted for files
   - Reject unexpected file size (checked before AND after hashing)
   - Stream SHA-256 with 64KB buffer and compare against declared digest
5. **Return** `VerifiedLocalModelArtifact` receipt with `Math.addExact()` for total size

### Symlink Policy

Strict policy: **reject all symlinks** in the artifact path or parent chain.

Implementation: `path.toRealPath() != path.toAbsolutePath().normalize()` detects symlinks anywhere in the path chain without distinguishing file-level from parent-directory symlinks.

A relaxed symlink mode (allow symlinks whose resolved target is within an allowed root) may be added later if a real deployment requires it.

### Streaming Hashing

```kotlin
try {
    Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536) // 64KB
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
} catch (_: Exception) {
    error("artifact-file-access-failed")
}
```

### Filesystem Hardening Rules

| Threat | Defense |
|--------|---------|
| Path traversal | Reject absolute paths and paths escaping an allowed root |
| Symlink substitution | Reject all symlinks (strict policy) |
| Missing file | Reject |
| Directory substituted for file | Reject |
| Unexpected file size | Reject before and after hashing |
| Byte tampering | Stream SHA-256 and reject mismatch |
| Duplicate paths | Reject manifest construction (case-sensitive) |
| Blank or malformed paths | Reject |
| Control characters | Reject |
| Manifest identity drift | Reject |
| Registry digest mismatch | Reject |
| Integer overflow | `Math.addExact()` for `totalSizeBytes` |
| Secret leakage | Fixed reason codes only; no raw filesystem paths in public exceptions |
| UTF-8 encoding | Canonical bytes explicitly UTF-8; digest computed on UTF-8 bytes |

### Exception Messages

Fixed safe codes only:
- `artifact-manifest-not-found`
- `artifact-manifest-identity-drift`
- `artifact-aggregate-digest-mismatch`
- `artifact-file-not-found`
- `artifact-file-symlink-rejected`
- `artifact-file-size-mismatch`
- `artifact-file-digest-mismatch`
- `artifact-file-access-failed`
- `artifact-traversal-rejected`
- `artifact-directory-substituted-for-file`
- `artifact-not-a-regular-file`
- `artifact-total-size-overflow`
- `artifact-verification-not-configured`

## Sovereign Runtime Binding

### Builder API Extension

Add to `SovereignTramai.Builder`:

```kotlin
private var modelArtifactVerifier: ModelArtifactVerifier? = null
private var verificationSettings: ModelArtifactVerificationSettings = ModelArtifactVerificationSettings()

fun modelArtifactVerifier(verifier: ModelArtifactVerifier): Builder = apply {
    this.modelArtifactVerifier = verifier
}

fun modelArtifactVerificationSettings(settings: ModelArtifactVerificationSettings): Builder = apply {
    this.verificationSettings = settings
}
```

### build() Behavior

In `SovereignTramai.Builder.build()`:

When `verificationSettings.enabled`:
- `checkNotNull(modelArtifactVerifier)` — fail closed if verifier is missing
- Use `runBlocking` (single blocking call at startup) to iterate **unique** (providerName, modelName) targets from primary AND fallback routes
- For each: check trust zone from `profile.providerZones` first
- For CLOUD-zone providers: skip without touching the model registry
- For LOCAL-zone providers: `modelRegistry.findApprovedModel(providerName, modelName)` then verify
  - If `requireDigestForLocalModels == true`: require `registeredModel.artifactDigest != null` (``IllegalStateException`` if missing)
  - Call `modelArtifactVerifier.verify(registeredModel)`
  - Sanitize SPI exceptions through a fixed-code allowlist; drop arbitrary ModelRegistry SPI cause
  - If returns `null` or throws: fail `build()` with `IllegalStateException`
  - Collect receipts
- Store receipts on `SovereignTramai` for the runtime lifetime

```kotlin
// In build():
private val verificationReceipts: List<VerifiedLocalModelArtifact> = ...
```

### Verification Timing

- **Verify during sovereign runtime build** — single `runBlocking` call at startup
- **Store receipts on `SovereignTramai`** — accessible via `receipts()` method
- **Fail startup on mismatch** — framework refuses to start with compromised artifacts
- **No rehashing on every inference** — receipts are immutable and retained for the runtime lifetime

### Receipt Access

```kotlin
class SovereignTramai private constructor(
    private val delegate: Tramai,
    private val verificationReceipts: List<VerifiedLocalModelArtifact>,
) {
    /** Returns immutable verification receipts from build-time artifact verification. */
    fun verificationReceipts(): List<VerifiedLocalModelArtifact> = verificationReceipts
    // ...
}
```

No `verifyAll()` on `SovereignTramaiRuntime` — it's on `SovereignTramai` only, populated at build time. Deferred: runtime re-verification as a follow-up capability.

## Test Matrix

### Manifest Tests (tramai-core)

| Test | Expected |
|------|----------|
| Valid single-file GGUF-style artifact | Pass |
| Valid multi-file artifact set | Pass |
| Duplicate artifact path | Reject |
| Blank path | Reject |
| Absolute path | Reject |
| `../` traversal | Reject |
| Invalid schema version | Reject |
| Manifest identity differs from registry identity | Reject |
| Aggregate manifest digest mismatch | Reject |
| `canonicalBytes()` produces deterministic UTF-8 output | Pass |
| Missing artifact list (empty) | Reject |
| `schemaVersion` required (no default) | Constructor must specify |

### Filesystem Tests (tramai-security)

| Test | Expected |
|------|----------|
| Correct bytes and size | Pass |
| One modified byte | Reject |
| File truncated | Reject |
| Extra bytes appended | Reject |
| Missing file | Reject |
| Directory substitution | Reject |
| Artifact symlink to allowed root | Pass (resolved path within root) |
| Artifact symlink escaping allowed root | Reject |
| Parent-directory symlink to allowed root | Pass (resolved path within root) |
| Parent-directory symlink escaping allowed root | Reject |
| Artifact outside allowed root | Reject |
| Large artifact hashed incrementally | Pass without full-memory loading |
| Empty file (0 bytes) | Pass |
| `totalSizeBytes` overflow detection | `Math.addExact()` throws |
| Error message contains no filesystem paths | Security test: only fixed codes |
| Concurrent verification (same model, two threads) | Pass (suspend-safe reads) |

### Sovereign Runtime Integration Tests (tramai-sovereign)

| Test | Expected |
|------|----------|
| Local model with valid manifest | Runtime builds, receipts present |
| Local model without digest when `requireDigestForLocalModels=true` | Runtime rejects |
| Local model with unknown manifest | Runtime rejects |
| Local model with modified bytes | Runtime rejects before provider invocation |
| Cloud model without local artifact manifest | Existing behavior preserved |
| Verification disabled (`enabled=false`) | Backward-compatible behavior preserved |
| No verifier configured (default) | Backward-compatible behavior preserved |
| Receipts accessible after build | `verificationReceipts()` returns non-empty list |

## Module Changes

### tramai-core
- Extract `validateField()` as package-level utility function
- Add `LocalModelArtifactManifestV1`, `LocalModelArtifactFileV1`
- Add `VerifiedLocalModelArtifact`
- Add `ModelArtifactVerifier` SPI
- Add `NoOpModelArtifactVerifier` (returns `null`)
- Add `ModelArtifactVerificationSettings`

### tramai-security
- Add `FileSystemModelArtifactVerifier`
- Add `FileSystemModelArtifactVerifier` unit tests

### tramai-sovereign
- Add builder extension: `.modelArtifactVerifier()`, `.modelArtifactVerificationSettings()`
- Wire verification into `build()` using `runBlocking` for LOCAL-zone routes
- Store `verificationReceipts: List<VerifiedLocalModelArtifact>` on `SovereignTramai`
- Expose `verificationReceipts()` method
- Add integration tests

## Documentation

- `docs/specs/spec-018-local-model-artifact-verification.md` — this file
- `docs/board/tasks/task-pr30-local-model-artifact-verification.md` — task tracking
- `docs/modules/tramai-security.md` — add verification section
- `docs/modules/tramai-sovereign.md` — add builder API + receipt section

## Roadmap After PR #30

| PR | Scope |
|----|-------|
| PR #29 | ✅ Encrypted suspended invocation store and restart-safe recovery |
| PR #30 | ✅ Completed — local-model artifact manifest and byte-level verification |
| PR #31 | Offline runtime profile and zero-egress verification harness |
