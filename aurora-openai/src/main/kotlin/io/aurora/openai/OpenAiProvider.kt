package io.aurora.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.aurora.core.exception.ConfigurationException
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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
 * This is intended for local experimentation and testing rather than as Aurora's primary
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
) : ModelProvider {

    override suspend fun complete(request: ModelRequest): ModelResponse = withContext(Dispatchers.IO) {
        val payload = linkedMapOf<String, Any?>(
            "model" to request.model,
            "stream" to false,
            "messages" to request.messages.map { message ->
                mapOf(
                    "role" to message.role.name.lowercase(),
                    "content" to message.content,
                )
            },
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
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw ProviderException("$providerName returned HTTP ${response.statusCode()}: ${response.body()}")
        }

        val body = objectMapper.readTree(response.body())
        val firstChoice = body.path("choices").firstOrNull()
            ?: throw ProviderException("$providerName response did not contain any completion choices")
        val message = firstChoice.path("message")

        ModelResponse(
            content = extractContent(message.path("content")),
            inputTokens = body.path("usage").path("prompt_tokens").takeIf { !it.isMissingNode }?.asInt(),
            outputTokens = body.path("usage").path("completion_tokens").takeIf { !it.isMissingNode }?.asInt(),
            modelUsed = body.path("model").takeIf { !it.isMissingNode }?.asText(),
            finishReason = when (firstChoice.path("finish_reason").asText("")) {
                "stop" -> FinishReason.STOP
                "length" -> FinishReason.LENGTH
                "content_filter" -> FinishReason.CONTENT_FILTER
                else -> FinishReason.OTHER
            },
        )
    }

    override fun providerId(): String = providerName

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

    companion object {
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
