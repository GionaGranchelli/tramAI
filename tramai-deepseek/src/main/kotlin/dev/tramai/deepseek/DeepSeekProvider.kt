package dev.tramai.deepseek

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.StreamCapable
import dev.tramai.openai.OpenAiCompatibleProvider
import dev.tramai.openai.StaticOpenAiAccessTokenSource
import java.net.http.HttpClient
import kotlinx.coroutines.flow.Flow

/**
 * Provider for DeepSeek's OpenAI-compatible chat completion API.
 *
 * DeepSeek uses the same `/chat/completions` request/response format as OpenAI,
 * making this a thin wrapper around [OpenAiCompatibleProvider].
 *
 * Supports: text, streaming, structured output, tools, vision (via DeepSeek's
 * multimodal models).
 *
 * Configuration via environment / system properties:
 * - `tramai.providers.deepseek.api-key` — DeepSeek API key
 * - `tramai.providers.deepseek.base-url` — Defaults to https://api.deepseek.com/v1
 * - `tramai.providers.deepseek.model` — Defaults to "deepseek-chat"
 */
class DeepSeekProvider @JvmOverloads constructor(
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    httpClient: HttpClient = HttpClient.newHttpClient(),
    objectMapper: ObjectMapper = ObjectMapper(),
) : ModelProvider, StreamCapable {

    private val delegate: OpenAiCompatibleProvider = OpenAiCompatibleProvider(
        accessTokenSource = StaticOpenAiAccessTokenSource(apiKey),
        providerName = PROVIDER_ID,
        baseUrl = baseUrl,
        httpClient = httpClient,
        objectMapper = objectMapper,
    )

    override fun providerId(): String = PROVIDER_ID

    override fun supportsCapability(capability: ProviderCapability): Boolean = when (capability) {
        ProviderCapability.VISION -> true
        ProviderCapability.TOOL_CALLING -> true
        ProviderCapability.STRUCTURED_OUTPUT -> true
        ProviderCapability.STREAMING -> true
    }

    override suspend fun complete(request: ModelRequest): ModelResponse =
        delegate.complete(request)

    override fun stream(request: ModelRequest): Flow<StreamChunk> =
        delegate.stream(request)

    companion object {
        private const val PROVIDER_ID = "deepseek"
        const val DEFAULT_BASE_URL: String = "https://api.deepseek.com/v1"
    }
}
