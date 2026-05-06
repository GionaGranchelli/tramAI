package dev.tramai.examples.supportagent

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.System as SystemMessage
import dev.tramai.core.annotations.User as UserMessage

@AiService
interface SupportAgent {
    @SystemMessage("You are a Tier-1 support agent. Be concise and helpful.")
    @UserMessage("Customer issue: {message}")
    @Operation(model = "gemma4:e2b", tools = ["lookupOrder"])
    suspend fun handle(message: String): Response
}
