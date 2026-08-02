package dev.tramai.standalone

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ToolInvalidInputException
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolFailureCode
import dev.tramai.core.observation.ToolFailureDiagnosticEvent
import dev.tramai.core.observation.ToolFailureDiagnosticObserver
import dev.tramai.core.provider.ModelProvider
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.reflect.KClass
import kotlin.test.Test

/**
 * Adapter-level tests for PR #219 — safe tool-failure boundaries in the
 * standalone [Tramai] composition.
 *
 * Proves that the [TramaiTool] adapter never derives model-visible text from
 * a caught exception message and that raw causes reach only the explicitly
 * configured [ToolFailureDiagnosticObserver].
 */
class ToolSafeFailureAdapterTest {

    private val sensitiveFixture = "token=sk-secret-219\n" +
        "/path/customer/alice\n" +
        "SELECT * FROM private_accounts\n" +
        "prompt: customer medical history"

    private val sensitiveFragments = listOf(
        "sk-secret-219",
        "/path/customer/alice",
        "private_accounts",
        "customer medical history",
    )

    // ---------------------------------------------------------------------
    // Non-idempotent execution failure
    // ---------------------------------------------------------------------

    @Test
    fun `non-idempotent tool failure leaks nothing beyond the diagnostic observer`() {
        val tool = FailingTool(idempotent = false, failure = RuntimeException(sensitiveFixture))
        val provider = ToolLoopProvider(
            ModelResponse(
                content = "using tool",
                toolCalls = listOf(ToolCall("1", tool.name, "\"x\"")),
            ),
            ModelResponse(content = "done"),
        )
        val diagnostics = RecordingDiagnosticObserver()

        val tramai = Tramai {
            provider(provider, default = true)
            model("test-model", "mock")
            tools(tool)
            toolFailureDiagnosticObserver(diagnostics)
        }
        val service = tramai.create<ToolTestService>()

        val result = runBlocking { service.execute("input") }

        assertThat(result).isEqualTo("done")
        assertThat(tool.calls.get()).isEqualTo(1)

        val event = diagnostics.events.single()
        assertThat(event.code).isEqualTo(ToolFailureCode.EXECUTION_FAILED)
        assertThat(event.attempt).isZero()
        assertThat(event.retryable).isFalse()
        assertThat(event.failure.message).contains("sk-secret-219")

        assertToolMessages(provider, "Permanent error: Tool execution failed")
        assertNoLeak(provider)
    }

    // ---------------------------------------------------------------------
    // Idempotent retry exhaustion
    // ---------------------------------------------------------------------

    @Test
    fun `idempotent retry exhaustion terminal message is fixed`() {
        val tool = FailingTool(idempotent = true, failure = RuntimeException(sensitiveFixture))
        val provider = ToolLoopProvider(
            ModelResponse(
                content = "using tool",
                toolCalls = listOf(ToolCall("1", tool.name, "\"x\"")),
            ),
            ModelResponse(content = "done"),
        )
        val diagnostics = RecordingDiagnosticObserver()

        val tramai = Tramai {
            provider(provider, default = true)
            model("test-model", "mock")
            tools(tool)
            toolFailureDiagnosticObserver(diagnostics)
        }
        val service = tramai.create<ToolTestService>()

        val result = runBlocking { service.execute("input") }

        assertThat(result).isEqualTo("done")
        // IDEMPOTENT_TOOL_MAX_ATTEMPTS = 2
        assertThat(tool.calls.get()).isEqualTo(2)

        assertThat(diagnostics.events.map { it.code }).containsExactly(
            ToolFailureCode.EXECUTION_FAILED,
            ToolFailureCode.EXECUTION_FAILED,
            ToolFailureCode.RETRY_EXHAUSTED,
        )
        assertThat(diagnostics.events[2].retryable).isFalse()

        assertToolMessages(provider, "Permanent error: Tool execution failed")
        assertNoLeak(provider)
    }

    // ---------------------------------------------------------------------
    // Invalid input
    // ---------------------------------------------------------------------

