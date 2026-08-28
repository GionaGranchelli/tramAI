package dev.tramai.engine.invocation

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.budget.TokenBudgetTracker
import dev.tramai.engine.cache.OperationCacheCoordinator
import dev.tramai.engine.cache.OperationCacheKeyRequest
import dev.tramai.engine.cache.OperationCacheLookupRequest
import dev.tramai.engine.cache.OperationCacheLookupResult
import dev.tramai.engine.cache.OperationCacheStoreRequest
import dev.tramai.engine.memory.ConversationMemoryCoordinator
import dev.tramai.engine.memory.PersistConversationTurnRequest
import dev.tramai.engine.planning.OperationExecutionPlan
import dev.tramai.engine.PolicyContextBuilder
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.CacheSecurityPartition

/**
 * STRING/UNIT response path: security context → correlation ID → initial
 * messages → memory injection → cache key → cache lookup → tool loop →
 * BEFORE_RESPONSE_RETURN → memory persistence → observation completion →
 * cache store → sanitized String. Order is frozen from the original handler.
 */
internal class RawResponseCoordinator(
    private val conversationMemoryCoordinator: ConversationMemoryCoordinator,
    private val operationCacheCoordinator: OperationCacheCoordinator,
    private val policyHelper: PolicyEnforcementHelper,
    private val toolLoopCoordinator: ToolLoopCoordinator,
) {
    suspend fun execute(request: RawResponseRequest): String {
        val operation = request.plan.definition
        val arguments = request.arguments
        val tokenBudgetTracker = request.tokenBudgetTracker
        val conversationId = request.conversationId
        val identity = request.identity
        val securityContext = ExecutionSecurityContext.fromArguments(arguments.toTypedArray())
        val initialMessages = operation.initialMessages(arguments)
        val prepared = conversationMemoryCoordinator.prepareMessages(initialMessages, conversationId)
        val history = prepared?.history ?: emptyList()
        val effectiveMessages = prepared?.effectiveMessages ?: initialMessages
        val cacheKey = operationCacheCoordinator.createKey(
            OperationCacheKeyRequest(
                digestSource = effectiveMessages,
                securityPartition = securityContext.toCacheSecurityPartition(),
                operationFingerprint = request.plan.fingerprint,
                requestedModel = operation.operation.model,
                explicitProvider = operation.operation.provider.takeIf { it.isNotBlank() },
                serviceInterface = operation.method.declaringClass.name,
                methodName = operation.method.name,
                toolDefinitions = operation.toolDefinitions,
                operation = operation.operation,
                returnKind = operation.returnKind,
                conversationId = conversationId,
            ),
        )
        if (cacheKey != null) {
            when (val cached = operationCacheCoordinator.lookup(
                OperationCacheLookupRequest(cacheKey, securityContext, identity.correlationId, conversationId),
            )) {
                is OperationCacheLookupResult.Hit -> return cached.value as String
                is OperationCacheLookupResult.Miss -> Unit
            }
        }

        val effectiveMutableMessages = effectiveMessages.toMutableList()

        val result = toolLoopCoordinator.execute(
            ToolLoopContext(
                operation = operation,
                messages = effectiveMutableMessages,
                tokenBudgetTracker = tokenBudgetTracker,
                correlationId = identity.correlationId,
                securityContext = securityContext,
                identity = identity,
                conversationId = conversationId,
                historySize = history.size,
            ),
        )

        // DLP is already applied inside ProviderAttemptExecutor — use the sanitized response directly

        // Enforce BEFORE_RESPONSE_RETURN
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                correlationId = identity.correlationId,
            ).providerId(result.providerId)
                .modelName(result.modelName)
                .applySecurityContext(securityContext)
                .build()
        )

        // Memory: persist response if chatMemory is configured
        if (conversationId != null) {
            val assistantMessage = Message(
                role = MessageRole.ASSISTANT,
                content = result.response.content,
                toolCalls = result.response.toolCalls,
            )
            conversationMemoryCoordinator.persistTurn(
                PersistConversationTurnRequest(conversationId, effectiveMutableMessages, history.size, assistantMessage),
            )
        }

        result.observation.onCallCompleted(parseSuccess = null)
        return result.response.content.also {
            cacheKey?.let { key ->
                operationCacheCoordinator.store(
                    OperationCacheStoreRequest(key, it, result.providerId, result.modelName, securityContext, conversationId, result.approvedModel, operation.operation.cacheTtlMillis),
                )
            }
        }
    }
}

internal data class RawResponseRequest(
    val plan: OperationExecutionPlan,
    val arguments: List<Any?>,
    val tokenBudgetTracker: TokenBudgetTracker,
    val conversationId: String?,
    val identity: EngineExecutionIdentity,
)

private fun PolicyContextBuilder.applySecurityContext(
    securityContext: ExecutionSecurityContext,
): PolicyContextBuilder = dataClassification(securityContext.dataClassification)
    .classificationSource(securityContext.classificationSource)

private fun ExecutionSecurityContext.toCacheSecurityPartition() = CacheSecurityPartition(
    dataClassification = dataClassification,
    classificationSource = classificationSource,
)
