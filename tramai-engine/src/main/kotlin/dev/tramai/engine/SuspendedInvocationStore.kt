package dev.tramai.engine

import dev.tramai.core.model.Message
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ResolvedTool

/**
 * Safe invocation metadata stored when a tool execution is suspended pending approval.
 *
 * Holds only safe state needed to resume the provider loop:
 * - NO raw tool arguments — those stay in the [dev.tramai.core.approval.ApprovalContinuationStore]
 * - NO approval tokens
 * - NO sensitive tool payloads
 *
 * @property approvalId The ID of the approval challenge this suspension corresponds to.
 * @property operation The operation definition being executed when suspension occurred.
 * @property toolCall The tool call that triggered suspension (without raw arguments in the store).
 * @property tool The resolved tool metadata.
 * @property messages The current message history at suspension point.
 * @property toolCallIndex The index of this tool call within the batch.
 * @property correlationId The correlation ID of the suspended invocation.
 * @property identity The engine execution identity at suspension point.
 */
data class SuspendedInvocation(
    val approvalId: String,
    val operation: OperationDefinition,
    val toolCall: ToolCall,
    val tool: ResolvedTool,
    val messages: List<Message>,
    val toolCallIndex: Int,
    val correlationId: String,
    val identity: EngineExecutionIdentity,
)

/**
 * Engine-level store for suspended invocation metadata.
 *
 * Used by [TramaiEngine] to persist and retrieve safe invocation state
 * when a tool execution is suspended pending human approval.
 *
 * Implementations must:
 * - NOT expose raw tool arguments, approval tokens, or sensitive tool payloads
 * - Be thread-safe (concurrent create/get/remove)
 * - Not persist beyond the JVM lifecycle (resume after restart is out of scope for v1)
 */
interface SuspendedInvocationStore {
    /**
     * Persist a [SuspendedInvocation].
     * @throws IllegalArgumentException if an invocation with the same [approvalId] already exists.
     */
    suspend fun create(invocation: SuspendedInvocation): SuspendedInvocation

    /**
     * Retrieve a suspended invocation by [approvalId].
     * Returns null if not found.
     */
    suspend fun get(approvalId: String): SuspendedInvocation?

    /**
     * Remove and return a suspended invocation by [approvalId].
     * Returns null if not found.
     */
    suspend fun remove(approvalId: String): SuspendedInvocation?
}
