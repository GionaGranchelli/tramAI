package io.aurora.anthropic

import com.fasterxml.jackson.databind.ObjectMapper
import io.aurora.core.exception.ProviderException
import io.aurora.core.model.FinishReason
import io.aurora.core.model.ModelRequest
import io.aurora.core.model.ModelResponse
import io.aurora.core.provider.ModelProvider
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
) : ModelProvider {

    override suspend fun complete(request: ModelRequest): ModelResponse = withContext(Dispatchers.IO) {
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
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw ProviderException("Anthropic returned HTTP ${response.statusCode()}: ${response.body()}")
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
    }

    /**
     * Returns the stable provider id used by the registry.
     */
    override fun providerId(): String = "anthropic"
}
