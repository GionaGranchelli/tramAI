# Module: `tramai-sovereign`

> **One-liner:** Secure embedded runtime profile that wires deny-by-default policy enforcement, approved-model registry enforcement, classification-aware provider routing, and hash-chained policy-decision audit emission without requiring the SaaS platform.
> **Module type:** `composition` + `secure profile`
> **Source files:** 2 files — `SovereignTramai.kt`, `SovereignProfileConfiguration.kt`

## Purpose

`tramai-sovereign` is the dependency aggregator and secure embedded runtime profile for sovereign TramAI deployments.

Its job is to compose existing security primitives (PolicyEngine, ModelRegistry, AuditStore, ProviderRoutingConfiguration) into a single, narrow configuration surface that prevents accidental fallback to permissive defaults.

## Threat Boundary

The sovereign profile validates configured provider-model identity and declared registry metadata. It does **not** yet verify:
- Deployed model bytes
- Runtime images or GPU hosts
- Network-isolation boundaries

These are separate follow-up capabilities tracked on the Phase 2 roadmap.

## Embedded Deployment Model

`tramai-sovereign` is designed to work embedded in a JVM application process without requiring:
- `tramai-platform` (SaaS platform)
- `tramai-server` (REST API server)
- `tramai-dashboard` (admin UI)
- Spring Boot (optional — profile works standalone)
- External databases or control plane

## Required Configuration

Every sovereign deployment must provide:

| Input | Required | Description |
|-------|----------|-------------|
| `SovereignProfileConfiguration` | Yes | Model, provider, tool allowlists and trust zones |
| `ModelRegistry` | Yes | Approved model identities |
| `AuditStore` | Yes | Hash-chained policy-decision audit storage |
| At least one `ModelProvider` | Yes | Registered with explicit trust zone |
| At least one model mapping | Yes | Maps a model name to a registered provider |

## Secure Defaults

- **Policy engine:** `DefaultPolicyEngine` with `PolicyConfiguration.secure()` (deny-by-default)
- **Model registry enforcement:** Always enabled
- **Provider routing matrix:** Always enabled (sovereign defaults)
- **Legacy permissive mode:** Not reachable through the sovereign API
- **Wildcard allowlists:** Rejected at construction time
- **SaaS platform dependency:** None

## Usage

```kotlin
// Build the approved-model registry
val registry = InMemoryModelRegistry.builder()
    .register(
        RegisteredModel(
            registryEntryId = "local-llama-3",
            providerId = "ollama",
            modelName = "llama3.2",
            revision = "2026-06",
        ),
    )
    .build()

// Build the sovereign runtime
val tramai = SovereignTramai.builder()
    .profile(
        SovereignProfileConfiguration(
            allowedModels = setOf("llama3.2"),
            allowedProviders = setOf("ollama"),
            providerZones = mapOf(
                "ollama" to ProviderTrustZone.LOCAL,
            ),
        ),
    )
    .modelRegistry(registry)
    .auditStore(InMemoryAuditStore())
    .provider(ollamaProvider, name = "ollama", default = true)
    .model("llama3.2", "ollama")
    .build()

// Create a service proxy
val service = tramai.create<MyService>()
```

## Dependencies

| Module | Type | Reason |
|--------|------|--------|
| `tramai-standalone` | API | Delegates runtime construction to the standalone builder |
| `tramai-security` | API | Supplies `DefaultPolicyEngine`, `AuditEngine`, audit emitter, routing configuration |

`tramai-platform` is **not** a dependency and is not required on the classpath.

## Build-Time Validation

`SovereignTramai.Builder.build()` validates at construction time:

- Profile configuration is present
- Model registry is present
- Audit store is present
- At least one provider is registered
- Every registered provider has an explicit trust zone
- Every configured model maps to an allowed provider

## Security Invariants

| Invariant | Mechanism |
|-----------|-----------|
| Missing model registry | Build fails with `IllegalStateException` |
| Missing audit store | Build fails with `IllegalStateException` |
| Unknown model | Policy engine denies before provider invocation |
| Disabled registered model | Registry enforcer denies before provider invocation |
| RESTRICTED data routed to GLOBAL_CLOUD | Routing matrix blocks before provider invocation |
| Unknown tool | Policy engine denies before tool execution |
| HIGH-risk tool | Policy engine requires human approval |
| Policy decision | Hash-chained audit event emitted via `AuditEnginePolicyDecisionAuditEmitter` |
| Legacy permissive mode | Not reachable through sovereign API |

## Limitations

- The sovereign profile validates configured provider-model identity and declared registry metadata.
- It does **not** verify deployed model bytes, runtime images, GPU hosts, or network-isolation boundaries.
- Artifact-byte verification is tracked as a follow-up capability (Phase 2).

## Module Source

- **Package:** `dev.tramai.sovereign`
- **Main types:** `SovereignTramai`, `SovereignProfileConfiguration`

## Follow-Up Roadmap

| PR | Capability |
|----|------------|
| #25 | Executable Sovereign Document Intelligence reference workflow |
| #26 | Approval timeout auto-deny |
| #27 | Zero-egress verification harness |
| #28 | Artifact-byte verification |
| #29 | Evidence pack and SBOM |
