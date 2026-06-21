package dev.tramai.examples.supportagent

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.System as SystemMessage
import dev.tramai.core.annotations.User as UserMessage

/**
 * TramAI service interface — the core abstraction.
 *
 * Annotate an interface with [@AiService], define methods with [@Operation],
 * and TramAI generates the proxy that routes calls through the LLM provider,
 * handles structured output parsing, tool selection, and retries.
 */
@AiService
fun interface SupportAgent {

    @SystemMessage("""You are a Tier-1 support agent for an online store.
Respond concisely and helpfully. Use the available tools when you need to look up
or modify order data. If you cannot resolve the issue, escalate and set resolved=false.""")

    @UserMessage("Customer issue: {message}")

    @Operation(
        model = "gemma4:e2b",
        tools = ["lookupOrder", "getCurrentTime"],
        maxRetries = 2,
    )
    suspend fun handle(message: String): Response
}
