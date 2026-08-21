package dev.tramai.bedrock

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.UsageMetrics
import dev.tramai.core.observation.NoOpProviderFailureDiagnosticObserver
import dev.tramai.core.observation.ProviderFailureDiagnosticObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.StreamCapable
import dev.tramai.core.provider.providerTransportFailure
import dev.tramai.core.provider.providerTransportFailureObserved
import dev.tramai.core.provider.safeProviderFailure
import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ProviderFailureCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.async.SdkPublisher
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamRequest
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamResponse
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamResponseHandler
import software.amazon.awssdk.services.bedrockruntime.model.PayloadPart
import software.amazon.awssdk.services.bedrockruntime.model.ResponseStream
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Internal seam for the AWS Bedrock runtime client.
 *
 * Production uses the default factory (real [BedrockRuntimeAsyncClient] builder);
 * tests inject a recording fake. The AWS SDK types never enter Tramai's public
 * API — the factory is `internal` and the AWS client is closed by the provider
 * after every call.
 *
 * The async client is used for both operations because the sync
 * [software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient] in the
 * pinned SDK version (2.31.67) has no `invokeModelWithResponseStream` — real
 * incremental streaming is only available through the async surface.
 */
internal fun interface BedrockRuntimeClientFactory {
    fun create(): BedrockRuntimeAsyncClient
}

/**
 * [ModelProvider] implementation for Amazon Bedrock using the InvokeModel API.
 *
 * This implementation uses the raw JSON-based InvokeModel API, which sends
 * model-specific payloads and receives model-specific responses. The provider
 * handles the Claude (Anthropic) message format by default, translating Tramai's
 * unified message model into the expected request/response shapes.
 *
 * Authentication uses AWS Signature V4 via the default credentials provider chain
 * (environment variables, ~/.aws/credentials, IAM roles, etc.).
 *
 * Configuration:
 * - `tramai.providers.bedrock.region` — AWS region (e.g. "us-west-2")
 * - `tramai.providers.bedrock.model` — Model ID (e.g. "anthropic.claude-3-sonnet-20240229-v1:0")
 *
 * Supports: text, streaming, tools, vision (for multimodal models).
 * Structured output depends on the underlying model.
 */
