package dev.tramai.anthropic

import dev.tramai.core.model.ToolCall
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.testing.provider.ProviderHttpFixtures
import dev.tramai.testing.provider.ProviderTck
import dev.tramai.testing.provider.ProviderTckHarness
import dev.tramai.testing.provider.RecordingProviderFailureDiagnosticObserver
import dev.tramai.testing.provider.StreamingSpec
import dev.tramai.testing.provider.StructuredOutputSpec
import dev.tramai.testing.provider.ToolSpec
import dev.tramai.testing.provider.UsageSpec
import dev.tramai.testing.provider.VisionSpec

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
}
