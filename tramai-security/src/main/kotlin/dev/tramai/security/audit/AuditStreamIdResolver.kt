package dev.tramai.security.audit

import dev.tramai.core.policy.PolicyContext

fun interface AuditStreamIdResolver {
    /**
     * Resolves a stable audit stream ID from the [context].
     *
     * The returned value must:
     * - be the **same** for every decision within one workflow execution
     * - NOT contain raw prompt content, tool arguments, or arbitrary
     *   user-controlled text (unless normalized and bounded)
     * - be non-blank and ≤ 256 characters
     */
    fun resolve(context: PolicyContext): String
}

val DefaultAuditStreamIdResolver: AuditStreamIdResolver = AuditStreamIdResolver { context ->
    val workflowRunId = context.workflowRunId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (workflowRunId != null) {
        workflowRunId
    } else {
        val correlationId = context.correlationId
            .trim()
            .takeIf { it.isNotEmpty() }

        correlationId ?: throw IllegalArgumentException(
            "AuditStreamIdResolver requires at least one of workflowRunId or correlationId to be non-blank",
        )
    }
}
