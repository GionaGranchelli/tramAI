package dev.tramai.engine.tool

import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.ToolRegistry

internal class ToolExposureCoordinator(
    private val toolRegistry: ToolRegistry,
    private val policyHelper: PolicyEnforcementHelper,
) {
    suspend fun enforce(operation: OperationDefinition, correlationId: String, securityContext: ExecutionSecurityContext) {
        operation.toolDefinitions.forEach { toolDef ->
            val tool = toolRegistry.resolve(toolDef.name)
            policyHelper.enforce(
                policyHelper.buildContext(enforcementPoint = EnforcementPoint.BEFORE_TOOL_EXPOSURE, correlationId = correlationId)
                    .toolName(toolDef.name).toolSecurity(tool?.security)
                    .dataClassification(securityContext.dataClassification)
                    .classificationSource(securityContext.classificationSource).build(),
            )
        }
    }
}
