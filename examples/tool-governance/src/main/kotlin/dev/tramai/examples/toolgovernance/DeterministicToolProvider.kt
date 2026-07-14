package dev.tramai.examples.toolgovernance

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ToolCall
import dev.tramai.core.provider.ModelProvider
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic mock provider that returns tool calls on the first invocation
 * and a final answer on subsequent invocations.
 *
 * Never calls a real model — requires no credentials, no network, no Docker.
 */
class DeterministicToolProvider(
    private val toolName: String,
    private val toolCallArgs: String = "{}",
) : ModelProvider {
    val callCount = AtomicInteger(0)

    override fun providerId() = "deterministic-provider"

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val count = callCount.incrementAndGet()
        return if (count == 1) {
            ModelResponse(
                content = "",
                inputTokens = 10,
                outputTokens = 5,
                modelUsed = request.model,
                toolCalls = listOf(ToolCall("call-1", toolName, toolCallArgs)),
            )
        } else {
            ModelResponse(
                content = "done",
                inputTokens = 10,
                outputTokens = 5,
                modelUsed = request.model,
            )
        }
    }
}
