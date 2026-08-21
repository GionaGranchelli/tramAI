package dev.tramai.ollama

import dev.tramai.core.provider.ProviderCapability
import dev.tramai.testing.provider.ProviderTck
import dev.tramai.testing.provider.ProviderTckHarness
import dev.tramai.testing.provider.RecordingProviderFailureDiagnosticObserver
import dev.tramai.testing.provider.StreamingSpec
import dev.tramai.testing.provider.UsageSpec

/**
 * Epic 6.1 TCK runner for [OllamaProvider] (Ollama `/api/chat`).
 *
 * Expected capability matrix is pinned here, not read from the provider:
 * STREAMING.
 *
 * VISION is deliberately NOT pinned. The adapter does encode
 * [dev.tramai.core.model.ContentPart.ImagePart], but the Ollama wire protocol
 * carries images as bare base64 strings in `images` — the Go server
 * base64-decodes each entry and rejects data URIs (`illegal base64 data at
 * input byte 4`), so no mime type can appear in the outbound body. The TCK's
 * vision contract (mime-preserving outbound encoding) is therefore not
 * satisfiable without corrupting the wire format; the image encoding itself
 * stays covered by `OllamaProviderTest`.
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
        expectedCapabilities = setOf(ProviderCapability.STREAMING),
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
