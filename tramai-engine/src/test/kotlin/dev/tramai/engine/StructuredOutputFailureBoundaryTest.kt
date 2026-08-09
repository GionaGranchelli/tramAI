package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.StructuredOutputException
import dev.tramai.core.exception.safeStructuredOutputFailure
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.structured.StructuredOutputContract
import dev.tramai.core.structured.StructuredOutputFailureCode
import dev.tramai.core.structured.StructuredOutputFailureDiagnosticEvent
import dev.tramai.core.structured.StructuredOutputFailureDiagnosticObserver
import dev.tramai.core.structured.StructuredOutputHandler
import dev.tramai.core.structured.StructuredOutputResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.reflect.KType
import kotlin.test.Test

class StructuredOutputFailureBoundaryTest {
    @Test
    fun `exhausted repair surfaces safe exception with no raw detail`() {
        val service = engine(FailingHandler()).create<BoundaryService>()

        assertThatThrownBy { runBlocking { service.answer(SO_FIXTURE) } }
            .isInstanceOfSatisfying(StructuredOutputException::class.java) { error ->
                assertThat(error.message).isEqualTo("Structured output parsing failed after 3 attempt(s)")
                assertThat(error.message).doesNotContain(SO_FIXTURE)
                assertThat(error.failureCode).isEqualTo(StructuredOutputFailureCode.REPAIR_EXHAUSTED)
                assertThat(error.attemptCount).isEqualTo(3)
                assertThat(error.originalPrompt).isNull()
                assertThat(error.lastRawResponse).isNull()
                assertThat(error.validationError).isNull()
                assertThat(error.cause).isNull()
                assertThat(error.suppressed).isEmpty()
            }
    }

    @Test
    fun `raw detail reaches only the diagnostic observer`() {
        val diagnostics = RecordingDiagnostics()
        val service = engine(FailingHandler(), diagnostics = diagnostics).create<BoundaryService>()

        assertThatThrownBy { runBlocking { service.answer(SO_FIXTURE) } }
            .isInstanceOf(StructuredOutputException::class.java)

        assertThat(diagnostics.events).hasSize(3)
        diagnostics.events.forEachIndexed { index, event ->
            assertThat(event.code).isEqualTo(StructuredOutputFailureCode.OUTPUT_REJECTED)
            assertThat(event.attempt).isEqualTo(index + 1)
            assertThat(event.willRetry).isEqualTo(index < 2)
            assertThat(event.rawResponsePreview).contains(SO_FIXTURE)
            assertThat(event.failure!!.message).contains(SO_FIXTURE)
        }
    }

    @Test
    fun `ordinary observer receives only redacted compatibility text`() {
        val observer = RecordingOperationObserver()
        val service = engine(FailingHandler(), operationObserver = observer).create<BoundaryService>()

        assertThatThrownBy { runBlocking { service.answer(SO_FIXTURE) } }
            .isInstanceOf(StructuredOutputException::class.java)

        assertThat(observer.failures).containsOnly(
            "<redacted structured-output failure>" to "Structured output failed validation",
        )
        assertThat(observer.failures.joinToString()).doesNotContain(SO_FIXTURE)
    }

    @Test
    fun `successful repair and first attempt remain unchanged`() {
        val repaired = engine(SequenceHandler(failuresBeforeSuccess = 1)).create<BoundaryService>()
        val direct = engine(SequenceHandler(failuresBeforeSuccess = 0)).create<BoundaryService>()

        runBlocking {
            assertThat(repaired.answer("normal")).isEqualTo(BoundaryValue("ok"))
            assertThat(direct.answer("normal")).isEqualTo(BoundaryValue("ok"))
        }
    }

    @Test
    fun `custom handler exception is re-sanitized as handler failed and observer failures fail open`() {
        val diagnostics = RecordingDiagnostics(throwAfterRecording = true)
        val service = engine(ThrowingHandler(), diagnostics = diagnostics).create<BoundaryService>()

        assertThatThrownBy { runBlocking { service.answer(SO_FIXTURE) } }
            .isInstanceOfSatisfying(StructuredOutputException::class.java) { error ->
                assertThat(error.failureCode).isEqualTo(StructuredOutputFailureCode.HANDLER_FAILED)
                assertThat(error.message).isEqualTo("Structured output handler failed")
                assertThat(error.message).doesNotContain(SO_FIXTURE)
                assertThat(error.cause).isNull()
            }
        assertThat(diagnostics.events.single().failure!!.message).contains(SO_FIXTURE)
    }

