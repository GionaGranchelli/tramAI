package dev.tramai.bedrock

import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.ToolCall
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.StreamCapable
import dev.tramai.testing.provider.ProviderHttpFixtures
import dev.tramai.testing.provider.ProviderTck
import dev.tramai.testing.provider.ProviderTckHarness
import dev.tramai.testing.provider.ProviderTransport
import dev.tramai.testing.provider.StubHttpClient
import dev.tramai.testing.provider.StreamingSpec
import dev.tramai.testing.provider.StructuredOutputSpec
import dev.tramai.testing.provider.ToolSpec
import dev.tramai.testing.provider.UsageSpec
import dev.tramai.testing.provider.VisionSpec
import org.assertj.core.api.Assertions.assertThat
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.async.SdkPublisher
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamRequest
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamResponseHandler
import software.amazon.awssdk.services.bedrockruntime.model.PayloadPart
import software.amazon.awssdk.services.bedrockruntime.model.ResponseStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/**
 * Epic 6.1 TCK runner for [BedrockProvider], driven through the SDK transport.
 *
 * The wire-level HTTP assertions (timeouts, statuses, Retry-After, bounded
 * diagnostics) are skipped by contract for `ProviderTransport.SDK`; the runner
 * instead drives the provider through the internal
 * [BedrockRuntimeClientFactory] seam with a recording fake client.
 *
 * The StubHttpClient still acts as the fixture carrier: [createProvider] probes
 * the stub's canned response (consuming and closing it, which satisfies the
 * TCK's body-closure assertions), re-arms a dummy response, and programs the
 * fake client to return the probed fixture body. The fake additionally mirrors
 * the provider's outbound payload into the stub so the TCK's wire-observation
 * assertions (tool markers, image base64) see the real request body.
 *
 * Expected capability matrix is pinned here, not read from the provider:
 * VISION + TOOL_CALLING + STRUCTURED_OUTPUT + STREAMING (real incremental
 * streaming via `invokeModelWithResponseStream`).
 */
class BedrockProviderTckTest : ProviderTck() {

    private val createdClients = mutableListOf<FakeBedrockRuntimeClient>()

