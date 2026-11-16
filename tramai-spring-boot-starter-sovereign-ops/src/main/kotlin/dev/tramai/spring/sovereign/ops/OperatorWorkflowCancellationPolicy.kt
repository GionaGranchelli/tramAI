package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.gateway.ApproverRole

enum class WorkflowUnauthorizedReason(
    val wireName: String,
) {
    MISSING_CAPABILITY("missing-capability"),
    WORKFLOW_POLICY_UNAVAILABLE("workflow-policy-unavailable"),
    ROLE_NOT_PERMITTED("role-not-permitted"),
}

/**
 * Per-workflow cancellation policy, resolved from server configuration (0.7.7m).
 *
 * Maps workflow name to the set of required roles permitted to cancel runs of that workflow.
 * An unmapped workflow rejects cancellation with [WorkflowUnauthorizedReason.WORKFLOW_POLICY_UNAVAILABLE].
 *
 * @param requiredRolesByWorkflow Map from workflow name (or wildcard "*") to the set of accepted roles.
 */
class OperatorWorkflowCancellationPolicy(
    requiredRolesByWorkflow: Map<String, Set<ApproverRole>>,
) {
    private val requiredRoles: Map<String, Set<ApproverRole>>

    init {
        requiredRolesByWorkflow.forEach { (workflowName, roles) ->
            require(workflowName.isNotBlank()) {
                "tramai-operator-workflow-cancellation-policy-blank-name: a workflow name key must not be blank"
            }
            require(roles.isNotEmpty()) {
                "tramai-operator-workflow-cancellation-policy-empty-roles: " +
                    "workflow '$workflowName' accepts no role, so no cancellation could ever satisfy it"
            }
        }
        requiredRoles =
            java.util.Collections.unmodifiableMap(
                requiredRolesByWorkflow.mapValues { (_, roles) -> roles.toSet() },
            )
    }

    fun configuredWorkflows(): Set<String> = requiredRoles.keys

    fun evaluate(
        workflowName: String,
        authority: OperatorAuthority,
    ): WorkflowUnauthorizedReason? {
        val accepted =
            requiredRoles[workflowName] ?: requiredRoles["*"]
                ?: return WorkflowUnauthorizedReason.WORKFLOW_POLICY_UNAVAILABLE
        val permitted = authority.approverRoles.any { it in accepted }
        return if (permitted) null else WorkflowUnauthorizedReason.ROLE_NOT_PERMITTED
    }
}