    @Test
    fun `observer throwing fake cancellation while the job is active is swallowed`() {
        val diagnostics = RecordingDiagnostics(throwFakeCancellation = true)
        val service = engine(FailingHandler(), diagnostics = diagnostics).create<BoundaryService>()

        val thrown = catchThrowable { runBlocking { service.answer(SO_FIXTURE) } }
        assertThat(thrown).isInstanceOf(StructuredOutputException::class.java)
        assertThat((thrown as StructuredOutputException).failureCode)
            .isEqualTo(StructuredOutputFailureCode.REPAIR_EXHAUSTED)
        // The fake cancellation must not replace the real structured failure
        // while the coroutine is active.
        assertThat(diagnostics.events).hasSize(3)
    }

    @Test
    fun `contract generation failure is sanitized as contract failed`() {
        val diagnostics = RecordingDiagnostics()
        val service = engine(ContractThrowingHandler(), diagnostics = diagnostics).create<BoundaryService>()

        val thrown = catchThrowable { runBlocking { service.answer(SO_FIXTURE) } }
        assertThat(thrown).isInstanceOf(StructuredOutputException::class.java)
        assertThat((thrown as StructuredOutputException).failureCode)
            .isEqualTo(StructuredOutputFailureCode.CONTRACT_FAILED)
        assertThat(thrown.message).isEqualTo("Structured output contract generation failed")
        assertThat(thrown.message).doesNotContain(SO_FIXTURE)
        assertThat(thrown.cause).isNull()
        assertThat(thrown.originalPrompt).isNull()
        assertThat(thrown.lastRawResponse).isNull()
        assertThat(thrown.validationError).isNull()
        // The sentinel reaches ONLY the privileged diagnostic observer.
        assertThat(diagnostics.events.single().failure!!.message).contains(SO_FIXTURE)
        assertThat(diagnostics.events.single().code).isEqualTo(StructuredOutputFailureCode.CONTRACT_FAILED)
    }

    @Test
    fun `handler-thrown raw structured exception is never trusted`() {
        val diagnostics = RecordingDiagnostics()
        val handler = object : FailingHandler() {
            override fun analyze(rawResponse: String, targetType: KType): StructuredOutputResult =
                throw StructuredOutputException(message = "secret $SO_FIXTURE")
        }
        val service = engine(handler, diagnostics = diagnostics).create<BoundaryService>()

        val thrown = catchThrowable { runBlocking { service.answer(SO_FIXTURE) } }
        assertThat(thrown).isInstanceOf(StructuredOutputException::class.java)
        assertThat((thrown as StructuredOutputException).failureCode)
            .isEqualTo(StructuredOutputFailureCode.HANDLER_FAILED)
        assertThat(thrown.message).isEqualTo("Structured output handler failed")
        assertThat(thrown.message).doesNotContain(SO_FIXTURE)
        assertThat(thrown.cause).isNull()
        // The raw handler exception reaches only the diagnostic observer, with
        // the operation identity that produced it.
        val event = diagnostics.events.single()
        assertThat(event.serviceName)
            .endsWith("StructuredOutputFailureBoundaryTest.BoundaryService")
        assertThat(event.methodName).isEqualTo("answer")
        assertThat(event.failure!!.message).contains(SO_FIXTURE)
    }

    @Test
    fun `safe factory message is fixed and cannot carry caller text`() {
        val exception = safeStructuredOutputFailure(
            code = StructuredOutputFailureCode.HANDLER_FAILED,
            attemptCount = 2,
        )
        assertThat(exception.message).isEqualTo("Structured output handler failed")
        assertThat(exception.failureCode).isEqualTo(StructuredOutputFailureCode.HANDLER_FAILED)
        assertThat(exception.attemptCount).isEqualTo(2)
        assertThat(exception.originalPrompt).isNull()
        assertThat(exception.lastRawResponse).isNull()
        assertThat(exception.validationError).isNull()
        assertThat(exception.cause).isNull()
    }

    @Test
    fun `genuine parent cancellation during diagnostic delivery stays primary`() {
        val observerEntered = CompletableDeferred<Unit>()
        val observerRelease = CompletableDeferred<Unit>()
        val diagnostics = object : StructuredOutputFailureDiagnosticObserver {
            override suspend fun onFailure(event: StructuredOutputFailureDiagnosticEvent) {
                observerEntered.complete(Unit)
                // Suspend (never block the dispatcher) until the test cancels.
                observerRelease.await()
            }
        }
        val service = engine(FailingHandler(), diagnostics = diagnostics).create<BoundaryService>()
        val outcome = CompletableDeferred<Throwable?>()
        runBlocking {
            coroutineScope {
                launch {
                    try {
                        service.answer(SO_FIXTURE)
                        outcome.complete(null)
                    } catch (t: Throwable) {
                        outcome.complete(t)
                    }
                }
                observerEntered.await()
                // Genuine parent cancellation while the diagnostic is being
                // delivered: the engine's execution is a child of the caller's
                // job (continuation.context), so this cancels it mid-delivery.
                coroutineContext.cancelChildren()
                observerRelease.complete(Unit)
                val terminal = outcome.await()
                assertThat(terminal).isInstanceOf(CancellationException::class.java)
                assertThat(terminal).isNotInstanceOf(StructuredOutputException::class.java)
            }
        }
    }

