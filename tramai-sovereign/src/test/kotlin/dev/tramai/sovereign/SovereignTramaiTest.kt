package dev.tramai.sovereign

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.User
import dev.tramai.core.exception.ModelDisabledException
import dev.tramai.core.exception.ModelNotRegisteredException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.model.ClassifiedDocument
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.provider.ModelProvider
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.audit.AuditChainVerifier
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.model.InMemoryModelRegistry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SovereignTramaiTest {

    // -------------------------------------------------------------------------
    // Fake implementations
    // -------------------------------------------------------------------------

    private class FakeProvider(private val name: String = "local-provider") : ModelProvider {
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            callCount.incrementAndGet()
            // Simulate a provider-level transport failure that triggers a retry
            if (name.contains("failing")) {
                throw java.io.IOException("provider transport failure")
            }
            return ModelResponse(content = "mock response for ${request.model}")
        }

        override fun providerId(): String = name
    }

    /**
     * Test wrapper around [InMemoryAuditStore] that records which stream IDs
     * received events, enabling assertions on audit emission.
     */
    private class CapturingAuditStore(
        private val delegate: InMemoryAuditStore = InMemoryAuditStore(),
    ) : AuditStore {
        val streamIds = linkedSetOf<String>()

        override suspend fun appendNext(
            auditStreamId: String,
            eventFactory: (AuditEvent?) -> AuditEvent,
        ): AuditEvent {
            streamIds += auditStreamId
            return delegate.appendNext(auditStreamId, eventFactory)
        }

        override suspend fun readStream(auditStreamId: String): List<AuditEvent> =
            delegate.readStream(auditStreamId)

        override suspend fun latestEvent(auditStreamId: String): AuditEvent? =
            delegate.latestEvent(auditStreamId)
    }

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private val defaultConfig = SovereignProfileConfiguration(
        allowedModels = setOf("test-model"),
        allowedProviders = setOf("local-provider"),
        providerZones = mapOf("local-provider" to ProviderTrustZone.LOCAL),
    )

    private val defaultRegistry = InMemoryModelRegistry.builder()
        .register(
            RegisteredModel(
                registryEntryId = "reg-test",
                providerId = "local-provider",
                modelName = "test-model",
                revision = "1.0",
            ),
        )
        .build()

    private fun validBuilder() = SovereignTramai.builder()
        .profile(defaultConfig)
        .modelRegistry(defaultRegistry)
        .auditStore(InMemoryAuditStore())
        .provider(FakeProvider(), name = "local-provider", default = true)
        .model("test-model", "local-provider")

    @Test
    fun `sovereign create and runtime share one owned engine`() {
        val tramai = validBuilder().build()

        tramai.create<EchoService>()
        val runtime = tramai.runtime()
        runtime.create<EchoService>()

        tramai.close()
        assertThatThrownBy { tramai.create<EchoService>() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Tramai runtime is closed")
    }

    @Test
    fun `sovereign runtime returns the same wrapper instance`() {
        val tramai = validBuilder().build()

        assertThat(tramai.runtime()).isSameAs(tramai.runtime())
    }

    @Test
    fun `sovereign close propagates to the delegate runtime`() {
        val tramai = validBuilder().build()
        val runtime = tramai.runtime()

        tramai.close()

        assertThatThrownBy { runtime.create<EchoService>() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Tramai runtime is closed")
    }

    // =========================================================================
    // Composition Validation
    // =========================================================================

    @Test
    fun `build fails when profile configuration is missing`() {
        assertThatThrownBy {
            SovereignTramai.builder()
                .modelRegistry(defaultRegistry)
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider(), name = "local-provider", default = true)
                .model("test-model", "local-provider")
                .build()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("SovereignProfileConfiguration")
    }

    @Test
    fun `build fails when model registry is missing`() {
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(defaultConfig)
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider(), name = "local-provider", default = true)
                .model("test-model", "local-provider")
                .build()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ModelRegistry")
    }

    @Test
    fun `build fails when audit store is missing`() {
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(defaultConfig)
                .modelRegistry(defaultRegistry)
                .provider(FakeProvider(), name = "local-provider", default = true)
                .model("test-model", "local-provider")
                .build()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("AuditStore")
    }

    @Test
    fun `build fails immediately when no provider is registered`() {
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(defaultConfig)
                .modelRegistry(defaultRegistry)
                .auditStore(InMemoryAuditStore())
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("At least one provider")
    }

    @Test
    fun `build fails when registered provider is not in allowedProviders`() {
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(defaultConfig)
                .modelRegistry(defaultRegistry)
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider("unlisted-provider"), name = "unlisted-provider", default = true)
                .model("test-model", "unlisted-provider")
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not in allowedProviders")
    }

    @Test
    fun `build fails when allowed provider is not registered`() {
        val config = SovereignProfileConfiguration(
            allowedModels = setOf("test-model"),
            allowedProviders = setOf("local-provider", "not-registered"),
            providerZones = mapOf(
                "local-provider" to ProviderTrustZone.LOCAL,
                "not-registered" to ProviderTrustZone.LOCAL,
            ),
        )
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(config)
                .modelRegistry(defaultRegistry)
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider(), name = "local-provider", default = true)
                .model("test-model", "local-provider")
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("has not been registered")
    }

    @Test
    fun `build fails when allowed model has no mapping`() {
        val config = SovereignProfileConfiguration(
            allowedModels = setOf("test-model", "orphan-model"),
            allowedProviders = setOf("local-provider"),
            providerZones = mapOf("local-provider" to ProviderTrustZone.LOCAL),
        )
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(config)
                .modelRegistry(defaultRegistry)
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider(), name = "local-provider", default = true)
                .model("test-model", "local-provider")
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("has no primary route")
    }

    @Test
    fun `build fails when model maps to unknown provider`() {
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(defaultConfig)
                .modelRegistry(defaultRegistry)
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider(), name = "local-provider", default = true)
                .model("test-model", "unknown-provider")
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("routes to unknown provider")
    }

    @Test
    fun `build fails when model maps to non-allowed model`() {
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(defaultConfig)
                .modelRegistry(defaultRegistry)
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider(), name = "local-provider", default = true)
                .model("extra-model", "local-provider")
                .model("test-model", "local-provider")
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not in allowedModels")
    }

    @Test
    fun `duplicate provider registration is rejected`() {
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(defaultConfig)
                .modelRegistry(defaultRegistry)
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider(), name = "local-provider", default = true)
                .provider(FakeProvider(), name = "local-provider")
                .model("test-model", "local-provider")
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Duplicate provider")
    }

    @Test
    fun `build fails when fallbackProvider target is not in allowedFallbackProviders`() {
        val config = SovereignProfileConfiguration(
            allowedModels = setOf("test-model"),
            allowedProviders = setOf("local-provider", "cloud-provider"),
            allowedFallbackProviders = emptySet(),
            providerZones = mapOf(
                "local-provider" to ProviderTrustZone.LOCAL,
                "cloud-provider" to ProviderTrustZone.LOCAL,
            ),
        )
        assertThatThrownBy {
            SovereignTramai.builder()
                .profile(config)
                .modelRegistry(defaultRegistry)
                .auditStore(InMemoryAuditStore())
                .provider(FakeProvider(), name = "local-provider", default = true)
                .provider(FakeProvider("cloud-provider"), name = "cloud-provider")
                .model("test-model", "local-provider")
                .fallbackProvider("test-model", "cloud-provider")
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not in allowedFallbackProviders")
    }

    // =========================================================================
    // Profile Configuration Validation
    // =========================================================================

    @Test
    fun `profile rejects wildcard models`() {
        assertThatThrownBy {
            defaultConfig.copy(allowedModels = setOf("*"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `profile rejects wildcard providers`() {
        assertThatThrownBy {
            defaultConfig.copy(allowedProviders = setOf("*"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `profile rejects fallback provider not in allowed providers`() {
        assertThatThrownBy {
            defaultConfig.copy(allowedFallbackProviders = setOf("unknown-fallback"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("subset")
    }

    // =========================================================================
    // Positive Path
    // =========================================================================

    @Test
    fun `approved local model executes through sovereign profile`() : Unit = runBlocking {
        val provider = FakeProvider()
        val registry = InMemoryModelRegistry.builder()
            .register(RegisteredModel("reg-1", "local-provider", "test-model", "1.0"))
            .build()
        val auditStore = InMemoryAuditStore()

        val tramai = SovereignTramai.builder()
            .profile(defaultConfig)
            .modelRegistry(registry)
            .auditStore(auditStore)
            .provider(provider, name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .build()

        val service = tramai.create<EchoService>()
        val result = service.echo("hello")

        assertThat(provider.callCount.get()).isOne()
        assertThat(result).isEqualTo("mock response for test-model")
    }

    // =========================================================================
    // Registry Enforcement (typed exceptions)
    // =========================================================================

    @Test
    fun `unregistered model is rejected with ModelNotRegisteredException`() : Unit = runBlocking {
        val provider = FakeProvider()
        val emptyRegistry = InMemoryModelRegistry.builder().build()

        val tramai = SovereignTramai.builder()
            .profile(defaultConfig)
            .modelRegistry(emptyRegistry)
            .auditStore(InMemoryAuditStore())
            .provider(provider, name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .build()

        val service = tramai.create<EchoService>()

        assertThatThrownBy {
            runBlocking { service.echo("test") }
        }.isInstanceOf(ModelNotRegisteredException::class.java)

        assertThat(provider.callCount.get()).isZero()
    }

    @Test
    fun `disabled model is rejected with ModelDisabledException`() : Unit = runBlocking {
        val provider = FakeProvider()
        val disabledRegistry = InMemoryModelRegistry.builder()
            .register(RegisteredModel("reg-disabled", "local-provider", "test-model", "1.0", enabled = false))
            .build()

        val tramai = SovereignTramai.builder()
            .profile(defaultConfig)
            .modelRegistry(disabledRegistry)
            .auditStore(InMemoryAuditStore())
            .provider(provider, name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .build()

        val service = tramai.create<EchoService>()

        assertThatThrownBy {
            runBlocking { service.echo("test") }
        }.isInstanceOf(ModelDisabledException::class.java)

        assertThat(provider.callCount.get()).isZero()
    }

    // =========================================================================
    // Routing Enforcement (runtime tests)
    // =========================================================================

    @Test
    fun `restricted document is allowed for local provider`() : Unit = runBlocking {
        val provider = FakeProvider()
        val auditStore = InMemoryAuditStore()
        val registry = InMemoryModelRegistry.builder()
            .register(RegisteredModel("r1", "local-provider", "test-model", "1.0"))
            .build()

        val tramai = SovereignTramai.builder()
            .profile(defaultConfig)
            .modelRegistry(registry)
            .auditStore(auditStore)
            .provider(provider, name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .build()

        val service = tramai.create<ClassifiedEchoService>()
        val doc = ClassifiedDocument("test", DataClassification.RESTRICTED, ClassificationSource.DECLARED)
        service.echo(doc)

        assertThat(provider.callCount.get()).isOne()
    }

    @Test
    fun `restricted document is blocked for global cloud provider with PolicyViolationException`() : Unit = runBlocking {
        val cloudProvider = FakeProvider("cloud-provider")
        val auditStore = InMemoryAuditStore()
        val registry = InMemoryModelRegistry.builder()
            .register(RegisteredModel("r1", "cloud-provider", "test-model", "1.0"))
            .build()

        val cloudConfig = SovereignProfileConfiguration(
            allowedModels = setOf("test-model"),
            allowedProviders = setOf("cloud-provider"),
            providerZones = mapOf("cloud-provider" to ProviderTrustZone.GLOBAL_CLOUD),
        )

        val tramai = SovereignTramai.builder()
            .profile(cloudConfig)
            .modelRegistry(registry)
            .auditStore(auditStore)
            .provider(cloudProvider, name = "cloud-provider", default = true)
            .model("test-model", "cloud-provider")
            .build()

        val service = tramai.create<ClassifiedEchoService>()
        val doc = ClassifiedDocument("test", DataClassification.RESTRICTED, ClassificationSource.DECLARED)

        assertThatThrownBy {
            runBlocking { service.echo(doc) }
        }.isInstanceOfSatisfying(PolicyViolationException::class.java) { exception ->
            assertThat(exception.decision.reasonCode)
                .isEqualTo("classification-routing-blocked")
        }

        assertThat(cloudProvider.callCount.get()).isZero()
    }

    @Test
    fun `restricted data fallback to global cloud is blocked`() {
        // Verify the sovereign routing matrix prevents RESTRICTED fallback to GLOBAL_CLOUD
        val pc = defaultConfig.toPolicyConfiguration()
        val routing = pc.providerRouting
        assertThat(routing.enabled).isTrue
        val restrictedRule = routing.rules[DataClassification.RESTRICTED]
        assertThat(restrictedRule).isNotNull
        assertThat(restrictedRule!!.allowedFallbackZones).isEmpty()
    }

    // =========================================================================
    // Audit (real store reads and hash-chain verification)
    // =========================================================================

    @Test
    fun `allowed policy decision emits audit events with valid hash chain`() : Unit = runBlocking {
        val provider = FakeProvider()
        val capturingStore = CapturingAuditStore()
        val registry = InMemoryModelRegistry.builder()
            .register(RegisteredModel("r1", "local-provider", "test-model", "1.0"))
            .build()

        val tramai = SovereignTramai.builder()
            .profile(defaultConfig)
            .modelRegistry(registry)
            .auditStore(capturingStore)
            .provider(provider, name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .build()

        val service = tramai.create<EchoService>()
        service.echo("test")

        assertThat(provider.callCount.get()).isOne()
        assertThat(capturingStore.streamIds).isNotEmpty

        for (streamId in capturingStore.streamIds) {
            val events = capturingStore.readStream(streamId)
            assertThat(events).isNotEmpty
            val result = AuditChainVerifier.verify(events)
            assertThat(result.isValid)
                .describedAs("Audit chain verification failed for stream $streamId: ${result.errors}")
                .isTrue
        }
    }

    @Test
    fun `denied policy decision emits audit events with valid hash chain`() : Unit = runBlocking {
        val cloudProvider = FakeProvider("cloud-provider")
        val capturingStore = CapturingAuditStore()
        val registry = InMemoryModelRegistry.builder()
            .register(RegisteredModel("r1", "cloud-provider", "test-model", "1.0"))
            .build()

        val cloudConfig = SovereignProfileConfiguration(
            allowedModels = setOf("test-model"),
            allowedProviders = setOf("cloud-provider"),
            providerZones = mapOf("cloud-provider" to ProviderTrustZone.GLOBAL_CLOUD),
        )

        val tramai = SovereignTramai.builder()
            .profile(cloudConfig)
            .modelRegistry(registry)
            .auditStore(capturingStore)
            .provider(cloudProvider, name = "cloud-provider", default = true)
            .model("test-model", "cloud-provider")
            .build()

        val service = tramai.create<ClassifiedEchoService>()
        val doc = ClassifiedDocument("test", DataClassification.RESTRICTED, ClassificationSource.DECLARED)

        assertThatThrownBy {
            runBlocking { service.echo(doc) }
        }.isInstanceOf(PolicyViolationException::class.java)

        assertThat(cloudProvider.callCount.get()).isZero()
        assertThat(capturingStore.streamIds).isNotEmpty

        for (streamId in capturingStore.streamIds) {
            val events = capturingStore.readStream(streamId)
            assertThat(events).isNotEmpty
            val result = AuditChainVerifier.verify(events)
            assertThat(result.isValid)
                .describedAs("Audit chain verification failed for stream $streamId: ${result.errors}")
                .isTrue
        }
    }

    // =========================================================================
    // Invariant Enforcement
    // =========================================================================

    @Test
    fun `registry enforcement cannot be disabled through sovereign API`() : Unit = runBlocking {
        val provider = FakeProvider()
        val emptyRegistry = InMemoryModelRegistry.builder().build()

        val tramai = SovereignTramai.builder()
            .profile(defaultConfig)
            .modelRegistry(emptyRegistry)
            .auditStore(InMemoryAuditStore())
            .provider(provider, name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .build()

        val service = tramai.create<EchoService>()

        assertThatThrownBy {
            runBlocking { service.echo("test") }
        }.isInstanceOf(ModelNotRegisteredException::class.java)

        assertThat(provider.callCount.get()).isZero()
    }

    @Test
    fun `sovereign profile always has routing matrix enabled`() {
        val pc = defaultConfig.toPolicyConfiguration()
        assertThat(pc.providerRouting.enabled).isTrue
    }

    @Test
    fun `sovereign profile never falls back to legacy permissive policy`() : Unit = runBlocking {
        val provider = FakeProvider()
        val emptyRegistry = InMemoryModelRegistry.builder().build()

        val tramai = SovereignTramai.builder()
            .profile(defaultConfig)
            .modelRegistry(emptyRegistry)
            .auditStore(InMemoryAuditStore())
            .provider(provider, name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .build()

        val service = tramai.create<EchoService>()

        assertThatThrownBy {
            runBlocking { service.echo("test") }
        }.isInstanceOf(ModelNotRegisteredException::class.java)

        assertThat(provider.callCount.get()).isZero()
    }
}

// -----------------------------------------------------------------------------
// Test service interfaces
// -----------------------------------------------------------------------------

@AiService
interface EchoService {
    @Operation(model = "test-model")
    @User("{message}")
    suspend fun echo(message: String): String
}

@AiService
interface ClassifiedEchoService {
    @Operation(model = "test-model")
    @User("{document}")
    suspend fun echo(document: ClassifiedDocument<String>): String
}
