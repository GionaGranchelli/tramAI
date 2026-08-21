package dev.tramai.anthropic

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.exception.ProviderFailureCode
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
import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.provider.applyTramaiTimeout
import dev.tramai.core.provider.logProviderHttpFailureDebug
import dev.tramai.core.provider.providerHttpFailureObserved
import dev.tramai.core.provider.providerTransportFailureObserved
import dev.tramai.core.provider.readErrorBodyPreview
import dev.tramai.core.provider.safeProviderFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import java.net.URI
import java.io.InputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.nio.charset.StandardCharsets.UTF_8

/**
 * [ModelProvider] implementation for Anthropic's Messages API.
 */
class AnthropicProvider @JvmOverloads constructor(
    private val apiKey: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val anthropicVersion: String = ANTHROPIC_API_VERSION,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
    private val providerFailureDiagnosticObserver: ProviderFailureDiagnosticObserver =
        NoOpProviderFailureDiagnosticObserver,
) : dev.tramai.core.provider.ModelProvider, dev.tramai.core.provider.StreamCapable {

    override suspend fun complete(request: ModelRequest): ModelResponse = withContext(ioDispatcher) {
        try {
            val payload = linkedMapOf<String, Any?>(
                "model" to request.model,
                "max_tokens" to (request.maxTokens ?: 1024),
                "messages" to request.messages
                    // Anthropic accepts the system prompt separately from the conversational message list.
                    .filter { it.role.name.lowercase() != "system" }
                    .map { message -> messageToMap(message) },
            )

            val systemMessage = request.messages.firstOrNull { it.role.name.lowercase() == "system" }?.content
            if (!systemMessage.isNullOrBlank()) {
                payload["system"] = systemMessage
            }

            request.tools?.let { tools ->
                payload["tools"] = tools.map { tool ->
                    mapOf(
                        "name" to tool.name,
                        "description" to tool.description,
                        "input_schema" to objectMapper.readTree(tool.inputSchemaJson),
                    )
                }
            }

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", anthropicVersion)
                .applyTramaiTimeout(request)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                val errorBody = readErrorBodyPreview(response.body())
                logProviderHttpFailureDebug(
                    logger = providerLogger,
                    providerName = PROVIDER_ID,
                    statusCode = response.statusCode(),
                    body = errorBody.text,
                )
                throw providerHttpFailureObserved(
                    providerId = PROVIDER_ID,
                    statusCode = response.statusCode(),
                    body = errorBody.text,
                    bodyTruncated = errorBody.truncated,
                    retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null),
                    observer = providerFailureDiagnosticObserver,
                )
            }

            val body = objectMapper.readTree(response.body().use { it.readAllBytes().decodeToString() })
            val contentBlocks = body.path("content")
            val textBlocks = contentBlocks.filter { node -> node.path("type").asText("") == "text" }
            val toolCalls = contentBlocks
                .filter { node -> node.path("type").asText("") == "tool_use" }
                .map { node ->
                    ToolCall(
                        id = node.path("id").asText(),
                        name = node.path("name").asText(),
                        argumentsJson = node.path("input").toString(),
                    )
                }
            if (textBlocks.isEmpty() && toolCalls.isEmpty()) {
                throw safeProviderFailure(
                    "Anthropic response did not contain a text or tool_use content block",
                    ProviderFailureCode.UNEXPECTED_FAILURE,
                )
            }

            ModelResponse(
                content = textBlocks.joinToString("") { it.path("text").asText("") },
                toolCalls = toolCalls.ifEmpty { null },
                inputTokens = body.path("usage").path("input_tokens").takeIf { !it.isMissingNode }?.asInt(),
                outputTokens = body.path("usage").path("output_tokens").takeIf { !it.isMissingNode }?.asInt(),
                thinkingTokens = null,
                modelUsed = body.path("model").takeIf { !it.isMissingNode }?.asText(),
                finishReason = when (body.path("stop_reason").asText("")) {
                    "end_turn" -> FinishReason.STOP
                    "max_tokens" -> FinishReason.LENGTH
                    else -> FinishReason.OTHER
                },
            )
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw providerTransportFailureObserved(PROVIDER_ID, error, providerFailureDiagnosticObserver)
        }
    }

    /**
     * Returns the stable provider id used by the registry.
     */
    override fun providerId(): String = PROVIDER_ID

    override fun supportsCapability(capability: ProviderCapability): Boolean = when (capability) {
        ProviderCapability.VISION -> true
        ProviderCapability.TOOL_CALLING -> true
        ProviderCapability.STRUCTURED_OUTPUT -> true
        ProviderCapability.STREAMING -> true
    }

    override fun stream(request: ModelRequest): Flow<StreamChunk> = flow {
        val response = try {
            withContext(ioDispatcher) {
                val payload = linkedMapOf<String, Any?>(
                    "model" to request.model,
                    "stream" to true,
                    "max_tokens" to (request.maxTokens ?: 1024),
                    "messages" to request.messages
                        .filter { it.role.name.lowercase() != "system" }
                        .map { message -> messageToMap(message) },
                )
                val systemMessage = request.messages.firstOrNull { it.role.name.lowercase() == "system" }?.content
                if (!systemMessage.isNullOrBlank()) payload["system"] = systemMessage

                request.tools?.let { tools ->
                    payload["tools"] = tools.map { tool ->
                        mapOf(
                            "name" to tool.name,
                            "description" to tool.description,
                            "input_schema" to objectMapper.readTree(tool.inputSchemaJson),
                        )
                    }
                }

                val httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create("${baseUrl.trimEnd('/')}/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", anthropicVersion)
                    .applyTramaiTimeout(request)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build()
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
            }
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            emit(StreamChunk.Error(providerTransportFailureObserved(PROVIDER_ID, error, providerFailureDiagnosticObserver)))
            return@flow
        }

        val errorChunk = handleHttpError(response)
        if (errorChunk != null) {
            emit(errorChunk)
            return@flow
        }

        parseAnthropicStreamResponse(response)
    }

    /**
     * Handles non-2xx HTTP responses for streaming requests.
     * Returns a [StreamChunk.Error] for failed responses, or `null` for 2xx.
     */
    private suspend fun handleHttpError(
        response: HttpResponse<InputStream>,
    ): StreamChunk.Error? {
        if (response.statusCode() in 200..299) return null
        val errorBody = try {
            readErrorBodyPreview(response.body())
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            return StreamChunk.Error(
                providerTransportFailureObserved(PROVIDER_ID, error, providerFailureDiagnosticObserver),
            )
        }
        logProviderHttpFailureDebug(
            logger = providerLogger,
            providerName = PROVIDER_ID,
            statusCode = response.statusCode(),
            body = errorBody.text,
        )
        return StreamChunk.Error(
            providerHttpFailureObserved(
                providerId = PROVIDER_ID,
                statusCode = response.statusCode(),
                body = errorBody.text,
                bodyTruncated = errorBody.truncated,
                retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null),
                observer = providerFailureDiagnosticObserver,
            ),
        )
    }

    /**
     * Parses the Anthropic SSE stream response, handling Anthropic-specific events:
     * - `content_block_delta` for text tokens
     * - `message_start` for initial usage metrics
     * - `message_delta` for output token updates
     */
    private suspend fun FlowCollector<StreamChunk>.parseAnthropicStreamResponse(
        response: HttpResponse<InputStream>,
    ) {
        val fullText = StringBuilder()
        var lastUsage: UsageMetrics? = null

        try {
            response.body().bufferedReader(UTF_8).use { reader ->
                var currentEvent: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("event: ")) {
                        currentEvent = line.substring(7).trim()
                    } else if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        val node = objectMapper.readTree(data)
                        lastUsage = handleAnthropicEvent(currentEvent, node, fullText, lastUsage)
                    }
                }
            }
            emit(StreamChunk.Complete(fullText.toString(), lastUsage ?: UsageMetrics()))
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            emit(StreamChunk.Error(providerTransportFailureObserved(PROVIDER_ID, e, providerFailureDiagnosticObserver)))
        }
    }

    /**
     * Handles a single Anthropic SSE event (content_block_delta, message_start, message_delta)
     * and returns the updated [UsageMetrics].
     */
    private suspend fun FlowCollector<StreamChunk>.handleAnthropicEvent(
        currentEvent: String?,
        node: com.fasterxml.jackson.databind.JsonNode,
        fullText: StringBuilder,
        lastUsage: UsageMetrics?,
    ): UsageMetrics? {
        when (currentEvent) {
            "content_block_delta" -> {
                val text = node.path("delta").path("text").asText("")
                if (text.isNotEmpty()) {
                    fullText.append(text)
                    emit(StreamChunk.Token(text))
                }
            }
            "message_start" -> {
                val usage = node.path("message").path("usage")
                if (!usage.isMissingNode) {
                    return UsageMetrics(
                        inputTokens = usage.path("input_tokens").asInt(),
                        outputTokens = usage.path("output_tokens").asInt(),
                        thinkingTokens = null,
                    )
                }
            }
            "message_delta" -> {
                val usage = node.path("usage")
                if (!usage.isMissingNode) {
                    return UsageMetrics(
                        inputTokens = lastUsage?.inputTokens,
                        outputTokens = usage.path("output_tokens").asInt(),
                        thinkingTokens = null,
                    )
                }
            }
        }
        return lastUsage
    }

    /**
     * Converts a Tramai [dev.tramai.core.model.Message] to an Anthropic-compatible request map.
     *
     * Three shapes are produced:
     * - Plain text (and image-carrying) messages keep the existing string/block content.
     * - Assistant messages carrying [dev.tramai.core.model.ToolCall]s become a content array
     *   of a text block (when non-blank) plus one `tool_use` block per call.
     * - [MessageRole.TOOL] results become a `user`-role message with a `tool_result` block.
     */
    private fun messageToMap(message: dev.tramai.core.model.Message): Map<String, Any?> {
        val role = when (message.role) {
            MessageRole.TOOL -> "user" // Anthropic requires tool results in user-role messages
            else -> message.role.name.lowercase()
        }
        val content: Any = when {
            message.role == MessageRole.TOOL -> {
                val toolCallId = requireNotNull(message.toolCallId) {
                    "TOOL message must carry a toolCallId to map to an Anthropic tool_result block"
                }
                // Rich tool results carry their payload in contentParts (the engine
                // empties content for those); a text-only result stays a plain string.
                // Anthropic accepts tool_result.content as a string or a block array.
                val toolParts = message.contentParts
                val resultContent =
                    if (!toolParts.isNullOrEmpty()) {
                        toolParts.map(::anthropicContentPart)
                    } else {
                        message.content
                    }
                listOf(
                    mapOf(
                        "type" to "tool_result",
                        "tool_use_id" to toolCallId,
                        "content" to resultContent,
                    ),
                )
            }
            message.role == MessageRole.ASSISTANT && !message.toolCalls.isNullOrEmpty() -> {
                val toolCalls = message.toolCalls.orEmpty()
                buildList {
                    if (message.content.isNotBlank()) {
                        add(mapOf("type" to "text", "text" to message.content))
                    }
                    toolCalls.forEach { call ->
                        add(
                            mapOf(
                                "type" to "tool_use",
                                "id" to call.id,
                                "name" to call.name,
                                "input" to objectMapper.readTree(call.argumentsJson),
                            ),
                        )
                    }
                }
            }
            else -> {
                val msgParts = message.contentParts
                if (!msgParts.isNullOrEmpty()) {
                    msgParts.map(::anthropicContentPart)
                } else {
                    message.content
                }
            }
        }

        return mapOf(
            "role" to role,
            "content" to content,
        )
    }

    /** Converts one TramAI content part to an Anthropic content block (text or image). */
    private fun anthropicContentPart(part: ContentPart): Map<String, Any?> = when (part) {
        is ContentPart.TextPart -> mapOf(
            "type" to "text",
            "text" to part.text,
        )
        is ContentPart.ImagePart -> {
            require(part.mimeType in SUPPORTED_IMAGE_TYPES) {
                "Unsupported image mimeType '${part.mimeType}'. Supported types: $SUPPORTED_IMAGE_TYPES"
            }
            mapOf(
                "type" to "image",
                "source" to mapOf(
                    "type" to "base64",
                    "media_type" to part.mimeType,
                    "data" to Base64.getEncoder().encodeToString(part.data),
                ),
            )
        }
        is ContentPart.ImageUrlContent -> {
            val resolved = dev.tramai.core.util.ImageDownloader.resolveToImagePart(part) as ContentPart.ImagePart
            require(resolved.mimeType in SUPPORTED_IMAGE_TYPES) {
                "Unsupported image mimeType '${resolved.mimeType}'. Supported types: $SUPPORTED_IMAGE_TYPES"
            }
            mapOf(
                "type" to "image",
                "source" to mapOf(
                    "type" to "base64",
                    "media_type" to resolved.mimeType,
                    "data" to Base64.getEncoder().encodeToString(resolved.data),
                ),
            )
        }
    }

    private companion object {
        private val providerLogger: System.Logger = System.getLogger(AnthropicProvider::class.java.name)

        val SUPPORTED_IMAGE_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

        const val ANTHROPIC_API_VERSION: String = "2023-06-01"
        const val PROVIDER_ID: String = "anthropic"
    }
}