    @Test
    fun `diagnostic raw preview is byte bounded`() {
        val diagnostics = RecordingDiagnostics()
        val service = engine(FailingHandler(), diagnostics = diagnostics, response = "a".repeat(8193) + SO_FIXTURE).create<BoundaryService>()

        assertThatThrownBy { runBlocking { service.answer(SO_FIXTURE) } }.isInstanceOf(StructuredOutputException::class.java)

        assertThat(diagnostics.events.first().rawResponseTruncated).isTrue()
        assertThat(diagnostics.events.first().rawResponsePreview!!.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(8192)
    }

    private fun engine(
        handler: StructuredOutputHandler,
        diagnostics: StructuredOutputFailureDiagnosticObserver = RecordingDiagnostics(),
        operationObserver: OperationObserver = RecordingOperationObserver(),
        response: String = "raw $SO_FIXTURE",
    ): TramaiEngine = TramaiEngine(
        provider = object : ModelProvider { override suspend fun complete(request: ModelRequest) = ModelResponse(content = response) },
        structuredOutputHandler = handler,
        structuredOutputFailureDiagnosticObserver = diagnostics,
        operationObserver = operationObserver,
    )

    private open class FailingHandler : StructuredOutputHandler {
        override fun createContract(targetType: KType) = StructuredOutputContract(targetType, "{}")
        override fun analyze(rawResponse: String, targetType: KType): StructuredOutputResult =
            StructuredOutputResult.Failure(rawResponse, "detail $SO_FIXTURE", "repair")
                .also { it.failure = IllegalStateException("handler $SO_FIXTURE") }
        override fun generateSchema(type: KType) = "{}"
        override fun deserialize(input: Any, targetType: KType): Any = error("unused")
        override fun serialize(value: Any): Any = value
    }

    private class ThrowingHandler : FailingHandler() {
        override fun analyze(rawResponse: String, targetType: KType): StructuredOutputResult = throw IllegalStateException(SO_FIXTURE)
    }

    private class ContractThrowingHandler : FailingHandler() {
        override fun createContract(targetType: KType): StructuredOutputContract = throw IllegalStateException(SO_FIXTURE)
    }

    private class SequenceHandler(private var failuresBeforeSuccess: Int) : FailingHandler() {
        override fun analyze(rawResponse: String, targetType: KType): StructuredOutputResult = if (failuresBeforeSuccess-- > 0) super.analyze(rawResponse, targetType) else StructuredOutputResult.Success(BoundaryValue("ok"), rawResponse)
    }

    private class RecordingDiagnostics(
        private val throwAfterRecording: Boolean = false,
        private val throwFakeCancellation: Boolean = false,
    ) : StructuredOutputFailureDiagnosticObserver {
        val events = mutableListOf<StructuredOutputFailureDiagnosticEvent>()
        override suspend fun onFailure(event: StructuredOutputFailureDiagnosticEvent) {
            events += event
            if (throwAfterRecording) throw IllegalStateException("observer boom")
            if (throwFakeCancellation) throw CancellationException("fake observer cancellation")
        }
    }

    private class RecordingOperationObserver : OperationObserver {
        val failures = mutableListOf<Pair<String, String>>()
        override fun onCallStarted(context: OperationCallContext): OperationObservation = object : OperationObservation {
            override fun onProviderResponse(response: ModelResponse) = Unit
            override fun onProviderFailure(error: Throwable) = Unit
            override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) { failures += rawResponse to errorSummary }
            override fun onCallCompleted(parseSuccess: Boolean?) = Unit
            override fun onCallCancelled() = Unit
        }
    }

    @AiService
    private interface BoundaryService {
        @Operation(prompt = "prompt $SO_FIXTURE", model = "boundary")
        suspend fun answer(input: String): BoundaryValue
    }

    private data class BoundaryValue(val value: String)

    private companion object { const val SO_FIXTURE = "fixture-sentinel-so-2b4" }
}
