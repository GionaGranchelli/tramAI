package dev.tramai.testing.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.ProviderFailureCode
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.ToolDefinition
import dev.tramai.core.model.UsageMetrics
import dev.tramai.core.observation.ProviderFailureDiagnosticEvent
import dev.tramai.core.observation.ProviderFailureDiagnosticObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.StreamCapable
import dev.tramai.core.provider.PROVIDER_ERROR_BODY_LIMIT_BYTES
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Deterministic, offline compatibility contract for every published provider.
 *
 * The runner pins [ProviderTckHarness.expectedProviderId] and
 * [ProviderTckHarness.expectedCapabilities] explicitly; the provider can never
 * make a contract test disappear by changing its own capability declaration.
 * All requests are answered by [StubHttpClient] canned responses — no network,
 * no credentials.
 *
 * HTTP providers additionally enforce the wire-level contract (timeout,
 * retryable status mapping, numeric Retry-After, bounded diagnostic bodies).
 * SDK providers (Bedrock) enforce the transport-agnostic contract through
 * their client-factory seam; the wire-level exemption is recorded in
 * docs/reference/provider-compatibility-contract.md.
 */
abstract class ProviderTck {

    abstract val harness: ProviderTckHarness

    protected val h get() = harness
    protected val isHttp get() = harness.transport == ProviderTransport.HTTP

    // ── helpers ────────────────────────────────────────────────────────

    protected fun request(
        messages: List<Message> = listOf(Message(MessageRole.USER, "hello")),
        tools: List<ToolDefinition>? = null,
        timeoutMillis: Long? = null,
    ): ModelRequest = ModelRequest(
        model = harness.modelName,
        messages = messages,
        tools = tools,
        timeoutMillis = timeoutMillis,
    )

    protected fun provider(stub: StubHttpClient): ModelProvider = harness.createProvider(stub)

    /** Runs [complete] against a fresh provider+stub and returns the exception or null. */
    protected fun completeOrThrow(
        stub: StubHttpClient,
        request: ModelRequest = request(),
    ): Throwable? = runCatching {
        runBlocking { provider(stub).complete(request) }
    }.exceptionOrNull()

    protected fun complete(
        stub: StubHttpClient,
        request: ModelRequest = request(),
    ): ModelResponse = runBlocking { provider(stub).complete(request) }

    protected fun assertSafeMessage(ex: Throwable, forbidden: List<String>) {
        val message = ex.message ?: ""
        forbidden.forEach { fragment ->
            assertThat(message)
                .withFailMessage("public message must not contain '$fragment' but was: '$message'")
                .doesNotContain(fragment)
        }
    }

    // ── identity & capability contract ─────────────────────────────────

    @Test
    fun `provider identity matches the pinned stable id`() {
        val stub = StubHttpClient()
        assertThat(provider(stub).providerId()).isEqualTo(harness.expectedProviderId)
    }

    @Test
    fun `capability declarations match the pinned matrix`() {
        val stub = StubHttpClient()
        val p = provider(stub)
        ProviderCapability.entries.forEach { capability ->
            assertThat(p.supportsCapability(capability))
                .withFailMessage(
                    "capability $capability: provider says ${p.supportsCapability(capability)} but " +
                        "the pinned matrix requires ${capability in harness.expectedCapabilities}",
                )
                .isEqualTo(capability in harness.expectedCapabilities)
        }
        val streamable = p is StreamCapable
        assertThat(streamable)
            .withFailMessage(
                "STREAMING is ${if (ProviderCapability.STREAMING in harness.expectedCapabilities) "pinned" else "NOT pinned"} " +
                    "but the provider ${if (streamable) "implements" else "does not implement"} StreamCapable",
            )
            .isEqualTo(ProviderCapability.STREAMING in harness.expectedCapabilities)
    }

    @Test
    fun `completion maps the happy-path text content`() {
        val stub = StubHttpClient().apply { enqueue(200, harness.happyPathBody) }
        val response = complete(stub)
        if (harness.happyPathExpectedContent != null) {
            assertThat(response.content).isEqualTo(harness.happyPathExpectedContent)
        }
        assertThat(stub.lastBodyClosed())
            .withFailMessage("completion success must close the response body")
            .isTrue()
    }

    @Test
    fun `usage metrics are normalized`() {
        val usage = harness.usage ?: return
        val stub = StubHttpClient().apply { enqueue(200, usage.body) }
        val response = complete(stub)
        assertThat(response.inputTokens).isEqualTo(usage.expectedInputTokens)
        assertThat(response.outputTokens).isEqualTo(usage.expectedOutputTokens)
        if (usage.expectedThinkingTokens != null) {
            assertThat(response.thinkingTokens).isEqualTo(usage.expectedThinkingTokens)
        }
    }

