package dev.tramai.engine.tool

import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.engine.PolicyEnforcementHelper

internal class ToolAuthorizationCoordinator(
    private val policyHelper: PolicyEnforcementHelper,
) {
    suspend fun authorize(request: ToolExecutionRequest, input: String): ToolAuthorizationDecision {
        val decision = policyHelper.evaluate(
            policyHelper.buildContext(enforcementPoint = EnforcementPoint.BEFORE_TOOL_EXECUTION, correlationId = request.correlationId)
                .toolName(request.tool.name).toolSecurity(request.tool.security)
                .dataClassification(request.securityContext.dataClassification)
                .classificationSource(request.securityContext.classificationSource).build(),
        )
        return when (decision) {
            is PolicyDecision.RequireApproval -> ToolAuthorizationDecision.RequireApproval(decision)
            is PolicyDecision.Deny -> ToolAuthorizationDecision.Deny(decision)
            else -> ToolAuthorizationDecision.Allow
        }
    }
}
