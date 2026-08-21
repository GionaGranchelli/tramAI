@file:OptIn(dev.tramai.core.provider.transport.ExperimentalProviderTransportApi::class)

package dev.tramai.ollama

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.exception.ProviderFailureCode
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.UsageMetrics
import dev.tramai.core.observation.NoOpProviderFailureDiagnosticObserver
import dev.tramai.core.observation.ProviderFailureDiagnosticObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.providerTransportFailureObserved
import dev.tramai.core.provider.transport.providerJsonRequest
import dev.tramai.core.provider.transport.rejectedProviderHttpResponse
import dev.tramai.core.provider.safeProviderFailure
import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import java.net.URI
import java.io.InputStream
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.util.Base64
import java.nio.charset.StandardCharsets.UTF_8

/**
 * [ModelProvider] implementation for Ollama's chat API.
 */
class OllamaProvider @JvmOverloads constructor(
    private val baseUrl: String = "http://localhost:11434",
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
    private val providerFailureDiagnosticObserver: ProviderFailureDiagnosticObserver =
        NoOpProviderFailureDiagnosticObserver,
) : ModelProvider, dev.tramai.core.provider.StreamCapable {

    override suspend fun complete(request: ModelRequest): ModelResponse = withContext(ioDispatcher) {
        try {
            val payload = mapOf(
                "model" to request.model,
                "stream" to false,
                "messages" to request.messages.map { message -> messageToMap(message) },
            )

            val httpRequest = providerJsonRequest(
                URI.create("${baseUrl.trimEnd('/')}/api/chat"),
                request,
                objectMapper.writeValueAsString(payload),
            ).build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                throw rejectedProviderHttpResponse(
                    providerId = PROVIDER_ID,
                    providerAlias = null,
                    response = response,
                    observer = providerFailureDiagnosticObserver,
                    logger = providerLogger,
                )
            }

            val body = objectMapper.readTree(response.body().use { it.readAllBytes().decodeToString() })
            val message = body.path("message")
            val role = message.path("role").asText("").lowercase()
            if (role.isNotBlank() && role != MessageRole.ASSISTANT.name.lowercase()) {
                throw safeProviderFailure(
                    "Unexpected Ollama response role",
                    ProviderFailureCode.UNEXPECTED_FAILURE,
                )
            }
            val content = message.path("content").asText("")
            if (content.isBlank()) {
                throw safeProviderFailure(
                    "Ollama response contained no content",
                    ProviderFailureCode.UNEXPECTED_FAILURE,
                )
            }

            ModelResponse(
                content = content,
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
        ProviderCapability.STREAMING -> true
        else -> false
    }

    override fun stream(request: ModelRequest): Flow<StreamChunk> = flow {
        val response = try {
            withContext(ioDispatcher) {
                val payload = mapOf(
                    "model" to request.model,
                    "stream" to true,
                    "messages" to request.messages.map { message -> messageToMap(message) },
                )
                val httpRequest = providerJsonRequest(
                    URI.create("${baseUrl.trimEnd('/')}/api/chat"),
                    request,
                    objectMapper.writeValueAsString(payload),
                ).build()
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
            }
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            emit(
                dev.tramai.core.model.StreamChunk.Error(
                    providerTransportFailureObserved(PROVIDER_ID, error, providerFailureDiagnosticObserver),
                ),
            )
            return@flow
        }

        val errorChunk = handleHttpError(response)
        if (errorChunk != null) {
            emit(errorChunk)
            return@flow
        }

        parseOllamaStreamResponse(response)
    }

    /**
     * Handles non-2xx HTTP responses by logging the error and returning a [StreamChunk.Error].
     * Returns `null` for successful (2xx) responses.
     */
    private suspend fun handleHttpError(
        response: HttpResponse<InputStream>,
    ): dev.tramai.core.model.StreamChunk.Error? {
        if (response.statusCode() in 200..299) return null
        return dev.tramai.core.model.StreamChunk.Error(
            rejectedProviderHttpResponse(
                providerId = PROVIDER_ID,
                providerAlias = null,
                response = response,
                observer = providerFailureDiagnosticObserver,
                logger = providerLogger,
            ),
        )
    }

    /**
     * Parses the Ollama SSE stream response, emitting [StreamChunk.Token] for each
     * content JSON line and a final [StreamChunk.Complete] when done.
     */
    private suspend fun FlowCollector<dev.tramai.core.model.StreamChunk>.parseOllamaStreamResponse(
        response: HttpResponse<InputStream>,
    ) {
        val fullText = StringBuilder()
        var lastUsage: dev.tramai.core.model.UsageMetrics? = null

        try {
            response.body().bufferedReader(UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
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
            e.rethrowIfCancellation()
            emit(
                dev.tramai.core.model.StreamChunk.Error(
                    providerTransportFailureObserved(PROVIDER_ID, e, providerFailureDiagnosticObserver),
                ),
            )
        }
    }

    /**
     * Converts a Tramai [Message] to an Ollama-compatible request map.
     *
     * When the message carries [ContentPart.ImagePart] items, the image data is
     * included as base64-encoded strings in the `images` field.
     * Otherwise, the plain text [content] is used directly.
     */
    private fun messageToMap(message: dev.tramai.core.model.Message): Map<String, Any?> {
        val msgMap = linkedMapOf<String, Any?>(
            "role" to message.role.name.lowercase(),
        )

        val msgParts = message.contentParts
        if (!msgParts.isNullOrEmpty()) {
            // Extract text from parts for the content field
            val textParts = msgParts.filterIsInstance<ContentPart.TextPart>()
            msgMap["content"] = if (textParts.isNotEmpty()) {
                textParts.joinToString("\n") { it.text }
            } else {
                ""
            }

            // Extract images as base64 strings for Ollama's images field
            val imageParts = msgParts.mapNotNull { part ->
                when (part) {
                    is ContentPart.ImagePart -> part
                    is ContentPart.ImageUrlContent -> {
                        val resolved = dev.tramai.core.util.ImageDownloader.resolveToImagePart(part) as ContentPart.ImagePart
                        resolved
                    }
                    else -> null
                }
            }
            if (imageParts.isNotEmpty()) {
                msgMap["images"] = imageParts.map { part ->
                    Base64.getEncoder().encodeToString(part.data)
                }
            }
        } else {
            msgMap["content"] = message.content
        }

        return msgMap
    }

    private companion object {
        private val providerLogger: System.Logger = System.getLogger(OllamaProvider::class.java.name)
        const val PROVIDER_ID: String = "ollama"
    }
}
