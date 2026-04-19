package dev.tramai.anthropic

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.applyTramaiTimeout
import dev.tramai.core.provider.providerHttpFailure
import dev.tramai.core.provider.providerTransportFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * [ModelProvider] implementation for Anthropic's Messages API.
 */
class AnthropicProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val anthropicVersion: String = "2023-06-01",
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : dev.tramai.core.provider.ModelProvider, dev.tramai.core.provider.StreamCapable {

    override suspend fun complete(request: ModelRequest): ModelResponse = withContext(Dispatchers.IO) {
        try {
            val payload = linkedMapOf<String, Any?>(
                "model" to request.model,
                "max_tokens" to (request.maxTokens ?: 1024),
                "messages" to request.messages
                    // Anthropic accepts the system prompt separately from the conversational message list.
                    .filter { it.role.name.lowercase() != "system" }
                    .map { message ->
                        mapOf(
                            "role" to message.role.name.lowercase(),
                            "content" to message.content,
                        )
                    },
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
                throw providerHttpFailure("Anthropic", response.statusCode(), response.body())
            }

            val body = objectMapper.readTree(response.body())
            val firstTextBlock = body.path("content")
                .firstOrNull { node -> node.path("type").asText("") == "text" }
                ?: throw ProviderException("Anthropic response did not contain a text content block")

            ModelResponse(
                content = firstTextBlock.path("text").asText(""),
                inputTokens = body.path("usage").path("input_tokens").takeIf { !it.isMissingNode }?.asInt(),
                outputTokens = body.path("usage").path("output_tokens").takeIf { !it.isMissingNode }?.asInt(),
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

    override suspend fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<dev.tramai.core.model.StreamChunk> = kotlinx.coroutines.flow.flow {
        val payload = linkedMapOf<String, Any?>(
            "model" to request.model,
            "stream" to true,
            "max_tokens" to (request.maxTokens ?: 1024),
            "messages" to request.messages
                .filter { it.role.name.lowercase() != "system" }
                .map { message ->
                    mapOf(
                        "role" to message.role.name.lowercase(),
                        "content" to message.content,
                    )
                },
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

        val response = withContext(Dispatchers.IO) {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())
        }

        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().toArray().joinToString("\n")
            emit(dev.tramai.core.model.StreamChunk.Error(providerHttpFailure("Anthropic", response.statusCode(), errorBody)))
            return@flow
        }

        val fullText = StringBuilder()
        var lastUsage: dev.tramai.core.model.UsageMetrics? = null

        try {
            response.body().use { lines ->
                var currentEvent: String? = null
                for (line in lines) {
                    if (line.startsWith("event: ")) {
                        currentEvent = line.substring(7).trim()
                    } else if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        val node = objectMapper.readTree(data)
                        
                        when (currentEvent) {
                            "content_block_delta" -> {
                                val text = node.path("delta").path("text").asText("")
                                if (text.isNotEmpty()) {
                                    fullText.append(text)
                                    emit(dev.tramai.core.model.StreamChunk.Token(text))
                                }
                            }
                            "message_start" -> {
                                val usage = node.path("message").path("usage")
                                if (!usage.isMissingNode) {
                                    lastUsage = dev.tramai.core.model.UsageMetrics(
                                        inputTokens = usage.path("input_tokens").asInt(),
                                        outputTokens = usage.path("output_tokens").asInt(),
                                    )
                                }
                            }
                            "message_delta" -> {
                                val usage = node.path("usage")
                                if (!usage.isMissingNode) {
                                    lastUsage = dev.tramai.core.model.UsageMetrics(
                                        inputTokens = lastUsage?.inputTokens,
                                        outputTokens = usage.path("output_tokens").asInt(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            emit(dev.tramai.core.model.StreamChunk.Complete(fullText.toString(), lastUsage ?: dev.tramai.core.model.UsageMetrics()))
        } catch (e: Exception) {
            emit(dev.tramai.core.model.StreamChunk.Error(providerTransportFailure("Anthropic", e)))
        }
    }
}