    @Test
    fun `empty response fails with a deterministic safe error`() {
        val stub = StubHttpClient().apply { enqueue(200, harness.emptyBody) }
        val ex = completeOrThrow(stub)
        assertThat(ex).isInstanceOf(ProviderException::class.java)
        val pe = ex as ProviderException
        assertThat(pe.failureCode).isEqualTo(ProviderFailureCode.UNEXPECTED_FAILURE)
        assertSafeMessage(pe, listOf(harness.emptyBody.take(40)))
    }

    @Test
    fun `malformed response fails safely without leaking parser detail`() {
        val stub = StubHttpClient().apply { enqueue(200, harness.malformedBody) }
        val ex = completeOrThrow(stub)
        assertThat(ex).isInstanceOf(ProviderException::class.java)
        assertSafeMessage(ex as ProviderException, listOf("trunc", "Unexpected end", "JsonParse", "MALFORMED"))
        assertThat(stub.lastBodyClosed())
            .withFailMessage("parse-failure path must close the response body")
            .isTrue()
    }

    @Test
    fun `transport failure maps to a safe retryable ProviderException`() {
        val stub = StubHttpClient().apply {
            enqueueTransportFailure(IOException("connection reset by peer: 10.0.0.1:443"))
        }
        val ex = completeOrThrow(stub)
        assertThat(ex).isInstanceOf(ProviderException::class.java)
        assertThat((ex as ProviderException).retryable).isTrue()
        assertSafeMessage(ex, listOf("connection reset by peer", "10.0.0.1:443", "reset"))
    }

    @Test
    fun `cancellation during completion escapes as cancellation`() {
        if (harness.transport == ProviderTransport.SDK) return // SDK runners cover via their seam
        val stub = StubHttpClient().apply {
            enqueue(200, harness.happyPathBody)
            armCancellation()
        }
        val p = provider(stub)
        runBlocking {
            val deferred = async(Dispatchers.Default) { p.complete(request()) }
            // Generous budget: under 16-module parallel test execution the
            // collector coroutine can be starved for seconds; the assertion is
            // about cancellation semantics, not speed.
            withTimeout(30_000) {
                while (stub.lastUri == null) delay(1)
            }
            deferred.cancel()
            stub.cancellationRelease.complete(Unit)
            val ex = runCatching { deferred.await() }.exceptionOrNull()
            assertThat(ex)
                .withFailMessage("cancellation must escape as CancellationException, was: $ex")
                .isInstanceOf(CancellationException::class.java)
        }
    }

    @Test
    fun `rejected response never leaks the provider body or credentials`() {
        val secret = "sk-tck-secret-1234567890abcdef"
        val body = """{"error":{"message":"invalid api key $secret for tenant 42"}}"""
        val stub = StubHttpClient().apply { enqueue(429, body) }
        val ex = completeOrThrow(stub)
        assertThat(ex).isInstanceOf(ProviderException::class.java)
        assertSafeMessage(ex as ProviderException, listOf(secret, "tenant", "invalid api key"))
    }

    // ── HTTP wire contract ─────────────────────────────────────────────

    @Test
    fun `request timeout is applied to the transport`() {
        if (!isHttp) return
        val stub = StubHttpClient().apply { enqueue(200, harness.happyPathBody) }
        complete(stub, request(timeoutMillis = 2_000))
        assertThat(stub.lastTimeout)
            .withFailMessage("ModelRequest.timeoutMillis must become the request timeout")
            .isEqualTo(java.time.Duration.ofMillis(2_000))
    }

    @Test
    fun `retryable statuses map as retryable`() {
        if (!isHttp) return
        listOf(408, 425, 429, 500, 502, 503, 504).forEach { status ->
            val stub = StubHttpClient().apply { enqueue(status, harness.httpErrorBody) }
            val ex = completeOrThrow(stub)
            assertThat(ex)
                .withFailMessage("status $status must produce a ProviderException")
                .isInstanceOf(ProviderException::class.java)
            val pe = ex as ProviderException
            assertThat(pe.retryable)
                .withFailMessage("status $status must be retryable")
                .isTrue()
            assertThat(pe.statusCode).isEqualTo(status)
            assertThat(pe.failureCode).isEqualTo(ProviderFailureCode.HTTP_REJECTED)
            assertThat(pe.message).isEqualTo("Provider request failed with HTTP $status")
        }
    }

