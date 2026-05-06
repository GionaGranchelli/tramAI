package dev.tramai.examples.supportagent

import dev.tramai.core.annotations.AiDescription

/**
 * Structured output for the support agent.
 * Tramai automatically generates a JSON schema from this data class,
 * so the LLM always returns typed, parseable data.
 */
data class Response(
    @AiDescription("Friendly answer to the customer's question")
    val answer: String,

    @AiDescription("Action taken (e.g., 'refunded', 'escalated', 'resolved')")
    val action: String? = null,

    @AiDescription("Estimated resolution or delivery date, if known")
    val eta: String? = null,

    @AiDescription("Whether the issue was fully resolved")
    val resolved: Boolean = false,
)
