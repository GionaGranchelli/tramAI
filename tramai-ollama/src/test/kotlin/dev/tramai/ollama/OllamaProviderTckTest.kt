package dev.tramai.ollama

import dev.tramai.core.provider.ProviderCapability
import dev.tramai.testing.provider.ProviderTck
import dev.tramai.testing.provider.ProviderTckHarness
import dev.tramai.testing.provider.RecordingProviderFailureDiagnosticObserver
import dev.tramai.testing.provider.StreamingSpec
import dev.tramai.testing.provider.UsageSpec
import dev.tramai.testing.provider.VisionSpec

/**
 * Epic 6.1 TCK runner for [OllamaProvider] (Ollama `/api/chat`).
 *
 * Expected capability matrix is pinned here, not read from the provider:
 * VISION + STREAMING.
 *
 * VISION is pinned via a protocol-aware [VisionSpec]: the adapter encodes
 * [dev.tramai.core.model.ContentPart.ImagePart] as base64 strings in the
 * outbound `images` array, and the Ollama wire protocol carries those images
 * WITHOUT a MIME marker — the Go server base64-decodes each entry directly
 * and rejects data URIs (`illegal base64 data at input byte 4`). The runner
 * therefore requires the base64 payload but not the MIME marker; production
 * capabilities are not downgraded to fit a generic assertion.
 *
 * Body fixtures are chat-shaped NDJSON matching the adapter's parser
 * (`message.content` + `done`), because the shared
 * [dev.tramai.testing.provider.ProviderHttpFixtures.Ollama] family targets the
 * legacy `generate` API shape (`response`).
 */
class OllamaProviderTckTest : ProviderTck() {

    private val observer = RecordingProviderFailureDiagnosticObserver()

    override val harness = ProviderTckHarness(
        expectedProviderId = "ollama",
        expectedCapabilities = setOf(ProviderCapability.VISION, ProviderCapability.STREAMING),
        createProvider = { stub ->
            OllamaProvider(
                httpClient = stub,
                providerFailureDiagnosticObserver = observer,
            )
        },
        diagnosticObserver = { observer },
        happyPathBody = chatBody(content = "Hello!"),
        happyPathExpectedContent = "Hello!",
        emptyBody = chatBody(content = ""),
        malformedBody = truncatedChatBody("trunc"),
        usage = UsageSpec(
            body = """{"model":"ollama-tck","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":"Usage check"},"done":true,"prompt_eval_count":100,"eval_count":42}""",
            expectedInputTokens = 100,
            expectedOutputTokens = 42,
        ),
        vision = VisionSpec(
            body = chatBody(content = "The image shows a cat."),
            requireBase64Payload = true,
            requireMimeTypeMarker = false,
        ),
        streaming = StreamingSpec(
            body = streamBody(listOf("Hello", " world")),
            malformedBody = truncatedChatBody("Hel"),
            expectedTokens = listOf("Hello", " world"),
        ),
    )

    /** One complete (non-streaming) chat response line. */
    private fun chatBody(content: String): String =
        """{"model":"ollama-tck","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":"$content"},"done":true}"""

    /** A chat response line truncated mid-content, matching the malformed fixtures' intent. */
    private fun truncatedChatBody(contentPrefix: String): String =
        """{"model":"ollama-tck","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":"$contentPrefix"""

    /** NDJSON stream: one `done:false` line per token, then a final `done:true` line with usage. */
    private fun streamBody(tokens: List<String>): String =
        tokens.joinToString("\n") { token ->
            """{"model":"ollama-tck","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":"$token"},"done":false}"""
        } + "\n" +
            """{"model":"ollama-tck","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":7,"eval_count":3}""" + "\n"
}
