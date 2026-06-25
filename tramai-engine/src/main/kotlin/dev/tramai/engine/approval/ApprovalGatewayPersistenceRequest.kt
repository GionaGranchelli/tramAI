package dev.tramai.engine.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata

/**
 * Aggregated low-level persistence records produced by [ApprovalGatewayRequestFactory].
 *
 * Mirrors the existing store split in a single transport object:
 * - [approvalRequest] → [dev.tramai.core.approval.ApprovalStore]
 * - [continuation] + [sensitiveArguments] → [dev.tramai.core.approval.ApprovalContinuationStore]
 * - [suspendedInvocationMetadata] + [replayEnvelope] → [dev.tramai.engine.SuspendedInvocationStore]
 *
 * @property resumeToken The public resume token returned in [ApprovalRequestResult.Suspended].
 *   This is the **credential** presented by the requestor at resume time — NOT the stored
 *   approval token digest. The factory is responsible for providing it; the gateway must never
 *   derive it from [ApprovalRequest.binding.approvalTokenDigest].
 */
data class ApprovalGatewayPersistenceRequest(
    val approvalRequest: ApprovalRequest,
    val continuation: ApprovalContinuation,
    val sensitiveArguments: SensitiveToolArguments,
    val suspendedInvocationMetadata: SuspendedInvocationMetadata,
    val replayEnvelope: SensitiveReplayEnvelope,
    val resumeToken: ResumeToken,
)