class BedrockProvider @JvmOverloads constructor(
    private val region: String,
    private val modelId: String = DEFAULT_MODEL_ID,
    private val credentialsProvider: AwsCredentialsProvider = DefaultCredentialsProvider.builder().build(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
    private val providerFailureDiagnosticObserver: ProviderFailureDiagnosticObserver =
        NoOpProviderFailureDiagnosticObserver,
) : ModelProvider, StreamCapable {

    /**
     * Test seam constructor: injects a [BedrockRuntimeClientFactory] so tests can
     * substitute a recording fake client. Internal — the AWS SDK types never
     * enter the stable public API.
     */
    internal constructor(
        region: String,
        modelId: String = DEFAULT_MODEL_ID,
        credentialsProvider: AwsCredentialsProvider = DefaultCredentialsProvider.builder().build(),
        objectMapper: ObjectMapper = ObjectMapper(),
        ioDispatcher: CoroutineContext = Dispatchers.IO,
        providerFailureDiagnosticObserver: ProviderFailureDiagnosticObserver =
            NoOpProviderFailureDiagnosticObserver,
        bedrockRuntimeClientFactory: BedrockRuntimeClientFactory,
    ) : this(region, modelId, credentialsProvider, objectMapper, ioDispatcher, providerFailureDiagnosticObserver) {
        this.clientFactory = bedrockRuntimeClientFactory
    }

    private var clientFactory: BedrockRuntimeClientFactory = BedrockRuntimeClientFactory { buildDefaultClient() }

    override fun providerId(): String = PROVIDER_ID

    override fun supportsCapability(capability: ProviderCapability): Boolean = when (capability) {
        ProviderCapability.VISION -> true
        ProviderCapability.TOOL_CALLING -> true
        ProviderCapability.STRUCTURED_OUTPUT -> true
        ProviderCapability.STREAMING -> true
    }

    override suspend fun complete(request: ModelRequest): ModelResponse = withContext(ioDispatcher) {
        val client = clientFactory.create()
        try {
            val effectiveModel = request.model.takeIf { it.isNotBlank() } ?: modelId
            val payload = buildClaudePayload(request)

            val invokeRequest = InvokeModelRequest.builder()
                .modelId(effectiveModel)
                .body(SdkBytes.fromUtf8String(objectMapper.writeValueAsString(payload)))
                .contentType("application/json")
                .build()

            val response = client.invokeModel(invokeRequest).join()
            val body = objectMapper.readTree(response.body().asUtf8String())
            val mapped = mapClaudeResponse(body, effectiveModel)
            if (mapped.content.isBlank() && mapped.toolCalls.isNullOrEmpty()) {
                throw safeProviderFailure(
                    "Provider response did not contain any completion content",
                    ProviderFailureCode.UNEXPECTED_FAILURE,
                )
            }
            mapped
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw providerTransportFailureObserved(PROVIDER_ID, error, providerFailureDiagnosticObserver)
        } finally {
            client.close()
        }
    }

    override fun stream(request: ModelRequest): Flow<StreamChunk> = channelFlow {
        val effectiveModel = request.model.takeIf { it.isNotBlank() } ?: modelId
        val client = clientFactory.create()
        var failed = false
        try {
            val payload = buildClaudePayload(request)

            val invokeRequest = InvokeModelWithResponseStreamRequest.builder()
                .modelId(effectiveModel)
                .body(SdkBytes.fromUtf8String(objectMapper.writeValueAsString(payload)))
                .contentType("application/json")
                .build()

            val fullText = StringBuilder()
            var usage: UsageMetrics? = null

            val handler = object : InvokeModelWithResponseStreamResponseHandler {
                override fun onEventStream(stream: SdkPublisher<ResponseStream>) {
                    stream.subscribe(object : Subscriber<ResponseStream> {
                        override fun onSubscribe(subscription: Subscription) {
                            subscription.request(Long.MAX_VALUE)
                        }

                        override fun onNext(item: ResponseStream) {
                            if (item !is PayloadPart) return
                            try {
                                val node = objectMapper.readTree(item.bytes().asUtf8String())
                                when (node.path("type").asText("")) {
                                    "content_block_delta" -> {
                                        val delta = node.path("delta")
                                        if (delta.path("type").asText("") == "text_delta") {
                                            val text = delta.path("text").asText("")
                                            if (text.isNotEmpty()) {
                                                fullText.append(text)
                                                trySend(StreamChunk.Token(text))
                                            }
                                        }
                                    }
                                    "message_delta" -> {
                                        val usageNode = node.path("usage")
                                        if (!usageNode.isMissingNode) {
                                            usage = UsageMetrics(
                                                inputTokens = usageNode.path("input_tokens")
                                                    .takeIf { !it.isMissingNode }?.asInt(),
                                                outputTokens = usageNode.path("output_tokens")
                                                    .takeIf { !it.isMissingNode }?.asInt(),
                                            )
                                        }
                                    }
                                }
                            } catch (parseError: Exception) {
                                parseError.rethrowIfCancellation()
                                failed = true
                                trySend(StreamChunk.Error(providerTransportFailure(PROVIDER_ID, parseError)))
                            }
                        }

                        override fun onError(t: Throwable) {
                            t.rethrowIfCancellation()
                            failed = true
                            trySend(StreamChunk.Error(providerTransportFailure(PROVIDER_ID, t)))
                        }

                        override fun onComplete() {
                            // Stream finished; Complete is emitted once the SDK future resolves.
                        }
                    })
                }

                override fun responseReceived(response: InvokeModelWithResponseStreamResponse) {
                    // Response headers only; the stream arrives via onEventStream.
                }

                override fun exceptionOccurred(t: Throwable) {
                    t.rethrowIfCancellation()
                    failed = true
                    trySend(StreamChunk.Error(providerTransportFailure(PROVIDER_ID, t)))
                }

                override fun complete() {
                    // Stream finished; Complete is emitted once the SDK future resolves.
                }
            }

            withContext(ioDispatcher) {
                client.invokeModelWithResponseStream(invokeRequest, handler).join()
            }

            if (!failed) {
                send(StreamChunk.Complete(fullText.toString(), usage ?: UsageMetrics()))
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            if (!failed) {
                trySend(StreamChunk.Error(providerTransportFailureObserved(PROVIDER_ID, e, providerFailureDiagnosticObserver)))
            }
        } finally {
            withContext(ioDispatcher) { client.close() }
        }
    }

    // ---- Client construction ----

    private fun buildDefaultClient(): BedrockRuntimeAsyncClient {
        return BedrockRuntimeAsyncClient.builder()
            .region(Region.of(region))
            .credentialsProvider(credentialsProvider)
            .build()
    }

    // ---- Payload construction (Claude Messages format) ----

    private fun buildClaudePayload(request: ModelRequest): Map<String, Any?> {
        val payload = linkedMapOf<String, Any?>(
            "anthropic_version" to "bedrock-2023-05-31",
            "max_tokens" to (request.maxTokens ?: 1024),
            "messages" to request.messages
                .filter { it.role != MessageRole.SYSTEM }
                .map { message -> buildClaudeMessage(message) },
        )

        request.temperature?.let { payload["temperature"] = it }

        // System prompt
        val systemMessage = request.messages.firstOrNull { it.role == MessageRole.SYSTEM }?.content
        if (!systemMessage.isNullOrBlank()) {
            payload["system"] = systemMessage
        }

        // Tool definitions (Claude tool format)
        request.tools?.let { tools ->
            payload["tools"] = tools.map { tool ->
                mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "input_schema" to objectMapper.readTree(tool.inputSchemaJson),
                )
            }
        }

        return payload
    }

    @Suppress("UNCHECKED_CAST")
    internal fun buildClaudeMessage(message: dev.tramai.core.model.Message): Map<String, Any?> {
        val role = when (message.role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.TOOL -> "user"
            MessageRole.SYSTEM -> "user"
        }

        val contentBlocks = mutableListOf<Map<String, Any?>>()

        // Handle tool calls (assistant role)
        message.toolCalls?.let { toolCalls ->
            for (tc in toolCalls) {
                val argsNode = objectMapper.readTree(tc.argumentsJson)
                contentBlocks.add(
                    mapOf(
                        "type" to "tool_use",
                        "id" to tc.id,
                        "name" to tc.name,
                        "input" to argsNode,
                    )
                )
            }
        }

        // Handle tool result
        if (message.role == MessageRole.TOOL) {
            contentBlocks.add(
                mapOf(
                    "type" to "tool_result",
                    "tool_use_id" to (message.toolCallId ?: "unknown"),
                    "content" to message.content,
                )
            )
        }

        // Handle regular content
        val msgParts = message.contentParts
        if (!msgParts.isNullOrEmpty()) {
            for (part in msgParts) {
                when (part) {
                    is ContentPart.TextPart -> {
                        contentBlocks.add(mapOf("type" to "text", "text" to part.text))
                    }
                    is ContentPart.ImagePart -> {
                        require(part.mimeType in SUPPORTED_IMAGE_TYPES) {
                            "Unsupported image mimeType '${part.mimeType}'. Supported types: $SUPPORTED_IMAGE_TYPES"
                        }
                        contentBlocks.add(
                            mapOf(
                                "type" to "image",
                                "source" to mapOf(
                                    "type" to "base64",
                                    "media_type" to part.mimeType,
                                    "data" to Base64.getEncoder().encodeToString(part.data),
                                ),
                            )
                        )
                    }
                    is ContentPart.ImageUrlContent -> {
                        val resolved = dev.tramai.core.util.ImageDownloader.resolveToImagePart(part) as ContentPart.ImagePart
                        require(resolved.mimeType in SUPPORTED_IMAGE_TYPES) {
                            "Unsupported image mimeType '${resolved.mimeType}'. Supported types: $SUPPORTED_IMAGE_TYPES"
                        }
                        contentBlocks.add(
                            mapOf(
                                "type" to "image",
                                "source" to mapOf(
                                    "type" to "base64",
                                    "media_type" to resolved.mimeType,
                                    "data" to Base64.getEncoder().encodeToString(resolved.data),
                                ),
                            )
                        )
                    }
                }
            }
        } else if (message.content.isNotBlank() && contentBlocks.isEmpty()) {
            contentBlocks.add(mapOf("type" to "text", "text" to message.content))
        }

        return mapOf(
            "role" to role,
            "content" to contentBlocks,
        )
    }

    // ---- Response mapping (Claude format) ----

    private fun mapClaudeResponse(body: JsonNode, effectiveModel: String): ModelResponse {
        val contentArray = body.path("content")
        val text = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        var index = 0

        if (contentArray.isArray) {
            for (block in contentArray) {
                val type = block.path("type").asText("")
                when (type) {
                    "text" -> text.append(block.path("text").asText(""))
                    "tool_use" -> {
                        val name = block.path("name").asText("")
                        val inputNode = block.path("input")
                        toolCalls.add(
                            ToolCall(
                                id = block.path("id").asText("tc_${name}_$index"),
                                name = name,
                                argumentsJson = objectMapper.writeValueAsString(inputNode),
                            )
                        )
                        index++
                    }
                }
            }
        }

        val stopReason = body.path("stop_reason").asText("")
        val usage = body.path("usage")

        return ModelResponse(
            content = text.toString(),
            toolCalls = toolCalls.ifEmpty { null },
            inputTokens = usage.path("input_tokens").takeIf { !it.isMissingNode }?.asInt(),
            outputTokens = usage.path("output_tokens").takeIf { !it.isMissingNode }?.asInt(),
            modelUsed = effectiveModel,
            finishReason = when (stopReason) {
                "end_turn" -> FinishReason.STOP
                "max_tokens" -> FinishReason.LENGTH
                "tool_use" -> FinishReason.STOP
                "content_filtered" -> FinishReason.CONTENT_FILTER
                "stop_sequence" -> FinishReason.STOP
                else -> FinishReason.OTHER
            },
        )
    }

    companion object {
        private const val PROVIDER_ID = "bedrock"

        const val DEFAULT_MODEL_ID: String = "anthropic.claude-3-sonnet-20240229-v1:0"

        val SUPPORTED_IMAGE_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }
}
