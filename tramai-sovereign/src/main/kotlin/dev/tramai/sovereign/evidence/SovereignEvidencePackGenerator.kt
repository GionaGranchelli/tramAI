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
     * @return A fully populated [SovereignEvidencePackV1] with a deterministic [generatedAt] timestamp.
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
    ): SovereignEvidencePackV1 {
        val artifacts = verificationReceipts.map { receipt ->
            ArtifactEvidenceV1(
                registryEntryId = receipt.registryEntryId,
                manifestDigest = receipt.manifestDigest.value,
                modelName = receipt.modelName,
                verifiedAt = receipt.verifiedAt.toString(),
                artifactCount = receipt.artifactCount,
                totalSizeBytes = receipt.totalSizeBytes,
            )
        }

        // Verified models — currently populated from receipts since we don't
        // have the full RegisteredModel info (providerId, revision) in the
        // receipt DTO. These are left empty and can be populated in a future
        // iteration when the SovereignTramai stores the registered models.
        val verifiedModels = emptyList<VerifiedModelEvidenceV1>()

        val settingsMap = linkedMapOf<String, Any?>(
            "enabled" to verificationSettings.enabled,
            "requireDigestForLocalModels" to verificationSettings.requireDigestForLocalModels,
        )

        return SovereignEvidencePackV1(
            schemaVersion = 1,
            deploymentMode = deploymentMode.name,
            allowedModels = allowedModels.toList().sorted(),
            allowedProviders = allowedProviders.toList().sorted(),
            providerZones = providerZones,
            artifactVerificationSettings = settingsMap,
            verifiedModels = verifiedModels,
            artifacts = artifacts,
            zeroEgress = zeroEgress,
            auditChain = auditChain,
            generatedAt = Instant.now().toString(),
        )
    }
}
