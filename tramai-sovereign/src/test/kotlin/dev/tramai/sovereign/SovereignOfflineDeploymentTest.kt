package dev.tramai.sovereign

import dev.tramai.core.model.ModelArtifactVerificationSettings
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.model.InMemoryModelRegistry
import java.time.Clock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SovereignOfflineDeploymentTest {

    private val clock: Clock = Clock.systemUTC()

    private val localProfile = SovereignProfileConfiguration(
        allowedModels = setOf("test-model"),
        allowedProviders = setOf("local-provider"),
        providerZones = mapOf("local-provider" to ProviderTrustZone.LOCAL),
        deploymentMode = SovereignDeploymentMode.OFFLINE,
    )

    private val euCloudProfile = SovereignProfileConfiguration(
        allowedModels = setOf("test-model"),
        allowedProviders = setOf("cloud-provider"),
        providerZones = mapOf("cloud-provider" to ProviderTrustZone.EU_CLOUD),
        deploymentMode = SovereignDeploymentMode.OFFLINE,
    )

    private val globalCloudProfile = SovereignProfileConfiguration(
        allowedModels = setOf("test-model"),
        allowedProviders = setOf("cloud-provider"),
        providerZones = mapOf("cloud-provider" to ProviderTrustZone.GLOBAL_CLOUD),
        deploymentMode = SovereignDeploymentMode.OFFLINE,
    )

    private val localModelRegistry: ModelRegistry = InMemoryModelRegistry.builder()
        .register(
            RegisteredModel(
                registryEntryId = "local-entry",
                providerId = "local-provider",
                modelName = "test-model",
                revision = "1.0",
            ),
        )
        .build()

    private fun localProvider(name: String = "local-provider"): ModelProvider =
        FakeProvider(name)

    // --- STANDARD tests ---

    @Test
    fun `STANDARD with LOCAL provider builds`() {
        val profile = localProfile.copy(deploymentMode = SovereignDeploymentMode.STANDARD)
        val tramai = SovereignTramai.builder()
            .profile(profile)
            .modelRegistry(localModelRegistry)
            .auditStore(InMemoryAuditStore())
            .provider(localProvider(), name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .clock(clock)
            .build()
        assertThat(tramai).isNotNull
    }

    @Test
    fun `STANDARD with EU_CLOUD provider builds`() {
        val profile = SovereignProfileConfiguration(
            allowedModels = setOf("test-model"),
            allowedProviders = setOf("cloud-provider"),
            providerZones = mapOf("cloud-provider" to ProviderTrustZone.EU_CLOUD),
            deploymentMode = SovereignDeploymentMode.STANDARD,
        )
        val registry = InMemoryModelRegistry.builder()
            .register(
                RegisteredModel(
                    registryEntryId = "cloud-entry",
                    providerId = "cloud-provider",
                    modelName = "test-model",
                    revision = "1.0",
                ),
            )
            .build()
        val tramai = SovereignTramai.builder()
            .profile(profile)
            .modelRegistry(registry)
            .auditStore(InMemoryAuditStore())
            .provider(FakeProvider("cloud-provider"), name = "cloud-provider", default = true)
            .model("test-model", "cloud-provider")
            .clock(clock)
            .build()
        assertThat(tramai).isNotNull
    }

    @Test
    fun `STANDARD with GLOBAL_CLOUD provider builds`() {
        val profile = SovereignProfileConfiguration(
            allowedModels = setOf("test-model"),
            allowedProviders = setOf("cloud-provider"),
            providerZones = mapOf("cloud-provider" to ProviderTrustZone.GLOBAL_CLOUD),
            deploymentMode = SovereignDeploymentMode.STANDARD,
        )
        val registry = InMemoryModelRegistry.builder()
            .register(
                RegisteredModel(
                    registryEntryId = "cloud-entry",
                    providerId = "cloud-provider",
                    modelName = "test-model",
                    revision = "1.0",
                ),
            )
            .build()
        val tramai = SovereignTramai.builder()
            .profile(profile)
            .modelRegistry(registry)
            .auditStore(InMemoryAuditStore())
            .provider(FakeProvider("cloud-provider"), name = "cloud-provider", default = true)
            .model("test-model", "cloud-provider")
            .clock(clock)
            .build()
        assertThat(tramai).isNotNull
    }

    // --- OFFLINE happy path ---

    @Test
    fun `OFFLINE with only LOCAL provider builds`() {
        val tramai = SovereignTramai.builder()
            .profile(localProfile)
            .modelRegistry(localModelRegistry)
            .auditStore(InMemoryAuditStore())
            .provider(localProvider(), name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .clock(clock)
            .build()
        assertThat(tramai).isNotNull
    }

    // --- OFFLINE rejection tests ---

    @Test
    fun `OFFLINE with EU_CLOUD registered provider rejects`() {
        val registry = InMemoryModelRegistry.builder()
            .register(
                RegisteredModel(
                    registryEntryId = "cloud-entry",
                    providerId = "cloud-provider",
                    modelName = "test-model",
                    revision = "1.0",
                ),
            )
            .build()
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(euCloudProfile)
                .modelRegistry(registry)
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider("cloud-provider"), name = "cloud-provider", default = true)
                .model("test-model", "cloud-provider")
                .clock(clock)
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("offline-profile-non-local-provider-rejected")
    }

    @Test
    fun `OFFLINE with GLOBAL_CLOUD registered provider rejects`() {
        val registry = InMemoryModelRegistry.builder()
            .register(
                RegisteredModel(
                    registryEntryId = "cloud-entry",
                    providerId = "cloud-provider",
                    modelName = "test-model",
                    revision = "1.0",
                ),
            )
            .build()
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(globalCloudProfile)
                .modelRegistry(registry)
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider("cloud-provider"), name = "cloud-provider", default = true)
                .model("test-model", "cloud-provider")
                .clock(clock)
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("offline-profile-non-local-provider-rejected")
    }

    @Test
    fun `OFFLINE with local primary route and local fallback builds`() {
        val profile = SovereignProfileConfiguration(
            allowedModels = setOf("test-model", "fallback-model"),
            allowedProviders = setOf("local-provider", "fallback-provider"),
            allowedFallbackProviders = setOf("fallback-provider"),
            providerZones = mapOf(
                "local-provider" to ProviderTrustZone.LOCAL,
                "fallback-provider" to ProviderTrustZone.LOCAL,
            ),
            deploymentMode = SovereignDeploymentMode.OFFLINE,
        )
        val registry = InMemoryModelRegistry.builder()
            .register(
                RegisteredModel(
                    registryEntryId = "local-entry",
                    providerId = "local-provider",
                    modelName = "test-model",
                    revision = "1.0",
                ),
            )
            .register(
                RegisteredModel(
                    registryEntryId = "fallback-entry",
                    providerId = "fallback-provider",
                    modelName = "fallback-model",
                    revision = "1.0",
                ),
            )
            .build()
        val tramai = SovereignTramai.builder()
            .profile(profile)
            .modelRegistry(registry)
            .auditStore(InMemoryAuditStore())
            .provider(localProvider("local-provider"), name = "local-provider", default = true)
            .provider(localProvider("fallback-provider"), name = "fallback-provider")
            .model("test-model", "local-provider")
            .model("fallback-model", "fallback-provider")
            .fallbackModel("test-model", "fallback-model", "fallback-provider")
            .clock(clock)
            .build()
        assertThat(tramai).isNotNull
    }

    @Test
    fun `OFFLINE with cloud fallback route rejects`() {
        // The registered-provider check fires before fallback validation,
        // because the fallback cloud provider must also be registered.
        // This test proves the overall failure; the specific error code
        // is offline-profile-non-local-provider-rejected since the first
        // loop catches every registered non-LOCAL provider.
        val profile = SovereignProfileConfiguration(
            allowedModels = setOf("test-model"),
            allowedProviders = setOf("local-provider", "cloud-provider"),
            allowedFallbackProviders = setOf("cloud-provider"),
            providerZones = mapOf(
                "local-provider" to ProviderTrustZone.LOCAL,
                "cloud-provider" to ProviderTrustZone.GLOBAL_CLOUD,
            ),
            deploymentMode = SovereignDeploymentMode.OFFLINE,
        )
        val registry = InMemoryModelRegistry.builder()
            .register(
                RegisteredModel(
                    registryEntryId = "local-entry",
                    providerId = "local-provider",
                    modelName = "test-model",
                    revision = "1.0",
                ),
            )
            .register(
                RegisteredModel(
                    registryEntryId = "cloud-entry",
                    providerId = "cloud-provider",
                    modelName = "test-model",
                    revision = "1.0",
                ),
            )
            .build()
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(profile)
                .modelRegistry(registry)
                .auditStore(InMemoryAuditStore())
                .provider(localProvider("local-provider"), name = "local-provider", default = true)
                .provider(FakeProvider("cloud-provider"), name = "cloud-provider")
                .model("test-model", "local-provider")
                .fallbackModel("test-model", "test-model", "cloud-provider")
                .clock(clock)
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            // Registered-provider check fires first — catches cloud-provider as non-LOCAL
            .hasMessage("offline-profile-non-local-provider-rejected")
    }

    @Test
    fun `OFFLINE with cloud default provider rejects`() {
        // Same ordering issue: the registered-provider check fires before
        // default-provider validation because the cloud provider must be
        // registered to be the default.
        val profile = SovereignProfileConfiguration(
            allowedModels = setOf("test-model"),
            allowedProviders = setOf("local-provider", "cloud-provider"),
            providerZones = mapOf(
                "local-provider" to ProviderTrustZone.LOCAL,
                "cloud-provider" to ProviderTrustZone.GLOBAL_CLOUD,
            ),
            deploymentMode = SovereignDeploymentMode.OFFLINE,
        )
        val registry = InMemoryModelRegistry.builder()
            .register(
                RegisteredModel(
                    registryEntryId = "local-entry",
                    providerId = "local-provider",
                    modelName = "test-model",
                    revision = "1.0",
                ),
            )
            .register(
                RegisteredModel(
                    registryEntryId = "cloud-entry",
                    providerId = "cloud-provider",
                    modelName = "test-model",
                    revision = "1.0",
                ),
            )
            .build()
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(profile)
                .modelRegistry(registry)
                .auditStore(InMemoryAuditStore())
                .provider(localProvider("local-provider"), name = "local-provider")
                .provider(FakeProvider("cloud-provider"), name = "cloud-provider", default = true)
                .model("test-model", "local-provider")
                .clock(clock)
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            // Registered-provider check fires first
            .hasMessage("offline-profile-non-local-provider-rejected")
    }

    @Test
    fun `offline validation occurs before artifact registry lookup`() {
        // Registry throws if called — offline check must reject before reaching it
        val throwingRegistry = object : ModelRegistry {
            override suspend fun findApprovedModel(
                providerId: String,
                modelName: String,
            ): RegisteredModel {
                error("Registry should not be called for this test")
            }
        }
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(euCloudProfile)
                .modelRegistry(throwingRegistry)
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider("cloud-provider"), name = "cloud-provider", default = true)
                .model("test-model", "cloud-provider")
                .clock(clock)
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("offline-profile-non-local-provider-rejected")
    }

    @Test
    fun `offline valid local route with PR 30 verification enabled builds and stores receipt`() {
        val verifier = RecordingOfflineVerifier()
        val tramai = SovereignTramai.builder()
            .profile(localProfile)
            .modelRegistry(localModelRegistry)
            .auditStore(InMemoryAuditStore())
            .provider(localProvider(), name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .clock(clock)
            .modelArtifactVerifier(verifier)
            .modelArtifactVerificationSettings(
                ModelArtifactVerificationSettings(
                    enabled = true,
                    requireDigestForLocalModels = false,
                ),
            )
            .build()
        assertThat(tramai.verificationReceipts()).isNotEmpty
        assertThat(verifier.seenModels).isNotEmpty
    }

    @Test
    fun `offline valid local route with PR 30 verification disabled builds and empty receipts`() {
        val verifier = RecordingOfflineVerifier()
        val tramai = SovereignTramai.builder()
            .profile(localProfile)
            .modelRegistry(localModelRegistry)
            .auditStore(InMemoryAuditStore())
            .provider(localProvider(), name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .clock(clock)
            .modelArtifactVerifier(verifier)
            .modelArtifactVerificationSettings(
                ModelArtifactVerificationSettings(
                    enabled = false,
                ),
            )
            .build()
        assertThat(tramai.verificationReceipts()).isEmpty()
        assertThat(verifier.seenModels).isEmpty()
    }

    private class FakeProvider(private val name: String) : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse =
            ModelResponse(content = "mock response for ${request.model}")
        override fun providerId(): String = name
    }

    private class RecordingOfflineVerifier(
        private val verifierClock: Clock = Clock.systemUTC(),
    ) : dev.tramai.core.model.ModelArtifactVerifier {
        val seenModels = mutableListOf<RegisteredModel>()

        override suspend fun verify(
            registeredModel: RegisteredModel,
        ): dev.tramai.core.model.VerifiedLocalModelArtifact? {
            seenModels += registeredModel
            return dev.tramai.core.model.VerifiedLocalModelArtifact(
                registryEntryId = registeredModel.registryEntryId,
                manifestDigest = registeredModel.artifactDigest
                    ?: dev.tramai.core.model.ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
                modelName = registeredModel.modelName,
                verifiedAt = verifierClock.instant(),
                artifactCount = 1,
                totalSizeBytes = 512,
            )
        }
    }
}
