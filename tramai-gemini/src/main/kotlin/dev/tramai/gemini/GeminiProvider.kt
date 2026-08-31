@file:OptIn(dev.tramai.core.provider.transport.ExperimentalProviderTransportApi::class)

package dev.tramai.gemini

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.coroutines.rethrowIfCancellation
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
import dev.tramai.core.provider.StreamCapable
import dev.tramai.core.provider.providerTransportFailureObserved
import dev.tramai.core.provider.safeProviderFailure
import dev.tramai.core.provider.transport.providerJsonRequest
import dev.tramai.core.provider.transport.readBoundedResponseBody
import dev.tramai.core.provider.transport.readSseDataPayload
import dev.tramai.core.provider.transport.rejectedProviderHttpResponse
import dev.tramai.core.util.ImageDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import kotlin.coroutines.CoroutineContext

/**
 * [ModelProvider] implementation for Google Gemini API.
 *
 * Maps Tramai concepts to the Gemini `generateContent` / `streamGenerateContent` endpoints.
 *
 * | Tramai Concept         | Gemini API Equivalent                                      |
 * |------------------------|------------------------------------------------------------|
 * | ModelRequest.model     | models/{model}:generateContent                             |
 * | SYSTEM message         | system_instruction field                                   |
 * | USER message           | contents[{role: "user"}]                                   |
 * | ASSISTANT message      | contents[{role: "model"}]                                  |
 * | TOOL message           | contents[{role: "function"}]                               |
 * | ToolDefinition         | tools[{function_declarations[...]}]                        |
 * | Structured output      | generationConfig.responseMimeType + responseSchema         |
 * | Streaming              | streamGenerateContent                                      |
 * | ImagePart              | inlineData { mimeType, data } within content parts         |
 */