    @Test
    fun `nonretryable statuses stay nonretryable`() {
        if (!isHttp) return
        listOf(400, 401).forEach { status ->
            val stub = StubHttpClient().apply { enqueue(status, harness.httpErrorBody) }
            val ex = completeOrThrow(stub) as ProviderException
            assertThat(ex.retryable)
                .withFailMessage("status $status must NOT be retryable")
                .isFalse()
            assertThat(ex.statusCode).isEqualTo(status)
        }
    }

    @Test
    fun `numeric Retry-After is converted to milliseconds`() {
        if (!isHttp) return
        val stub = StubHttpClient().apply {
            enqueue(429, harness.httpErrorBody, headers = mapOf("Retry-After" to "2"))
        }
        val ex = completeOrThrow(stub) as ProviderException
        assertThat(ex.retryAfterMillis).isEqualTo(2_000L)
    }

    @Test
    fun `rejected response body is closed`() {
        if (!isHttp) return
        val stub = StubHttpClient().apply { enqueue(429, harness.httpErrorBody) }
        completeOrThrow(stub)
        assertThat(stub.lastBodyClosed())
            .withFailMessage("rejected response body must be closed")
            .isTrue()
    }

    @Test
    fun `diagnostic body preview is bounded`() {
        if (!isHttp) return
        val observer = harness.diagnosticObserver?.invoke()
            ?: return // no observer wiring for this provider (SDK)
        val bigBody = "x".repeat(64 * 1024)
        val stub = StubHttpClient().apply { enqueue(500, bigBody) }
        completeOrThrow(stub)
        val events = (observer as RecordingProviderFailureDiagnosticObserver).events
        assertThat(events).isNotEmpty()
        val preview = events.last().httpBodyPreview ?: ""
        assertThat(preview.length)
            .withFailMessage("diagnostic preview must be bounded to $PROVIDER_ERROR_BODY_LIMIT_BYTES bytes")
            .isLessThanOrEqualTo(PROVIDER_ERROR_BODY_LIMIT_BYTES)
        assertThat(preview).doesNotContain(bigBody.substring(32_768))
    }

    // ── tool-calling contract ──────────────────────────────────────────

    @Test
    fun `tool definitions are serialized outbound`() {
        val tools = harness.tools ?: return
        val stub = StubHttpClient().apply { enqueue(200, tools.toolCallBody) }
        complete(stub, request(tools = listOf(TOOL_DEFINITION)))
        val body = stub.lastRequestBody ?: ""
        assertThat(body)
            .withFailMessage("outbound request must contain the tool name")
            .contains(tools.requestToolNameMarker)
        assertThat(body)
            .withFailMessage("outbound request must contain the tool schema marker")
            .contains(tools.requestToolSchemaMarker)
    }

    @Test
    fun `tool calls are parsed from the provider response`() {
        val tools = harness.tools ?: return
        val stub = StubHttpClient().apply { enqueue(200, tools.toolCallBody) }
        val response = complete(stub, request(tools = listOf(TOOL_DEFINITION)))
        assertThat(response.toolCalls).isNotNull()
        val call = response.toolCalls!!.single()
        assertThat(call).isEqualTo(tools.expectedToolCall)
    }

    @Test
    fun `tool-only responses are not treated as empty text failures`() {
        val tools = harness.tools ?: return
        val stub = StubHttpClient().apply { enqueue(200, tools.toolOnlyBody) }
        val ex = completeOrThrow(stub, request(tools = listOf(TOOL_DEFINITION)))
        assertThat(ex)
            .withFailMessage("a tool-only response must not fail as empty text, was: $ex")
            .isNull()
        val response = complete(StubHttpClient().apply { enqueue(200, tools.toolOnlyBody) }, request(tools = listOf(TOOL_DEFINITION)))
        assertThat(response.toolCalls).isEqualTo(listOf(tools.expectedToolCall))
    }

    // ── vision contract ────────────────────────────────────────────────

