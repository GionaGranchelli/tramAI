package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolResult
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Epic 5.3 — secondary-failure preservation at the engine boundary.
 *
 * A throwing telemetry observer must never change the business outcome:
 * a successful operation stays successful, and a provider failure stays the
 * primary exception.
 */
class SecondaryFailurePreservationEngineTest {

    @AiService
    interface BasicService {
        @Operation(prompt = "test", model = "test-model", providerRetries = 0)
        fun respond(input: String): String
    }

    private class CountingProvider : ModelProvider {
        val callCount = AtomicInteger(0)

        override fun providerId(): String = "test-provider"

        override suspend fun complete(request: ModelRequest): ModelResponse {
            callCount.incrementAndGet()
            return ModelResponse(content = "ok:${request.messages.firstOrNull()?.content ?: ""}")
        }
    }

    private class FailingProvider : ModelProvider {
        override fun providerId(): String = "failing-provider"

        override suspend fun complete(request: ModelRequest): ModelResponse =
            throw dev.tramai.core.exception.ProviderException("provider-boom")
    }

    /** Observer whose every per-attempt callback throws. */
    private val throwingObserver = object : OperationObserver {
        override fun onCallStarted(context: OperationCallContext): OperationObservation =
            object : OperationObservation {
                override fun onProviderResponse(response: ModelResponse) = throw IllegalStateException("observer-boom")
                override fun onProviderFailure(error: Throwable) = throw IllegalStateException("observer-boom")
                override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) = throw IllegalStateException("observer-boom")
                override fun onEngineEvent(name: String, attributes: Map<String, Any?>) = throw IllegalStateException("observer-boom")
                override fun onCallCompleted(parseSuccess: Boolean?) = throw IllegalStateException("observer-boom")
            }
    }

    @Test
    fun `business success survives throwing operation observer`() {
        runBlocking {
            val provider = CountingProvider()
            val engine = TramaiEngine(
                provider = provider,
                operationObserver = throwingObserver,
            )
            val service = engine.create<BasicService>()
            val result = service.respond("hello")
            // Prompt-rendered content includes the arguments block; the point
            // is the operation SUCCEEDED despite every observer callback throwing.
            assertThat(result).startsWith("ok")
            assertThat(provider.callCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun `provider failure remains primary when observer also throws`() {
        runBlocking {
            val engine = TramaiEngine(
                provider = FailingProvider(),
                operationObserver = throwingObserver,
            )
            val service = engine.create<BasicService>()
            assertThatThrownBy { runBlocking { service.respond("hello") } }
                .isInstanceOf(dev.tramai.core.exception.ProviderException::class.java)
                .hasMessageContaining("provider-boom")
        }
    }
}
