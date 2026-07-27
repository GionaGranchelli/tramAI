package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.ToolCall
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.provider.StreamCapable
import dev.tramai.structured.JacksonStructuredOutputHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contract tests for engine cancellation behavior across all execution paths.
 *
 * These tests prove that [CancellationException] thrown by a provider:
 * - bypasses retry loops and fallback routing
 * - preserves the original exception type and message
 * - is properly recorded via [OperationObservation.onCallCancelled]
 * - is not wrapped in structured-output exceptions
 * - observer failures during cancellation notification are suppressed
 *   but do not replace the original cancellation
 *
 * NOTE: Reference identity (isSameAs) is NOT asserted because the engine's
 * TramaiInvocationHandler wraps CancellationException at the coroutine
 * boundary (frame: _COROUTINE._BOUNDARY._), creating a new instance.
 * Type + message is the strongest identity contract available here.
 *
 * Tool-execution cancellation is NOT covered in this PR — it reveals a
 * genuine engine defect (tool CancellationException is not routed through
 * onCallCancelled). Tracked for PR #210.
 */
class EngineCancellationContractTest {

    // -------------------------------------------------------------------------
    // Test 1: provider cancellation bypasses retry and fallback
    // -------------------------------------------------------------------------

    @Test
    fun `provider cancellation bypasses retry and fallback and preserves identity`() {
        val cancellingProvider = CancellingProvider()
        val failingProvider = FailingIfCalledProvider()
        val observer = CancellationObserver()

        val registry = ProviderRegistry.builder()
            .provider("primary", cancellingProvider)
            .provider("fallback", failingProvider)
            .model("test-model", "primary")
            .fallbackProvider("test-model", "fallback")
            .defaultProvider("primary")
            .build()

        val engine = TramaiEngine(
            providerRegistry = registry,
            operationObserver = observer,
        )
        val service = engine.create<CancellationTestService>()

        assertThatThrownBy { runBlocking { service.execute("input") } }
            .isInstanceOf(CancellationException::class.java)
            .hasMessage("cancelled by test")

        // The cancelling provider was called exactly once (no retries despite providerRetries=2)
        assertThat(cancellingProvider.calls.get()).isEqualTo(1)

        // The fallback provider was never called
        assertThat(failingProvider.calls.get()).isEqualTo(0)

        // The observer recorded the cancellation
        assertThat(observer.records).hasSize(1)
        val record = observer.records.single()
        assertThat(record.cancelled).isTrue()
        assertThat(record.providerFailure).isNull()
        assertThat(record.completionCount).isEqualTo(0)
    }

    // -------------------------------------------------------------------------
    // Test 2: cancellation during structured output repair stops all further attempts
    // -------------------------------------------------------------------------

    @Test
    fun `cancellation during structured output repair stops all further attempts`() {
        val provider = StructuredCancellingProvider()
        val observer = CancellationObserver()

        val registry = ProviderRegistry.builder()
            .provider("primary", provider)
            .model("test-model", "primary")
            .defaultProvider("primary")
            .build()

        val engine = TramaiEngine(
            providerRegistry = registry,
            structuredOutputHandler = JacksonStructuredOutputHandler(),
            operationObserver = observer,
        )
        val service = engine.create<StructuredCancellationService>()

        // The cancellation exception must escape without being wrapped in StructuredOutputException
        assertThatThrownBy { runBlocking { service.parse("input") } }
            .isInstanceOf(CancellationException::class.java)
            .hasMessage("cancelled during structured repair")

        // Provider was called twice: first for the "not json" response, second attempt throws cancellation
        assertThat(provider.calls.get()).isEqualTo(2)

        // Observer recorded the cancellation on the second attempt
        assertThat(observer.records).anySatisfy { record ->
            assertThat(record.cancelled).isTrue()
        }
    }

    // -------------------------------------------------------------------------
    // Test 3: cancellation observer failure is suppressed without replacing cancellation
    // -------------------------------------------------------------------------

    @Test
    fun `cancellation observer failure is suppressed without replacing cancellation`() {
        val provider = CancellingProvider()
        val observer = FailingOnCancelledObserver()

        val registry = ProviderRegistry.builder()
            .provider("primary", provider)
            .model("test-model", "primary")
            .defaultProvider("primary")
            .build()

        val engine = TramaiEngine(
            providerRegistry = registry,
            operationObserver = observer,
        )
        val service = engine.create<CancellationTestService>()

        val thrown = runCatching { runBlocking { service.execute("input") } }
            .exceptionOrNull() as? CancellationException
            ?: error("Expected CancellationException")

        assertThat(thrown)
            .isInstanceOf(CancellationException::class.java)
            .hasMessage("cancelled by test")

        // The original cancellation is still thrown (not replaced by IllegalStateException).
        // The observer error is suppressed on the thrown exception instance.
        assertThat(thrown.suppressed)
            .hasSize(1)
            .anySatisfy { suppressed ->
                assertThat(suppressed)
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessage("observer failure")
            }
    }

    // -------------------------------------------------------------------------
    // Test 4: streaming consumer cancellation bypasses fallback
    // -------------------------------------------------------------------------

