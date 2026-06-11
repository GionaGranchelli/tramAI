# SPEC-017: Approved Model Registry and Sovereign Model Pinning

**Status:** implemented
**PR:** #23
**Branch:** feat/approved-model-registry

## Problem Statement

TramAI supports provider allowlists, classification routing, DLP, audit, and approval gates. However, provider and model identities are still trusted primarily as configured strings. The framework cannot yet enforce:

> *"This exact provider–model deployment is explicitly approved for use by this runtime."*

## Scope

Implement an approved-model registry SPI with:
- Immutable registered model descriptor with identity, revision, and optional artifact digest
- `ModelRegistry` SPI (interface + no-op default)
- `InMemoryModelRegistry` implementation in `tramai-security`
- Central runtime enforcement via `ModelRegistryEnforcer` in `tramai-engine`
- Cache provenance extension for registry-bound cache entries
- Standalone builder wiring
- Spring Boot auto-configuration wiring
- Deterministic tests across all layers

## Non-Goals (explicitly excluded)

This PR does NOT implement:
- GGUF, Safetensors, or Ollama artifact-byte reading
- Model downloads or remote attestation
- TPM integration or GPU detection
- JDBC or file-backed registry persistence
- The executable Sovereign Document Intelligence reference workflow
- Approval continuation changes or stale-claim recovery changes
- UI or automatic registry synchronization

## Core Contracts (tramai-core)

### ModelArtifactDigest

```kotlin
@JvmInline
value class ModelArtifactDigest private constructor(val value: String) {
    companion object {
        fun of(raw: String): ModelArtifactDigest
    }
}
```

Format: `sha256:<64 lowercase hex characters>`. Rejects uppercase hex, blank, invalid format.

### RegisteredModel

```kotlin
data class RegisteredModel(
    val registryEntryId: String,
    val providerId: String,
    val modelName: String,
    val revision: String,
    val artifactDigest: ModelArtifactDigest? = null,
    val enabled: Boolean = true,
)
```

Validation: all string fields non-blank, no surrounding whitespace, no ISO control characters, max lengths enforced. Duplicate `providerId + modelName` rejected.

### ModelRegistry SPI

```kotlin
interface ModelRegistry {
    suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel?
}

object NoOpModelRegistry : ModelRegistry
```

### ModelRegistrySettings

```kotlin
data class ModelRegistrySettings(
    val enabled: Boolean = false,
)
```

When `enabled == false`, registry lookup has no effect on existing behavior. When `enabled == true`, unknown/disabled registry results fail closed.

### Exception Hierarchy

- `ModelRegistryException` (base)
- `ModelNotRegisteredException`
- `ModelDisabledException`
- `ModelRegistryUnavailableException`
- `ModelRegistryContractViolationException`
- `CachedModelProvenanceMismatchException`

All exceptions have sanitized messages (no raw prompts, secrets, model paths, or internal URLs). `CancellationException` propagates unchanged.

## Registry Enforcement (tramai-engine)

### ModelRegistryEnforcer

Internal helper class that authorizes `(providerId, modelName)` pairs:

- `enabled == false` → return `null` without enforcement
- `enabled == true` → query registry → sanitize adapter exceptions → reject missing → reject disabled → validate returned descriptor → return approved descriptor

### Enforcement Boundaries

| Path | Behavior |
|------|----------|
| Initial non-streaming invocation | Authorize before provider call |
| Initial streaming invocation | Authorize inside cold-flow before streaming |
| Fallback route | Independently authorize fallback pair |
| Circuit-breaker skip to fallback | Authorize fallback pair |
| Structured-output retry | Every provider invocation authorized |
| Tool loop | Subsequent provider calls re-authorized |
| Resumed approval workflow | Authorized through shared path |
| Cache reuse | Revalidate against current registry |

### No Silent Fallback After Registry Denial

Registry rejection is a security decision, not a provider availability failure. Must NOT:
- Catch registry rejection and proceed to another provider
- Count as retryable provider error
- Update circuit-breaker counters
- Trigger fallback after unknown/disabled model

## In-Memory Registry (tramai-security)

Immutable defensive snapshot after construction. Deterministic lookup by `providerId + modelName`. Rejects duplicates and malformed entries during construction. Thread-safe reads. No silent normalization.

## Cache Provenance Extension

Extend `CachedResponseProvenance` with nullable fields:
- `modelRegistryEntryId`
- `modelRevision`
- `modelArtifactDigest`

When registry enforcement is enabled, cache writes persist the approved registry snapshot. Cache hits reauthorize against current registry. Reject cache reuse if entry missing, disabled, or metadata changed.

## Composition Wiring

### Standalone Builder

```kotlin
Tramai.builder()
    .modelRegistry(registry)
    .modelRegistrySettings(settings)
```

Defaults: `NoOpModelRegistry`, `ModelRegistrySettings(enabled = false)`.

### Spring Boot

```yaml
tramai:
  security:
    model-registry:
      enabled: true
```

Optional `ModelRegistry` bean resolution (zero → no-op, one → wired, multiple → fail startup). Optional `ModelRegistrySettings` bean resolution (same pattern).

## Trust-Zone Routing vs Model Registry

| Concern | Who Owns It | Question Answered |
|---------|------------|-------------------|
| May this classification reach this trust zone? | Existing `ProviderRoutingConfiguration` (tramai-security) | Routing matrix |
| Is this exact provider-model deployment approved? | `ModelRegistry` (tramai-core SPI, tramai-security impl) | Model identity pinning |

Both checks are independent and both required for sovereign operation.

## Limitations

The approved-model registry validates configured provider–model identity and declared registry revision metadata. It does not cryptographically prove the deployed model bytes, runtime image, GPU host, or network isolation boundary. Artifact-byte verification and runtime attestation are separate follow-up capabilities.
