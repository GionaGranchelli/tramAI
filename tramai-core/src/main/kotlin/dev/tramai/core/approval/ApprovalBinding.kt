package dev.tramai.core.approval

data class ApprovalBinding(
    val workflowRunId: String,
    val toolName: String,
    val argumentsDigest: String,
    val policyVersion: String?,
    val workflowDigest: String?,
)