    @Test
    fun `image parts are encoded with mime type in the outbound request`() {
        val vision = harness.vision ?: return
        val imageBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val base64 = Base64.getEncoder().encodeToString(imageBytes)
        val stub = StubHttpClient().apply { enqueue(200, vision.body) }
        complete(
            stub,
            request(
                messages = listOf(
                    Message(
                        role = MessageRole.USER,
                        content = "",
                        contentParts = listOf(
                            ContentPart.TextPart("what is in this image?"),
                            ContentPart.ImagePart("image/png", imageBytes),
                        ),
                    ),
                ),
            ),
        )
        val body = stub.lastRequestBody ?: ""
        if (vision.requireBase64Payload) {
            assertThat(body)
                .withFailMessage("outbound request must carry the base64 image payload")
                .contains(base64)
        }
        if (vision.requireMimeTypeMarker) {
            assertThat(body)
                .withFailMessage("outbound request must carry the image mime type")
                .contains("image/png")
        }
    }

    @Test
    fun `vision completion succeeds`() {
        val vision = harness.vision ?: return
        val stub = StubHttpClient().apply { enqueue(200, vision.body) }
        val response = complete(stub)
        assertThat(response.content).isNotNull()
    }

    // ── structured-output contract ─────────────────────────────────────
    //
    // NOTE (Epic 6.1 scope): ModelRequest has no structured-schema contract
    // yet, so this proves the provider returns the fixture content for a
    // completion whose prompt requests JSON — it does NOT prove a
    // provider-native structured-output request. Native structured-output
    // enforcement is Phase 7 territory.

    @Test
    fun `structured output completion returns the fixture content`() {
        val structured = harness.structuredOutput ?: return
        val stub = StubHttpClient().apply { enqueue(200, structured.body) }
        val response = complete(stub)
        if (structured.expectedContent != null) {
            assertThat(response.content).isEqualTo(structured.expectedContent)
        } else {
            assertThat(response.content).isNotNull()
        }
    }

    // ── streaming contract ─────────────────────────────────────────────

    protected fun streamChunks(stub: StubHttpClient, request: ModelRequest = request()): List<StreamChunk> {
        val p = provider(stub)
        assertThat(p).isInstanceOf(StreamCapable::class.java)
        return runBlocking { (p as StreamCapable).stream(request).toList() }
    }

    @Test
    fun `stream preserves token order`() {
        val streaming = harness.streaming ?: return
        val chunks = streamChunks(StubHttpClient().apply { enqueue(200, streaming.body) })
        val tokens = chunks.filterIsInstance<StreamChunk.Token>().map { it.text }
        assertThat(tokens).isEqualTo(streaming.expectedTokens)
    }

    @Test
    fun `successful stream ends with exactly one Complete after the tokens`() {
        val streaming = harness.streaming ?: return
        val chunks = streamChunks(StubHttpClient().apply { enqueue(200, streaming.body) })
        val completes = chunks.filterIsInstance<StreamChunk.Complete>()
        assertThat(completes).hasSize(1)
        assertThat(chunks.last()).isInstanceOf(StreamChunk.Complete::class.java)
        val lastTokenIndex = chunks.indexOfLast { it is StreamChunk.Token }
        assertThat(chunks.indexOfFirst { it is StreamChunk.Complete })
            .withFailMessage("Complete must occur after all token chunks")
            .isGreaterThan(lastTokenIndex)
    }

    @Test
    fun `stream Complete carries the concatenated text`() {
        val streaming = harness.streaming ?: return
        val chunks = streamChunks(StubHttpClient().apply { enqueue(200, streaming.body) })
        val complete = chunks.filterIsInstance<StreamChunk.Complete>().single()
        assertThat(complete.fullText).isEqualTo(streaming.expectedTokens.joinToString(""))
    }

    @Test
    fun `stream Complete carries the usage metrics reported by the protocol`() {
        val streaming = harness.streaming ?: return
        if (streaming.expectedInputTokens == null && streaming.expectedOutputTokens == null) return
        val chunks = streamChunks(StubHttpClient().apply { enqueue(200, streaming.body) })
        val complete = chunks.filterIsInstance<StreamChunk.Complete>().single()
        streaming.expectedInputTokens?.let { expected ->
            assertThat(complete.usage?.inputTokens)
                .withFailMessage("stream Complete must carry the protocol-reported input tokens")
                .isEqualTo(expected)
        }
        streaming.expectedOutputTokens?.let { expected ->
            assertThat(complete.usage?.outputTokens)
                .withFailMessage("stream Complete must carry the protocol-reported output tokens")
                .isEqualTo(expected)
        }
    }

    @Test
    fun `stream transport failure emits a terminal Error chunk`() {
        val streaming = harness.streaming ?: return
        val stub = StubHttpClient().apply {
            enqueueBodyThatFailsOnRead(IOException("stream reset by peer"))
        }
        val chunks = streamChunks(stub)
        val error = chunks.filterIsInstance<StreamChunk.Error>()
        assertThat(error).hasSize(1)
        assertThat(chunks.last()).isInstanceOf(StreamChunk.Error::class.java)
        assertThat(error.single().cause).isInstanceOf(ProviderException::class.java)
        assertSafeMessage(error.single().cause, listOf("stream reset by peer"))
    }

