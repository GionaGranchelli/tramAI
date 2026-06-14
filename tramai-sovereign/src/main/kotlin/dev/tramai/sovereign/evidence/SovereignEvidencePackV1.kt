package dev.tramai.sovereign.evidence

/**
 * Top-level container for the Sovereign Evidence Pack V1.
 *
 * A deterministic, safe-for-auditors JSON artifact that captures the
 * security posture of a sovereign TramAI deployment without leaking
 * prompts, payloads, tokens, secrets, stack traces, or filesystem paths.
 *
 * Designed for CI attestation, enterprise security review, and
 * future air-gap validation evidence chains.
 *
 * @property schemaVersion Schema version (currently 1).
 * @property deploymentMode The deployment connectivity contract (e.g. STANDARD, OFFLINE).
 * @property allowedModels The set of model names allowed by the profile (sorted).
 * @property allowedProviders The set of provider names allowed by the profile (sorted).
 * @property providerZones Trust-zone mapping for each allowed provider.
 * @property artifactVerificationSettings Settings that controlled artifact verification.
 * @property artifacts Summary of individual artifact verification receipts.
 * @property zeroEgress Optional zero-egress verification subsection.
 * @property auditChain Optional audit-chain validation subsection.
 * @property supplyChain Optional supply-chain SBOM linkage subsection.
 * @property attestation Optional CI/CD attestation subsection.
 * @property generatedAt ISO-8601 instant when this pack was generated.
 */
data class SovereignEvidencePackV1(
    val schemaVersion: Int = 1,
    val deploymentMode: String,
    val allowedModels: List<String>,
    val allowedProviders: List<String>,
    val providerZones: Map<String, String>,
    val artifactVerificationSettings: Map<String, Any?>,
    val artifacts: List<ArtifactEvidenceV1>,
    val zeroEgress: ZeroEgressEvidenceV1? = null,
    val auditChain: AuditChainEvidenceV1? = null,
    val supplyChain: SupplyChainEvidenceV1? = null,
    val attestation: AttestationEvidenceV1? = null,
    val generatedAt: String,
)
