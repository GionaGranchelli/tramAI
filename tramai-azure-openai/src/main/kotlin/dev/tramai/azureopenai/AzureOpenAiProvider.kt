package dev.tramai.azureopenai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.UsageMetrics
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.StreamCapable
import dev.tramai.core.provider.applyTramaiTimeout
import dev.tramai.core.provider.logProviderHttpFailureDebug
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
import java.util.Base64

/**
 * [ModelProvider] implementation for Azure OpenAI.
 *
 * Azure OpenAI uses a deployment-based endpoint rather than model-based routing.
 * The endpoint URL is constructed as:
 * `https://{resource}.openai.azure.com/openai/deployments/{deployment-id}/chat/completions?apiVersion={version}`
 *
 * Authentication supports either:
 * - **API key** (passed via `apiKey` parameter)
 * - **Entra ID token** (passed via `entraAccessTokenSource` parameter, e.g. using DefaultAzureCredential)
 *
 * When both are provided, API key takes precedence.
 *
 * Supports: text, streaming, structured output, tools, vision.
 * Responses may include content-filtering metadata in headers and content-filter
 * finish reasons.
 */
class AzureOpenAiProvider @JvmOverloads constructor(
    private val resourceName: String,
    private val deploymentId: String,
    private val apiKey: String? = null,
    private val apiVersion: String = DEFAULT_API_VERSION,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
    /** Source for Entra ID (Azure AD) bearer tokens. Called on every request. */
    private val entraAccessTokenSource: AzureEntraAccessTokenSource? = null,
) : ModelProvider, StreamCapable {

    init {
        require(resourceName.isNotBlank()) { "resourceName must not be blank" }
        require(deploymentId.isNotBlank()) { "deploymentId must not be blank" }
        require(apiKey != null || entraAccessTokenSource != null) {
            "Either apiKey or entraAccessTokenSource must be provided"
        }
    }

    override fun providerId(): String = PROVIDER_ID

    private fun buildUrl(): String {
        return "https://$resourceName.openai.azure.com/openai/deployments/$deploymentId" +
            "/chat/completions?apiVersion=$apiVersion"
    }

    private fun resolveAuthToken(): String? {
        apiKey?.let { return it }
        return entraAccessTokenSource?.accessToken()
            ?: throw ConfigurationException(
                "Azure OpenAI requires either an API key or an Entra ID access token"
            )
    }

    override suspend fun complete(request: ModelRequest): ModelResponse = withContext(Dispatchers.IO) {
        try {
            val payload = linkedMapOf<String, Any?>(
                "model" to request.model,
                "stream" to false,
                "messages" to request.messages.map { message -> messageToMap(message) },
            )

            request.tools?.let { tools ->
                payload["tools"] = tools.map { tool ->
                    mapOf(
                        "type" to "function",
                        "function" to mapOf(
                            "name" to tool.name,
                            "description" to tool.description,
                            "parameters" to objectMapper.readTree(tool.inputSchemaJson),
                        ),
                    )
                }
            }

            request.maxTokens?.let { payload["max_tokens"] = it }
            request.temperature?.let { payload["temperature"] = it }

            val authToken = resolveAuthToken()
            val httpRequestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl()))
                .header("Content-Type", "application/json")
                .applyTramaiTimeout(request)

            // API key auth uses the api-key header; Entra ID uses Bearer auth
            if (apiKey != null) {
                httpRequestBuilder.header("api-key", authToken!!)
            } else {
                httpRequestBuilder.header("Authorization", "Bearer $authToken")
            }

            val httpRequest = httpRequestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logProviderHttpFailureDebug(
                    logger = providerLogger,
                    providerName = PROVIDER_ID,
                    statusCode = response.statusCode(),
                    body = response.body(),
                )
                throw providerHttpFailure(
                    providerName = PROVIDER_ID,
                    statusCode = response.statusCode(),
                    body = response.body(),
                    retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null),
                )
            }

            mapResponse(objectMapper.readTree(response.body()))
        } catch (error: Throwable) {
            throw providerTransportFailure(PROVIDER_ID, error)
        }
    }

    override suspend fun stream(request: ModelRequest): Flow<StreamChunk> = flow {
        val payload = linkedMapOf<String, Any?>(
            "model" to request.model,
            "stream" to true,
            "messages" to request.messages.map { message -> messageToMap(message) },
        )

        request.maxTokens?.let { payload["max_tokens"] = it }
        request.temperature?.let { payload["temperature"] = it }

        val authToken = resolveAuthToken()
        val httpRequestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(buildUrl()))
            .header("Content-Type", "application/json")
            .applyTramaiTimeout(request)

        if (apiKey != null) {
            httpRequestBuilder.header("api-key", authToken!!)
        } else {
            httpRequestBuilder.header("Authorization", "Bearer $authToken")
        }

        val httpRequest = httpRequestBuilder
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build()

        val response = withContext(Dispatchers.IO) {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())
        }

        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().toArray().joinToString("\n")
            logProviderHttpFailureDebug(
                logger = providerLogger,
                providerName = PROVIDER_ID,
                statusCode = response.statusCode(),
                body = errorBody,
            )
            emit(
                StreamChunk.Error(
                    providerHttpFailure(
                        providerName = PROVIDER_ID,
                        statusCode = response.statusCode(),
                        body = errorBody,
                        retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null),
                    ),
                ),
            )
            return@flow
        }

        val fullText = StringBuilder()
        var lastUsage: UsageMetrics? = null

        try {
            response.body().use { lines ->
                for (line in lines) {
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        if (data == "[DONE]") break

                        val chunk = objectMapper.readTree(data)
                        val delta = chunk.path("choices").firstOrNull()?.path("delta")
                        val content = delta?.path("content")?.asText("") ?: ""
                        if (content.isNotEmpty()) {
                            fullText.append(content)
                            emit(StreamChunk.Token(content))
                        }

                        val usage = chunk.path("usage")
                        if (!usage.isMissingNode) {
                            lastUsage = UsageMetrics(
                                inputTokens = usage.path("prompt_tokens").asInt(),
                                outputTokens = usage.path("completion_tokens").asInt(),
                            )
                        }
                    }
                }
            }
            emit(StreamChunk.Complete(fullText.toString(), lastUsage ?: UsageMetrics()))
        } catch (e: Exception) {
            emit(StreamChunk.Error(providerTransportFailure(PROVIDER_ID, e)))
        }
    }

    // ---- Response mapping ----

    private fun mapResponse(body: JsonNode): ModelResponse {
        val firstChoice = body.path("choices").firstOrNull()
            ?: throw ProviderException("Azure OpenAI response did not contain any completion choices")
        val message = firstChoice.path("message")

        val toolCalls = message.path("tool_calls").takeIf { it.isArray }?.map { tc ->
            ToolCall(
                id = tc.path("id").asText(),
                name = tc.path("function").path("name").asText(),
                argumentsJson = tc.path("function").path("arguments").asText(),
            )
        }

        val finishReason = when (firstChoice.path("finish_reason").asText("")) {
            "stop" -> FinishReason.STOP
            "length" -> FinishReason.LENGTH
            "tool_calls" -> FinishReason.STOP
            "content_filter" -> FinishReason.CONTENT_FILTER
            else -> FinishReason.OTHER
        }

        return ModelResponse(
            content = extractContent(message.path("content")),
            toolCalls = toolCalls,
            inputTokens = body.path("usage").path("prompt_tokens").takeIf { !it.isMissingNode }?.asInt(),
            outputTokens = body.path("usage").path("completion_tokens").takeIf { !it.isMissingNode }?.asInt(),
            modelUsed = body.path("model").takeIf { !it.isMissingNode }?.asText(),
            finishReason = finishReason,
        )
    }

    private fun extractContent(contentNode: JsonNode): String {
        if (contentNode.isTextual) {
            return contentNode.asText("")
        }
        if (contentNode.isArray) {
            return contentNode.mapNotNull { part ->
                when {
                    part.path("type").asText("") in setOf("text", "output_text") ->
                        part.path("text").asText("").takeIf { it.isNotBlank() }
                    part.hasNonNull("text") -> part.path("text").asText("").takeIf { it.isNotBlank() }
                    else -> null
                }
            }.joinToString(separator = "\n")
        }
        return contentNode.path("text").asText("")
    }

    // ---- Message conversion ----

    private fun messageToMap(message: dev.tramai.core.model.Message): Map<String, Any?> {
        val msgMap = mutableMapOf<String, Any?>("role" to message.role.name.lowercase())

        val msgParts = message.contentParts
        if (msgParts != null && msgParts.isNotEmpty()) {
            msgMap["content"] = msgParts.map { part ->
                when (part) {
                    is ContentPart.TextPart -> mapOf("type" to "text", "text" to part.text)
                    is ContentPart.ImagePart -> {
                        require(part.mimeType in SUPPORTED_IMAGE_TYPES) {
                            "Unsupported image mimeType '${part.mimeType}'. Supported types: $SUPPORTED_IMAGE_TYPES"
                        }
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf(
                                "url" to "data:${part.mimeType};base64,${Base64.getEncoder().encodeToString(part.data)}",
                            ),
                        )
                    }
                }
            }
        } else {
            msgMap["content"] = message.content
        }

        message.toolCallId?.let { msgMap["tool_call_id"] = it }
        message.toolCalls?.let { toolCalls ->
            msgMap["tool_calls"] = toolCalls.map { tc ->
                mapOf(
                    "id" to tc.id,
                    "type" to "function",
                    "function" to mapOf(
                        "name" to tc.name,
                        "arguments" to tc.argumentsJson,
                    ),
                )
            }
        }
        return msgMap
    }

    companion object {
        private val providerLogger: System.Logger = System.getLogger(AzureOpenAiProvider::class.java.name)

        const val PROVIDER_ID: String = "azure-openai"
        const val DEFAULT_API_VERSION: String = "2024-10-21"

        val SUPPORTED_IMAGE_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }
}

/**
 * Source for Entra ID (Azure AD) bearer tokens used by [AzureOpenAiProvider].
 */
fun interface AzureEntraAccessTokenSource {
    /**
     * Returns a bearer token for Azure OpenAI authentication.
     */
    fun accessToken(): String
}

/**
 * Static access-token source for fixed Entra ID bearer tokens.
 */
class StaticAzureEntraAccessTokenSource(
    private val token: String,
) : AzureEntraAccessTokenSource {
    override fun accessToken(): String = token.trim().takeIf { it.isNotBlank() }
        ?: throw ConfigurationException("Azure Entra ID access token must not be blank")
}
