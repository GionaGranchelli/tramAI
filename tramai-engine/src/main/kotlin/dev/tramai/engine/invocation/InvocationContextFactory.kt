package dev.tramai.engine.invocation

import dev.tramai.core.memory.ChatMemory
import dev.tramai.engine.memory.ConversationMemoryCoordinator
import dev.tramai.engine.planning.OperationExecutionPlan
import java.lang.reflect.Method

/**
 * Builds the pre-dispatch [InvocationExecutionContext] for a JVM proxy
 * invocation. Conversation-ID resolution happens here — before the
 * suspend/blocking dispatch — exactly as it did in the monolithic handler, so
 * timing/error semantics for suspend methods are unchanged.
 */
internal class InvocationContextFactory(
    private val chatMemory: ChatMemory?,
    private val conversationMemoryCoordinator: ConversationMemoryCoordinator,
) {
    fun create(
        plan: OperationExecutionPlan,
        method: Method,
        args: Array<out Any?>,
    ): InvocationExecutionContext {
        val conversationId = if (chatMemory != null) {
            conversationMemoryCoordinator.resolveConversationId(method, args)
        } else {
            null
        }
        return InvocationExecutionContext(
            plan = plan,
            arguments = args.toList(),
            conversationId = conversationId,
        )
    }
}
