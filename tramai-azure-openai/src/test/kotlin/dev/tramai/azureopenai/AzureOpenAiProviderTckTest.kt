package dev.tramai.azureopenai

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
 * Epic 6.1 TCK runner for [AzureOpenAiProvider].
 *
 * Azure OpenAI speaks the OpenAI chat/completions wire format, so fixtures
 * come from [ProviderHttpFixtures.OpenAi]. Expected capability matrix is
 * pinned here, not read from the provider:
 * VISION + TOOL_CALLING + STRUCTURED_OUTPUT + STREAMING.
 */
class AzureOpenAiProviderTckTest : ProviderTck() {

    private val observer = RecordingProviderFailureDiagnosticObserver()

    override val harness = ProviderTckHarness(
        expectedProviderId = AzureOpenAiProvider.PROVIDER_ID,
        expectedCapabilities = setOf(
            ProviderCapability.VISION,
            ProviderCapability.TOOL_CALLING,
            ProviderCapability.STRUCTURED_OUTPUT,
            ProviderCapability.STREAMING,
        ),
        createProvider = { stub ->
            AzureOpenAiProvider(
                resourceName = "tck",
                deploymentId = "tck-model",
                apiKey = "«redacted:tck-api-key»",
                httpClient = stub,
                providerFailureDiagnosticObserver = observer,
            )
        },
        diagnosticObserver = { observer },
        happyPathBody = ProviderHttpFixtures.OpenAi.happy("Hello!"),
        happyPathExpectedContent = "Hello!",
        emptyBody = ProviderHttpFixtures.OpenAi.emptyChoices(),
        malformedBody = ProviderHttpFixtures.OpenAi.malformed(),
        usage = UsageSpec(
            body = ProviderHttpFixtures.OpenAi.withUsage(input = 100, output = 42, thinking = 7),
            expectedInputTokens = 100,
            expectedOutputTokens = 42,
            expectedThinkingTokens = 7,
        ),
        tools = ToolSpec(
            toolCallBody = ProviderHttpFixtures.OpenAi.toolCall(
                call = TCK_TOOL_CALL,
                text = "Let me check the weather.",
            ),
            toolOnlyBody = ProviderHttpFixtures.OpenAi.toolCall(call = TCK_TOOL_CALL),
            expectedToolCall = TCK_TOOL_CALL,
            requestToolNameMarker = "get_weather",
            requestToolSchemaMarker = "\"parameters\"",
        ),
        vision = VisionSpec(
            body = ProviderHttpFixtures.OpenAi.happy("The image shows a cat."),
        ),
        structuredOutput = StructuredOutputSpec(
            body = ProviderHttpFixtures.OpenAi.happy("""{"answer":42}"""),
            expectedContent = """{"answer":42}""",
        ),
        streaming = StreamingSpec(
            body = ProviderHttpFixtures.OpenAi.stream(listOf("Hello", " world")),
            malformedBody = ProviderHttpFixtures.OpenAi.streamMalformed(),
            expectedTokens = listOf("Hello", " world"),
        ),
    )

    companion object {
        private val TCK_TOOL_CALL = ToolCall(
            id = "call_tck_1",
            name = "get_weather",
            argumentsJson = """{"location":"Amsterdam"}""",
        )
    }
}
