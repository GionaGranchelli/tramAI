package dev.tramai.security.audit

import dev.tramai.core.policy.PolicyContext

fun interface AuditStreamIdResolver {
    fun resolve(context: PolicyContext): String
}

object DefaultAuditStreamIdResolver : AuditStreamIdResolver {
    override fun resolve(context: PolicyContext): String {
        // Priority: workflowRunId > correlationId (always present) > generated
        return context.workflowRunId ?: context.correlationId
    }
}