    @Test
    fun `malformed stream terminates deterministically with exactly one Error and no Complete`() {
        val streaming = harness.streaming ?: return
        val stub = StubHttpClient().apply { enqueue(200, streaming.malformedBody) }
        val chunks = streamChunks(stub)
        val error = chunks.filterIsInstance<StreamChunk.Error>()
        // A malformed fixture is a contract violation, not a silent success:
        // exactly one terminal Error, no Complete, no chunks after the Error.
        assertThat(error)
            .withFailMessage(
                "malformed stream must produce exactly one Error chunk, was ${error.size}. " +
                    "If this protocol legitimately treats EOF as successful termination, " +
                    "model that as a named provider deviation — not a generic loophole.",
            )
            .hasSize(1)
        assertThat(chunks.last()).isInstanceOf(StreamChunk.Error::class.java)
        assertThat(chunks.filterIsInstance<StreamChunk.Complete>())
            .withFailMessage("malformed stream must not end in a successful Complete")
            .isEmpty()
        assertThat(error.single().cause).isInstanceOf(ProviderException::class.java)
        assertThat(stub.lastBodyClosed())
            .withFailMessage("malformed-stream termination must close the response body")
            .isTrue()
    }

    @Test
    fun `stream cancellation remains cancellation`() {
        val streaming = harness.streaming ?: return
        val stub = StubHttpClient().apply { enqueue(200, streaming.body) }
        val p = provider(stub) as StreamCapable
        runBlocking {
            // Deterministic: block the collector inside the first token callback,
            // cancel the job, then release the collector so the next emit observes
            // the cancellation.
            val gate = Channel<Unit>(1)
            val deferred = async(Dispatchers.Default) {
                p.stream(request()).collect { chunk ->
                    if (chunk is StreamChunk.Token) {
                        gate.trySend(Unit)
                        gate.receive()
                    }
                }
            }
            // Generous budget: under 16-module parallel test execution the
            // collector coroutine can be starved for seconds; the assertion is
            // about cancellation semantics, not speed.
            withTimeout(30_000) { gate.receive() } // collector is inside the first token
            deferred.cancel()
            gate.send(Unit) // release the collector
            val ex = runCatching { deferred.await() }.exceptionOrNull()
            assertThat(ex)
                .withFailMessage("parent cancellation must remain cancellation, was: $ex")
                .isInstanceOf(CancellationException::class.java)
            assertThat(stub.lastBodyClosed())
                .withFailMessage("cancelled stream must close the response body")
                .isTrue()
        }
    }

    @Test
    fun `stopping collection early closes the response resource`() {
        val streaming = harness.streaming ?: return
        val stub = StubHttpClient().apply { enqueue(200, streaming.body) }
        val p = provider(stub) as StreamCapable
        runBlocking { p.stream(request()).take(1).toList() }
        assertThat(stub.lastBodyClosed())
            .withFailMessage("early stream collection must close the response body")
            .isTrue()
    }

    @Test
    fun `normal stream completion closes the response resource`() {
        val streaming = harness.streaming ?: return
        val stub = StubHttpClient().apply { enqueue(200, streaming.body) }
        streamChunks(stub)
        assertThat(stub.lastBodyClosed())
            .withFailMessage("normal stream completion must close the response body")
            .isTrue()
    }

    // ── shared fixtures ────────────────────────────────────────────────

    protected fun toolDefinition(name: String = "get_weather") = ToolDefinition(
        name = name,
        description = "Get the current weather for a location",
        inputSchemaJson = """{"type":"object","properties":{"location":{"type":"string"}}}""",
    )

    companion object {
        protected val TOOL_DEFINITION = ToolDefinition(
            name = "get_weather",
            description = "Get the current weather for a location",
            inputSchemaJson = """{"type":"object","properties":{"location":{"type":"string"}}}""",
        )
    }
}

/**
 * Records every diagnostic event delivered by a provider under test.
 */
class RecordingProviderFailureDiagnosticObserver : ProviderFailureDiagnosticObserver {
    val events = mutableListOf<ProviderFailureDiagnosticEvent>()
    override fun record(event: ProviderFailureDiagnosticEvent) {
        events.add(event)
    }
}
