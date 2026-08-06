package dev.tramai.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.ProviderFailureCode
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.FinishReason
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
import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.provider.applyTramaiTimeout
import dev.tramai.core.provider.logProviderHttpFailureDebug
import dev.tramai.core.provider.providerHttpFailureObserved
import dev.tramai.core.provider.providerTransportFailureObserved
import dev.tramai.core.provider.readErrorBodyPreview
import dev.tramai.core.provider.safeProviderFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import java.net.URI
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.nio.charset.StandardCharsets.UTF_8
import java.util.stream.Stream

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
open class OpenAiCompatibleProvider @JvmOverloads constructor(
    private val accessTokenSource: OpenAiAccessTokenSource,
    private val providerName: String = PROVIDER_LABEL,
    private val baseUrl: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val organization: String? = null,
    private val project: String? = null,
    private val ioDispatcher: CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
    private val providerFailureDiagnosticObserver: ProviderFailureDiagnosticObserver =
        NoOpProviderFailureDiagnosticObserver,
    private val diagnosticProviderId: String = PROVIDER_DIAGNOSTIC_ID,
) : ModelProvider, StreamCapable {

    override suspend fun complete(request: ModelRequest): ModelResponse = withContext(ioDispatcher) {
        try {
            val payload = linkedMapOf<String, Any?>(
                "model" to request.model,
                "stream" to false,
                "messages" to request.messages.map { message -> messageToMap(message, request.imageDetail) },
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

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                val errorBody = readErrorBodyPreview(response.body())
                logProviderHttpFailureDebug(
                    logger = providerLogger,
                    providerName = diagnosticProviderId,
                    statusCode = response.statusCode(),
                    body = errorBody.text,
                )
                throw providerHttpFailureObserved(
                    providerId = diagnosticProviderId,
                    providerAlias = providerName,
                    statusCode = response.statusCode(),
                    body = errorBody.text,
                    bodyTruncated = errorBody.truncated,
                    retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null),
                    observer = providerFailureDiagnosticObserver,
                )
            }

            val body = objectMapper.readTree(response.body().use { it.readAllBytes().decodeToString() })
            val firstChoice = body.path("choices").firstOrNull()
                ?: throw safeProviderFailure(
                    "Provider response did not contain any completion choices",
                    ProviderFailureCode.UNEXPECTED_FAILURE,
                )
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
                thinkingTokens = body.path("usage").path("completion_tokens_details").path("reasoning_tokens").takeIf { !it.isMissingNode }?.asInt(),
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
            error.rethrowIfCancellation()
            throw providerTransportFailureObserved(
                diagnosticProviderId,
                providerName,
                error,
                providerFailureDiagnosticObserver,
            )
        }
    }

    override fun providerId(): String = providerName

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
                    "messages" to request.messages.map { message -> messageToMap(message, request.imageDetail) },
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

                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
            }
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            emit(
                StreamChunk.Error(
                    providerTransportFailureObserved(
                        diagnosticProviderId,
                        providerName,
                        error,
                        providerFailureDiagnosticObserver,
                    ),
                ),
            )
            return@flow
        }

        if (handleHttpResponseError(response)) return@flow

        try {
            val lines = BufferedReader(InputStreamReader(response.body(), UTF_8)).lines()
            val (text, usage) = parseSseLines(lines)
            emit(StreamChunk.Complete(text, usage))
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            emit(
                StreamChunk.Error(
                    providerTransportFailureObserved(
                        diagnosticProviderId,
                        providerName,
                        e,
                        providerFailureDiagnosticObserver,
                    ),
                ),
            )
        }
    }

    /**
     * Handles HTTP error responses (non-2xx status codes) from the OpenAI-compatible API.
     * Reads a bounded preview of the error body, logs metadata only, and emits a
     * [StreamChunk.Error].
     *
     * @return `true` if the response was an error (non-2xx), `false` if the response is OK.
     */
    private suspend fun FlowCollector<StreamChunk>.handleHttpResponseError(
        response: HttpResponse<InputStream>,
    ): Boolean {
        if (response.statusCode() in 200..299) return false

        val errorBody = try {
            readErrorBodyPreview(response.body())
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            emit(
                StreamChunk.Error(
                    providerTransportFailureObserved(
                        diagnosticProviderId,
                        providerName,
                        error,
                        providerFailureDiagnosticObserver,
                    ),
                ),
            )
            return true
        }
        logProviderHttpFailureDebug(
            logger = providerLogger,
            providerName = diagnosticProviderId,
            statusCode = response.statusCode(),
            body = errorBody.text,
        )
        emit(
            StreamChunk.Error(
                providerHttpFailureObserved(
                    providerId = diagnosticProviderId,
                    providerAlias = providerName,
                    statusCode = response.statusCode(),
                    body = errorBody.text,
                    bodyTruncated = errorBody.truncated,
                    retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null),
                    observer = providerFailureDiagnosticObserver,
                ),
            ),
        )
        return true
    }

    /**
     * Parsed result of a single SSE data line.
     */
    private data class SseData(
        val isDone: Boolean = false,
        val tokenText: String = "",
        val usage: UsageMetrics? = null,
    )

    /**
     * Parses a single SSE `data: ` line body from an OpenAI-compatible stream.
     *
     * @param dataLine the raw JSON payload after stripping the `data: ` prefix (already trimmed).
     * @return [SseData] describing whether the stream is done, the delta token, and any usage metrics.
     */
    private fun parseSseData(dataLine: String): SseData {
        if (dataLine == "[DONE]") return SseData(isDone = true)

        val chunk = objectMapper.readTree(dataLine)

        val delta = chunk.path("choices").firstOrNull()?.path("delta")
        val content = delta?.path("content")?.asText("") ?: ""

        val usageNode = chunk.path("usage")
        val usage = if (!usageNode.isMissingNode) {
            UsageMetrics(
                inputTokens = usageNode.path("prompt_tokens").asInt(),
                outputTokens = usageNode.path("completion_tokens").asInt(),
                thinkingTokens = usageNode.path("completion_tokens_details").path("reasoning_tokens").takeIf { !it.isMissingNode }?.asInt(),
            )
        } else null

        return SseData(tokenText = content, usage = usage)
    }

    /**
     * Parses an OpenAI-compatible SSE (Server-Sent Events) stream response.
     * Handles `data: ` prefix stripping, `[DONE]` delimiter detection,
     * delta/content extraction, and usage metrics tracking.
     *
     * @return A [Pair] of the accumulated full response text and final [UsageMetrics].
     */
    private suspend fun FlowCollector<StreamChunk>.parseSseLines(
        lines: Stream<String>,
    ): Pair<String, UsageMetrics> {
        val fullText = StringBuilder()
        var lastUsage: UsageMetrics? = null

        lines.use { lineStream ->
            for (line in lineStream) {
                if (!line.startsWith("data: ")) continue

                val data = line.substring(6).trim()
                val result = parseSseData(data)
                if (result.isDone) break
                if (result.tokenText.isNotEmpty()) {
                    fullText.append(result.tokenText)
                    emit(StreamChunk.Token(result.tokenText))
                }
                if (result.usage != null) {
                    lastUsage = result.usage
                }
            }
        }

        return Pair(fullText.toString(), lastUsage ?: UsageMetrics())
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
    private fun messageToMap(
        message: dev.tramai.core.model.Message,
        imageDetail: dev.tramai.core.model.ImageDetail = dev.tramai.core.model.ImageDetail.AUTO,
    ): Map<String, Any?> {
        val msgMap = mutableMapOf<String, Any?>(
            "role" to message.role.name.lowercase(),
        )

        val msgParts = message.contentParts
        if (!msgParts.isNullOrEmpty()) {
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
                        val imageUrl = mutableMapOf<String, Any?>(
                            "url" to "data:${part.mimeType};base64,${Base64.getEncoder().encodeToString(part.data)}",
                        )
                        if (imageDetail != dev.tramai.core.model.ImageDetail.AUTO) {
                            imageUrl["detail"] = imageDetail.name.lowercase()
                        }
                        mapOf(
                            "type" to "image_url",
                            "image_url" to imageUrl,
                        )
                    }
                    is ContentPart.ImageUrlContent -> {
                        val imageUrl = mutableMapOf<String, Any?>(
                            "url" to part.url,
                        )
                        if (imageDetail != dev.tramai.core.model.ImageDetail.AUTO) {
                            imageUrl["detail"] = imageDetail.name.lowercase()
                        }
                        mapOf(
                            "type" to "image_url",
                            "image_url" to imageUrl,
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
            providerName: String = PROVIDER_LABEL,
            httpClient: HttpClient = HttpClient.newHttpClient(),
            objectMapper: ObjectMapper = ObjectMapper(),
            providerFailureDiagnosticObserver: ProviderFailureDiagnosticObserver =
                NoOpProviderFailureDiagnosticObserver,
        ): OpenAiCompatibleProvider = OpenAiCompatibleProvider(
            accessTokenSource = StaticOpenAiAccessTokenSource(bearerToken),
            providerName = providerName,
            baseUrl = baseUrl,
            httpClient = httpClient,
            objectMapper = objectMapper,
            providerFailureDiagnosticObserver = providerFailureDiagnosticObserver,
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
            providerName: String = PROVIDER_LABEL,
            authFile: Path = CodexAuthFileTokenSource.defaultAuthFile(),
            httpClient: HttpClient = HttpClient.newHttpClient(),
            objectMapper: ObjectMapper = ObjectMapper(),
            providerFailureDiagnosticObserver: ProviderFailureDiagnosticObserver =
                NoOpProviderFailureDiagnosticObserver,
        ): OpenAiCompatibleProvider = OpenAiCompatibleProvider(
            accessTokenSource = CodexAuthFileTokenSource(authFile = authFile, objectMapper = objectMapper),
            providerName = providerName,
            baseUrl = baseUrl,
            httpClient = httpClient,
            objectMapper = objectMapper,
            providerFailureDiagnosticObserver = providerFailureDiagnosticObserver,
        )
    }
}

/** @see OpenAiCompatibleProvider */
private const val PROVIDER_LABEL = "openai-compatible"
private const val PROVIDER_DIAGNOSTIC_ID = "openai"

/**
 * Provider for OpenAI's public API.
 */
class OpenAiProvider @JvmOverloads constructor(
    accessTokenSource: OpenAiAccessTokenSource,
    baseUrl: String = DEFAULT_BASE_URL,
    httpClient: HttpClient = HttpClient.newHttpClient(),
    objectMapper: ObjectMapper = ObjectMapper(),
    organization: String? = null,
    project: String? = null,
    providerFailureDiagnosticObserver: ProviderFailureDiagnosticObserver =
        NoOpProviderFailureDiagnosticObserver,
) : OpenAiCompatibleProvider(
    accessTokenSource = accessTokenSource,
    providerName = "openai",
    baseUrl = baseUrl,
    httpClient = httpClient,
    objectMapper = objectMapper,
    organization = organization,
    project = project,
    providerFailureDiagnosticObserver = providerFailureDiagnosticObserver,
) {
    /**
     * Creates an OpenAI provider using a standard API key.
     */
    @JvmOverloads
    constructor(
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        httpClient: HttpClient = HttpClient.newHttpClient(),
        objectMapper: ObjectMapper = ObjectMapper(),
        organization: String? = null,
        project: String? = null,
        providerFailureDiagnosticObserver: ProviderFailureDiagnosticObserver =
            NoOpProviderFailureDiagnosticObserver,
    ) : this(
        accessTokenSource = StaticOpenAiAccessTokenSource(apiKey),
        baseUrl = baseUrl,
        httpClient = httpClient,
        objectMapper = objectMapper,
        organization = organization,
        project = project,
        providerFailureDiagnosticObserver = providerFailureDiagnosticObserver,
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
            providerFailureDiagnosticObserver: ProviderFailureDiagnosticObserver =
                NoOpProviderFailureDiagnosticObserver,
        ): OpenAiProvider = OpenAiProvider(
            accessTokenSource = StaticOpenAiAccessTokenSource(bearerToken),
            baseUrl = baseUrl,
            httpClient = httpClient,
            objectMapper = objectMapper,
            organization = organization,
            project = project,
            providerFailureDiagnosticObserver = providerFailureDiagnosticObserver,
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
            providerFailureDiagnosticObserver: ProviderFailureDiagnosticObserver =
                NoOpProviderFailureDiagnosticObserver,
        ): OpenAiProvider = OpenAiProvider(
            accessTokenSource = CodexAuthFileTokenSource(authFile = authFile, objectMapper = objectMapper),
            baseUrl = baseUrl,
            httpClient = httpClient,
            objectMapper = objectMapper,
            organization = organization,
            project = project,
            providerFailureDiagnosticObserver = providerFailureDiagnosticObserver,
        )
    }
}