    override val harness = ProviderTckHarness(
        expectedProviderId = "bedrock",
        expectedCapabilities = setOf(
            ProviderCapability.VISION,
            ProviderCapability.TOOL_CALLING,
            ProviderCapability.STRUCTURED_OUTPUT,
            ProviderCapability.STREAMING,
        ),
        createProvider = { stub -> createProvider(stub) },
        transport = ProviderTransport.SDK,
        modelName = "tck-model",
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

    // ── seam wiring ────────────────────────────────────────────────────

    private fun createProvider(stub: StubHttpClient, script: StreamScript? = null): BedrockProvider {
        val programmed = probeStub(stub)
        if (programmed.failure == null) {
            // Re-arm a canned response so the fake's outbound mirror send
            // (which feeds the TCK's wire observations) has something to consume.
            stub.enqueue(200, "{}")
        }
        val client = FakeBedrockRuntimeClient(programmed, stub, script)
        createdClients += client
        return BedrockProvider(
            region = "us-east-1",
            modelId = "tck-model",
            bedrockRuntimeClientFactory = BedrockRuntimeClientFactory { client },
        )
    }

    /**
     * Reads the test's canned body out of the stub and closes it (satisfying
     * the TCK's body-closure assertions). A queued transport failure or
     * fails-on-read body surfaces as an [IOException] the fake client is
     * programmed to reproduce from both invoke entry points.
     */
    private fun probeStub(stub: StubHttpClient): ProgrammedResponse = try {
        val probeRequest = HttpRequest.newBuilder(PROBE_URI).GET().build()
        val response = stub.send(probeRequest, HttpResponse.BodyHandlers.ofInputStream())
        val text = response.body().use { it.readAllBytes().decodeToString() }
        ProgrammedResponse(body = text, failure = null)
    } catch (e: IOException) {
        ProgrammedResponse(body = null, failure = e)
    } catch (e: RuntimeException) {
        // Nothing queued (not expected in the TCK) — behave like an empty body.
        ProgrammedResponse(body = "", failure = null)
    }

    // ── seam-ownership assertions (review finding: client closure) ─────

    @Test
    fun `completion closes the factory-created client`() {
        val stub = StubHttpClient().apply { enqueue(200, h.happyPathBody) }
        complete(stub)
        assertThat(createdClients.last().closed).isTrue()
    }

    @Test
    fun `stream closes the factory-created client`() {
        val streaming = h.streaming ?: return
        val chunks = streamChunks(StubHttpClient().apply { enqueue(200, streaming.body) })
        assertThat(createdClients.last().closed).isTrue()
        assertThat(chunks.last()).isInstanceOf(StreamChunk.Complete::class.java)
    }

    // ── terminal-state machine (review findings P1-3 / P1-4 / P2-1) ────

    @Test
    fun `parse error followed by SDK onError emits exactly one terminal Error`() {
        val failure = IOException("sdk stream failed after parse error")
        val script = StreamScript(
            parts = listOf(malformedPart()),
            subscriberOnError = failure,
            future = CompletableFuture.failedFuture<Void>(failure),
        )
        val chunks = collectScripted(script)
        val errors = chunks.filterIsInstance<StreamChunk.Error>()
        assertThat(errors).hasSize(1)
        assertThat(chunks.last()).isInstanceOf(StreamChunk.Error::class.java)
        assertThat(chunks.filterIsInstance<StreamChunk.Complete>()).isEmpty()
        assertThat(chunks.filterIsInstance<StreamChunk.Token>()).isEmpty()
    }

    @Test
    fun `payload delivered after a parse error emits no tokens and cancels the subscription`() {
        val script = StreamScript(parts = listOf(malformedPart(), tokenPart("late")))
        val chunks = collectScripted(script)
        assertThat(chunks.filterIsInstance<StreamChunk.Error>()).hasSize(1)
        assertThat(chunks.filterIsInstance<StreamChunk.Token>()).isEmpty()
        assertThat(chunks.filterIsInstance<StreamChunk.Complete>()).isEmpty()
        assertThat(script.subscriptionCancelled).isTrue()
    }

    @Test
    fun `exceptionOccurred followed by subscriber onError emits exactly one terminal Error`() {
        val failure = IOException("sdk stream failed")
        val script = StreamScript(
            exceptionOccurred = failure,
            subscriberOnError = failure,
            future = CompletableFuture.failedFuture<Void>(failure),
        )
        val chunks = collectScripted(script)
        val errors = chunks.filterIsInstance<StreamChunk.Error>()
        assertThat(errors).hasSize(1)
        assertThat(chunks.last()).isInstanceOf(StreamChunk.Error::class.java)
        assertThat(chunks.filterIsInstance<StreamChunk.Complete>()).isEmpty()
        assertThat(chunks.filterIsInstance<StreamChunk.Token>()).isEmpty()
    }

    @Test
    fun `cancelling the collector cancels the in-flight invoke future and closes the client`() {
        val future = CompletableFuture<Void>()
        val script = StreamScript(parts = listOf(tokenPart("late")), future = future)
        val p = createProvider(StubHttpClient(), script) as StreamCapable
        runBlocking {
            val deferred = async(Dispatchers.Default) { p.stream(request()).toList() }
            // Deterministic: the provider registers whenComplete on the invoke
            // future before suspending — wait for that, then cancel.
            withTimeout(5_000) { while (future.getNumberOfDependents() == 0) delay(1) }
            deferred.cancel()
            val ex = runCatching { deferred.await() }.exceptionOrNull()
            assertThat(ex)
                .withFailMessage("cancellation must remain cancellation, was: $ex")
                .isInstanceOf(CancellationException::class.java)
            assertThat(future.isCancelled).isTrue()
            assertThat(createdClients.last().closed).isTrue()
        }
    }

    private fun collectScripted(script: StreamScript): List<StreamChunk> =
        runBlocking { (createProvider(StubHttpClient(), script) as StreamCapable).stream(request()).toList() }

    // ── test doubles ───────────────────────────────────────────────────

    private data class ProgrammedResponse(
        val body: String?,
        val failure: IOException?,
    )

    /**
     * Scripted streaming scenario for mutation-sensitive terminal-state tests.
     * Drives the exact callback sequences a real SDK would produce and exposes
     * the invoke future so tests can assert cancellation propagation. A real
     * [BedrockRuntimeAsyncClient] reports failures through an exceptionally
     * completed [CompletableFuture] — never by synchronously throwing.
     */
    private class StreamScript(
        val parts: List<PayloadPart> = emptyList(),
        val exceptionOccurred: Throwable? = null,
        val subscriberOnError: Throwable? = null,
        val future: CompletableFuture<Void> = CompletableFuture.completedFuture(null),
    ) {
        @Volatile
        var subscriptionCancelled: Boolean = false
            private set

        fun recordSubscriptionCancellation() {
            subscriptionCancelled = true
        }
    }

    /**
     * Recording fake over the AWS [BedrockRuntimeAsyncClient] interface
     * (interface in the SDK — only [serviceName] and [close] are abstract).
     */
    private class FakeBedrockRuntimeClient(
        private val programmed: ProgrammedResponse,
        private val stub: StubHttpClient,
        private val streamScript: StreamScript? = null,
    ) : BedrockRuntimeAsyncClient {

        @Volatile
        var closed: Boolean = false
            private set

        @Volatile
        var lastInvokeRequest: InvokeModelRequest? = null
            private set

        @Volatile
        var lastStreamRequest: InvokeModelWithResponseStreamRequest? = null
            private set

        override fun invokeModel(request: InvokeModelRequest): CompletableFuture<InvokeModelResponse> {
            lastInvokeRequest = request
            programmed.failure?.let { return CompletableFuture.failedFuture<InvokeModelResponse>(it) }
            mirrorOutboundPayload(request.body().asUtf8String())
            return CompletableFuture.completedFuture(
                InvokeModelResponse.builder()
                    .body(SdkBytes.fromUtf8String(programmed.body ?: ""))
                    .contentType("application/json")
                    .build(),
            )
        }

        override fun invokeModelWithResponseStream(
            request: InvokeModelWithResponseStreamRequest,
            handler: InvokeModelWithResponseStreamResponseHandler,
        ): CompletableFuture<Void> {
            lastStreamRequest = request
            programmed.failure?.let { return CompletableFuture.failedFuture<Void>(it) }
            streamScript?.let { script ->
                handler.onEventStream(
                    ScriptedPublisher(script, handler),
                )
                return script.future
            }
            handler.onEventStream(
                SynchronousPublisher(programmed.body?.let { ssePayloadParts(it) } ?: emptyList()),
            )
            handler.complete()
            return CompletableFuture.completedFuture<Void>(null)
        }

        override fun close() {
            closed = true
        }

        override fun serviceName(): String = "tck-bedrock-runtime"

        /** Feeds the provider's outbound payload into the stub for TCK wire observation. */
        private fun mirrorOutboundPayload(body: String) {
            val mirror = HttpRequest.newBuilder(PROBE_URI)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            try {
                stub.send(mirror, HttpResponse.BodyHandlers.ofInputStream()).body().close()
            } catch (e: IOException) {
                // Mirror is only for wire observation; the result comes from `programmed`.
            }
        }

        /** Splits an SSE fixture body into one PayloadPart per `data:` event. */
        private fun ssePayloadParts(body: String): List<PayloadPart> =
            body.split("\n\n").mapNotNull { event ->
                val data = event.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("data:") }
                    ?: return@mapNotNull null
                PayloadPart.builder()
                    .bytes(SdkBytes.fromUtf8String(data.removePrefix("data:").trim()))
                    .build()
            }
    }

