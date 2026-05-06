package dev.tramai.examples.supportagent

import dev.tramai.core.annotations.AiDescription

data class Response(
    @AiDescription("Answer to the customer")
    val answer: String,
    @AiDescription("Action taken, if any")
    val action: String? = null,
)
