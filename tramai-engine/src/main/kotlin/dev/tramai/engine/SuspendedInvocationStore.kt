package dev.tramai.engine

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.Message
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.policy.ToolSecurityMetadata

/**
 * Snapshot of token budget tracker state at the point of suspension.
 */
data class TokenBudgetSnapshot(
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalInputCost: Double,
    val totalOutputCost: Double,
    val warnIfExceeded: Boolean,
)

/**
 * Safe metadata stored when a tool execution is suspended pending approval.
 *
 * Holds only safe state needed to resume the provider loop:
 * - NO raw tool arguments — those stay in the [dev.tramai.core.approval.ApprovalContinuationStore]
 * - NO approval tokens
 * - NO sensitive tool payloads
 * - NO messages (which contain prompts and content)
 * - NO runtime objects (OperationDefinition, ResolvedTool, ToolCall)
 *
 * @property approvalId The ID of the approval challenge this suspension corresponds to.
 * @property toolCallId The ID of the tool call that triggered suspension.
 * @property toolName The name of the tool whose execution was suspended.
 * @property toolCallIndex The index of this tool call within the batch.
 * @property correlationId The correlation ID of the suspended invocation.
 * @property identity The engine execution identity at suspension point.
 * @property securityContext The execution security context (classification and source) at suspension point.
 * @property operationReference Stable reference to the resume-able operation (service + method + digest).
 * @property replayEnvelopeDigest Digest of the [SensitiveReplayEnvelope] content for tamper detection.
 * @property conversationId The conversation ID for memory persistence, if any.
 * @property historySize The number of history messages at the point of suspension.
 * @property tokenBudgetSnapshot Snapshot of token budget tracker state, if available.
 * @property toolSecurity Security metadata for the suspended tool, used for policy context during resume.
 */
data class SuspendedInvocationMetadata(
    val approvalId: String,
    val toolCallId: String,
    val toolName: String,
    val toolCallIndex: Int,
    val correlationId: String,
    val identity: EngineExecutionIdentity,
    val securityContext: ExecutionSecurityContext,
    val operationReference: ResumeOperationReference,
    val replayEnvelopeDigest: Sha256Digest,
    val conversationId: String? = null,
    val historySize: Int = 0,
    val tokenBudgetSnapshot: TokenBudgetSnapshot? = null,
    val toolSecurity: ToolSecurityMetadata? = null,
)

/**
 * Opaque wrapper around sensitive replay messages.
 *
 * - Never serialized as-is
 * - [toString] returns [REDACTED]
 * - Only accessible via [revealForResume] inside a trusted code path
 * - Contains ONLY [Message] objects — no runtime objects, no reflection
 *
 * Replaces the previous [SensitiveResumeContext] which stored OperationDefinition,
 * ResolvedTool, and ToolCall as runtime objects.
 *
 * @deprecated Use [SensitiveReplayEnvelope] instead. Kept for transition.
 *   Will be removed in a future version once all consumers migrate.
 */
@Deprecated("Use SensitiveReplayEnvelope instead", ReplaceWith("SensitiveReplayEnvelope"))
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
 *
 * @deprecated Use [SensitiveReplayEnvelope] + [ResumeOperationRegistry] instead.
 *   Kept for transition. Will be removed in a future version.
 */
@Deprecated("Use ReplayPayload from SensitiveReplayEnvelope instead")
data class ResumeContext(
    val operation: OperationDefinition,
    val tool: ResolvedTool,
    val messages: List<Message>,
    val toolCall: ToolCall,
)

/**
 * Engine-level store for suspended invocation metadata and replay envelope.
 *
 * Used by [TramaiEngine] to persist and retrieve safe invocation state
 * when a tool execution is suspended pending human approval.
 *
 * Implementations must:
 * - NOT expose raw tool arguments, approval tokens, or sensitive tool payloads via [get]
 * - Be thread-safe (concurrent create/get/remove)
 * - Not persist beyond the JVM lifecycle (resume after restart is out of scope for v1)
 *
 * ⚠️ Expiry / sweep is a legitimate lifecycle concern but is deferred.
 *    Entries created here have no automatic TTL — they must be explicitly
 *    removed via [remove]. A background sweep or TTL-based eviction is
 *    tracked as future work and is out of scope for the current PR
 *    (which is explicitly limited to process-local stores).
 */
interface SuspendedInvocationStore {
    /**
     * Persist safe invocation [metadata] and the [replayEnvelope] needed for resume.
     * @throws IllegalArgumentException if an invocation with the same [approvalId] already exists.
     */
    suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    )

    /**
     * Retrieve only the safe metadata by [approvalId].
     * Returns null if not found.
     *
     * The replay envelope is NOT returned here — it is released only
     * via [revealReplayEnvelope] after the continuation is claimed.
     */
    suspend fun get(approvalId: String): SuspendedInvocationMetadata?

    /**
     * Release the sensitive replay envelope for a claimed continuation.
     *
     * MUST only be called after [ApprovalContinuationStore.claimForExecution] succeeds.
     * Returns null if the approval ID is not found.
     */
    suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope?

    /**
     * Remove a suspended invocation (both metadata and replay envelope) by [approvalId].
     * Returns the safe metadata if found, null otherwise.
     */
    suspend fun remove(approvalId: String): SuspendedInvocationMetadata?

    // ---- Deprecated methods (transition support) ----

    /**
     * @deprecated Use [revealReplayEnvelope] instead.
     */
    @Deprecated("Use revealReplayEnvelope instead", ReplaceWith("revealReplayEnvelope(approvalId)"))
    suspend fun revealSensitiveContext(approvalId: String): SensitiveResumeContext? =
        revealReplayEnvelope(approvalId)?.let { envelope ->
            error("revealSensitiveContext is deprecated — the store no longer stores SensitiveResumeContext")
        }
}
