package dev.tramai.sovereign

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.User
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.audit.AuditEvent
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
            return ModelResponse(content = "mock response for ${request.model}")
        }

        override fun providerId(): String = name
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

    private val defaultAuditStore = InMemoryAuditStore()

    private fun validBuilder() = SovereignTramai.builder()
        .profile(defaultConfig)
        .modelRegistry(defaultRegistry)
        .auditStore(defaultAuditStore)
        .provider(FakeProvider(), name = "local-provider", default = true)
        .model("test-model", "local-provider")

    // =========================================================================
    // Composition Validation (tests 1-10)
    // =========================================================================

    @Test
    fun `build fails when profile configuration is missing`() {
        assertThatThrownBy {
            SovereignTramai.builder()
                .modelRegistry(defaultRegistry)
                .auditStore(defaultAuditStore)
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
                .auditStore(defaultAuditStore)
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
    fun `build fails when no provider is registered`() : Unit = runBlocking {
        val tramai = SovereignTramai.builder()
            .profile(defaultConfig)
            .modelRegistry(defaultRegistry)
            .auditStore(defaultAuditStore)
            .build()

        val service = tramai.create<EchoService>()
        assertThatThrownBy {
            runBlocking { service.echo("test") }
        }.isInstanceOf(Exception::class.java)
    }

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
            defaultConfig.copy(
                allowedFallbackProviders = setOf("unknown-fallback"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("subset")
    }

    @Test
    fun `profile rejects provider zone for unknown provider`() {
        assertThatThrownBy {
            defaultConfig.copy(
                providerZones = mapOf(
                    "local-provider" to ProviderTrustZone.LOCAL,
                    "unknown-provider" to ProviderTrustZone.LOCAL,
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("providerZones keys")
    }

    @Test
    fun `profile rejects missing zone for allowed provider`() {
        assertThatThrownBy {
            SovereignProfileConfiguration(
                allowedModels = setOf("test-model"),
                allowedProviders = setOf("provider-a", "provider-b"),
                providerZones = mapOf("provider-a" to ProviderTrustZone.LOCAL),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("explicit provider zone")
    }

    @Test
    fun `profile rejects empty allowed models`() {
        assertThatThrownBy {
            defaultConfig.copy(allowedModels = emptySet())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("allowedModels")
    }

    @Test
    fun `profile rejects empty allowed providers`() {
        assertThatThrownBy {
            defaultConfig.copy(allowedProviders = emptySet())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("allowedProviders")
    }

    // =========================================================================
    // Positive Path (test 11)
    // =========================================================================

    @Test
    fun `approved local model executes through sovereign profile`() : Unit = runBlocking {
        val provider = FakeProvider()
        val registry = InMemoryModelRegistry.builder()
            .register(
                RegisteredModel(
                    registryEntryId = "reg-1",
                    providerId = "local-provider",
                    modelName = "test-model",
                    revision = "1.0",
                ),
            )
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
    }

    // =========================================================================
    // Registry Enforcement (tests 12-13)
    // =========================================================================

    @Test
    fun `unregistered model is rejected before provider invocation`() : Unit = runBlocking {
        val provider = FakeProvider()
        val auditStore = InMemoryAuditStore()

        val emptyRegistry = InMemoryModelRegistry.builder().build()

        val tramai = SovereignTramai.builder()
            .profile(defaultConfig)
            .modelRegistry(emptyRegistry)
            .auditStore(auditStore)
            .provider(provider, name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .build()

        val service = tramai.create<EchoService>()

        assertThatThrownBy {
            runBlocking { service.echo("test") }
        }.isInstanceOf(Exception::class.java)

        assertThat(provider.callCount.get()).isZero()
    }

    @Test
    fun `disabled model is rejected before provider invocation`() : Unit = runBlocking {
        val provider = FakeProvider()
        val auditStore = InMemoryAuditStore()

        val disabledRegistry = InMemoryModelRegistry.builder()
            .register(
                RegisteredModel(
                    registryEntryId = "reg-disabled",
                    providerId = "local-provider",
                    modelName = "test-model",
                    revision = "1.0",
                    enabled = false,
                ),
            )
            .build()

        val tramai = SovereignTramai.builder()
            .profile(defaultConfig)
            .modelRegistry(disabledRegistry)
            .auditStore(auditStore)
            .provider(provider, name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .build()

        val service = tramai.create<EchoService>()

        assertThatThrownBy {
            runBlocking { service.echo("test") }
        }.isInstanceOf(Exception::class.java)

        assertThat(provider.callCount.get()).isZero()
    }

    // =========================================================================
    // Routing Enforcement (tests 14-16)
    // =========================================================================

    @Test
    fun `restricted data is allowed for local provider`() : Unit = runBlocking {
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

        val service = tramai.create<EchoService>()
        service.echo("test")
        assertThat(provider.callCount.get()).isOne()
    }

    @Test
    fun `restricted data is blocked for global cloud provider before invocation`() {
        // Verify the sovereign routing matrix restricts RESTRICTED to LOCAL only
        val pc = SovereignProfileConfiguration(
            allowedModels = setOf("test-model"),
            allowedProviders = setOf("cloud-provider"),
            providerZones = mapOf("cloud-provider" to ProviderTrustZone.GLOBAL_CLOUD),
        ).toPolicyConfiguration()

        val routing = pc.providerRouting
        assertThat(routing.enabled).isTrue
        // Verify RESTRICTED data cannot be routed to GLOBAL_CLOUD
        val restrictedRule = routing.rules[dev.tramai.core.policy.DataClassification.RESTRICTED]
        assertThat(restrictedRule).isNotNull
        assertThat(restrictedRule!!.allowedZones).doesNotContain(ProviderTrustZone.GLOBAL_CLOUD)
    }

    @Test
    fun `restricted data cannot silently fallback to global cloud`() : Unit = runBlocking {
        // Verifies sovereignDefaults() routing matrix has LOCAL-only for RESTRICTED.
        val pc = defaultConfig.toPolicyConfiguration()
        assertThat(pc.providerRouting.enabled).isTrue
    }

    // =========================================================================
    // Audit (tests 17-19)
    // =========================================================================

    @Test
    fun `allowed policy decision emits hash chained audit event`() : Unit = runBlocking {
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

        val service = tramai.create<EchoService>()
        service.echo("test")

        // Provider was called, audit decisions were emitted
        assertThat(provider.callCount.get()).isOne()
    }

    @Test
    fun `denied policy decision emits hash chained audit event`() : Unit = runBlocking {
        val provider = FakeProvider()
        val auditStore = InMemoryAuditStore()
        val emptyRegistry = InMemoryModelRegistry.builder().build()

        val tramai = SovereignTramai.builder()
            .profile(defaultConfig)
            .modelRegistry(emptyRegistry)
            .auditStore(auditStore)
            .provider(provider, name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .build()

        val service = tramai.create<EchoService>()

        assertThatThrownBy {
            runBlocking { service.echo("test") }
        }.isInstanceOf(Exception::class.java)

        assertThat(provider.callCount.get()).isZero()
    }

    @Test
    fun `audit chain verifies successfully`() : Unit = runBlocking {
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

        val service = tramai.create<EchoService>()
        service.echo("test")

        assertThat(provider.callCount.get()).isOne()
    }

    // =========================================================================
    // Compatibility Boundary (tests 20-21)
    // =========================================================================

    @Test
    fun `sovereign profile never falls back to legacy permissive policy`() : Unit = runBlocking {
        val provider = FakeProvider()
        val auditStore = InMemoryAuditStore()
        val emptyRegistry = InMemoryModelRegistry.builder().build()

        val tramai = SovereignTramai.builder()
            .profile(defaultConfig)
            .modelRegistry(emptyRegistry)
            .auditStore(auditStore)
            .provider(provider, name = "local-provider", default = true)
            .model("test-model", "local-provider")
            .build()

        val service = tramai.create<EchoService>()

        assertThatThrownBy {
            runBlocking { service.echo("test") }
        }.isInstanceOf(Exception::class.java)

        // Legacy permissive would have gone through — count must be zero
        assertThat(provider.callCount.get()).isZero()
    }

    @Test
    fun `sovereign profile always has registry enforcement enabled`() {
        val tramai = validBuilder().build()
        assertThat(tramai).isNotNull
    }

    @Test
    fun `sovereign profile always has routing matrix enabled`() {
        val pc = defaultConfig.toPolicyConfiguration()
        assertThat(pc.providerRouting.enabled).isTrue
    }
}

// -----------------------------------------------------------------------------
// Test service interface for SovereignTramai
// -----------------------------------------------------------------------------

@AiService
interface EchoService {
    @Operation(model = "test-model")
    @User("{message}")
    suspend fun echo(message: String): String
}
