package dev.tramai.core.exception

import dev.tramai.core.approval.ApprovalChallenge

/**
 * Raised when a tool execution is suspended pending human approval.
 *
 * Replaces the placeholder [ApprovalRequiredException] with a real suspension flow.
 *
 * Rules:
 * - No raw tool arguments in message or fields
 * - No approval token in message or fields
 * - No argument digests in message
 *
 * @property challenge The approval challenge containing approval ID, token, and expiry.
 * @property approvalId The ID of the approval challenge.
 * @property workflowRunId The workflow run that was suspended.
 * @property toolCallId The tool call ID within the provider response.
 * @property toolName The name of the tool whose execution was suspended.
 * @property continuationVersion The expected version of the continuation at suspension time.
 */
class ApprovalSuspendedException(
    val challenge: ApprovalChallenge,
    val approvalId: String,
    val workflowRunId: String,
    val toolCallId: String,
    val toolName: String,
    val continuationVersion: Long,
) : TramaiException(
    "Tool execution suspended pending approval for '$toolName' " +
        "[approvalId='$approvalId', workflowRunId='$workflowRunId']"
)
