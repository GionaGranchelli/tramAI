package dev.tramai.openai

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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64

/**
 * Source for bearer tokens used by OpenAI-compatible providers.
 */
fun interface OpenAiAccessTokenSource {
    /**
     * Returns the bearer token to place in the `Authorization` header.
     */
    fun accessToken(): String
}

/**
 * Static access-token source for API keys or fixed bearer tokens.
 */
class StaticOpenAiAccessTokenSource(
    private val token: String,
) : OpenAiAccessTokenSource {
    override fun accessToken(): String = token.trim().takeIf { it.isNotBlank() }
        ?: throw ConfigurationException("OpenAI access token must not be blank")
}

/**
 * Reads the current Codex ChatGPT OAuth token from the local Codex auth file.
 *
 * This is intended for local experimentation and testing rather than as Tramai's primary
 * production authentication path.
 */
@ExperimentalCodexAuth
class CodexAuthFileTokenSource(
    private val authFile: Path = defaultAuthFile(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : OpenAiAccessTokenSource {

    override fun accessToken(): String {
        if (!Files.exists(authFile)) {
            throw ConfigurationException("Codex auth file was not found at $authFile")
        }

        val auth = Files.newBufferedReader(authFile).use(objectMapper::readTree)
        val authMode = auth.path("auth_mode").asText("")
        if (authMode != "chatgpt") {
            throw ConfigurationException("Codex auth file at $authFile is not configured for ChatGPT authentication")
        }

        return auth.path("tokens").path("access_token").asText("").trim()
            .takeIf { it.isNotBlank() }
            ?: throw ConfigurationException("Codex auth file at $authFile does not contain an access token")
    }

    companion object {
        /**
         * Returns the default Codex auth file path under the current user's home directory.
         */
        @JvmStatic
        fun defaultAuthFile(): Path = Paths.get(System.getProperty("user.home"), ".codex", "auth.json")
    }
}

/**
 * Provider for any OpenAI-compatible `/chat/completions` endpoint.
 */
open class OpenAiCompatibleProvider(
    private val accessTokenSource: OpenAiAccessTokenSource,
    private val providerName: String = "openai-compatible",
    private val baseUrl: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val organization: String? = null,
    private val project: String? = null,
) : ModelProvider, StreamCapable {

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
                            "parameters" to objectMapper.readTree(tool.inputSchemaJson)
                        )
                    )
                }
            }

            request.maxTokens?.let { payload["max_tokens"] = it }
            request.temperature?.let { payload["temperature"] = it }

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/chat/completions"))
                .header("Authorization", "Bearer ${accessTokenSource.accessToken()}")
                .header("Content-Type", "application/json")
                .apply {
                    organization?.takeIf { it.isNotBlank() }?.let { header("OpenAI-Organization", it) }
                    project?.takeIf { it.isNotBlank() }?.let { header("OpenAI-Project", it) }
                }
                .applyTramaiTimeout(request)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logProviderHttpFailureDebug(
                    logger = providerLogger,
                    providerName = providerName,
                    statusCode = response.statusCode(),
                    body = response.body(),
                )
                throw providerHttpFailure(
                    providerName = providerName,
                    statusCode = response.statusCode(),
                    body = response.body(),
                    retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null),
                )
            }

            val body = objectMapper.readTree(response.body())
            val firstChoice = body.path("choices").firstOrNull()
                ?: throw ProviderException("$providerName response did not contain any completion choices")
            val message = firstChoice.path("message")

            val toolCalls = message.path("tool_calls").takeIf { it.isArray }?.map { tc ->
                ToolCall(
                    id = tc.path("id").asText(),
                    name = tc.path("function").path("name").asText(),
                    argumentsJson = tc.path("function").path("arguments").asText(),
                )
            }

            ModelResponse(
                content = extractContent(message.path("content")),
                toolCalls = toolCalls,
                inputTokens = body.path("usage").path("prompt_tokens").takeIf { !it.isMissingNode }?.asInt(),
                outputTokens = body.path("usage").path("completion_tokens").takeIf { !it.isMissingNode }?.asInt(),
                modelUsed = body.path("model").takeIf { !it.isMissingNode }?.asText(),
                finishReason = when (firstChoice.path("finish_reason").asText("")) {
                    "stop" -> FinishReason.STOP
                    "length" -> FinishReason.LENGTH
                    "tool_calls" -> FinishReason.STOP // We map tool_calls to STOP for simple orchestration
                    "content_filter" -> FinishReason.CONTENT_FILTER
                    else -> FinishReason.OTHER
                },
            )
        } catch (error: Throwable) {
            throw providerTransportFailure(providerName, error)
        }
    }

    override fun providerId(): String = providerName

    override suspend fun stream(request: ModelRequest): Flow<StreamChunk> = flow {
        val payload = linkedMapOf<String, Any?>(
            "model" to request.model,
            "stream" to true,
            "messages" to request.messages.map { message -> messageToMap(message) },
        )

        request.maxTokens?.let { payload["max_tokens"] = it }
        request.temperature?.let { payload["temperature"] = it }

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}/chat/completions"))
            .header("Authorization", "Bearer ${accessTokenSource.accessToken()}")
            .header("Content-Type", "application/json")
            .apply {
                organization?.takeIf { it.isNotBlank() }?.let { header("OpenAI-Organization", it) }
                project?.takeIf { it.isNotBlank() }?.let { header("OpenAI-Project", it) }
            }
            .applyTramaiTimeout(request)
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build()

        val response = withContext(Dispatchers.IO) {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())
        }

        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().toArray().joinToString("\n")
            logProviderHttpFailureDebug(
                logger = providerLogger,
                providerName = providerName,
                statusCode = response.statusCode(),
                body = errorBody,
            )
            emit(
                StreamChunk.Error(
                    providerHttpFailure(
                        providerName = providerName,
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
            emit(StreamChunk.Error(providerTransportFailure(providerName, e)))
        }
    }

    private fun extractContent(contentNode: JsonNode): String {
        if (contentNode.isTextual) {
            return contentNode.asText("")
        }

        if (contentNode.isArray) {
            return contentNode.mapNotNull { part ->
                when {
                    part.path("type").asText("") in setOf("text", "output_text") -> part.path("text").asText("").takeIf { it.isNotBlank() }
                    part.hasNonNull("text") -> part.path("text").asText("").takeIf { it.isNotBlank() }
                    else -> null
                }
            }.joinToString(separator = "\n")
        }

        return contentNode.path("text").asText("")
    }

    /**
     * Converts a Tramai [Message] to an OpenAI-compatible request map.
     *
     * When the message carries [ContentPart.ImagePart] items, the content is
     * serialised as a JSON content-array; otherwise a plain string is used.
     */
    private fun messageToMap(message: dev.tramai.core.model.Message): Map<String, Any?> {
        val msgMap = mutableMapOf<String, Any?>(
            "role" to message.role.name.lowercase(),
        )

        val msgParts = message.contentParts
        if (msgParts != null && msgParts.isNotEmpty()) {
            msgMap["content"] = msgParts.map { part ->
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
        private val providerLogger: System.Logger = System.getLogger(OpenAiCompatibleProvider::class.java.name)

        val SUPPORTED_IMAGE_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

        /**
         * Creates an OpenAI-compatible provider backed by a static bearer token.
         */
        @JvmStatic
        fun bearerToken(
            bearerToken: String,
            baseUrl: String,
            providerName: String = "openai-compatible",
            httpClient: HttpClient = HttpClient.newHttpClient(),
            objectMapper: ObjectMapper = ObjectMapper(),
        ): OpenAiCompatibleProvider = OpenAiCompatibleProvider(
            accessTokenSource = StaticOpenAiAccessTokenSource(bearerToken),
            providerName = providerName,
            baseUrl = baseUrl,
            httpClient = httpClient,
            objectMapper = objectMapper,
        )

        /**
         * Creates an OpenAI-compatible provider that reads its bearer token from Codex ChatGPT auth.
         *
         * Experimental: intended for local testing and exploratory integrations.
         */
        @ExperimentalCodexAuth
        @JvmStatic
        fun codexAuth(
            baseUrl: String,
            providerName: String = "openai-compatible",
            authFile: Path = CodexAuthFileTokenSource.defaultAuthFile(),
            httpClient: HttpClient = HttpClient.newHttpClient(),
            objectMapper: ObjectMapper = ObjectMapper(),
        ): OpenAiCompatibleProvider = OpenAiCompatibleProvider(
            accessTokenSource = CodexAuthFileTokenSource(authFile = authFile, objectMapper = objectMapper),
            providerName = providerName,
            baseUrl = baseUrl,
            httpClient = httpClient,
            objectMapper = objectMapper,
        )
    }
}

/**
 * Provider for OpenAI's public API.
 */
class OpenAiProvider(
    accessTokenSource: OpenAiAccessTokenSource,
    baseUrl: String = DEFAULT_BASE_URL,
    httpClient: HttpClient = HttpClient.newHttpClient(),
    objectMapper: ObjectMapper = ObjectMapper(),
    organization: String? = null,
    project: String? = null,
) : OpenAiCompatibleProvider(
    accessTokenSource = accessTokenSource,
    providerName = "openai",
    baseUrl = baseUrl,
    httpClient = httpClient,
    objectMapper = objectMapper,
    organization = organization,
    project = project,
) {
    /**
     * Creates an OpenAI provider using a standard API key.
     */
    constructor(
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        httpClient: HttpClient = HttpClient.newHttpClient(),
        objectMapper: ObjectMapper = ObjectMapper(),
        organization: String? = null,
        project: String? = null,
    ) : this(
        accessTokenSource = StaticOpenAiAccessTokenSource(apiKey),
        baseUrl = baseUrl,
        httpClient = httpClient,
        objectMapper = objectMapper,
        organization = organization,
        project = project,
    )

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"

        /**
         * Creates an OpenAI provider using a static bearer token.
         */
        @JvmStatic
        fun bearerToken(
            bearerToken: String,
            baseUrl: String = DEFAULT_BASE_URL,
            httpClient: HttpClient = HttpClient.newHttpClient(),
            objectMapper: ObjectMapper = ObjectMapper(),
            organization: String? = null,
            project: String? = null,
        ): OpenAiProvider = OpenAiProvider(
            accessTokenSource = StaticOpenAiAccessTokenSource(bearerToken),
            baseUrl = baseUrl,
            httpClient = httpClient,
            objectMapper = objectMapper,
            organization = organization,
            project = project,
        )

        /**
         * Creates an OpenAI provider that reads the bearer token from Codex ChatGPT auth.
         *
         * Experimental: intended for local testing and exploratory integrations.
         */
        @ExperimentalCodexAuth
        @JvmStatic
        fun codexAuth(
            authFile: Path = CodexAuthFileTokenSource.defaultAuthFile(),
            baseUrl: String = DEFAULT_BASE_URL,
            httpClient: HttpClient = HttpClient.newHttpClient(),
            objectMapper: ObjectMapper = ObjectMapper(),
            organization: String? = null,
            project: String? = null,
        ): OpenAiProvider = OpenAiProvider(
            accessTokenSource = CodexAuthFileTokenSource(authFile = authFile, objectMapper = objectMapper),
            baseUrl = baseUrl,
            httpClient = httpClient,
            objectMapper = objectMapper,
            organization = organization,
            project = project,
        )
    }
}