    @Test
    fun `invalid input exception text is diagnostic-only by default`() {
        val tool = FailingTool(
            idempotent = true,
            failure = ToolInvalidInputException("validation failure: $sensitiveFixture"),
        )
        val provider = ToolLoopProvider(
            ModelResponse(
                content = "using tool",
                toolCalls = listOf(ToolCall("1", tool.name, "\"x\"")),
            ),
            ModelResponse(content = "done"),
        )
        val diagnostics = RecordingDiagnosticObserver()

        val tramai = Tramai {
            provider(provider, default = true)
            model("test-model", "mock")
            tools(tool)
            toolFailureDiagnosticObserver(diagnostics)
        }
        val service = tramai.create<ToolTestService>()

        val result = runBlocking { service.execute("input") }

        assertThat(result).isEqualTo("done")
        assertThat(tool.calls.get()).isEqualTo(1)

        val event = diagnostics.events.single()
        assertThat(event.code).isEqualTo(ToolFailureCode.INVALID_INPUT)
        assertThat(event.failure).isInstanceOf(ToolInvalidInputException::class.java)
        assertThat(event.failure.message).contains("sk-secret-219")

        assertToolMessages(provider, "Error: Invalid tool input")
        assertNoLeak(provider)
    }

    @Test
    fun `explicit safe validation feedback reaches the model`() {
        val tool = FailingTool(
            idempotent = true,
            failure = ToolInvalidInputException.withSafeModelMessage(
                message = "diagnostic detail: $sensitiveFixture",
                modelMessage = "Input rejected: account must be numeric",
            ),
        )
        val provider = ToolLoopProvider(
            ModelResponse(
                content = "using tool",
                toolCalls = listOf(ToolCall("1", tool.name, "\"x\"")),
            ),
            ModelResponse(content = "done"),
        )
        val diagnostics = RecordingDiagnosticObserver()

        val tramai = Tramai {
            provider(provider, default = true)
            model("test-model", "mock")
            tools(tool)
            toolFailureDiagnosticObserver(diagnostics)
        }
        val service = tramai.create<ToolTestService>()

        val result = runBlocking { service.execute("input") }

        assertThat(result).isEqualTo("done")
        assertThat(diagnostics.events.single().code).isEqualTo(ToolFailureCode.INVALID_INPUT)

        assertToolMessages(provider, "Error: Input rejected: account must be numeric")
        assertNoLeak(provider)
    }

    @Test
    fun `deserialization failure with sensitive input is contained`() {
        val tool = FailingTool(
            idempotent = true,
            failure = RuntimeException("not used"),
        )
        // Malformed non-JSON input: Jackson's parse error echoes the unexpected token.
        val provider = ToolLoopProvider(
            ModelResponse(
                content = "using tool",
                toolCalls = listOf(ToolCall("1", tool.name, sensitiveFixture)),
            ),
            ModelResponse(content = "done"),
        )
        val diagnostics = RecordingDiagnosticObserver()

        val tramai = Tramai {
            provider(provider, default = true)
            model("test-model", "mock")
            tools(tool)
            toolFailureDiagnosticObserver(diagnostics)
        }
        val service = tramai.create<ToolTestService>()

        val result = runBlocking { service.execute("input") }

        assertThat(result).isEqualTo("done")
        assertThat(tool.calls.get()).isZero()

        val event = diagnostics.events.single()
        assertThat(event.code).isEqualTo(ToolFailureCode.INVALID_INPUT)
        // The deserialization error is internally diagnosable; the structured
        // handler redacts source text from parse errors, so the failure message
        // must not echo the raw input token either.
        assertThat(event.failure).isNotNull()
        assertThat(event.failure.message).doesNotContain("sk-secret-219")

        assertToolMessages(provider, "Error: Invalid tool input")
        assertNoLeak(provider)
    }

    // ---------------------------------------------------------------------
    // Observer failure and cancellation
    // ---------------------------------------------------------------------

    @Test
    fun `diagnostic observer failure is fail-open`() {
        val tool = FailingTool(idempotent = false, failure = RuntimeException(sensitiveFixture))
        val provider = ToolLoopProvider(
            ModelResponse(
                content = "using tool",
                toolCalls = listOf(ToolCall("1", tool.name, "\"x\"")),
            ),
            ModelResponse(content = "done"),
        )
        val throwingDiagnostics = ToolFailureDiagnosticObserver {
            throw RuntimeException("diagnostic sink exploded")
        }

        val tramai = Tramai {
            provider(provider, default = true)
            model("test-model", "mock")
            tools(tool)
            toolFailureDiagnosticObserver(throwingDiagnostics)
        }
        val service = tramai.create<ToolTestService>()

        val result = runBlocking { service.execute("input") }

        assertThat(result).isEqualTo("done")
        assertThat(tool.calls.get()).isEqualTo(1)
        assertToolMessages(provider, "Permanent error: Tool execution failed")
        assertNoLeak(provider)
    }

