package dev.tramai.engine

import dev.tramai.core.model.Message
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ResolvedTool

/**
 * Safe metadata stored when a tool execution is suspended pending approval.
 *
 * Holds only safe state needed to resume the provider loop:
 * - NO raw tool arguments — those stay in the [dev.tramai.core.approval.ApprovalContinuationStore]
 * - NO approval tokens
 * - NO sensitive tool payloads
 * - NO messages (which contain prompts and content)
 *
 * @property approvalId The ID of the approval challenge this suspension corresponds to.
 * @property toolCallId The ID of the tool call that triggered suspension.
 * @property toolName The name of the tool whose execution was suspended.
 * @property toolCallIndex The index of this tool call within the batch.
 * @property correlationId The correlation ID of the suspended invocation.
 * @property identity The engine execution identity at suspension point.
 * @property securityContext The execution security context (classification and source) at suspension point.
 */
data class SuspendedInvocationMetadata(
    val approvalId: String,
    val toolCallId: String,
    val toolName: String,
    val toolCallIndex: Int,
    val correlationId: String,
    val identity: EngineExecutionIdentity,
    val securityContext: ExecutionSecurityContext,
)

/**
 * Opaque wrapper around sensitive resume context (operation, tool, messages, tool calls).
 *
 * - Never serialized as-is
 * - [toString] returns [REDACTED]
 * - Only accessible via [revealForResume] inside a trusted code path
 */
class SensitiveResumeContext private constructor(
    private val operation: OperationDefinition,
    private val tool: ResolvedTool,
    private val messages: List<Message>,
    private val toolCall: ToolCall,
) {
    fun revealForResume(): ResumeContext = ResumeContext(operation, tool, messages, toolCall)

    override fun toString(): String = "[REDACTED]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SensitiveResumeContext) return false
        return operation == other.operation &&
            tool == other.tool &&
            messages == other.messages &&
            toolCall == other.toolCall
    }

    override fun hashCode(): Int {
        var result = operation.hashCode()
        result = 31 * result + tool.hashCode()
        result = 31 * result + messages.hashCode()
        result = 31 * result + toolCall.hashCode()
        return result
    }

    companion object {
        fun of(
            operation: OperationDefinition,
            tool: ResolvedTool,
            messages: List<Message>,
            toolCall: ToolCall,
        ): SensitiveResumeContext = SensitiveResumeContext(operation, tool, messages, toolCall)
    }
}

/**
 * Resolved context for resuming a suspended tool execution.
 *
 * Returned by [SensitiveResumeContext.revealForResume] inside the trusted
 * code path after the continuation has been claimed.
 */
data class ResumeContext(
    val operation: OperationDefinition,
    val tool: ResolvedTool,
    val messages: List<Message>,
    val toolCall: ToolCall,
)

/**
 * Engine-level store for suspended invocation metadata and sensitive resume context.
 *
 * Used by [TramaiEngine] to persist and retrieve safe invocation state
 * when a tool execution is suspended pending human approval.
 *
 * Implementations must:
 * - NOT expose raw tool arguments, approval tokens, or sensitive tool payloads via [get]
 * - Be thread-safe (concurrent create/get/remove)
 * - Not persist beyond the JVM lifecycle (resume after restart is out of scope for v1)
 */
interface SuspendedInvocationStore {
    /**
     * Persist safe invocation [metadata] and the [sensitiveContext] needed for resume.
     * @throws IllegalArgumentException if an invocation with the same [approvalId] already exists.
     */
    suspend fun create(
        metadata: SuspendedInvocationMetadata,
        sensitiveContext: SensitiveResumeContext,
    )

    /**
     * Retrieve only the safe metadata by [approvalId].
     * Returns null if not found.
     *
     * The sensitive context is NOT returned here — it is released only
     * via [revealSensitiveContext] after the continuation is claimed.
     */
    suspend fun get(approvalId: String): SuspendedInvocationMetadata?

    /**
     * Release the sensitive resume context for a claimed continuation.
     *
     * MUST only be called after [ApprovalContinuationStore.claimForExecution] succeeds.
     * Returns null if the approval ID is not found.
     */
    suspend fun revealSensitiveContext(approvalId: String): SensitiveResumeContext?

    /**
     * Remove a suspended invocation (both metadata and sensitive context) by [approvalId].
     * Returns the safe metadata if found, null otherwise.
     */
    suspend fun remove(approvalId: String): SuspendedInvocationMetadata?
}
