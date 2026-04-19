package dev.tramai.ollama

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.applyTramaiTimeout
import dev.tramai.core.provider.providerHttpFailure
import dev.tramai.core.provider.providerTransportFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * [ModelProvider] implementation for Ollama's chat API.
 */
class OllamaProvider(
    private val baseUrl: String = "http://localhost:11434",
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : ModelProvider, dev.tramai.core.provider.StreamCapable {

    override suspend fun complete(request: ModelRequest): ModelResponse = withContext(Dispatchers.IO) {
        try {
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
                .applyTramaiTimeout(request)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw providerHttpFailure("Ollama", response.statusCode(), response.body())
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
        } catch (error: Throwable) {
            throw providerTransportFailure("Ollama", error)
        }
    }

    /**
     * Returns the stable provider id used by the registry.
     */
    override fun providerId(): String = "ollama"

    override suspend fun stream(request: ModelRequest): Flow<dev.tramai.core.model.StreamChunk> = flow {
        val payload = mapOf(
            "model" to request.model,
            "stream" to true,
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
            .applyTramaiTimeout(request)
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build()

        val response = withContext(Dispatchers.IO) {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())
        }

        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().toArray().joinToString("\n")
            emit(dev.tramai.core.model.StreamChunk.Error(providerHttpFailure("Ollama", response.statusCode(), errorBody)))
            return@flow
        }

        val fullText = StringBuilder()
        var lastUsage: dev.tramai.core.model.UsageMetrics? = null

        try {
            response.body().use { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    
                    val node = objectMapper.readTree(line)
                    val content = node.path("message").path("content").asText("")
                    if (content.isNotEmpty()) {
                        fullText.append(content)
                        emit(dev.tramai.core.model.StreamChunk.Token(content))
                    }

                    if (node.path("done").asBoolean()) {
                        lastUsage = dev.tramai.core.model.UsageMetrics(
                            inputTokens = node.path("prompt_eval_count").takeIf { !it.isMissingNode }?.asInt(),
                            outputTokens = node.path("eval_count").takeIf { !it.isMissingNode }?.asInt(),
                        )
                        break
                    }
                }
            }
            emit(dev.tramai.core.model.StreamChunk.Complete(fullText.toString(), lastUsage ?: dev.tramai.core.model.UsageMetrics()))
        } catch (e: Exception) {
            emit(dev.tramai.core.model.StreamChunk.Error(providerTransportFailure("Ollama", e)))
        }
    }
}
