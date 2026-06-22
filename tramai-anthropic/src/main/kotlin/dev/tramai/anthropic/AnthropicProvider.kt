package dev.tramai.anthropic

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.UsageMetrics
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.applyTramaiTimeout
import dev.tramai.core.provider.logProviderHttpFailureDebug
import dev.tramai.core.provider.providerHttpFailure
import dev.tramai.core.provider.providerTransportFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.stream.Stream

/**
 * [ModelProvider] implementation for Anthropic's Messages API.
 */
class AnthropicProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val anthropicVersion: String = ANTHROPIC_API_VERSION,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
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

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", anthropicVersion)
                .applyTramaiTimeout(request)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logProviderHttpFailureDebug(
                    logger = providerLogger,
                    providerName = "Anthropic",
                    statusCode = response.statusCode(),
                    body = response.body(),
                )
                throw providerHttpFailure(
                    providerName = "Anthropic",
                    statusCode = response.statusCode(),
                    body = response.body(),
                    retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null),
                )
            }

            val body = objectMapper.readTree(response.body())
            val firstTextBlock = body.path("content")
                .firstOrNull { node -> node.path("type").asText("") == "text" }
                ?: throw ProviderException("Anthropic response did not contain a text content block")

            ModelResponse(
                content = firstTextBlock.path("text").asText(""),
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
            throw providerTransportFailure("Anthropic", error)
        }
    }

    /**
     * Returns the stable provider id used by the registry.
     */
    override fun providerId(): String = "anthropic"

    override fun supportsCapability(capability: ProviderCapability): Boolean = when (capability) {
        ProviderCapability.VISION -> true
        ProviderCapability.TOOL_CALLING -> true
        ProviderCapability.STRUCTURED_OUTPUT -> true
        ProviderCapability.STREAMING -> true
    }

    override fun stream(request: ModelRequest): Flow<StreamChunk> = flow {
        val payload = linkedMapOf<String, Any?>(
            "model" to request.model,
            "stream" to true,
            "max_tokens" to (request.maxTokens ?: 1024),
            "messages" to request.messages
                .filter { it.role.name.lowercase() != "system" }
                .map { message -> messageToMap(message) },
        )

        val systemMessage = request.messages.firstOrNull { it.role.name.lowercase() == "system" }?.content
        if (!systemMessage.isNullOrBlank()) {
            payload["system"] = systemMessage
        }

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", anthropicVersion)
            .applyTramaiTimeout(request)
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build()

        val response = withContext(ioDispatcher) {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())
        }

        val errorChunk = handleHttpError(response, "Anthropic")
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
    private fun handleHttpError(
        response: HttpResponse<Stream<String>>,
        providerName: String,
    ): StreamChunk.Error? {
        if (response.statusCode() in 200..299) return null
        val errorBody = response.body().toArray().joinToString("\n")
        logProviderHttpFailureDebug(
            logger = providerLogger,
            providerName = providerName,
            statusCode = response.statusCode(),
            body = errorBody,
        )
        return StreamChunk.Error(
            providerHttpFailure(
                providerName = providerName,
                statusCode = response.statusCode(),
                body = errorBody,
                retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null),
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
        response: HttpResponse<Stream<String>>,
    ) {
        val fullText = StringBuilder()
        var lastUsage: UsageMetrics? = null

        try {
            response.body().use { lines ->
                var currentEvent: String? = null
                for (line in lines) {
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
            emit(StreamChunk.Error(providerTransportFailure("Anthropic", e)))
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
     * Converts a Tramai [Message] to an Anthropic-compatible request map.
     *
     * When the message carries [ContentPart.ImagePart] items, the content is
     * serialised as Anthropic content blocks; otherwise a plain string is used.
     */
    private fun messageToMap(message: dev.tramai.core.model.Message): Map<String, Any?> {
        val msgParts = message.contentParts
        val content: Any = if (!msgParts.isNullOrEmpty()) {
            msgParts.map { part ->
                when (part) {
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
            }
        } else {
            message.content
        }

        return mapOf(
            "role" to message.role.name.lowercase(),
            "content" to content,
        )
    }

    private companion object {
        private val providerLogger: System.Logger = System.getLogger(AnthropicProvider::class.java.name)

        val SUPPORTED_IMAGE_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

        const val ANTHROPIC_API_VERSION: String = "2023-06-01"
    }
}
