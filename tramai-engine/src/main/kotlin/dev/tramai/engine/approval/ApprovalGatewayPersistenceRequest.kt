package dev.tramai.engine.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata

/**
 * Aggregated low-level persistence records produced by [ApprovalGatewayRequestFactory].
 *
 * Mirrors the existing store split in a single transport object:
 * - [approvalRequest] → [dev.tramai.core.approval.ApprovalStore]
 * - [continuation] + [sensitiveArguments] → [dev.tramai.core.approval.ApprovalContinuationStore]
 * - [suspendedInvocationMetadata] + [replayEnvelope] → [dev.tramai.engine.SuspendedInvocationStore]
 */
data class ApprovalGatewayPersistenceRequest(
    val approvalRequest: ApprovalRequest,
    val continuation: ApprovalContinuation,
    val sensitiveArguments: SensitiveToolArguments,
    val suspendedInvocationMetadata: SuspendedInvocationMetadata,
    val replayEnvelope: SensitiveReplayEnvelope,
)
