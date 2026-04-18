package io.aurora.core.model

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

data class Message(
    val role: MessageRole,
    val content: String,
)
