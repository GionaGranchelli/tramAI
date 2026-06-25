package dev.tramai.core.approval.gateway

import java.time.Instant

// ── Value types ──

@JvmInline
value class ApprovalSubject(val value: String) {
    init {
        require(value.isNotBlank()) { "approval-subject-blank" }
    }
}

@JvmInline
value class ApproverRole(val value: String) {
    init {
        require(value.isNotBlank()) { "approver-role-blank" }
    }
}

@JvmInline
value class ApprovalId(val value: String) {
    init {
        require(value.isNotBlank()) { "approval-id-blank" }
    }
}

@JvmInline
value class WorkflowRunId(val value: String) {
    init {
        require(value.isNotBlank()) { "workflow-run-id-blank" }
    }
}

@JvmInline
value class AuditStreamId(val value: String) {
    init {
        require(value.isNotBlank()) { "audit-stream-id-blank" }
    }
}

@JvmInline
value class ResumeToken(val value: String) {
    init {
        require(value.isNotBlank()) { "resume-token-blank" }
    }
}

// ── Recommendation ──

data class ApprovalRecommendation(
    val type: String,
    val summary: String,
    val payload: Map<String, String> = emptyMap(),
) {
    init {
        require(type.isNotBlank()) { "approval-recommendation-type-blank" }
        require(summary.isNotBlank()) { "approval-recommendation-summary-blank" }
    }
}

// ── Human decision ──

sealed interface HumanApprovalDecision {
    val approvalId: ApprovalId
    val decidedBy: String
    val decidedAt: Instant
    val comment: String?

    data class Approved(
        override val approvalId: ApprovalId,
        override val decidedBy: String,
        override val decidedAt: Instant,
        override val comment: String? = null,
    ) : HumanApprovalDecision

    data class Denied(
        override val approvalId: ApprovalId,
        override val decidedBy: String,
        override val decidedAt: Instant,
        override val comment: String? = null,
        val reason: String,
    ) : HumanApprovalDecision
}

// ── Approval request result ──

sealed interface ApprovalRequestResult {
    data class Suspended(
        val approvalId: ApprovalId,
        val workflowRunId: WorkflowRunId,
        val auditStreamId: AuditStreamId,
        val resumeToken: ResumeToken,
    ) : ApprovalRequestResult

    data class AlreadyApproved(
        val decision: HumanApprovalDecision,
    ) : ApprovalRequestResult

    data class AlreadyDenied(
        val decision: HumanApprovalDecision,
    ) : ApprovalRequestResult

    data class Expired(
        val approvalId: ApprovalId,
        val expiredAt: Instant,
        val reason: String,
    ) : ApprovalRequestResult
}
