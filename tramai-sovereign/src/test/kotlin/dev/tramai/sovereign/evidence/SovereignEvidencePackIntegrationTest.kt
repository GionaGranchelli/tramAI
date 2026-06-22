package dev.tramai.sovereign.evidence

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.User
import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelArtifactVerificationSettings
import dev.tramai.core.model.ModelArtifactVerifier
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.model.VerifiedLocalModelArtifact
import dev.tramai.core.provider.ModelProvider
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.model.InMemoryModelRegistry
import dev.tramai.sovereign.SovereignDeploymentMode
import dev.tramai.sovereign.SovereignProfileConfiguration
import dev.tramai.sovereign.SovereignTramai
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Integration tests for [SovereignEvidencePackV1] generation.
 */
class SovereignEvidencePackIntegrationTest {

    private val fixedInstant = Instant.parse("2026-01-02T03:04:05Z")
    private val fixedClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"))

    @Test
    fun `generates evidence pack from sovereign tramai with LOCAL provider`() {
        val registeredModel = RegisteredModel(
            registryEntryId = "local-entry",
            providerId = "local-provider",
            modelName = "test-model",
            revision = "1.0",
            artifactDigest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
        )
        val registry = InMemoryModelRegistry.builder()
            .register(registeredModel)
            .build()

        val verifier = RecordingVerifier { model ->
            VerifiedLocalModelArtifact(
                registryEntryId = model.registryEntryId,
                manifestDigest = model.artifactDigest!!,
                modelName = model.modelName,
                verifiedAt = fixedClock.instant(),
                artifactCount = 2,
                totalSizeBytes = 1024,
            )
        }

        val tramai = SovereignTramai.builder()
            .profile(
                SovereignProfileConfiguration(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("local-provider"),
                    providerZones = mapOf("local-provider" to ProviderTrustZone.LOCAL),
                ),
            )
            .modelRegistry(registry)
            .auditStore(InMemoryAuditStore())
            .provider(FakeProvider("local-provider"), name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .clock(fixedClock)
            .modelArtifactVerifier(verifier)
            .modelArtifactVerificationSettings(
                ModelArtifactVerificationSettings(enabled = true),
            )
            .build()

        val pack = tramai.evidencePack()

        assertThat(pack.deploymentMode).isEqualTo("STANDARD")
        assertThat(pack.allowedModels).containsExactly("test-model")
        assertThat(pack.allowedProviders).containsExactly("local-provider")
        assertThat(pack.providerZones).containsEntry("local-provider", "LOCAL")
        assertThat(pack.artifactVerificationSettings["enabled"]).isEqualTo(true)
        assertThat(pack.artifacts).hasSize(1)
        assertThat(pack.artifacts[0].registryEntryId).isEqualTo("local-entry")
        assertThat(pack.artifacts[0].manifestDigest).isEqualTo("sha256:${"a".repeat(64)}")
        assertThat(pack.artifacts[0].modelName).isEqualTo("test-model")
        assertThat(pack.artifacts[0].artifactCount).isEqualTo(2)
        assertThat(pack.artifacts[0].totalSizeBytes).isEqualTo(1024)
        assertThat(pack.zeroEgress).isNull()
        assertThat(pack.auditChain).isNull()
        assertThat(pack.schemaVersion).isEqualTo(1)
        assertThat(pack.generatedAt).isNotNull()
    }

    @Test
    fun `generates evidence pack in OFFLINE mode`() {
        val tramai = buildOfflineTramai()
        val pack = tramai.evidencePack()

        assertThat(pack.deploymentMode).isEqualTo("OFFLINE")
        assertThat(pack.allowedModels).containsExactly("test-model")
        assertThat(pack.allowedProviders).containsExactly("local-provider")
    }

    @Test
    fun `evidence pack contains verification receipt summaries`() {
        val registeredModel = RegisteredModel(
            registryEntryId = "receipt-entry",
            providerId = "local-provider",
            modelName = "test-model",
            revision = "1.0",
            artifactDigest = ModelArtifactDigest.of("sha256:${"b".repeat(64)}"),
        )
        val registry = InMemoryModelRegistry.builder()
            .register(registeredModel)
            .build()

        val verifier = RecordingVerifier { model ->
            VerifiedLocalModelArtifact(
                registryEntryId = model.registryEntryId,
                manifestDigest = model.artifactDigest!!,
                modelName = model.modelName,
                verifiedAt = fixedClock.instant(),
                artifactCount = 3,
                totalSizeBytes = 2048,
            )
        }

        val tramai = SovereignTramai.builder()
            .profile(
                SovereignProfileConfiguration(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("local-provider"),
                    providerZones = mapOf("local-provider" to ProviderTrustZone.LOCAL),
                ),
            )
            .modelRegistry(registry)
            .auditStore(InMemoryAuditStore())
            .provider(FakeProvider("local-provider"), name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .clock(fixedClock)
            .modelArtifactVerifier(verifier)
            .modelArtifactVerificationSettings(
                ModelArtifactVerificationSettings(enabled = true),
            )
            .build()

        val pack = tramai.evidencePack()

        assertThat(pack.artifacts).hasSize(1)
        val artifact = pack.artifacts[0]
        assertThat(artifact.registryEntryId).isEqualTo("receipt-entry")
        assertThat(artifact.manifestDigest).isEqualTo("sha256:${"b".repeat(64)}")
        assertThat(artifact.artifactCount).isEqualTo(3)
        assertThat(artifact.totalSizeBytes).isEqualTo(2048)
    }

    @Test
    fun `evidence pack field ordering is stable across multiple generations`() {
        val tramai = buildOfflineTramai()

        val pack1 = tramai.evidencePack(
            zeroEgress = ZeroEgressEvidenceV1(
                deploymentMode = "OFFLINE",
                runtimeBuildSucceeded = true,
                loopbackProviderInvocationSucceeded = true,
                loopbackProviderInvocationCount = 1,
                externalTcpProbeBlocked = true,
                externalDnsProbeBlocked = true,
            ),
            auditChain = AuditChainEvidenceV1(
                isValid = true,
                totalEvents = 3,
            ),
        )

        val pack2 = tramai.evidencePack(
            zeroEgress = ZeroEgressEvidenceV1(
                deploymentMode = "OFFLINE",
                runtimeBuildSucceeded = true,
                loopbackProviderInvocationSucceeded = true,
                loopbackProviderInvocationCount = 1,
                externalTcpProbeBlocked = true,
                externalDnsProbeBlocked = true,
            ),
            auditChain = AuditChainEvidenceV1(
                isValid = true,
                totalEvents = 3,
            ),
        )

        // Both packs should have the same structural content
        assertThat(pack1.deploymentMode).isEqualTo(pack2.deploymentMode)
        assertThat(pack1.allowedModels).isEqualTo(pack2.allowedModels)
        assertThat(pack1.allowedProviders).isEqualTo(pack2.allowedProviders)
        assertThat(pack1.providerZones).isEqualTo(pack2.providerZones)

        // Serialize both and verify identical structure
        val json1 = serializeToString(pack1)
        val json2 = serializeToString(pack2)

        // Strip the generatedAt timestamp for comparison
        val stripped1 = json1.replace(Regex("\"generatedAt\": \".+?\""), "\"generatedAt\": \"<snap>\"")
        val stripped2 = json2.replace(Regex("\"generatedAt\": \".+?\""), "\"generatedAt\": \"<snap>\"")

        assertThat(stripped1).isEqualTo(stripped2)
    }

    @Test
    fun `zero-egress subsection is included when provided`() {
        val tramai = buildOfflineTramai()

        val pack = tramai.evidencePack(
            zeroEgress = ZeroEgressEvidenceV1(
                deploymentMode = "OFFLINE",
                runtimeBuildSucceeded = true,
                loopbackProviderInvocationSucceeded = true,
                loopbackProviderInvocationCount = 2,
                externalTcpProbeBlocked = true,
                externalDnsProbeBlocked = false,
            ),
        )

        assertThat(pack.zeroEgress).isNotNull()
        assertThat(pack.zeroEgress!!.deploymentMode).isEqualTo("OFFLINE")
        assertThat(pack.zeroEgress!!.runtimeBuildSucceeded).isTrue()
        assertThat(pack.zeroEgress!!.loopbackProviderInvocationSucceeded).isTrue()
        assertThat(pack.zeroEgress!!.loopbackProviderInvocationCount).isEqualTo(2)
        assertThat(pack.zeroEgress!!.externalTcpProbeBlocked).isTrue()
        assertThat(pack.zeroEgress!!.externalDnsProbeBlocked).isFalse()
        assertThat(pack.auditChain).isNull()
    }

    @Test
    fun `audit-chain subsection is included when provided`() {
        val tramai = buildOfflineTramai()

        val pack = tramai.evidencePack(
            auditChain = AuditChainEvidenceV1(
                isValid = true,
                totalEvents = 7,
            ),
        )

        assertThat(pack.auditChain).isNotNull()
        assertThat(pack.auditChain!!.isValid).isTrue()
        assertThat(pack.auditChain!!.totalEvents).isEqualTo(7)
        assertThat(pack.zeroEgress).isNull()
    }

    @Test
    fun `both optional subsections are null when not provided`() {
        val tramai = buildOfflineTramai()

        val pack = tramai.evidencePack()

        assertThat(pack.zeroEgress).isNull()
        assertThat(pack.auditChain).isNull()
        assertThat(pack.supplyChain).isNull()
        assertThat(pack.releaseBundle).isNull()
        assertThat(pack.attestation).isNull()
    }

    @Test
    fun `evidence pack includes supply-chain subsection when provided`() {
        val tramai = buildOfflineTramai()

        val pack = tramai.evidencePack(
            supplyChain = SupplyChainEvidenceV1(
                sbomFormat = "CycloneDX",
                sbomSpecVersion = "1.6",
                sbomFileName = "tramai-cyclonedx-sbom.json",
                sbomSha256 = "sha256:${"a".repeat(64)}",
                generatedBy = "CycloneDX Gradle Plugin 3.2.4",
            ),
        )

        assertThat(pack.supplyChain).isNotNull()
        assertThat(pack.supplyChain!!.sbomFormat).isEqualTo("CycloneDX")
        assertThat(pack.supplyChain!!.sbomSpecVersion).isEqualTo("1.6")
        assertThat(pack.supplyChain!!.sbomFileName).isEqualTo("tramai-cyclonedx-sbom.json")
        assertThat(pack.supplyChain!!.sbomSha256).isEqualTo("sha256:${"a".repeat(64)}")
        assertThat(pack.supplyChain!!.generatedBy).isEqualTo("CycloneDX Gradle Plugin 3.2.4")
        assertThat(pack.supplyChain!!.schemaVersion).isEqualTo(1)
    }

    @Test
    fun `evidence pack includes attestation subsection when provided`() {
        val tramai = buildOfflineTramai()

        val pack = tramai.evidencePack(
            attestation = AttestationEvidenceV1(
                provider = "GitHub Artifact Attestations",
                workflowName = "CI",
                workflowRunId = "1234567",
                repository = "my-org/my-repo",
                commitSha = "abcdefabcdefabcdefabcdefabcdefabcdefabcd",
                attestedSubjects = listOf(
                    AttestedSubjectV1(
                        attestationType = "build-provenance",
                        fileName = "tramai.jar",
                        sha256 = "sha256:${"a".repeat(64)}",
                    ),
                ),
            ),
        )

        assertThat(pack.attestation).isNotNull()
        assertThat(pack.attestation!!.provider).isEqualTo("GitHub Artifact Attestations")
        assertThat(pack.attestation!!.workflowRunId).isEqualTo("1234567")
        assertThat(pack.attestation!!.repository).isEqualTo("my-org/my-repo")
        assertThat(pack.attestation!!.commitSha).isEqualTo("abcdefabcdefabcdefabcdefabcdefabcdefabcd")
        assertThat(pack.attestation!!.attestedSubjects).hasSize(1)
        assertThat(pack.attestation!!.attestedSubjects[0].fileName).isEqualTo("tramai.jar")
    }

    @Test
    fun `evidence pack includes attestation with sbom type and multiple subjects`() {
        val tramai = buildOfflineTramai()

        val pack = tramai.evidencePack(
            attestation = AttestationEvidenceV1(
                provider = "GitHub Artifact Attestations",
                workflowName = "CI",
                workflowRunId = "987654",
                repository = "org/repo-name",
                commitSha = "1234567890abcdef1234567890abcdef12345678",
                attestedSubjects = listOf(
                    AttestedSubjectV1(
                        attestationType = "sbom",
                        fileName = "sbom-a.json",
                        sha256 = "sha256:${"b".repeat(64)}",
                    ),
                    AttestedSubjectV1(
                        attestationType = "sbom",
                        fileName = "sbom-b.json",
                        sha256 = "sha256:${"c".repeat(64)}",
                    ),
                ),
            ),
        )

        assertThat(pack.attestation).isNotNull()
        assertThat(pack.attestation!!.provider).isEqualTo("GitHub Artifact Attestations")
        assertThat(pack.attestation!!.attestedSubjects).hasSize(2)
        assertThat(pack.attestation!!.attestedSubjects[0].fileName).isEqualTo("sbom-a.json")
        assertThat(pack.attestation!!.attestedSubjects[1].fileName).isEqualTo("sbom-b.json")
    }

    @Test
    fun `evidence pack includes release-bundle subsection when provided`() {
        val tramai = buildOfflineTramai()

        val pack = tramai.evidencePack(
            releaseBundle = ReleaseBundleEvidenceV1(
                buildTool = "Gradle",
                javaVersion = "25.0.1",
                gradleVersion = "8.10",
                artifacts = listOf(
                    ReleaseArtifactEvidenceV1(
                        groupId = "dev.tramai",
                        artifactId = "tramai-core",
                        version = "1.0.0",
                        classifier = null,
                        extension = "jar",
                        fileName = "tramai-core-1.0.0.jar",
                        sha256 = "sha256:${"a".repeat(64)}",
                        sizeBytes = 51200,
                    ),
                ),
            ),
        )

        assertThat(pack.releaseBundle).isNotNull()
        assertThat(pack.releaseBundle!!.buildTool).isEqualTo("Gradle")
        assertThat(pack.releaseBundle!!.javaVersion).isEqualTo("25.0.1")
        assertThat(pack.releaseBundle!!.gradleVersion).isEqualTo("8.10")
        assertThat(pack.releaseBundle!!.artifacts).hasSize(1)
        assertThat(pack.releaseBundle!!.artifacts[0].artifactId).isEqualTo("tramai-core")
        assertThat(pack.releaseBundle!!.artifacts[0].sha256).isEqualTo("sha256:${"a".repeat(64)}")
    }

    @Test
    fun `legacy overload produces same result as GenerationParams API`() {
        val deploymentMode = SovereignDeploymentMode.STANDARD
        val allowedModels = setOf("model-a", "model-b")
        val allowedProviders = setOf("provider-a")
        val providerZones = mapOf("provider-a" to "LOCAL")
        val verificationSettings = ModelArtifactVerificationSettings(enabled = true)
        val verificationReceipts = listOf(
            VerifiedLocalModelArtifact(
                registryEntryId = "entry-1",
                manifestDigest = dev.tramai.core.model.ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
                modelName = "model-a",
                verifiedAt = java.time.Instant.parse("2026-01-02T03:04:05Z"),
                artifactCount = 2,
                totalSizeBytes = 1024,
            ),
        )
        val zeroEgress = ZeroEgressEvidenceV1(
            deploymentMode = "STANDARD",
            runtimeBuildSucceeded = true,
            loopbackProviderInvocationSucceeded = true,
            loopbackProviderInvocationCount = 1,
            externalTcpProbeBlocked = true,
            externalDnsProbeBlocked = true,
        )
        val auditChain = AuditChainEvidenceV1(isValid = true, totalEvents = 3)

        val legacyResult = SovereignEvidencePackGenerator.generate(
            deploymentMode = deploymentMode,
            allowedModels = allowedModels,
            allowedProviders = allowedProviders,
            providerZones = providerZones,
            verificationSettings = verificationSettings,
            verificationReceipts = verificationReceipts,
            zeroEgress = zeroEgress,
            auditChain = auditChain,
        )

        val newResult = SovereignEvidencePackGenerator.generate(
            SovereignEvidencePackGenerator.GenerationParams(
                deploymentMode = deploymentMode,
                allowedModels = allowedModels,
                allowedProviders = allowedProviders,
                providerZones = providerZones,
                verification = SovereignEvidencePackGenerator.VerificationEvidence(
                    verificationSettings = verificationSettings,
                    verificationReceipts = verificationReceipts,
                ),
                optionalEvidence = SovereignEvidencePackGenerator.OptionalEvidence(
                    zeroEgress = zeroEgress,
                    auditChain = auditChain,
                ),
            ),
        )

        // Compare all fields except generatedAt (wall-clock time)
        assertThat(legacyResult.schemaVersion).isEqualTo(newResult.schemaVersion)
        assertThat(legacyResult.deploymentMode).isEqualTo(newResult.deploymentMode)
        assertThat(legacyResult.allowedModels).isEqualTo(newResult.allowedModels)
        assertThat(legacyResult.allowedProviders).isEqualTo(newResult.allowedProviders)
        assertThat(legacyResult.providerZones).isEqualTo(newResult.providerZones)
        assertThat(legacyResult.artifactVerificationSettings).isEqualTo(newResult.artifactVerificationSettings)
        assertThat(legacyResult.artifacts).isEqualTo(newResult.artifacts)
        assertThat(legacyResult.zeroEgress).isEqualTo(newResult.zeroEgress)
        assertThat(legacyResult.auditChain).isEqualTo(newResult.auditChain)
        assertThat(legacyResult.supplyChain).isEqualTo(newResult.supplyChain)
        assertThat(legacyResult.releaseBundle).isEqualTo(newResult.releaseBundle)
        assertThat(legacyResult.attestation).isEqualTo(newResult.attestation)
    }

    // -- Helpers -----------------------------------------------------------------

    private fun buildOfflineTramai(): SovereignTramai {
        val registeredModel = RegisteredModel(
            registryEntryId = "offline-entry",
            providerId = "local-provider",
            modelName = "test-model",
            revision = "1.0",
            artifactDigest = ModelArtifactDigest.of("sha256:${"c".repeat(64)}"),
        )
        val registry = InMemoryModelRegistry.builder()
            .register(registeredModel)
            .build()

        val verifier = RecordingVerifier { model ->
            VerifiedLocalModelArtifact(
                registryEntryId = model.registryEntryId,
                manifestDigest = model.artifactDigest!!,
                modelName = model.modelName,
                verifiedAt = fixedClock.instant(),
                artifactCount = 1,
                totalSizeBytes = 512,
            )
        }

        return SovereignTramai.builder()
            .profile(
                SovereignProfileConfiguration(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("local-provider"),
                    providerZones = mapOf("local-provider" to ProviderTrustZone.LOCAL),
                    deploymentMode = SovereignDeploymentMode.OFFLINE,
                ),
            )
            .modelRegistry(registry)
            .auditStore(InMemoryAuditStore())
            .provider(FakeProvider("local-provider"), name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .clock(fixedClock)
            .modelArtifactVerifier(verifier)
            .modelArtifactVerificationSettings(
                ModelArtifactVerificationSettings(enabled = true),
            )
            .build()
    }

    private fun serializeToString(pack: SovereignEvidencePackV1): String {
        val tempFile = Files.createTempFile("integration-test-", ".json")
        try {
            SovereignEvidencePackWriter.write(pack, tempFile)
            return Files.readString(tempFile)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private class RecordingVerifier(
        private val block: suspend (RegisteredModel) -> VerifiedLocalModelArtifact?,
    ) : ModelArtifactVerifier {
        override suspend fun verify(registeredModel: RegisteredModel): VerifiedLocalModelArtifact? =
            block(registeredModel)
    }

    private class FakeProvider(private val name: String) : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse =
            ModelResponse(content = "mock response for ${request.model}")

        override fun providerId(): String = name
    }
}
