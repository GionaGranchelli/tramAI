# Module: `tramai-sovereign`

> **One-liner:** Secure embedded runtime profile that wires deny-by-default policy enforcement, approved-model registry enforcement, classification-aware provider routing, offline deployment validation, artifact-byte verification, and hash-chained policy-decision audit emission without requiring the SaaS platform.
> **Module type:** `composition` + `secure profile`
> **Source files:** 3 files — `SovereignTramai.kt`, `SovereignProfileConfiguration.kt`, `SovereignDeploymentMode.kt`

## Purpose

`tramai-sovereign` is the dependency aggregator and secure embedded runtime profile for sovereign TramAI deployments.

Its job is to compose existing security primitives (PolicyEngine, ModelRegistry, AuditStore, ProviderRoutingConfiguration) into a single, narrow configuration surface that prevents accidental fallback to permissive defaults.

## Threat Boundary

The sovereign profile validates:
- Configured provider-model identity and declared registry metadata (SPEC-017)
- Deployed model bytes via artifact manifest and streaming SHA-256 verification (SPEC-018)
- Local-only provider composition in `OFFLINE` deployment mode (SPEC-019)

It does **not** yet:
- Verify runtime images or GPU hosts
- Enforce infrastructure-level network isolation (firewalls, NetworkPolicy, sandboxing — this is a shared responsibility)
- Periodically re-attest model artifact files

## Offline Deployment Mode

`SovereignDeploymentMode.OFFLINE` enforces that every registered provider, primary route, fallback route, and default provider targets `ProviderTrustZone.LOCAL`. This is a build-time composition contract — it does not replace infrastructure-level network isolation.

See SPEC-019 and `examples/sovereign-offline-verification/` for the reference zero-egress verification harness.

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
| `SovereignProfileConfiguration` | Yes | Model, provider, tool allowlists, trust zones, and deployment mode |
| `ModelRegistry` | Yes | Approved model identities |
| `AuditStore` | Yes | Hash-chained policy-decision audit storage |
| At least one `ModelProvider` | Yes | Registered with explicit trust zone |
| At least one model mapping | Yes | Maps a model name to a registered provider |

## Secure Defaults

- **Policy engine:** `DefaultPolicyEngine` with `PolicyConfiguration.secure()` (deny-by-default)
- **Model registry enforcement:** Always enabled (cannot be disabled through sovereign API)
- **Provider routing matrix:** Always enabled (sovereign defaults)
- **Legacy permissive mode:** Not reachable through the sovereign API
- **Wildcard allowlists:** Rejected at construction time
- **Deployment mode:** `STANDARD` by default
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

### Offline deployment with artifact verification

```kotlin
val tramai = SovereignTramai.builder()
    .profile(
        SovereignProfileConfiguration(
            allowedModels = setOf("offline-test-model"),
            allowedProviders = setOf("loopback-local-provider"),
            providerZones = mapOf("loopback-local-provider" to ProviderTrustZone.LOCAL),
            deploymentMode = SovereignDeploymentMode.OFFLINE,
        ),
    )
    .modelRegistry(registry)
    .auditStore(auditStore)
    .provider(loopbackProvider, name = "loopback-local-provider", default = true)
    .model("offline-test-model", "loopback-local-provider")
    .modelArtifactVerifier(verifier)
    .modelArtifactVerificationSettings(ModelArtifactVerificationSettings(enabled = true))
    .build()

// Verification receipts are accessible after build
val receipts = tramai.verificationReceipts()
```

## Dependencies

| Module | Type | Reason |
|--------|------|--------|
| `tramai-standalone` | API | Delegates runtime construction to the standalone builder |
| `tramai-security` | API | Supplies `DefaultPolicyEngine`, `AuditEngine`, audit emitter, routing configuration |

`tramai-platform` is **not** a dependency and is not required on the classpath.

## Build-Time Validation

`SovereignTramai.Builder.build()` validates the immutable provider routing plan at construction time:

- Profile configuration is present
- Model registry is present
- Audit store is present
- At least one registered provider
- Every registered provider appears in `allowedProviders`
- Every `allowedProviders` entry has a registered provider
- Every registered provider has an explicit trust zone
- Every allowed model has a primary route
- Each primary route targets a registered, allowed provider
- Fallback providers appear in `allowedFallbackProviders`
- Fallback models appear in `allowedModels`
- Duplicate provider registrations are rejected
- In `OFFLINE` mode: every registered provider, primary route, fallback route, and default provider targets `LOCAL`
- When artifact verification enabled: LOCAL models are verified against their manifest

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
| Offline mode with non-LOCAL provider | Build fails with fixed safe reason code |
| Artifact byte tampering | Streaming SHA-256 verification; build fails before provider invocation |

## Evidence Pack

`SovereignTramai.evidencePack()` generates a deterministic [SovereignEvidencePackV1] —
a safe-for-auditors JSON artifact that captures the deployment's security posture:

| Field | Source |
|-------|--------|
| `schemaVersion` | Fixed at 1 |
| `deploymentMode` | `SovereignProfileConfiguration.deploymentMode` |
| `allowedModels` | Sorted from `SovereignProfileConfiguration.allowedModels` |
| `allowedProviders` | Sorted from `SovereignProfileConfiguration.allowedProviders` |
| `providerZones` | Mapped from `SovereignProfileConfiguration.providerZones` |
| `artifactVerificationSettings` | From `modelArtifactVerificationSettings` |
| `artifacts` | From `verificationReceipts()` |
| `zeroEgress` | Optional subsection from offline harness |
| `auditChain` | Optional subsection from audit-chain validation |
| `supplyChain` | Optional subsection from CycloneDX SBOM linkage |
| `attestation` | Optional subsection from GitHub Artifact Attestations CI/CD provenance |
| `generatedAt` | ISO-8601 instant at generation time |

**Security invariants:**
- Contains no secrets, tokens, prompts, stack traces, or filesystem paths
- Safe for CI artifact upload and auditor review
- Deterministic field ordering for diff-compatible output
- Full JSON control-character escaping via `SovereignEvidencePackWriter`

**Usage:**
```kotlin
val pack = tramai.evidencePack(
    zeroEgress = zeroEgressResult,
    auditChain = auditChainResult,
    supplyChain = supplyChainResult,
    attestation = attestationResult,
)
SovereignEvidencePackWriter.write(pack, Path.of("build/sovereign-evidence", "sovereign-evidence-pack-v1.json"))
```

The offline verification harness overrides this path via `--evidence-path=/out/sovereign-evidence-pack-v1.json`.

## Limitations

- Artifact verification happens once at build time — periodic re-attestation is deferred
- `OFFLINE` mode is a composition contract, not a firewall — production network isolation requires infrastructure controls
- The zero-egress verification harness (`examples/sovereign-offline-verification/`) proves loopback model invocation inside `--network=none`, but does not replace production firewall, NetworkPolicy, or sandboxing

## Module Source

- **Package:** `dev.tramai.sovereign`
- **Main types:** `SovereignTramai`, `SovereignProfileConfiguration`, `SovereignDeploymentMode`

## Follow-Up Roadmap

| PR | Capability |
|----|------------|
| #29 | ✅ Encrypted suspended invocation store and restart-safe recovery |
| #30 | ✅ Local-model artifact manifest and byte-level verification |
| #31 | ✅ Offline runtime profile and zero-egress verification harness |
| #32 | ✅ Sovereign evidence pack for auditor-safe deployment attestation |
| #33 | ✅ CycloneDX SBOM generation and evidence linkage |
| #34 | 🚧 GitHub Artifact Attestations for CI/CD provenance |
