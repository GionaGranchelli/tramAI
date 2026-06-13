package dev.tramai.sovereign

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
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SovereignTramaiArtifactVerificationTest {

    private val fixedInstant = Instant.parse("2026-01-02T03:04:05Z")
    private val fixedClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"))

    @Test
    fun `local model with valid manifest builds and stores receipts`() {
        val registeredModel = localRegisteredModel(
            artifactDigest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
        )
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

        val tramai = localBuilder(registeredModel)
            .clock(fixedClock)
            .modelArtifactVerifier(verifier)
            .modelArtifactVerificationSettings(
                ModelArtifactVerificationSettings(
                    enabled = true,
                    requireDigestForLocalModels = true,
                ),
            )
            .build()

        assertThat(verifier.seenModels).containsExactly(registeredModel)
        assertThat(tramai.verificationReceipts()).containsExactly(
            VerifiedLocalModelArtifact(
                registryEntryId = "local-entry",
                manifestDigest = registeredModel.artifactDigest!!,
                modelName = "test-model",
                verifiedAt = fixedInstant,
                artifactCount = 2,
                totalSizeBytes = 1024,
            ),
        )
    }

    @Test
    fun `local model without digest when required rejects before build`() {
        val registeredModel = localRegisteredModel(artifactDigest = null)
        val verifier = RecordingVerifier { error("verifier should not be invoked") }

        assertThatThrownBy {
            localBuilder(registeredModel)
                .modelArtifactVerifier(verifier)
                .modelArtifactVerificationSettings(
                    ModelArtifactVerificationSettings(
                        enabled = true,
                        requireDigestForLocalModels = true,
                    ),
                )
                .build()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("artifact-digest-required-for-local-model")

        assertThat(verifier.seenModels).isEmpty()
    }

    @Test
    fun `local model with unknown manifest rejects`() {
        val registeredModel = localRegisteredModel(
            artifactDigest = ModelArtifactDigest.of("sha256:${"b".repeat(64)}"),
        )
        val verifier = RecordingVerifier { null }

        assertThatThrownBy {
            localBuilder(registeredModel)
                .modelArtifactVerifier(verifier)
                .modelArtifactVerificationSettings(
                    ModelArtifactVerificationSettings(enabled = true),
                )
                .build()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("artifact-manifest-not-found")

        assertThat(verifier.seenModels).containsExactly(registeredModel)
    }

    @Test
    fun `local model with verifier throwing modified byte error rejects before build`() {
        val registeredModel = localRegisteredModel(
            artifactDigest = ModelArtifactDigest.of("sha256:${"b".repeat(64)}"),
        )
        val verifier = RecordingVerifier { throw IllegalStateException("artifact-file-digest-mismatch") }

        assertThatThrownBy {
            localBuilder(registeredModel)
                .modelArtifactVerifier(verifier)
                .modelArtifactVerificationSettings(
                    ModelArtifactVerificationSettings(enabled = true),
                )
                .build()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("artifact-file-digest-mismatch")

        assertThat(verifier.seenModels).containsExactly(registeredModel)
    }

    @Test
    fun `cloud model without local artifact manifest preserves existing behavior`() = runBlocking {
        val registeredModel = RegisteredModel(
            registryEntryId = "cloud-entry",
            providerId = "cloud-provider",
            modelName = "test-model",
            revision = "1.0",
        )
        val verifier = RecordingVerifier { null }

        val tramai = cloudBuilder(registeredModel)
            .modelArtifactVerifier(verifier)
            .modelArtifactVerificationSettings(
                ModelArtifactVerificationSettings(
                    enabled = true,
                    requireDigestForLocalModels = true,
                ),
            )
            .build()

        val result = tramai.create<EchoService>().echo("hello")

        assertThat(result).isEqualTo("mock response for test-model")
        assertThat(verifier.seenModels).isEmpty()
        assertThat(tramai.verificationReceipts()).isEmpty()
    }

    @Test
    fun `verification disabled preserves backward compatible behavior`() = runBlocking {
        val registeredModel = localRegisteredModel(artifactDigest = null)
        val verifier = RecordingVerifier { error("verifier should not be invoked") }

        val tramai = localBuilder(registeredModel)
            .modelArtifactVerifier(verifier)
            .modelArtifactVerificationSettings(
                ModelArtifactVerificationSettings(
                    enabled = false,
                    requireDigestForLocalModels = true,
                ),
            )
            .build()

        val result = tramai.create<EchoService>().echo("hello")

        assertThat(result).isEqualTo("mock response for test-model")
        assertThat(verifier.seenModels).isEmpty()
        assertThat(tramai.verificationReceipts()).isEmpty()
    }

    @Test
    fun `no verifier configured preserves backward compatible behavior`() = runBlocking {
        val registeredModel = localRegisteredModel(artifactDigest = null)

        val tramai = localBuilder(registeredModel)
            .modelArtifactVerificationSettings(
                ModelArtifactVerificationSettings(
                    enabled = true,
                    requireDigestForLocalModels = true,
                ),
            )
            .build()

        val result = tramai.create<EchoService>().echo("hello")

        assertThat(result).isEqualTo("mock response for test-model")
        assertThat(tramai.verificationReceipts()).isEmpty()
    }

    private fun localBuilder(registeredModel: RegisteredModel): SovereignTramai.Builder {
        val registry = InMemoryModelRegistry.builder()
            .register(registeredModel)
            .build()
        val profile = SovereignProfileConfiguration(
            allowedModels = setOf("test-model"),
            allowedProviders = setOf("local-provider"),
            providerZones = mapOf("local-provider" to ProviderTrustZone.LOCAL),
        )
        return SovereignTramai.builder()
            .profile(profile)
            .modelRegistry(registry)
            .auditStore(InMemoryAuditStore())
            .provider(FakeProvider("local-provider"), name = "local-provider", default = true)
            .model("test-model", "local-provider")
    }

    private fun cloudBuilder(registeredModel: RegisteredModel): SovereignTramai.Builder {
        val registry = InMemoryModelRegistry.builder()
            .register(registeredModel)
            .build()
        val profile = SovereignProfileConfiguration(
            allowedModels = setOf("test-model"),
            allowedProviders = setOf("cloud-provider"),
            providerZones = mapOf("cloud-provider" to ProviderTrustZone.GLOBAL_CLOUD),
        )
        return SovereignTramai.builder()
            .profile(profile)
            .modelRegistry(registry)
            .auditStore(InMemoryAuditStore())
            .provider(FakeProvider("cloud-provider"), name = "cloud-provider", default = true)
            .model("test-model", "cloud-provider")
    }

    private fun localRegisteredModel(artifactDigest: ModelArtifactDigest?): RegisteredModel =
        RegisteredModel(
            registryEntryId = "local-entry",
            providerId = "local-provider",
            modelName = "test-model",
            revision = "1.0",
            artifactDigest = artifactDigest,
        )

    private class RecordingVerifier(
        private val block: suspend (RegisteredModel) -> VerifiedLocalModelArtifact?,
    ) : ModelArtifactVerifier {
        val seenModels = mutableListOf<RegisteredModel>()

        override suspend fun verify(registeredModel: RegisteredModel): VerifiedLocalModelArtifact? {
            seenModels += registeredModel
            return block(registeredModel)
        }
    }

    private class FakeProvider(private val name: String) : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse =
            ModelResponse(content = "mock response for ${request.model}")

        override fun providerId(): String = name
    }
}
