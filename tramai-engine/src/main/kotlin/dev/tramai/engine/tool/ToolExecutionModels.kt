package dev.tramai.engine.tool

import dev.tramai.core.model.Message
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolFailureCode
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.TokenBudgetTracker

internal const val UNREGISTERED_LABEL = "<unregistered>"
internal const val IDEMPOTENT_TOOL_MAX_ATTEMPTS = 2
internal const val MAX_SAFE_TOOL_NAME_LENGTH = 128
internal const val DLP_TOOL_REJECTED_METRIC = "tramai.dlp.tool_result_rejected"

internal data class ToolExecutionRequest(
    val tool: ResolvedTool,
    val toolCall: ToolCall,
    val operation: OperationDefinition,
    val correlationId: String,
    val securityContext: ExecutionSecurityContext,
    val identity: EngineExecutionIdentity,
    val messages: List<Message>,
    val toolCallIndex: Int = -1,
    val tokenBudgetTracker: TokenBudgetTracker? = null,
    val conversationId: String? = null,
    val historySize: Int = 0,
    val resumingApproval: Boolean = false,
    val parentApprovalId: String? = null,
    val idempotencyKey: String? = null,
    val allowRenewedApprovedBindingDuringResume: Boolean = false,
)

internal data class ToolCallBatchRequest(
    val operation: OperationDefinition,
    val messages: MutableList<Message>,
    val toolCalls: List<ToolCall>,
    val correlationId: String,
    val securityContext: ExecutionSecurityContext,
    val identity: EngineExecutionIdentity,
    val tokenBudgetTracker: TokenBudgetTracker? = null,
    val conversationId: String? = null,
    val historySize: Int = 0,
    val resumingApproval: Boolean = false,
    val parentApprovalId: String? = null,
)

internal sealed interface ToolAuthorizationDecision {
    data object Allow : ToolAuthorizationDecision
    data class Deny(val decision: PolicyDecision.Deny) : ToolAuthorizationDecision
    data class RequireApproval(val decision: PolicyDecision.RequireApproval) : ToolAuthorizationDecision
}

internal sealed interface ToolRetryDecision {
    data object Retry : ToolRetryDecision
    data class Stop(val terminalCode: ToolFailureCode) : ToolRetryDecision
}

internal fun interface ToolApprovalGate {
    suspend fun requireApproval(request: ToolExecutionRequest, decision: PolicyDecision.RequireApproval, input: String)
}
