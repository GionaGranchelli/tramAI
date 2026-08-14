package dev.tramai.standalone

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.NoOpOperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Proves that the standalone composition path produces the same immutable,
 * validated runtime snapshot as the engine component model — with the public
 * builder API unchanged.
 */
class TramaiComponentCompositionTest {

    @Test
    fun `invalid provider routing fails at build`() {
        val provider = object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse = ModelResponse(content = "unused")

            override fun providerId(): String = "primary"
        }

        assertThatThrownBy {
            Tramai.builder()
                .provider(provider, name = "duplicate")
                .provider(provider, name = "duplicate")
                .build()
        }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessage("Duplicate provider 'duplicate'")

        assertThatThrownBy {
            Tramai.builder()
                .defaultProvider("missing")
                .build()
        }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessage("Default provider 'missing' is not registered")
    }

    @Test
    fun `partial approval configuration fails at build`() {
        val store = collaborator<ApprovalContinuationStore>()
        val digester = collaborator<ToolArgumentsDigester>()
        val coordinator = collaborator<ApprovalGateCoordinator>()

        val partials = listOf(
            Triple(store, null, null),
            Triple(null, digester, null),
            Triple(null, null, coordinator),
            Triple(store, digester, null),
            Triple(store, null, coordinator),
            Triple(null, digester, coordinator),
        )

        partials.forEach { (continuationStore, argumentsDigester, gateCoordinator) ->
            val builder = baseBuilder()
            if (continuationStore != null) builder.approvalContinuationStore(continuationStore)
            if (argumentsDigester != null) builder.toolArgumentsDigester(argumentsDigester)
            if (gateCoordinator != null) builder.approvalGateCoordinator(gateCoordinator)

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("Approval suspension requires continuation store, arguments digester, and gate coordinator")
        }
    }

    @Test
    fun `complete approval configuration builds and materializes the runtime`() {
        val tramai = baseBuilder()
            .approvalContinuationStore(collaborator<ApprovalContinuationStore>())
            .toolArgumentsDigester(collaborator<ToolArgumentsDigester>())
            .approvalGateCoordinator(collaborator<ApprovalGateCoordinator>())
            .build()

        // The capability wiring through the public bridge must not fail.
        assertThat(tramai.runtime()).isNotNull()
    }

    @Test
    fun `builder mutation after build cannot mutate the built runtime`() {
        val providerACalls = AtomicInteger()
        val providerA = object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse =
                ModelResponse(content = "provider-a:${providerACalls.incrementAndGet()}")

            override fun providerId(): String = "primary"
        }
        val observerACalls = AtomicInteger()
        val observerA = OperationObserver {
            observerACalls.incrementAndGet()
            NoOpOperationObservation
        }

        val builder = Tramai.builder()
            .provider(providerA, default = true)
            .model("mock-model", "primary")
            .observer(observerA)

        val runtimeA = builder.build()

        // Mutate the builder after build — must not affect the built runtime.
        val providerBCalls = AtomicInteger()
        val providerB = object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse =
                ModelResponse(content = "provider-b:${providerBCalls.incrementAndGet()}")

            override fun providerId(): String = "other"
        }
        builder
            .provider(providerB, default = true)
            .observer(OperationObserver { NoOpOperationObservation })

        val service = runtimeA.create<GreetingService>()
        val result = runBlocking { service.greet("world") }

        assertThat(result).isEqualTo("provider-a:1")
        assertThat(providerBCalls.get()).isZero()
        assertThat(observerACalls.get()).isEqualTo(1)
    }

    @Test
    fun `builder routing mutation after build is visible to a later build`() {
        val providerACalls = AtomicInteger()
        val providerA = object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse =
                ModelResponse(content = "provider-a:${providerACalls.incrementAndGet()}")

            override fun providerId(): String = "primary"
        }
        val providerBCalls = AtomicInteger()
        val providerB = object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse =
                ModelResponse(content = "provider-b:${providerBCalls.incrementAndGet()}")

            override fun providerId(): String = "other"
        }

        val builder = Tramai.builder()
            .provider(providerA, default = true)

        val runtimeA = builder.build()

        // Mutate routing after the first build: runtime A stays frozen on provider A,
        // but the next build must reflect the new routing state (not a cached plan).
        builder
            .provider(providerB, default = true)

        val runtimeB = builder.build()

        val serviceA = runtimeA.create<GreetingService>()
        assertThat(runBlocking { serviceA.greet("world") }).isEqualTo("provider-a:1")

        val serviceB = runtimeB.create<GreetingService>()
        assertThat(runBlocking { serviceB.greet("world") }).isEqualTo("provider-b:1")
        assertThat(providerACalls.get()).isEqualTo(1)
        assertThat(providerBCalls.get()).isEqualTo(1)
    }

    @Test
    fun `no-op default behaviour remains unchanged`() {
        val tramai = baseBuilder().build()

        val service = tramai.create<GreetingService>()
        val result = runBlocking { service.greet("world") }

        assertThat(result).isEqualTo("hello")
    }

    @Test
    fun `legacy policy behaviour remains unchanged`() {
        // No policyEngine configured → permissive legacy fallback, call succeeds.
        val tramai = baseBuilder().build()

        val service = tramai.create<GreetingService>()
        assertThat(runBlocking { service.greet("world") }).isEqualTo("hello")
    }

    @AiService
    private interface GreetingService {
        @Operation(model = "mock-model")
        suspend fun greet(name: String): String
    }

    private fun baseBuilder(): Tramai.Builder = Tramai.builder()
        .provider(
            object : ModelProvider {
                override suspend fun complete(request: ModelRequest): ModelResponse =
                    ModelResponse(content = "hello")

                override fun providerId(): String = "mock"
            },
            default = true,
        )
        .model("mock-model", "mock")

    private inline fun <reified T> collaborator(): T {
        assertThat(T::class.java.isInterface).isTrue()
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> error("unused") } as T
    }
}