    @Test
    fun `tools registered before the observer still report diagnostics`() {
        val tool = FailingTool(idempotent = false, failure = RuntimeException("boom"))
        val provider = ToolLoopProvider(
            ModelResponse(
                content = "using tool",
                toolCalls = listOf(ToolCall("1", tool.name, "\"x\"")),
            ),
            ModelResponse(content = "done"),
        )
        val diagnostics = RecordingDiagnosticObserver()

        // tools() is called before toolFailureDiagnosticObserver() on purpose:
        // the adapter resolves the observer lazily at failure time.
        val tramai = Tramai {
            provider(provider, default = true)
            model("test-model", "mock")
            tools(tool)
            toolFailureDiagnosticObserver(diagnostics)
        }
        val service = tramai.create<ToolTestService>()

        runBlocking { service.execute("input") }

        assertThat(diagnostics.events).hasSize(1)
        assertThat(diagnostics.events.single().code).isEqualTo(ToolFailureCode.EXECUTION_FAILED)
    }

    @Test
    fun `tool cancellation escapes unchanged with no diagnostic`() {
        val tool = FailingTool(
            idempotent = true,
            failure = kotlinx.coroutines.CancellationException("cancelled by standalone tool"),
        )
        val provider = ToolLoopProvider(
            ModelResponse(
                content = "using tool",
                toolCalls = listOf(ToolCall("1", tool.name, "\"x\"")),
            ),
        )
        val diagnostics = RecordingDiagnosticObserver()

        val tramai = Tramai {
            provider(provider, default = true)
            model("test-model", "mock")
            tools(tool)
            toolFailureDiagnosticObserver(diagnostics)
        }
        val service = tramai.create<ToolTestService>()

        assertThatThrownBy { runBlocking { service.execute("input") } }
            .isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
            .hasMessage("cancelled by standalone tool")

        assertThat(tool.calls.get()).isEqualTo(1)
        assertThat(provider.requests).hasSize(1)
        assertThat(diagnostics.events).isEmpty()
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private fun assertToolMessages(provider: ToolLoopProvider, expectedContent: String) {
        val toolMessages = provider.requests.flatMap { it.messages }
            .filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages.map { it.content }).containsExactly(expectedContent)
    }

    private fun assertNoLeak(provider: ToolLoopProvider) {
        val providerJson = provider.requests
            .flatMap { it.messages }
            .joinToString("\n") { message ->
                "{\"role\":\"${jsonEscape(message.role.name)}\",\"content\":\"${jsonEscape(message.content)}\"}"
            }
        for (fragment in sensitiveFragments) {
            assertThat(providerJson)
                .describedAs("sensitive fragment '$fragment' must not reach the provider")
                .doesNotContain(fragment)
        }
    }

    private fun jsonEscape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    private class ToolLoopProvider(vararg responses: ModelResponse) : ModelProvider {
        private val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<ModelRequest>()

        override suspend fun complete(request: ModelRequest): ModelResponse {
            requests += request
            return queue.removeFirstOrNull() ?: error("No more queued responses")
        }

        override fun providerId(): String = "mock"
    }

    private class RecordingDiagnosticObserver : ToolFailureDiagnosticObserver {
        val events = mutableListOf<ToolFailureDiagnosticEvent>()

        override fun record(event: ToolFailureDiagnosticEvent) {
            events += event
        }
    }

    /** Tool that throws the configured failure on every call. */
    private class FailingTool(
        override val idempotent: Boolean,
        private val failure: Throwable,
    ) : dev.tramai.core.model.TramaiTool<String, String> {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        override val name: String = "failing-tool"
        override val description: String = "a tool that always fails"
        override val inputType: KClass<String> = String::class

        override suspend fun execute(input: String, context: ToolExecutionContext): String {
            calls.incrementAndGet()
            throw failure
        }
    }

    @AiService
    private interface ToolTestService {
        @Operation(
            prompt = "Execute the input",
            model = "test-model",
            tools = ["failing-tool"],
        )
        suspend fun execute(input: String): String
    }
}
