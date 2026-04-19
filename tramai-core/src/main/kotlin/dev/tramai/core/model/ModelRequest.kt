package dev.tramai.core.model

/**
 * Definition of an external tool presented to the model.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
)

/**
 * Normalized provider request produced by the engine.
 */
data class ModelRequest(
    /** Requested model identifier. */
    val model: String,
    /** Ordered chat history sent to the provider. */
    val messages: List<Message>,
    /** Optional list of tool definitions available to the model. */
    val tools: List<ToolDefinition>? = null,
    /** Optional provider-specific maximum token budget. */
    val maxTokens: Int? = null,
    /** Optional provider-specific temperature override. */
    val temperature: Double? = null,
    /** Maximum duration, in milliseconds, allowed for a single provider attempt. */
    val timeoutMillis: Long? = null,
    /** Fully qualified service interface name, when known. */
    val operationInterface: String? = null,
    /** Service method name, when known. */
    val operationMethod: String? = null,
)
