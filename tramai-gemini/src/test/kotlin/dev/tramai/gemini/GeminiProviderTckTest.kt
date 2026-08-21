package dev.tramai.gemini

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
 * Epic 6.1 TCK runner for [GeminiProvider] (Google Gemini generateContent API).
 *
 * Expected capability matrix is pinned here, not read from the provider:
 * VISION + TOOL_CALLING + STRUCTURED_OUTPUT + STREAMING.
 */
class GeminiProviderTckTest : ProviderTck() {

    private val observer = RecordingProviderFailureDiagnosticObserver()

    override val harness = ProviderTckHarness(
        expectedProviderId = GeminiProvider.PROVIDER_ID,
        expectedCapabilities = setOf(
            ProviderCapability.VISION,
            ProviderCapability.TOOL_CALLING,
            ProviderCapability.STRUCTURED_OUTPUT,
            ProviderCapability.STREAMING,
        ),
        createProvider = { stub ->
            GeminiProvider(
                apiKey = "«redacted:gemini-tck-key»",
                baseUrl = "https://generativelanguage.test",
                httpClient = stub,
                providerFailureDiagnosticObserver = observer,
            )
        },
        diagnosticObserver = { observer },
        happyPathBody = ProviderHttpFixtures.Gemini.happy("Hello!"),
        happyPathExpectedContent = "Hello!",
        emptyBody = ProviderHttpFixtures.Gemini.emptyCandidates(),
        malformedBody = ProviderHttpFixtures.Gemini.malformed(),
        usage = UsageSpec(
            body = ProviderHttpFixtures.Gemini.withUsage(input = 100, output = 42),
            expectedInputTokens = 100,
            expectedOutputTokens = 42,
        ),
        tools = ToolSpec(
            toolCallBody = ProviderHttpFixtures.Gemini.toolCall(
                call = TCK_TOOL_CALL,
                text = "Let me check the weather.",
            ),
            toolOnlyBody = ProviderHttpFixtures.Gemini.toolCall(call = TCK_TOOL_CALL),
            expectedToolCall = TCK_TOOL_CALL,
            requestToolNameMarker = "get_weather",
            requestToolSchemaMarker = "\"parameters\"",
        ),
        vision = VisionSpec(
            body = ProviderHttpFixtures.Gemini.happy("The image shows a cat."),
        ),
        structuredOutput = StructuredOutputSpec(
            body = ProviderHttpFixtures.Gemini.happy("""{"answer":42}"""),
            expectedContent = """{"answer":42}""",
        ),
        streaming = StreamingSpec(
            body = ProviderHttpFixtures.Gemini.stream(listOf("Hello", " world")),
            malformedBody = ProviderHttpFixtures.Gemini.streamMalformed(),
            expectedTokens = listOf("Hello", " world"),
        ),
    )

    companion object {
        // Gemini functionCall parts carry no id; the adapter synthesizes "fc_<name>_<index>".
        private val TCK_TOOL_CALL = ToolCall(
            id = "fc_get_weather_0",
            name = "get_weather",
            argumentsJson = """{"location":"Amsterdam"}""",
        )
    }
}
