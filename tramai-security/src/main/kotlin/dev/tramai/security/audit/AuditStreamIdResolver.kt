package dev.tramai.security.audit

import dev.tramai.core.policy.PolicyContext
import java.util.UUID

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

object DefaultAuditStreamIdResolver : AuditStreamIdResolver {
    override fun resolve(context: PolicyContext): String {
        // Priority: non-blank workflowRunId > non-blank correlationId
        val workflowRunId = context.workflowRunId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (workflowRunId != null) return workflowRunId

        val correlationId = context.correlationId
            .trim()
            .takeIf { it.isNotEmpty() }
        if (correlationId != null) return correlationId

        // Generated fallback — must be propagated consistently across the
        // entire execution to avoid breaking the hash chain.
        // Production code should always provide a stable workflowRunId
        // or correlationId. This fallback exists only for edge cases where
        // neither is set.
        return "generated-${UUID.randomUUID()}"
    }
}
