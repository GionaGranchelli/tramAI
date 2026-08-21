package dev.tramai.testing.provider

import dev.tramai.core.model.ToolCall
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.observation.ProviderFailureDiagnosticObserver

/**
 * Transport category of the provider under test.
 *
 * HTTP providers are driven through [StubHttpClient] and participate in the
 * full HTTP contract (timeouts, status mapping, Retry-After, bounded bodies).
 * SDK providers (currently Bedrock) are driven through their own test seam and
 * are exempt from the HTTP wire-level assertions, which the documented
 * compatibility matrix records explicitly.
 */
enum class ProviderTransport {
    HTTP,
    SDK,
}

/** Expected usage normalization for a provider completion fixture. */
data class UsageSpec(
    /** Raw provider body containing usage counters. */
    val body: String,
    val expectedInputTokens: Int,
    val expectedOutputTokens: Int,
    val expectedThinkingTokens: Int? = null,
)

/** Expected tool-calling behaviour for a provider completion fixture. */
data class ToolSpec(
    /** Body whose response carries a tool call (with or without text). */
    val toolCallBody: String,
    /** Body whose response carries ONLY a tool call and no text block. */
    val toolOnlyBody: String,
    /** The exact normalized [ToolCall] expected to be extracted. */
    val expectedToolCall: ToolCall,
    /** Request-side marker that must appear in the outbound body. */
    val requestToolNameMarker: String,
    val requestToolSchemaMarker: String,
)

/** Expected vision behaviour for a provider completion fixture. */
data class VisionSpec(
    /** Body answering a request that contained an image part. */
    val body: String,
)

/** Expected structured-output behaviour for a provider completion fixture. */
data class StructuredOutputSpec(
    /** Body proving the adapter performs its own structured-output handling. */
    val body: String,
    /** Text content expected from [body], when non-null. */
    val expectedContent: String? = null,
)

/** Expected streaming behaviour for a provider fixture. */
data class StreamingSpec(
    /** Full raw stream body (SSE/NDJSON/…) yielding the expected tokens in order. */
    val body: String,
    /** Raw stream body that terminates mid-event (malformed). */
    val malformedBody: String,
    /** Ordered text fragments the adapter must emit as [dev.tramai.core.model.StreamChunk.Token]. */
    val expectedTokens: List<String>,
    /** Transport-failure stream: adapter must emit a terminal Error chunk. */
    val transportFailureBody: String? = null,
)

/**
 * Explicit, test-supplied contract matrix for one provider.
 *
 * The runner (not the provider) pins [expectedProviderId] and
 * [expectedCapabilities]; a provider can never make a TCK test disappear by
 * changing its own `supportsCapability(...)` return. Declaring a capability in
 * [expectedCapabilities] REQUIRES the matching spec (enforced in [init]), so
 * the runner file itself is the reviewed compatibility contract.
 */
class ProviderTckHarness(
    val expectedProviderId: String,
    val expectedCapabilities: Set<ProviderCapability>,
    val createProvider: (StubHttpClient) -> ModelProvider,
    val transport: ProviderTransport = ProviderTransport.HTTP,
    /** Model identifier sent in TCK requests. */
    val modelName: String = "tck-model",
    /** Body for an ordinary text completion. */
    val happyPathBody: String,
    /** Text content expected from [happyPathBody] when non-null. */
    val happyPathExpectedContent: String? = null,
    /** Protocol-shaped body with no completion content (empty choices/candidates/etc). */
    val emptyBody: String,
    /** Protocol-shaped body that is truncated mid-payload. */
    val malformedBody: String,
    val usage: UsageSpec? = null,
    val tools: ToolSpec? = null,
    val vision: VisionSpec? = null,
    val structuredOutput: StructuredOutputSpec? = null,
    val streaming: StreamingSpec? = null,
    /** Body returned for any rejected HTTP status (4xx/5xx). */
    val httpErrorBody: String = "{\"error\":{\"message\":\"provider declined the request\"}}",
    /**
     * Returns a recording [ProviderFailureDiagnosticObserver] wired into the
     * provider under test, when the provider exposes observer injection.
     * Required for HTTP providers so the TCK can prove the bounded-preview
     * contract; runners wire their own recorder here.
     */
    val diagnosticObserver: (() -> ProviderFailureDiagnosticObserver)? = null,
) {
    init {
        require(ProviderCapability.VISION !in expectedCapabilities || vision != null) {
            "VISION is pinned in expectedCapabilities ⇒ vision spec is required"
        }
        require(ProviderCapability.TOOL_CALLING !in expectedCapabilities || tools != null) {
            "TOOL_CALLING is pinned in expectedCapabilities ⇒ tools spec is required"
        }
        require(ProviderCapability.STRUCTURED_OUTPUT !in expectedCapabilities || structuredOutput != null) {
            "STRUCTURED_OUTPUT is pinned in expectedCapabilities ⇒ structured-output spec is required"
        }
        require(ProviderCapability.STREAMING !in expectedCapabilities || streaming != null) {
            "STREAMING is pinned in expectedCapabilities ⇒ streaming spec is required"
        }
        require(transport == ProviderTransport.SDK || createProvider != null) {
            "HTTP providers must supply a createProvider hook"
        }
        require(transport == ProviderTransport.SDK || diagnosticObserver != null) {
            "HTTP providers must wire a diagnostic observer for the bounded-preview contract"
        }
    }
}
