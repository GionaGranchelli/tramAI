package dev.tramai.sovereign.evidence

import dev.tramai.core.model.ModelArtifactVerificationSettings
import dev.tramai.core.model.VerifiedLocalModelArtifact
import dev.tramai.sovereign.SovereignDeploymentMode
import dev.tramai.sovereign.SovereignProfileConfiguration
import java.time.Instant

/**
 * Collects deployment state into a deterministic [SovereignEvidencePackV1].
 *
 * Designed to be called from [dev.tramai.sovereign.SovereignTramai.evidencePack].
 */
object SovereignEvidencePackGenerator {

    /**
     * Generates a [SovereignEvidencePackV1] from the current deployment state.
     *
     * @param deploymentMode The deployment connectivity contract.
     * @param allowedModels The sorted list of allowed model names.
     * @param allowedProviders The sorted list of allowed provider names.
     * @param providerZones The trust-zone mapping for each provider (zone name strings).
     * @param verificationSettings The settings that controlled artifact verification.
     * @param verificationReceipts The verification receipts from build-time artifact verification.
     * @param zeroEgress Optional zero-egress verification subsection.
     * @param auditChain Optional audit-chain validation subsection.
     * @param supplyChain Optional supply-chain SBOM linkage subsection.
     * @return A fully populated [SovereignEvidencePackV1] with the [generatedAt] timestamp
     * set to the current wall-clock time when this method is called.
     */
    fun generate(
        deploymentMode: SovereignDeploymentMode,
        allowedModels: Set<String>,
        allowedProviders: Set<String>,
        providerZones: Map<String, String>,
        verificationSettings: ModelArtifactVerificationSettings,
        verificationReceipts: List<VerifiedLocalModelArtifact>,
        zeroEgress: ZeroEgressEvidenceV1? = null,
        auditChain: AuditChainEvidenceV1? = null,
        supplyChain: SupplyChainEvidenceV1? = null,
    ): SovereignEvidencePackV1 {
        // Sanitize all string identifiers before building DTOs
        val sanitizedModels = allowedModels.map { EvidenceSafeString.sanitize(it) }.toSet()
        val sanitizedProviders = allowedProviders.map { EvidenceSafeString.sanitize(it) }.toSet()
        val sanitizedZones = providerZones.mapKeys { EvidenceSafeString.sanitize(it.key) }
            .mapValues { EvidenceSafeString.sanitize(it.value) }

        val artifacts = verificationReceipts.map { receipt ->
            ArtifactEvidenceV1(
                registryEntryId = EvidenceSafeString.sanitize(receipt.registryEntryId),
                manifestDigest = EvidenceSafeString.sanitize(receipt.manifestDigest.value),
                modelName = EvidenceSafeString.sanitize(receipt.modelName),
                verifiedAt = receipt.verifiedAt.toString(),
                artifactCount = receipt.artifactCount,
                totalSizeBytes = receipt.totalSizeBytes,
            )
        }

        val settingsMap = linkedMapOf<String, Any?>(
            "enabled" to verificationSettings.enabled,
            "requireDigestForLocalModels" to verificationSettings.requireDigestForLocalModels,
        )

        // Validate and sanitize supply-chain evidence
        val sanitizedSupplyChain = supplyChain?.let { sc ->
            // sbomSha256 must match "sha256:<hex>" format — do NOT run through EvidenceSafeString
            // (hex could contain substrings like "token" coincidentally)
            val digestRegex = Regex("^sha256:[a-fA-F0-9]{64}$")
            require(digestRegex.matches(sc.sbomSha256)) {
                "evidence-unsafe-digest-format"
            }

            SupplyChainEvidenceV1(
                schemaVersion = sc.schemaVersion,
                sbomFormat = EvidenceSafeString.sanitize(sc.sbomFormat),
                sbomSpecVersion = EvidenceSafeString.sanitize(sc.sbomSpecVersion),
                sbomFileName = EvidenceSafeString.sanitize(sc.sbomFileName),
                sbomSha256 = sc.sbomSha256,
                generatedBy = EvidenceSafeString.sanitize(sc.generatedBy),
            )
        }

        return SovereignEvidencePackV1(
            schemaVersion = 1,
            deploymentMode = deploymentMode.name,
            allowedModels = sanitizedModels.toList().sorted(),
            allowedProviders = sanitizedProviders.toList().sorted(),
            providerZones = sanitizedZones,
            artifactVerificationSettings = settingsMap,
            artifacts = artifacts,
            zeroEgress = zeroEgress,
            auditChain = auditChain,
            supplyChain = sanitizedSupplyChain,
            generatedAt = Instant.now().toString(),
        )
    }
}
