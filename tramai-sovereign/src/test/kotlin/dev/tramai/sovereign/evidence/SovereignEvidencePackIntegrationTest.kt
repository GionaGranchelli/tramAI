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
        assertThat(pack.verifiedModels).isEmpty()
        assertThat(pack.zeroEgress).isNull()
        assertThat(pack.auditChain).isNull()
        assertThat(pack.schemaVersion).isEqualTo(1)
        assertThat(pack.generatedAt).isNotNull
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

        assertThat(pack.zeroEgress).isNotNull
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

        assertThat(pack.auditChain).isNotNull
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
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
