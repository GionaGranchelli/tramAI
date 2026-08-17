package dev.tramai.engine.structured

import dev.tramai.core.model.Message
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.budget.TokenBudgetTracker
import dev.tramai.engine.provider.ProviderCallResult

internal data class StructuredResponseRequest(
    val operation: OperationDefinition,
    val arguments: List<Any?>,
    val tokenBudgetTracker: TokenBudgetTracker,
    val conversationId: String?,
    val identity: EngineExecutionIdentity,
    val operationFingerprint: String?,
)

internal data class ResumedStructuredResponseRequest(
    val operation: OperationDefinition,
    val loopResult: ProviderCallResult,
    val messages: List<Message>,
    val correlationId: String,
    val securityContext: ExecutionSecurityContext,
    val conversationId: String?,
    val historySize: Int,
)

internal data class StructuredAttemptExecutionRequest(
    val operation: OperationDefinition,
    val messages: MutableList<Message>,
    val tokenBudgetTracker: TokenBudgetTracker,
    val correlationId: String,
    val securityContext: ExecutionSecurityContext,
    val identity: EngineExecutionIdentity,
    val conversationId: String?,
    val historySize: Int,
)