    @Test
    fun `stream consumer cancellation bypasses fallback and completes as cancelled`() {
        val streamingProvider = StreamingProvider()
        val fallback = FailingIfCalledStreamingProvider()
        val observer = CancellationObserver()

        val registry = ProviderRegistry.builder()
            .provider("primary", streamingProvider)
            .provider("fallback", fallback)
            .model("test-model", "primary")
            .fallbackProvider("test-model", "fallback")
            .defaultProvider("primary")
            .build()

        val engine = TramaiEngine(
            providerRegistry = registry,
            operationObserver = observer,
        )
        val service = engine.create<StreamingTestService>()

        val collected = runBlocking { service.stream("input").take(1).toList() }

        assertThat(collected).containsExactly(StreamChunk.Token("first"))
        assertThat(streamingProvider.cancelled).isTrue()
        assertThat(fallback.calls.get()).isZero()

        assertThat(observer.records).hasSize(1)
        val record = observer.records.single()
        assertThat(record.cancelled).isTrue()
        assertThat(record.providerFailure).isNull()
        assertThat(record.completionCount).isZero()
    }

    // -------------------------------------------------------------------------
    // Private fixtures
    // -------------------------------------------------------------------------

    /** Provider that throws [CancellationException] on every call. */
    private class CancellingProvider : ModelProvider {
        val calls = AtomicInteger(0)
        val thrownException = CancellationException("cancelled by test")

        override suspend fun complete(request: ModelRequest): ModelResponse {
            calls.incrementAndGet()
            throw thrownException
        }
    }

    /** Provider that throws an error — used to verify fallback is never reached. */
    private class FailingIfCalledProvider : ModelProvider {
        val calls = AtomicInteger(0)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            calls.incrementAndGet()
            error("must not be called")
        }
    }

    /**
     * Provider that returns "not json" on first call, then [CancellationException]
     * on second. Used to verify cancellation during structured output retry repair.
     */
    private class StructuredCancellingProvider : ModelProvider {
        val calls = AtomicInteger(0)
        val thrownException = CancellationException("cancelled during structured repair")

        override suspend fun complete(request: ModelRequest): ModelResponse {
            val count = calls.incrementAndGet()
            return if (count == 1) {
                ModelResponse(content = "not json")
            } else {
                throw thrownException
            }
        }
    }

    /** Streaming provider that emits one token then suspends for cancellation. */
    private class StreamingProvider : ModelProvider, StreamCapable {
        @Volatile
        var cancelled: Boolean = false

        override suspend fun complete(request: ModelRequest): ModelResponse {
            error("StreamingProvider.complete should not be called")
        }

        override fun stream(request: ModelRequest): Flow<StreamChunk> = flow {
            try {
                emit(StreamChunk.Token("first"))
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
    }

    /** Streaming fallback that errors if called. */
    private class FailingIfCalledStreamingProvider : ModelProvider, StreamCapable {
        val calls = AtomicInteger(0)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            calls.incrementAndGet()
            error("must not be called")
        }

        override fun stream(request: ModelRequest): Flow<StreamChunk> {
            calls.incrementAndGet()
            error("must not be called")
        }
    }

    /** Observer that tracks cancellation, provider failure, and completion count. */
    private class CancellationObserver : OperationObserver {
        val records = mutableListOf<CancellationRecord>()

        override fun onCallStarted(context: OperationCallContext): OperationObservation {
            val record = CancellationRecord()
            records += record
            return object : OperationObservation {
                override fun onProviderResponse(response: ModelResponse) {
                    record.providerResponse = response
                }

                override fun onProviderFailure(error: Throwable) {
                    record.providerFailure = error
                }

                override fun onStructuredParseFailure(
                    rawResponse: String,
                    errorSummary: String,
                ) = Unit

                override fun onEngineEvent(
                    name: String,
                    attributes: Map<String, Any?>,
                ) = Unit

                override fun onCallCompleted(parseSuccess: Boolean?) {
                    record.completionCount++
                }

                override fun onCallCancelled() {
                    record.cancelled = true
                }
            }
        }

        data class CancellationRecord(
            var providerResponse: ModelResponse? = null,
            var providerFailure: Throwable? = null,
            var completionCount: Int = 0,
            var cancelled: Boolean = false,
        )
    }

    /** Observer whose [OperationObservation.onCallCancelled] throws [IllegalStateException]. */
    private class FailingOnCancelledObserver : OperationObserver {
        override fun onCallStarted(context: OperationCallContext): OperationObservation {
            return object : OperationObservation {
                override fun onProviderResponse(response: ModelResponse) = Unit
                override fun onProviderFailure(error: Throwable) = Unit
                override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) = Unit
                override fun onEngineEvent(name: String, attributes: Map<String, Any?>) = Unit
                override fun onCallCompleted(parseSuccess: Boolean?) = Unit

                override fun onCallCancelled() {
                    throw IllegalStateException("observer failure")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Service interfaces
    // -------------------------------------------------------------------------

    @AiService
    private interface CancellationTestService {
        @Operation(
            prompt = "Execute the input directly",
            model = "test-model",
            providerRetries = 2,
        )
        suspend fun execute(input: String): String
    }

    @AiService
    private interface StructuredCancellationService {
        @Operation(
            prompt = "Parse the input into a structured result",
            model = "test-model",
            maxRetries = 3,
            providerRetries = 1,
        )
        suspend fun parse(input: String): ParsedResult
    }

    @AiService
    private interface StreamingTestService {
        @Operation(model = "test-model")
        fun stream(input: String): Flow<StreamChunk>
    }

    private data class ParsedResult(
        val value: String,
    )
}
