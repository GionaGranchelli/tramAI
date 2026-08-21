package dev.tramai.sovereign

import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelArtifactVerificationSettings
import dev.tramai.core.model.ModelArtifactVerifier
import dev.tramai.core.model.ModelRegistry
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
import kotlinx.coroutines.CancellationException
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
    fun `enabled equals true without verifier rejects before build`() {
        val registeredModel = localRegisteredModel(
            artifactDigest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
        )

        assertThatThrownBy {
            localBuilder(registeredModel)
                .modelArtifactVerificationSettings(
                    ModelArtifactVerificationSettings(enabled = true),
                )
                .build()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("artifact-verification-not-configured")
    }

    @Test
    fun `digest optional transitional mode builds and verifies file bytes without registry pinning`() {
        val registeredModel = localRegisteredModel(artifactDigest = null)
        val verifier = RecordingVerifier { model ->
            VerifiedLocalModelArtifact(
                registryEntryId = model.registryEntryId,
                manifestDigest = ModelArtifactDigest.of("sha256:${"d".repeat(64)}"),
                modelName = model.modelName,
                verifiedAt = fixedClock.instant(),
                artifactCount = 1,
                totalSizeBytes = 512,
            )
        }

        val tramai = localBuilder(registeredModel)
            .clock(fixedClock)
            .modelArtifactVerifier(verifier)
            .modelArtifactVerificationSettings(
                ModelArtifactVerificationSettings(
                    enabled = true,
                    requireDigestForLocalModels = false,
                ),
            )
            .build()

        assertThat(verifier.seenModels).containsExactly(registeredModel)
        assertThat(tramai.verificationReceipts()).isNotEmpty
    }

    @Test
    fun `model registry SPI exception sanitized through cause chain`() {
        val registeredModel = localRegisteredModel(
            artifactDigest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
        )
        val verifier = RecordingVerifier { null }

        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(
                    SovereignProfileConfiguration(
                        allowedModels = setOf("test-model"),
                        allowedProviders = setOf("local-provider"),
                        providerZones = mapOf("local-provider" to ProviderTrustZone.LOCAL),
                    ),
                )
                .modelRegistry(object : ModelRegistry {
                    override suspend fun findApprovedModel(
                        providerId: String,
                        modelName: String,
                    ): RegisteredModel? {
                        throw IllegalStateException("/secret/model/path.gguf")
                    }
                })
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider("local-provider"), name = "local-provider", default = true)
                .model("test-model", "local-provider")
                .clock(fixedClock)
                .modelArtifactVerifier(verifier)
                .modelArtifactVerificationSettings(
                    ModelArtifactVerificationSettings(enabled = true),
                )
                .build()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("artifact-approved-model-lookup-failed")
    }

    @Test
    fun `cloud model without local artifact manifest preserves existing behavior`() { runBlocking {
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
    }

    @Test
    fun `verification disabled preserves backward compatible behavior`() { runBlocking {
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
    }

    @Test
    fun `cancellation from verifier propagates unchanged`() {
        val registeredModel = localRegisteredModel(
            artifactDigest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
        )
        val verifier = RecordingVerifier {
            throw CancellationException("verifier cancelled")
        }

        assertThatThrownBy {
            localBuilder(registeredModel)
                .clock(fixedClock)
                .modelArtifactVerifier(verifier)
                .modelArtifactVerificationSettings(
                    ModelArtifactVerificationSettings(enabled = true),
                )
                .build()
        }.isInstanceOf(CancellationException::class.java)
            .hasMessage("verifier cancelled")
    }

    @Test
    fun `custom verifier path leakage sanitized via safe-code allowlist`() {
        val registeredModel = localRegisteredModel(
            artifactDigest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
        )
        val verifier = RecordingVerifier {
            throw IllegalStateException("/mnt/models/customer-secret.gguf")
        }

        assertThatThrownBy {
            localBuilder(registeredModel)
                .clock(fixedClock)
                .modelArtifactVerifier(verifier)
                .modelArtifactVerificationSettings(
                    ModelArtifactVerificationSettings(enabled = true),
                )
                .build()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("artifact-verification-failed")
    }

    @Test
    fun `GLOBAL_CLOUD primary with LOCAL fallback and valid manifest produces LOCAL receipt`() {
        val localRegistered = RegisteredModel(
            registryEntryId = "local-entry",
            providerId = "fallback-provider",
            modelName = "test-model-fallback",
            revision = "1.0",
            artifactDigest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
        )
        val cloudRegistered = RegisteredModel(
            registryEntryId = "cloud-entry",
            providerId = "cloud-provider",
            modelName = "test-model",
            revision = "1.0",
        )

        val registry = InMemoryModelRegistry.builder()
            .register(localRegistered)
            .register(cloudRegistered)
            .build()

        val profile = SovereignProfileConfiguration(
            allowedModels = setOf("test-model", "test-model-fallback"),
            allowedProviders = setOf("cloud-provider", "fallback-provider"),
            allowedFallbackProviders = setOf("fallback-provider"),
            providerZones = mapOf(
                "cloud-provider" to ProviderTrustZone.GLOBAL_CLOUD,
                "fallback-provider" to ProviderTrustZone.LOCAL,
            ),
        )

        var invokedWith: RegisteredModel? = null
        val verifier = RecordingVerifier { model ->
            invokedWith = model
            VerifiedLocalModelArtifact(
                registryEntryId = model.registryEntryId,
                manifestDigest = model.artifactDigest!!,
                modelName = model.modelName,
                verifiedAt = fixedClock.instant(),
                artifactCount = 1,
                totalSizeBytes = 512,
            )
        }

        val tramai = SovereignTramai.builder()
            .profile(profile)
            .modelRegistry(registry)
            .auditStore(InMemoryAuditStore())
            .provider(FakeProvider("cloud-provider"), name = "cloud-provider", default = true)
            .provider(FakeProvider("fallback-provider"), name = "fallback-provider")
            .model("test-model", "cloud-provider")
            .model("test-model-fallback", "fallback-provider")
            .fallbackModel("test-model", "test-model-fallback", "fallback-provider")
            .clock(fixedClock)
            .modelArtifactVerifier(verifier)
            .modelArtifactVerificationSettings(
                ModelArtifactVerificationSettings(enabled = true),
            )
            .build()

        assertThat(invokedWith).isNotNull
        assertThat(invokedWith?.registryEntryId).isEqualTo("local-entry")
        assertThat(tramai.verificationReceipts()).hasSize(1)
    }

    @Test
    fun `GLOBAL_CLOUD primary with LOCAL fallback and missing manifest rejects`() {
        val cloudRegistered = RegisteredModel(
            registryEntryId = "cloud-entry",
            providerId = "cloud-provider",
            modelName = "test-model",
            revision = "1.0",
        )
        val localRegistered = RegisteredModel(
            registryEntryId = "local-entry",
            providerId = "fallback-provider",
            modelName = "test-model-fallback",
            revision = "1.0",
            artifactDigest = ModelArtifactDigest.of("sha256:${"b".repeat(64)}"),
        )

        val registry = InMemoryModelRegistry.builder()
            .register(cloudRegistered)
            .register(localRegistered)
            .build()

        val profile = SovereignProfileConfiguration(
            allowedModels = setOf("test-model", "test-model-fallback"),
            allowedProviders = setOf("cloud-provider", "fallback-provider"),
            allowedFallbackProviders = setOf("fallback-provider"),
            providerZones = mapOf(
                "cloud-provider" to ProviderTrustZone.GLOBAL_CLOUD,
                "fallback-provider" to ProviderTrustZone.LOCAL,
            ),
        )

        val verifier = RecordingVerifier { null }

        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(profile)
                .modelRegistry(registry)
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider("cloud-provider"), name = "cloud-provider", default = true)
                .provider(FakeProvider("fallback-provider"), name = "fallback-provider")
                .model("test-model", "cloud-provider")
                .model("test-model-fallback", "fallback-provider")
                .fallbackModel("test-model", "test-model-fallback", "fallback-provider")
                .clock(fixedClock)
                .modelArtifactVerifier(verifier)
                .modelArtifactVerificationSettings(
                    ModelArtifactVerificationSettings(enabled = true),
                )
                .build()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("artifact-manifest-not-found")
    }

    @Test
    fun `fallback identical to primary is rejected before artifact verification`() {
        // A fallback identical to the primary (same provider, same effective model) is a
        // degenerate route — under the canonical plan it is rejected at build, so artifact
        // verification never sees it. This is the fail-fast contract of Epic 2.2.
        val registry = InMemoryModelRegistry.builder()
            .register(localRegisteredModel(artifactDigest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}")))
            .build()

        val profile = SovereignProfileConfiguration(
            allowedModels = setOf("test-model"),
            allowedProviders = setOf("local-provider"),
            allowedFallbackProviders = setOf("local-provider"),
            providerZones = mapOf("local-provider" to ProviderTrustZone.LOCAL),
        )

        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(profile)
                .modelRegistry(registry)
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider("local-provider"), name = "local-provider", default = true)
                .model("test-model", "local-provider")
                .fallbackProvider("test-model", "local-provider")
                .clock(fixedClock)
                .modelArtifactVerifier(RecordingVerifier { error("verifier should not be invoked") })
                .modelArtifactVerificationSettings(
                    ModelArtifactVerificationSettings(enabled = true),
                )
                .build()
        }
            .isInstanceOf(dev.tramai.core.exception.ConfigurationException::class.java)
            .hasMessageContaining("duplicates its primary route")
    }

    @Test
    fun `GLOBAL_CLOUD route does not call registry when verification is enabled`() {
        val verifier = RecordingVerifier { error("verifier should not be invoked") }

        val tramai = SovereignTramai.builder()
            .profile(
                SovereignProfileConfiguration(
                    allowedModels = setOf("test-model"),
                    allowedProviders = setOf("cloud-provider"),
                    providerZones = mapOf("cloud-provider" to ProviderTrustZone.GLOBAL_CLOUD),
                ),
            )
            .modelRegistry(object : ModelRegistry {
                override suspend fun findApprovedModel(
                    providerId: String,
                    modelName: String,
                ): RegisteredModel {
                    error("Registry should not be called for CLOUD routes")
                }
            })
            .auditStore(InMemoryAuditStore())
            .provider(FakeProvider("cloud-provider"), name = "cloud-provider", default = true)
            .model("test-model", "cloud-provider")
            .clock(fixedClock)
            .modelArtifactVerifier(verifier)
            .modelArtifactVerificationSettings(
                ModelArtifactVerificationSettings(enabled = true),
            )
            .build()

        assertThat(tramai.verificationReceipts()).isEmpty()
        assertThat(verifier.seenModels).isEmpty()
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
