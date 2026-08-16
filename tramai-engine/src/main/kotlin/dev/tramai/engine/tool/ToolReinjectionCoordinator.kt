package dev.tramai.engine.tool

import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolFailureCode
import dev.tramai.core.model.ToolResult
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.ToolRegistry

internal class ToolReinjectionCoordinator(
    private val toolRegistry: ToolRegistry,
    private val policyHelper: PolicyEnforcementHelper,
    private val invocationExecutor: ToolInvocationExecutor,
    private val resultSanitizer: ToolResultSanitizer,
) {
    suspend fun process(request: ToolCallBatchRequest) {
        request.toolCalls.forEachIndexed { index, toolCall -> processOne(request, toolCall, index) }
    }

    suspend fun processOne(request: ToolCallBatchRequest, toolCall: ToolCall, toolCallIndex: Int) {
        val tool = toolRegistry.resolve(toolCall.name)
        val toolResult = if (tool == null) ToolResult.PermanentFailure(ToolFailureCode.EXECUTION_FAILED.defaultModelMessage) else invocationExecutor.execute(
            ToolExecutionRequest(
                tool = tool, toolCall = toolCall, operation = request.operation, correlationId = request.correlationId,
                securityContext = request.securityContext, identity = request.identity, messages = request.messages,
                toolCallIndex = toolCallIndex, tokenBudgetTracker = request.tokenBudgetTracker, conversationId = request.conversationId,
                historySize = request.historySize, resumingApproval = request.resumingApproval, parentApprovalId = request.parentApprovalId,
            ),
        )
        reinject(request, toolCall.id, toolCall.name, toolResult, tool)
    }

    suspend fun reinjectKnownResult(request: ToolCallBatchRequest, toolCallId: String, toolName: String, toolResult: ToolResult) {
        reinject(request, toolCallId, toolName, toolResult, toolRegistry.resolve(toolName))
    }

    private suspend fun reinject(request: ToolCallBatchRequest, toolCallId: String, toolName: String, toolResult: ToolResult, tool: dev.tramai.core.model.ResolvedTool?) {
        policyHelper.enforce(
            policyHelper.buildContext(enforcementPoint = EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION, correlationId = request.correlationId)
                .toolName(tool?.name ?: UNREGISTERED_LABEL).toolSecurity(tool?.security)
                .dataClassification(request.securityContext.dataClassification)
                .classificationSource(request.securityContext.classificationSource).build(),
        )
        val toolMessage = resultSanitizer.format(toolResult, toolCallId)
        request.messages += resultSanitizer.sanitize(toolMessage, request.operation, toolName, request.correlationId, request.securityContext)
    }
}