class GeminiProvider
    @JvmOverloads
    constructor(
        private val apiKey: String,
        private val baseUrl: String = DEFAULT_BASE_URL,
        private val httpClient: HttpClient = HttpClient.newHttpClient(),
        private val objectMapper: ObjectMapper = ObjectMapper(),
        /** Optional JSON schema string for structured output (responseSchema). */
        private val responseSchema: String? = null,
        /** Gemini API version to use, e.g. "v1" or "v1beta". */
        private val apiVersion: String = DEFAULT_API_VERSION,
        private val ioDispatcher: CoroutineContext = Dispatchers.IO,
        private val providerFailureDiagnosticObserver: ProviderFailureDiagnosticObserver =
            NoOpProviderFailureDiagnosticObserver,
    ) : ModelProvider,
        StreamCapable {
        override fun providerId(): String = PROVIDER_ID

        override fun supportsCapability(capability: ProviderCapability): Boolean =
            when (capability) {
                ProviderCapability.VISION -> true
                ProviderCapability.TOOL_CALLING -> true
                ProviderCapability.STRUCTURED_OUTPUT -> true
                ProviderCapability.STREAMING -> true
            }

        override suspend fun complete(request: ModelRequest): ModelResponse =
            withContext(ioDispatcher) {
                try {
                    val modelName = request.model.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
                    val url = "${baseUrl.trimEnd('/')}/$apiVersion/models/$modelName:generateContent"
                    val payload = buildPayload(request)
                    val httpRequest =
                        providerJsonRequest(
                            URI.create(url),
                            request,
                            objectMapper.writeValueAsString(payload),
                        ).apply {
                            header("X-Goog-Api-Key", apiKey)
                        }.build()

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

                    val body = objectMapper.readTree(readBoundedResponseBody(response).text)
                    mapResponse(body)
                } catch (error: Throwable) {
                    error.rethrowIfCancellation()
                    throw providerTransportFailureObserved(PROVIDER_ID, error, providerFailureDiagnosticObserver)
                }
            }

        override fun stream(request: ModelRequest): Flow<StreamChunk> =
            flow {
                val response =
                    try {
                        withContext(ioDispatcher) {
                            val modelName = request.model.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
                            val url = "${baseUrl.trimEnd('/')}/$apiVersion/models/$modelName:streamGenerateContent?alt=sse"
                            val payload = buildPayload(request)
                            val httpRequest =
                                providerJsonRequest(
                                    URI.create(url),
                                    request,
                                    objectMapper.writeValueAsString(payload),
                                ).apply {
                                    header("X-Goog-Api-Key", apiKey)
                                }.build()
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

                parseGeminiStreamResponse(response)
            }

        /**
         * Handles non-2xx HTTP responses for streaming requests.
         * Returns a [StreamChunk.Error] for failed responses, or `null` for 2xx.
         */
        private suspend fun handleHttpError(response: HttpResponse<InputStream>): StreamChunk.Error? {
            if (response.statusCode() in 200..299) return null
            return StreamChunk.Error(
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
         * Parses the Gemini SSE stream response, handling Gemini-specific
         * `data: ` format with nested `candidates[].content.parts[].text` structure
         * and `usageMetadata` tracking.
         */
        private suspend fun FlowCollector<StreamChunk>.parseGeminiStreamResponse(response: HttpResponse<InputStream>) {
            val fullText = StringBuilder()
            var lastUsage: UsageMetrics? = null

            try {
                response.body().bufferedReader(UTF_8).use { reader ->
                    while (true) {
                        val data = readSseDataPayload(reader) ?: break
                        // Gemini SSE format: "data: {...}"
                        if (data.isEmpty()) continue
                        if (data == "[DONE]") break
                        lastUsage = handleGeminiDataLine(data, fullText, lastUsage)
                    }
                }
                emit(StreamChunk.Complete(fullText.toString(), lastUsage ?: UsageMetrics()))
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                emit(StreamChunk.Error(providerTransportFailureObserved(PROVIDER_ID, e, providerFailureDiagnosticObserver)))
            }
        }

        /**
         * Handles a single Gemini SSE data line, extracting candidate text parts and usage metadata.
         */
        private suspend fun FlowCollector<StreamChunk>.handleGeminiDataLine(
            data: String,
            fullText: StringBuilder,
            lastUsage: UsageMetrics?,
        ): UsageMetrics? {
            val chunk = objectMapper.readTree(data)
            val candidates = chunk.path("candidates")
            if (candidates.isArray && candidates.size() > 0) {
                val content = candidates[0].path("content")
                val parts = content.path("parts")
                if (parts.isArray) {
                    for (part in parts) {
                        val text = part.path("text").asText("")
                        if (text.isNotEmpty()) {
                            fullText.append(text)
                            emit(StreamChunk.Token(text))
                        }
                    }
                }
            }

            val usageMetadata = chunk.path("usageMetadata")
            return if (!usageMetadata.isMissingNode) {
                UsageMetrics(
                    inputTokens = usageMetadata.path("promptTokenCount").takeIf { !it.isMissingNode }?.asInt(),
                    outputTokens = usageMetadata.path("candidatesTokenCount").takeIf { !it.isMissingNode }?.asInt(),
                )
            } else {
                lastUsage
            }
        }

        // ---- Payload construction ----

        private fun buildPayload(request: ModelRequest): Map<String, Any?> {
            val payload = linkedMapOf<String, Any?>()

            // Map messages to Gemini contents array
            val contents =
                request.messages
                    .filter { it.role.name.lowercase() != "system" }
                    .map { message ->
                        val role =
                            when (message.role.name.lowercase()) {
                                "assistant" -> "model"
                                "tool" -> "function"
                                else -> "user"
                            }
                        mapOf(
                            "role" to role,
                            "parts" to buildContentParts(message),
                        )
                    }

            if (contents.isNotEmpty()) {
                payload["contents"] = contents
            }

            // System instruction
            val systemMessage = request.messages.firstOrNull { it.role.name.lowercase() == "system" }?.content
            if (!systemMessage.isNullOrBlank()) {
                payload["system_instruction"] =
                    mapOf(
                        "parts" to listOf(mapOf("text" to systemMessage)),
                    )
            }

            // Tool definitions — wrap all in a single function_declarations array
            request.tools?.let { tools ->
                payload["tools"] =
                    listOf(
                        mapOf(
                            "function_declarations" to
                                tools.map { tool ->
                                    mapOf(
                                        "name" to tool.name,
                                        "description" to tool.description,
                                        "parameters" to objectMapper.readTree(tool.inputSchemaJson),
                                    )
                                },
                        ),
                    )
            }

            // Generation configuration
            val generationConfig = linkedMapOf<String, Any?>()
            request.maxTokens?.let { generationConfig["maxOutputTokens"] = it }
            request.temperature?.let { generationConfig["temperature"] = it }
            val schema = responseSchema
            if (schema != null) {
                generationConfig["responseMimeType"] = APPLICATION_JSON
                generationConfig["responseSchema"] = objectMapper.readTree(schema)
            }

            if (generationConfig.isNotEmpty()) {
                payload["generationConfig"] = generationConfig
            }

            return payload
        }

        private fun buildContentParts(message: dev.tramai.core.model.Message): List<Any> {
            // Tool (function) responses use Gemini's functionResponse format
            if (message.role == MessageRole.TOOL) {
                val name = message.toolCallId ?: "unknown_function"
                return listOf(
                    mapOf(
                        "functionResponse" to
                            mapOf(
                                "name" to name,
                                "response" to
                                    mapOf(
                                        "content" to message.content,
                                    ),
                            ),
                    ),
                )
            }

            val msgParts = message.contentParts
            if (msgParts.isNullOrEmpty()) {
                // Fall back to plain text content
                return listOf(mapOf("text" to message.content))
            }

            return msgParts.map { part ->
                when (part) {
                    is ContentPart.TextPart -> {
                        mapOf("text" to part.text)
                    }

                    is ContentPart.ImagePart -> {
                        mapOf(
                            "inlineData" to
                                mapOf(
                                    "mimeType" to part.mimeType,
                                    "data" to Base64.getEncoder().encodeToString(part.data),
                                ),
                        )
                    }

                    is ContentPart.ImageUrlContent -> {
                        val resolved = ImageDownloader.resolveToImagePart(part) as ContentPart.ImagePart
                        mapOf(
                            "inlineData" to
                                mapOf(
                                    "mimeType" to resolved.mimeType,
                                    "data" to Base64.getEncoder().encodeToString(resolved.data),
                                ),
                        )
                    }
                }
            }
        }

        // ---- Response mapping ----

        private fun mapResponse(body: JsonNode): ModelResponse {
            val candidate =
                body.path("candidates").firstOrNull()
                    ?: throw safeProviderFailure(
                        "Gemini response did not contain any candidates",
                        ProviderFailureCode.UNEXPECTED_FAILURE,
                    )

            val content = candidate.path("content")
            val parts = content.path("parts")

            val text =
                if (parts.isArray) {
                    parts
                        .mapNotNull { it.path("text").asText("").takeIf { t -> t.isNotEmpty() } }
                        .joinToString("")
                } else {
                    ""
                }

            // Extract function calls if present
            val toolCalls =
                if (parts.isArray) {
                    val calls = mutableListOf<ToolCall>()
                    var index = 0
                    for (part in parts) {
                        val fc = part.path("functionCall")
                        if (!fc.isMissingNode) {
                            val name = fc.path("name").asText("")
                            calls.add(
                                ToolCall(
                                    id = "fc_${name}_$index",
                                    name = name,
                                    argumentsJson = objectMapper.writeValueAsString(fc.path("args")),
                                ),
                            )
                            index++
                        } else {
                            // Log warning for non-text, non-function-call response parts.
                            // The part itself is untrusted response data: never log it.
                            val partText = part.path("text").asText("")
                            if (partText.isEmpty()) {
                                providerLogger.log(
                                    System.Logger.Level.WARNING,
                                    "Gemini response contained a part that is neither text nor functionCall",
                                )
                            }
                        }
                    }
                    calls.ifEmpty { null }
                } else {
                    null
                }

            val finishReason = candidate.path("finishReason").asText("")
            val usageMetadata = body.path("usageMetadata")

            return ModelResponse(
                content = text,
                toolCalls = toolCalls,
                inputTokens = usageMetadata.path("promptTokenCount").takeIf { !it.isMissingNode }?.asInt(),
                outputTokens = usageMetadata.path("candidatesTokenCount").takeIf { !it.isMissingNode }?.asInt(),
                modelUsed = body.path("model").takeIf { !it.isMissingNode }?.asText(),
                finishReason = mapFinishReason(finishReason),
            )
        }

        private fun mapFinishReason(reason: String): FinishReason =
            when (reason) {
                "STOP" -> FinishReason.STOP
                "MAX_TOKENS" -> FinishReason.LENGTH
                "SAFETY" -> FinishReason.CONTENT_FILTER
                "RECITATION" -> FinishReason.CONTENT_FILTER
                else -> FinishReason.OTHER
            }

        companion object {
            private val providerLogger: System.Logger = System.getLogger(GeminiProvider::class.java.name)

            const val DEFAULT_BASE_URL: String = "https://generativelanguage.googleapis.com"
            const val DEFAULT_MODEL: String = "gemini-2.0-flash"
            const val DEFAULT_API_VERSION: String = "v1beta"
            const val PROVIDER_ID: String = "gemini"
        }
    }

/** @see GeminiProvider */
private const val APPLICATION_JSON = "application/json"
