package dev.tramai.engine.approval

import dev.tramai.core.approval.*
import dev.tramai.core.exception.ApprovalNotFoundException
import dev.tramai.core.exception.ApprovalTokenRejectedException
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.engine.SuspendedInvocationMetadata

internal class ContinuationClaimService(private val approvalContinuationStore: ApprovalContinuationStore) {
    suspend fun loadPendingForResume(store: ApprovalContinuationStore, command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata, registered: RegisteredResumeOperation): ApprovalContinuation {
        val continuation = store.get(command.approvalId) ?: throw ApprovalNotFoundException(command.approvalId)
        if (continuation.status == ApprovalContinuationStatus.COMPLETED) throw ApprovalTokenRejectedException(command.approvalId)
        validateBindings(continuation, command, metadata, registered); return continuation
    }
    fun validateBindings(continuation: ApprovalContinuation, command: ResumeApprovalCommand, metadata: SuspendedInvocationMetadata, registered: RegisteredResumeOperation) {
        require(continuation.workflowRunId == metadata.identity.workflowRunId) { "cross-store-mismatch-workflow-run-id" }; require(continuation.correlationId == metadata.correlationId) { "cross-store-mismatch-correlation-id" }; require(metadata.identity.correlationId == metadata.correlationId) { "metadata-identity-mismatch-correlation-id" }; require(continuation.workflowDigest == metadata.identity.workflowDigest) { "cross-store-mismatch-workflow-digest" }; require(continuation.policyVersion == metadata.identity.policyVersion) { "cross-store-mismatch-policy-version" }; require(continuation.toolName == metadata.toolName) { "continuation-tool-name-mismatch" }; require(continuation.toolCallId == metadata.toolCallId) { "continuation-tool-call-id-mismatch" }; require(metadata.operationReference.resumeDefinitionDigest == registered.reference.resumeDefinitionDigest) { "resume-operation-definition-drift" }; require(continuation.status == ApprovalContinuationStatus.PENDING) { "continuation-not-pending" }; require(continuation.version == command.continuationExpectedVersion) { "continuation-version-mismatch" }; require(metadata.approvalId == command.approvalId) { "metadata-approval-id-mismatch" }; require(continuation.approvalId == command.approvalId) { "continuation-approval-id-mismatch" }
    }
    suspend fun claim(approvalId: String, expectedVersion: Long, claimedBy: String): ClaimedApprovalContinuation = approvalContinuationStore.claimForExecution(approvalId, expectedVersion, claimedBy)
    suspend fun complete(approvalId: String, expectedVersion: Long, completedBy: String): ApprovalContinuation = approvalContinuationStore.complete(approvalId, expectedVersion, completedBy)
    suspend fun cancel(approvalId: String, expectedVersion: Long): ApprovalContinuation = approvalContinuationStore.cancel(approvalId, expectedVersion)
}