    /** Publisher that delivers all parts synchronously on subscribe (deterministic). */
    private class SynchronousPublisher(
        private val parts: List<PayloadPart>,
    ) : SdkPublisher<ResponseStream> {
        override fun subscribe(subscriber: Subscriber<in ResponseStream>) {
            subscriber.onSubscribe(object : Subscription {
                override fun request(n: Long) {
                    parts.forEach { subscriber.onNext(it) }
                    subscriber.onComplete()
                }

                override fun cancel() {
                    // Ignored: the payload list is already materialized.
                }
            })
        }
    }

    /**
     * Scripted publisher: replays the script's parts, then fires the scripted
     * handler/subscriber failure sequence exactly as the real SDK does
     * (exceptionOccurred → subscriber.onError; never onComplete after an
     * error). Records cancellation so tests can assert the provider cancelled
     * the subscription on its terminal Error.
     */
    private class ScriptedPublisher(
        private val script: StreamScript,
        private val handler: InvokeModelWithResponseStreamResponseHandler,
    ) : SdkPublisher<ResponseStream> {
        override fun subscribe(subscriber: Subscriber<in ResponseStream>) {
            subscriber.onSubscribe(object : Subscription {
                override fun request(n: Long) {
                    script.parts.forEach { subscriber.onNext(it) }
                    when {
                        script.exceptionOccurred != null -> {
                            handler.exceptionOccurred(script.exceptionOccurred)
                            script.subscriberOnError?.let { subscriber.onError(it) }
                        }
                        script.subscriberOnError != null -> subscriber.onError(script.subscriberOnError)
                        else -> {
                            subscriber.onComplete()
                            handler.complete()
                        }
                    }
                }

                override fun cancel() {
                    script.recordSubscriptionCancellation()
                }
            })
        }
    }

    companion object {
        private val TCK_TOOL_CALL = ToolCall(
            id = "call_tck_1",
            name = "get_weather",
            argumentsJson = """{"location":"Amsterdam"}""",
        )

        private val PROBE_URI = URI.create("https://bedrock-runtime.tck.invalid/invoke")

        private fun payloadPart(json: String): PayloadPart =
            PayloadPart.builder().bytes(SdkBytes.fromUtf8String(json)).build()

        private fun tokenPart(text: String): PayloadPart =
            payloadPart("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}""")

        private fun malformedPart(): PayloadPart =
            payloadPart("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel""")
    }
}
