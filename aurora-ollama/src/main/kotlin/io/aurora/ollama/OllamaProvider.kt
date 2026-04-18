package io.aurora.ollama

import com.fasterxml.jackson.databind.ObjectMapper
import io.aurora.core.exception.ProviderException
import io.aurora.core.model.FinishReason
import io.aurora.core.model.MessageRole
import io.aurora.core.model.ModelRequest
import io.aurora.core.model.ModelResponse
import io.aurora.core.provider.ModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class OllamaProvider(
    private val baseUrl: String = "http://localhost:11434",
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : ModelProvider {

    override suspend fun complete(request: ModelRequest): ModelResponse = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "model" to request.model,
            "stream" to false,
            "messages" to request.messages.map { message ->
                mapOf(
                    "role" to message.role.name.lowercase(),
                    "content" to message.content,
                )
            },
        )

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw ProviderException("Ollama returned HTTP ${response.statusCode()}: ${response.body()}")
        }

        val body = objectMapper.readTree(response.body())
        val message = body.path("message")
        val role = message.path("role").asText("").lowercase()
        if (role.isNotBlank() && role != MessageRole.ASSISTANT.name.lowercase()) {
            throw ProviderException("Unexpected Ollama response role '$role'")
        }

        ModelResponse(
            content = message.path("content").asText(""),
            inputTokens = body.path("prompt_eval_count").takeIf { !it.isMissingNode }?.asInt(),
            outputTokens = body.path("eval_count").takeIf { !it.isMissingNode }?.asInt(),
            modelUsed = body.path("model").takeIf { !it.isMissingNode }?.asText(),
            finishReason = when (body.path("done_reason").asText("")) {
                "stop" -> FinishReason.STOP
                "length" -> FinishReason.LENGTH
                else -> FinishReason.OTHER
            },
        )
    }

    override fun providerId(): String = "ollama"
}
