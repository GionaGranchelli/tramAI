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
    data class GenerationParams(
        val deploymentMode: SovereignDeploymentMode,
        val allowedModels: Set<String>,
        val allowedProviders: Set<String>,
        val providerZones: Map<String, String>,
        val verification: VerificationEvidence,
        val optionalEvidence: OptionalEvidence = OptionalEvidence(),
    )

    data class VerificationEvidence(
        val verificationSettings: ModelArtifactVerificationSettings,
        val verificationReceipts: List<VerifiedLocalModelArtifact>,
    )

    data class OptionalEvidence(
        val zeroEgress: ZeroEgressEvidenceV1? = null,
        val auditChain: AuditChainEvidenceV1? = null,
        val supplyChain: SupplyChainEvidenceV1? = null,
        val releaseBundle: ReleaseBundleEvidenceV1? = null,
        val attestation: AttestationEvidenceV1? = null,
    )

    private fun sanitizeFileNameOnly(value: String): String {
        val sanitized = EvidenceSafeString.sanitize(value)
        require(!sanitized.contains('/')) { "evidence-unsafe-identifier" }
        require(!sanitized.contains('\\')) { "evidence-unsafe-identifier" }
        return sanitized
    }

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
     * @param releaseBundle Optional release-bundle artifact manifest subsection.
     * @param attestation Optional CI/CD attestation subsection.
     * @return A fully populated [SovereignEvidencePackV1] with the [generatedAt] timestamp
     * set to the current wall-clock time when this method is called.
     */
     fun generate(params: GenerationParams): SovereignEvidencePackV1 {
        // Sanitize all string identifiers before building DTOs
        val sanitizedModels = params.allowedModels.map { EvidenceSafeString.sanitize(it) }.toSet()
        val sanitizedProviders = params.allowedProviders.map { EvidenceSafeString.sanitize(it) }.toSet()
        val sanitizedZones = params.providerZones.mapKeys { EvidenceSafeString.sanitize(it.key) }
            .mapValues { EvidenceSafeString.sanitize(it.value) }

        val artifacts = params.verification.verificationReceipts.map { receipt ->
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
            "enabled" to params.verification.verificationSettings.enabled,
            "requireDigestForLocalModels" to params.verification.verificationSettings.requireDigestForLocalModels,
        )

        // Validate and sanitize supply-chain evidence
        val sanitizedSupplyChain = params.optionalEvidence.supplyChain?.let { sc ->
            // sbomSha256 must match "sha256:<hex>" format — do NOT run through EvidenceSafeString
            // (hex could contain substrings like "token" coincidentally)
            val digestRegex = Regex(SHA256_REGEX)
            require(digestRegex.matches(sc.sbomSha256)) {
                ERROR_UNSAFE_DIGEST
            }

            require(sc.schemaVersion == 1) {
                "evidence-unsupported-supply-chain-schema-version"
            }

            SupplyChainEvidenceV1(
                schemaVersion = sc.schemaVersion,
                sbomFormat = EvidenceSafeString.sanitize(sc.sbomFormat),
                sbomSpecVersion = EvidenceSafeString.sanitize(sc.sbomSpecVersion),
                sbomFileName = sanitizeFileNameOnly(sc.sbomFileName),
                sbomSha256 = sc.sbomSha256,
                generatedBy = EvidenceSafeString.sanitize(sc.generatedBy),
            )
        }

        // Validate and sanitize attestation evidence
        val sanitizedAttestation = params.optionalEvidence.attestation?.let { a ->
            require(a.schemaVersion == 1) {
                "evidence-unsupported-attestation-schema-version"
            }

            val workflowRunIdRegex = Regex("^[0-9]+$")
            require(workflowRunIdRegex.matches(a.workflowRunId)) {
                "evidence-unsafe-attestation-workflow-run-id"
            }

            val repositoryRegex = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
            require(repositoryRegex.matches(a.repository)) {
                "evidence-unsafe-attestation-repository"
            }

            val commitShaRegex = Regex("^[a-fA-F0-9]{40}$")
            require(commitShaRegex.matches(a.commitSha)) {
                "evidence-unsafe-attestation-commit-sha"
            }

            require(a.attestedSubjects.isNotEmpty()) {
                "evidence-unsafe-attestation-subjects"
            }

            val sha256Regex = Regex(SHA256_REGEX)
            val sanitizedSubjects = a.attestedSubjects.map { subject ->
                require(sha256Regex.matches(subject.sha256)) {
                    ERROR_UNSAFE_DIGEST
                }
                require(
                    subject.attestationType == "build-provenance" ||
                    subject.attestationType == "sbom"
                ) {
                    "evidence-unsupported-attestation-type"
                }
                // Do NOT run sha256 hex values through EvidenceSafeString
                AttestedSubjectV1(
                    fileName = sanitizeFileNameOnly(subject.fileName),
                    sha256 = subject.sha256,
                    attestationType = subject.attestationType,
                )
            }

            AttestationEvidenceV1(
                schemaVersion = a.schemaVersion,
                provider = EvidenceSafeString.sanitize(a.provider),
                workflowName = EvidenceSafeString.sanitize(a.workflowName),
                workflowRunId = a.workflowRunId,
                repository = EvidenceSafeString.sanitize(a.repository),
                commitSha = a.commitSha,
                attestedSubjects = sanitizedSubjects,
            )
        }

        // Validate and sanitize release-bundle evidence
        val sanitizedReleaseBundle = params.optionalEvidence.releaseBundle?.let { r ->
            require(r.schemaVersion == 1) {
                "evidence-unsupported-release-bundle-schema-version"
            }

            require(r.artifacts.isNotEmpty()) {
                "evidence-unsafe-release-artifacts-empty"
            }

            val digestRegex = Regex(SHA256_REGEX)
            val sanitizedArtifacts = r.artifacts.map { artifact ->
                require(digestRegex.matches(artifact.sha256)) {
                    ERROR_UNSAFE_DIGEST
                }

                require(artifact.sizeBytes >= 0) {
                    "evidence-unsafe-artifact-size-negative"
                }

                ReleaseArtifactEvidenceV1(
                    groupId = EvidenceSafeString.sanitize(artifact.groupId),
                    artifactId = EvidenceSafeString.sanitize(artifact.artifactId),
                    version = EvidenceSafeString.sanitize(artifact.version),
                    classifier = artifact.classifier?.let { EvidenceSafeString.sanitize(it) },
                    extension = EvidenceSafeString.sanitize(artifact.extension),
                    fileName = sanitizeFileNameOnly(artifact.fileName),
                    sha256 = artifact.sha256,
                    sizeBytes = artifact.sizeBytes,
                )
            }

            ReleaseBundleEvidenceV1(
                schemaVersion = r.schemaVersion,
                buildTool = EvidenceSafeString.sanitize(r.buildTool),
                javaVersion = EvidenceSafeString.sanitize(r.javaVersion),
                gradleVersion = EvidenceSafeString.sanitize(r.gradleVersion),
                artifacts = sanitizedArtifacts,
            )
        }

        return SovereignEvidencePackV1(
            schemaVersion = 1,
            deploymentMode = params.deploymentMode.name,
            allowedModels = sanitizedModels.toList().sorted(),
            allowedProviders = sanitizedProviders.toList().sorted(),
            providerZones = sanitizedZones,
            artifactVerificationSettings = settingsMap,
            artifacts = artifacts,
            zeroEgress = params.optionalEvidence.zeroEgress,
            auditChain = params.optionalEvidence.auditChain,
            supplyChain = sanitizedSupplyChain,
            releaseBundle = sanitizedReleaseBundle,
            attestation = sanitizedAttestation,
            generatedAt = Instant.now().toString(),
        )
    }
}

/** @see SovereignEvidencePackGenerator */
private const val SHA256_REGEX = "^sha256:[a-fA-F0-9]{64}$"

/** @see SovereignEvidencePackGenerator */
private const val ERROR_UNSAFE_DIGEST = "evidence-unsafe-digest-format"
