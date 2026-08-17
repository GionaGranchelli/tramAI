package dev.tramai.engine.approval

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.Message
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolCall
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.RehydratedReplayPayload
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.TokenBudgetTracker
import dev.tramai.engine.OperationDefinition
import dev.tramai.core.policy.PolicyDecision

internal data class SuspendToolExecutionRequest(
    val tool: ResolvedTool,
    val toolCall: ToolCall,
    val operation: OperationDefinition,
    val correlationId: String,
    val input: String,
    val identity: EngineExecutionIdentity,
    val toolCallIndex: Int,
    val messages: List<Message>,
    val argumentsDigest: Sha256Digest,
    val timeoutMillis: Long,
    val securityContext: ExecutionSecurityContext,
    val tokenBudgetTracker: TokenBudgetTracker? = null,
    val conversationId: String? = null,
    val historySize: Int = 0,
)

internal class ResumeUncertainOutcome(var emitted: Boolean = false)

internal data class ResumeExecutionContext(
    val command: ResumeApprovalCommand,
    val metadata: SuspendedInvocationMetadata,
    val registered: RegisteredResumeOperation,
    val resolvedTool: ResolvedTool,
    val uncertainOutcome: ResumeUncertainOutcome,
)

internal data class ClaimedResumeExecutionRequest(
    val command: ResumeApprovalCommand,
    val metadata: SuspendedInvocationMetadata,
    val registered: RegisteredResumeOperation,
    val resolvedTool: ResolvedTool,
    val rehydratedPayload: RehydratedReplayPayload,
    val validatedInput: String,
    val expectedArgsDigest: Sha256Digest,
    val uncertainOutcomeEmitter: suspend (String) -> Unit,
)

internal sealed interface ReplayAuthorizationDecision {
    data class Allowed(val replayed: Boolean) : ReplayAuthorizationDecision
    data class Denied(val decision: PolicyDecision.Deny) : ReplayAuthorizationDecision
    data object RequiresNestedApproval : ReplayAuthorizationDecision
}
