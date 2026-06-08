package dev.tramai.core.approval

/**
 * SPI for emitting safe lifecycle audit events related to approval suspension and resume.
 *
 * Implementations must NOT emit raw tool arguments, approval tokens, or sensitive tool payloads.
 * Only safe metadata (IDs, names, timestamps, digests) should be included in events.
 */
interface ApprovalLifecycleAuditEmitter {

    /**
     * Called when a tool execution is suspended pending approval.
     *
     * @param approvalId The approval ID for the suspended execution.
     * @param workflowRunId The workflow run that was suspended.
     * @param toolName The name of the tool whose execution was suspended.
     * @param toolCallId The tool call ID within the provider response.
     * @param correlationId The correlation ID of the suspended operation.
     * @param argumentsDigest The SHA-256 digest of the tool arguments (not the raw arguments).
     * @param expiresAt The time at which the approval challenge expires.
     */
    suspend fun onToolExecutionSuspended(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        toolCallId: String,
        correlationId: String,
        argumentsDigest: Sha256Digest,
        expiresAt: java.time.Instant,
    )

    /**
     * Called when an approval is resumed and the tool is about to execute.
     *
     * @param approvalId The approval ID being resumed.
     * @param workflowRunId The workflow run being resumed.
     * @param toolName The name of the tool being executed.
     * @param resumedBy The identity that initiated the resume.
     */
    suspend fun onToolExecutionResumed(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        resumedBy: String,
    )

    /**
     * Called when a tool execution completes successfully after resume.
     *
     * @param approvalId The approval ID that was completed.
     * @param workflowRunId The workflow run that completed.
     * @param toolName The name of the tool that was executed.
     * @param completedBy The identity that completed the execution.
     */
    suspend fun onToolExecutionCompleted(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        completedBy: String,
    )

    /**
     * Called when a tool execution encounters an uncertain outcome after resume
     * (e.g., interruption after claimForExecution).
     *
     * @param approvalId The approval ID with uncertain outcome.
     * @param workflowRunId The workflow run with uncertain outcome.
     * @param toolName The name of the tool with uncertain outcome.
     * @param reason A safe description of why the outcome is uncertain.
     */
    suspend fun onUncertainOutcome(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        reason: String,
    )

    /**
     * Called when a suspended invocation is cancelled (e.g., due to partial failure compensation).
     *
     * @param approvalId The approval ID that was cancelled.
     * @param workflowRunId The workflow run whose suspension was cancelled.
     * @param toolName The name of the tool that was cancelled.
     * @param reason A safe description of why the cancellation occurred.
     */
    suspend fun onSuspensionCancelled(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        reason: String,
    )
}

/** No-op implementation of [ApprovalLifecycleAuditEmitter]. */
object NoOpApprovalLifecycleAuditEmitter : ApprovalLifecycleAuditEmitter {
    override suspend fun onToolExecutionSuspended(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        toolCallId: String,
        correlationId: String,
        argumentsDigest: Sha256Digest,
        expiresAt: java.time.Instant,
    ) = Unit

    override suspend fun onToolExecutionResumed(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        resumedBy: String,
    ) = Unit

    override suspend fun onToolExecutionCompleted(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        completedBy: String,
    ) = Unit

    override suspend fun onUncertainOutcome(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        reason: String,
    ) = Unit

    override suspend fun onSuspensionCancelled(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        reason: String,
    ) = Unit
}
