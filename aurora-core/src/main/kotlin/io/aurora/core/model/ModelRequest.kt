package io.aurora.core.model

data class ModelRequest(
    val model: String,
    val messages: List<Message>,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val operationInterface: String? = null,
    val operationMethod: String? = null,
)
