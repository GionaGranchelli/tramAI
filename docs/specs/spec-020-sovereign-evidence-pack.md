# SPEC-020: Sovereign Evidence Pack

**Status:** implemented (PR #32)
**PR:** #32
**Branch:** feat/sovereign-evidence-pack

## Problem Statement

PR #29 (encrypted sovereign stores), PR #30 (model artifact verification), and PR #31 (offline runtime profile and zero-egress harness) each produce security-relevant state: verification receipts, deployment configuration, offline probes, and audit-chain validity. However, there is no single deterministic artifact that summarizes this state for:

- CI pipeline attestation
- Enterprise security review
- Grant reporting for sovereign AI deployments
- Future air-gap validation evidence chains

This spec defines a **Sovereign Evidence Pack V1**: a deterministic, safe-for-auditors JSON artifact that captures the security posture of a sovereign TramAI deployment without leaking prompts, payloads, tokens, secrets, stack traces, or filesystem paths.

## Scope

Implement:

1. Evidence DTOs (`SovereignEvidencePackV1`, `ArtifactEvidenceV1`, `ZeroEgressEvidenceV1`, `AuditChainEvidenceV1`)
2. `SovereignEvidencePackWriter` — deterministic JSON serializer with full control-character escaping
3. `SovereignEvidencePackGenerator` — collects state from `SovereignTramai`, verification receipts, optional zero-egress results, and optional audit-chain results
4. `SovereignTramai.evidencePack(...)` method for convenient generation
5. Integration with `examples/sovereign-offline-verification` — generates evidence pack alongside existing zero-egress report
6. CI upload of evidence pack as GitHub Actions artifact
7. Unit and integration tests
8. Documentation (SPEC-020, task doc, module docs, security model update)

## Non-Goals

- SBOM generation
- SLSA provenance
- Image signing
- Runtime host attestation
- Periodic model re-attestation
- Full audit event export
- Exposing prompts, payloads, tool arguments, approval tokens, secrets, stack traces, or filesystem paths

## Evidence DTOs

### SovereignEvidencePackV1

Top-level container:

```kotlin
data class SovereignEvidencePackV1(
    val schemaVersion: Int = 1,
    val deploymentMode: String,
    val allowedModels: List<String>,
    val allowedProviders: List<String>,
    val providerZones: Map<String, String>,
    val artifactVerificationSettings: Map<String, Any?>,
    val artifacts: List<ArtifactEvidenceV1>,
    val zeroEgress: ZeroEgressEvidenceV1?,
    val auditChain: AuditChainEvidenceV1?,
    val generatedAt: String,
)
```

### ArtifactEvidenceV1

Summary of artifact verification for one model:

```kotlin
data class ArtifactEvidenceV1(
    val registryEntryId: String,
    val manifestDigest: String,
    val modelName: String,
    val verifiedAt: String,
    val artifactCount: Int,
    val totalSizeBytes: Long,
)
```

### ZeroEgressEvidenceV1

Summarizes the zero-egress verification result:

```kotlin
data class ZeroEgressEvidenceV1(
    val deploymentMode: String,
    val runtimeBuildSucceeded: Boolean,
    val loopbackProviderInvocationSucceeded: Boolean,
    val loopbackProviderInvocationCount: Int,
    val externalTcpProbeBlocked: Boolean,
    val externalDnsProbeBlocked: Boolean,
)
```

### AuditChainEvidenceV1

Aggregate audit-chain result:

```kotlin
data class AuditChainEvidenceV1(
    val isValid: Boolean,
    val totalEvents: Int,
)
```

## Evidence Writer

`SovereignEvidencePackWriter.write(pack: SovereignEvidencePackV1, path: Path)` produces deterministic JSON with:

- Stable field ordering matching data class declaration order
- Full JSON control-character escaping (all chars < 0x20 as `\uXXXX`)
- No external JSON library dependency

## Evidence Generator

`SovereignEvidencePackGenerator.generate(...)` collects from:

1. `SovereignTramai` — deployment mode, provider zones, verification settings, verification receipts
2. `SovereignProfileConfiguration` — allowed models, allowed providers
3. Optional `ZeroEgressVerificationReportV1` — probe results
4. Optional `AuditChainEvidenceV1` — aggregate audit summary

Added to `SovereignTramai`:

```kotlin
fun evidencePack(
    zeroEgressReport: ZeroEgressVerificationReportV1? = null,
    auditChainResult: AuditChainEvidenceV1? = null,
): SovereignEvidencePackV1
```

## Evidence Identifier Sanitizer

`EvidenceSafeString.sanitize(value: String): String` validates that identifiers written into the evidence pack do not contain fragments that could leak sensitive information:

- Rejects path prefixes (`/tmp/`, `/home/`, `/Users/`)
- Rejects secrets-adjacent terms (`token`, `secret`, `password`, `prompt`, `rawRequest`, `rawResponse`, `stacktrace`)
- Rejects ISO control characters
- Throws `IllegalArgumentException` with a fixed safe reason code on any violation

Applied in `SovereignEvidencePackGenerator.generate()` to: `allowedModels`, `allowedProviders`, `providerZones` keys and values, `registryEntryId`, `manifestDigest`, and `modelName` — **before** the DTOs are constructed.

## Security Invariants

- Evidence pack must be safe to share with auditors
- Evidence pack must not contain secrets or sensitive runtime data
- Evidence pack must summarize verification state, not duplicate raw operational data
- Evidence pack generation must be deterministic and testable

## Residual Risks

| Risk | Mitigation |
|------|------------|
| Evidence pack contains stale verification data | Generated at query time from current runtime state; caller determines freshness |
| Evidence pack size grows with many models | Only summary fields (digest, count) — no full manifest or file bytes |
| Evidence pack leaked | Contains no secrets, tokens, or sensitive paths — safe for CI artifacts and auditor review |

## Roadmap After PR #32

| PR | Scope |
|----|-------|
| PR #29 | ✅ Encrypted suspended invocation store and restart-safe recovery |
| PR #30 | ✅ Local-model artifact manifest and byte-level verification |
| PR #31 | ✅ Offline runtime profile and zero-egress verification harness |
| PR #32 | ✅ Sovereign evidence pack for auditor-safe deployment attestation |
