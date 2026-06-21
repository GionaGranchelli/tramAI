package dev.tramai.bedrock

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.UsageMetrics
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.StreamCapable
import dev.tramai.core.provider.providerTransportFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * [ModelProvider] implementation for Amazon Bedrock using the InvokeModel API.
 *
 * This implementation uses the raw JSON-based InvokeModel API, which sends
 * model-specific payloads and receives model-specific responses. The provider
 * handles the Claude (Anthropic) message format by default, translating Tramai's
 * unified message model into the expected request/response shapes.
 *
 * Authentication uses AWS Signature V4 via the default credentials provider chain
 * (environment variables, ~/.aws/credentials, IAM roles, etc.).
 *
 * Configuration:
 * - `tramai.providers.bedrock.region` — AWS region (e.g. "us-west-2")
 * - `tramai.providers.bedrock.model` — Model ID (e.g. "anthropic.claude-3-sonnet-20240229-v1:0")
 *
 * Supports: text, streaming, tools, vision (for multimodal models).
 * Structured output depends on the underlying model.
 */
class BedrockProvider @JvmOverloads constructor(
    private val region: String,
    private val modelId: String = DEFAULT_MODEL_ID,
    private val credentialsProvider: AwsCredentialsProvider = DefaultCredentialsProvider.create(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
) : ModelProvider, StreamCapable {

    override fun providerId(): String = PROVIDER_ID

    override fun supportsCapability(capability: ProviderCapability): Boolean = when (capability) {
        ProviderCapability.VISION -> true
        ProviderCapability.TOOL_CALLING -> true
        ProviderCapability.STRUCTURED_OUTPUT -> true
        ProviderCapability.STREAMING -> true
    }

    override suspend fun complete(request: ModelRequest): ModelResponse = withContext(ioDispatcher) {
        try {
            val effectiveModel = request.model.takeIf { it.isNotBlank() } ?: modelId
            val client = buildClient()
            val payload = buildClaudePayload(request)

            val invokeRequest = InvokeModelRequest.builder()
                .modelId(effectiveModel)
                .body(SdkBytes.fromUtf8String(objectMapper.writeValueAsString(payload)))
                .contentType("application/json")
                .build()

            val response = client.invokeModel(invokeRequest)
            val body = objectMapper.readTree(response.body().asUtf8String())
            mapClaudeResponse(body, effectiveModel)
        } catch (error: Throwable) {
            throw providerTransportFailure(PROVIDER_ID, error)
        }
    }

    override fun stream(request: ModelRequest): Flow<StreamChunk> = flow {
        val effectiveModel = request.model.takeIf { it.isNotBlank() } ?: modelId

        try {
            val client = withContext(ioDispatcher) { buildClient() }
            val payload = buildClaudePayload(request)

            val invokeRequest = InvokeModelRequest.builder()
                .modelId(effectiveModel)
                .body(SdkBytes.fromUtf8String(objectMapper.writeValueAsString(payload)))
                .contentType("application/json")
                .build()

            val response = withContext(ioDispatcher) {
                client.invokeModel(invokeRequest)
            }
            val body = objectMapper.readTree(response.body().asUtf8String())
            val fullText = mapClaudeContent(body)
            emit(StreamChunk.Token(fullText))
            emit(StreamChunk.Complete(
                fullText = fullText,
                usage = extractUsage(body),
            ))
        } catch (e: Exception) {
            emit(StreamChunk.Error(providerTransportFailure(PROVIDER_ID, e)))
        }
    }

    // ---- Client construction ----

    private fun buildClient(): BedrockRuntimeClient {
        return BedrockRuntimeClient.builder()
            .region(Region.of(region))
            .credentialsProvider(credentialsProvider)
            .build()
    }

    // ---- Payload construction (Claude Messages format) ----

    private fun buildClaudePayload(request: ModelRequest): Map<String, Any?> {
        val payload = linkedMapOf<String, Any?>(
            "anthropic_version" to "bedrock-2023-05-31",
            "max_tokens" to (request.maxTokens ?: 1024),
            "messages" to request.messages
                .filter { it.role != MessageRole.SYSTEM }
                .map { message -> buildClaudeMessage(message) },
        )

        request.temperature?.let { payload["temperature"] = it }

        // System prompt
        val systemMessage = request.messages.firstOrNull { it.role == MessageRole.SYSTEM }?.content
        if (!systemMessage.isNullOrBlank()) {
            payload["system"] = systemMessage
        }

        // Tool definitions (Claude tool format)
        request.tools?.let { tools ->
            payload["tools"] = tools.map { tool ->
                mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "input_schema" to objectMapper.readTree(tool.inputSchemaJson),
                )
            }
        }

        return payload
    }

    @Suppress("UNCHECKED_CAST")
    internal fun buildClaudeMessage(message: dev.tramai.core.model.Message): Map<String, Any?> {
        val role = when (message.role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.TOOL -> "user"
            MessageRole.SYSTEM -> "user"
        }

        val contentBlocks = mutableListOf<Map<String, Any?>>()

        // Handle tool calls (assistant role)
        message.toolCalls?.let { toolCalls ->
            for (tc in toolCalls) {
                val argsNode = objectMapper.readTree(tc.argumentsJson)
                contentBlocks.add(
                    mapOf(
                        "type" to "tool_use",
                        "id" to tc.id,
                        "name" to tc.name,
                        "input" to argsNode,
                    )
                )
            }
        }

        // Handle tool result
        if (message.role == MessageRole.TOOL) {
            contentBlocks.add(
                mapOf(
                    "type" to "tool_result",
                    "tool_use_id" to (message.toolCallId ?: "unknown"),
                    "content" to message.content,
                )
            )
        }

        // Handle regular content
        val msgParts = message.contentParts
        if (!msgParts.isNullOrEmpty()) {
            for (part in msgParts) {
                when (part) {
                    is ContentPart.TextPart -> {
                        contentBlocks.add(mapOf("type" to "text", "text" to part.text))
                    }
                    is ContentPart.ImagePart -> {
                        require(part.mimeType in SUPPORTED_IMAGE_TYPES) {
                            "Unsupported image mimeType '${part.mimeType}'. Supported types: $SUPPORTED_IMAGE_TYPES"
                        }
                        contentBlocks.add(
                            mapOf(
                                "type" to "image",
                                "source" to mapOf(
                                    "type" to "base64",
                                    "media_type" to part.mimeType,
                                    "data" to Base64.getEncoder().encodeToString(part.data),
                                ),
                            )
                        )
                    }
                    is ContentPart.ImageUrlContent -> {
                        val resolved = dev.tramai.core.util.ImageDownloader.resolveToImagePart(part) as ContentPart.ImagePart
                        require(resolved.mimeType in SUPPORTED_IMAGE_TYPES) {
                            "Unsupported image mimeType '${resolved.mimeType}'. Supported types: $SUPPORTED_IMAGE_TYPES"
                        }
                        contentBlocks.add(
                            mapOf(
                                "type" to "image",
                                "source" to mapOf(
                                    "type" to "base64",
                                    "media_type" to resolved.mimeType,
                                    "data" to Base64.getEncoder().encodeToString(resolved.data),
                                ),
                            )
                        )
                    }
                }
            }
        } else if (message.content.isNotBlank() && contentBlocks.isEmpty()) {
            contentBlocks.add(mapOf("type" to "text", "text" to message.content))
        }

        return mapOf(
            "role" to role,
            "content" to contentBlocks,
        )
    }

    // ---- Response mapping (Claude format) ----

    private fun mapClaudeResponse(body: JsonNode, effectiveModel: String): ModelResponse {
        val contentArray = body.path("content")
        val text = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        var index = 0

        if (contentArray.isArray) {
            for (block in contentArray) {
                val type = block.path("type").asText("")
                when (type) {
                    "text" -> text.append(block.path("text").asText(""))
                    "tool_use" -> {
                        val name = block.path("name").asText("")
                        val inputNode = block.path("input")
                        toolCalls.add(
                            ToolCall(
                                id = block.path("id").asText("tc_${name}_$index"),
                                name = name,
                                argumentsJson = objectMapper.writeValueAsString(inputNode),
                            )
                        )
                        index++
                    }
                }
            }
        }

        val stopReason = body.path("stop_reason").asText("")
        val usage = body.path("usage")

        return ModelResponse(
            content = text.toString(),
            toolCalls = toolCalls.ifEmpty { null },
            inputTokens = usage.path("input_tokens").takeIf { !it.isMissingNode }?.asInt(),
            outputTokens = usage.path("output_tokens").takeIf { !it.isMissingNode }?.asInt(),
            modelUsed = effectiveModel,
            finishReason = when (stopReason) {
                "end_turn" -> FinishReason.STOP
                "max_tokens" -> FinishReason.LENGTH
                "tool_use" -> FinishReason.STOP
                "content_filtered" -> FinishReason.CONTENT_FILTER
                "stop_sequence" -> FinishReason.STOP
                else -> FinishReason.OTHER
            },
        )
    }

    private fun mapClaudeContent(body: JsonNode): String {
        val contentArray = body.path("content")
        val text = StringBuilder()
        if (contentArray.isArray) {
            for (block in contentArray) {
                if (block.path("type").asText("") == "text") {
                    text.append(block.path("text").asText(""))
                }
            }
        }
        return text.toString()
    }

    private fun extractUsage(body: JsonNode): UsageMetrics {
        val usage = body.path("usage")
        return UsageMetrics(
            inputTokens = usage.path("input_tokens").takeIf { !it.isMissingNode }?.asInt(),
            outputTokens = usage.path("output_tokens").takeIf { !it.isMissingNode }?.asInt(),
        )
    }

    companion object {
        private const val PROVIDER_ID = "bedrock"

        const val DEFAULT_MODEL_ID: String = "anthropic.claude-3-sonnet-20240229-v1:0"

        val SUPPORTED_IMAGE_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }
}
