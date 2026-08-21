package dev.tramai.anthropic

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.testing.provider.ProviderHttpFixtures
import dev.tramai.testing.provider.ProviderTck
import dev.tramai.testing.provider.ProviderTckHarness
import dev.tramai.testing.provider.RecordingProviderFailureDiagnosticObserver
import dev.tramai.testing.provider.StreamingSpec
import dev.tramai.testing.provider.StructuredOutputSpec
import dev.tramai.testing.provider.StubHttpClient
import dev.tramai.testing.provider.ToolSpec
import dev.tramai.testing.provider.UsageSpec
import dev.tramai.testing.provider.VisionSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 6.1 TCK runner for [AnthropicProvider] (Anthropic Messages API).
 *
 * Expected capability matrix is pinned here, not read from the provider:
 * VISION + TOOL_CALLING + STRUCTURED_OUTPUT + STREAMING.
 */
class AnthropicProviderTckTest : ProviderTck() {

    private val observer = RecordingProviderFailureDiagnosticObserver()

    override val harness = ProviderTckHarness(
        expectedProviderId = "anthropic",
        expectedCapabilities = setOf(
            ProviderCapability.VISION,
            ProviderCapability.TOOL_CALLING,
            ProviderCapability.STRUCTURED_OUTPUT,
            ProviderCapability.STREAMING,
        ),
        createProvider = { stub ->
            AnthropicProvider(
                apiKey = "test-key",
                baseUrl = "https://api.anthropic.test",
                httpClient = stub,
                providerFailureDiagnosticObserver = observer,
            )
        },
        diagnosticObserver = { observer },
        happyPathBody = ProviderHttpFixtures.Anthropic.happy("Hello!"),
        happyPathExpectedContent = "Hello!",
        emptyBody = ProviderHttpFixtures.Anthropic.emptyContent(),
        malformedBody = ProviderHttpFixtures.Anthropic.malformed(),
        usage = UsageSpec(
            body = ProviderHttpFixtures.Anthropic.withUsage(input = 100, output = 42),
            expectedInputTokens = 100,
            expectedOutputTokens = 42,
        ),
        tools = ToolSpec(
            toolCallBody = ProviderHttpFixtures.Anthropic.toolCall(
                call = TCK_TOOL_CALL,
                text = "Let me check the weather.",
            ),
            toolOnlyBody = ProviderHttpFixtures.Anthropic.toolCall(call = TCK_TOOL_CALL),
            expectedToolCall = TCK_TOOL_CALL,
            requestToolNameMarker = "get_weather",
            requestToolSchemaMarker = "\"input_schema\"",
        ),
        vision = VisionSpec(
            body = ProviderHttpFixtures.Anthropic.happy("The image shows a cat."),
        ),
        structuredOutput = StructuredOutputSpec(
            body = ProviderHttpFixtures.Anthropic.happy("""{"answer":42}"""),
            expectedContent = """{"answer":42}""",
        ),
        streaming = StreamingSpec(
            body = ProviderHttpFixtures.Anthropic.stream(listOf("Hello", " world")),
            malformedBody = ProviderHttpFixtures.Anthropic.streamMalformed(),
            expectedTokens = listOf("Hello", " world"),
        ),
    )

    companion object {
        private val TCK_TOOL_CALL = ToolCall(
            id = "toolu_tck_1",
            name = "get_weather",
            argumentsJson = """{"location":"Amsterdam"}""",
        )
    }

    // ── two-turn tool loop contract (extra runner test) ─────────────────

    /**
     * Proves the real tool loop round-trip: turn 1 returns a tool_use block, then
     * the second request — built exactly like ToolLoopCoordinator builds it
     * (user prompt, assistant message carrying the ToolCall, TOOL-role result) —
     * serialises the assistant toolCalls back into a `tool_use` block and the
     * TOOL result into a `user`-role `tool_result` block.
     */
    @Test
    fun `two-turn tool loop round-trips tool_use and tool_result blocks`() {
        val call = ToolCall(
            id = "call_tck_1",
            name = "get_weather",
            argumentsJson = """{"location":"Amsterdam"}""",
        )
        val userPrompt = Message(MessageRole.USER, "What is the weather in Amsterdam?")
        val stub = StubHttpClient().apply {
            enqueue(200, ProviderHttpFixtures.Anthropic.toolCall(call = call, text = "Let me check the weather."))
            enqueue(200, ProviderHttpFixtures.Anthropic.happy("Amsterdam: 18°C, partly cloudy."))
        }

        // Turn 1: the provider answers with a tool_use block.
        val first = complete(stub, request(messages = listOf(userPrompt)))
        assertThat(first.toolCalls).isEqualTo(listOf(call))

        // Turn 2: exactly what ToolLoopCoordinator would send after the tool executed.
        val second = complete(
            stub,
            request(
                messages = listOf(
                    userPrompt,
                    Message(
                        role = MessageRole.ASSISTANT,
                        content = "Let me check the weather.",
                        toolCalls = listOf(call),
                    ),
                    Message(
                        role = MessageRole.TOOL,
                        content = "18°C, partly cloudy",
                        toolCallId = "call_tck_1",
                    ),
                ),
            ),
        )
        assertThat(second.content).isEqualTo("Amsterdam: 18°C, partly cloudy.")

        val body = stub.lastRequestBody ?: ""
        assertThat(body)
            .withFailMessage("second request must carry the assistant tool_use block")
            .contains("""{"type":"tool_use","id":"call_tck_1","name":"get_weather","input":{"location":"Amsterdam"}}""")
        assertThat(body)
            .withFailMessage("second request must carry the tool result as a user-role tool_result block")
            .contains(""""tool_use_id":"call_tck_1"""")
            .contains(""""role":"user"""")
            .doesNotContain(""""role":"tool"""")
    }
}
