package dev.tramai.engine.structured

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.safeStructuredOutputFailure
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.structured.StructuredOutputFailureCode
import dev.tramai.core.structured.StructuredOutputFailureDiagnosticEvent
import dev.tramai.core.structured.StructuredOutputFailureDiagnosticObserver
import dev.tramai.core.structured.StructuredOutputHandler
import dev.tramai.core.structured.StructuredOutputResult
import dev.tramai.core.structured.boundedStructuredOutputDetailPreview
import dev.tramai.engine.CacheSecurityPartition
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationCacheKey
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.PolicyContextBuilder
import dev.tramai.engine.budget.TokenBudgetTracker
import dev.tramai.engine.cache.OperationCacheCoordinator
import dev.tramai.engine.cache.OperationCacheKeyRequest
import dev.tramai.engine.cache.OperationCacheLookupRequest
import dev.tramai.engine.cache.OperationCacheLookupResult
import dev.tramai.engine.cache.OperationCacheStoreRequest
import dev.tramai.engine.memory.ConversationMemoryCoordinator
import dev.tramai.engine.memory.PersistConversationTurnRequest
import dev.tramai.engine.memory.PersistStructuredConversationTurnRequest
import dev.tramai.engine.provider.ProviderCallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun interface StructuredAttemptExecutor {
    suspend fun execute(request: StructuredAttemptExecutionRequest): ProviderCallResult
}

internal class StructuredResponseCoordinator(
    private val structuredOutputHandler: StructuredOutputHandler?,
    private val structuredOutputFailureDiagnosticObserver: StructuredOutputFailureDiagnosticObserver,
    private val conversationMemoryCoordinator: ConversationMemoryCoordinator,
    private val operationCacheCoordinator: OperationCacheCoordinator,
    private val policyHelper: PolicyEnforcementHelper,
    private val attemptExecutor: StructuredAttemptExecutor,
    private val serviceTypeName: String,
) {
    suspend fun execute(request: StructuredResponseRequest): Any {
        val operation = request.operation
        val securityContext = ExecutionSecurityContext.fromArguments(request.arguments.toTypedArray())
        val handler = structuredOutputHandler ?: throw ConfigurationException(
            "Structured return type ${operation.returnTypeDescription} requires a StructuredOutputHandler implementation from tramai-structured",
        )
        val contract = try {
            operation.structuredContract(handler)
        } catch (failure: Throwable) {
            failure.rethrowIfCancellation()
            rethrowContractFailure(operation, failure)
        }
        val initialMessages = operation.initialMessages(request.arguments, contract.schemaJson)
        val prepared = conversationMemoryCoordinator.prepareMessages(initialMessages, request.conversationId)
        val history = prepared?.history ?: emptyList()
        val effectiveMessages = prepared?.effectiveMessages ?: initialMessages
        val cacheKey = operationCacheCoordinator.createKey(
            OperationCacheKeyRequest(
                digestSource = effectiveMessages,
                securityPartition = securityContext.toCacheSecurityPartition(),
                operationFingerprint = request.operationFingerprint,
                requestedModel = operation.operation.model,
                explicitProvider = operation.operation.provider.takeIf { it.isNotBlank() },
                serviceInterface = operation.method.declaringClass.name,
                methodName = operation.method.name,
                toolDefinitions = operation.toolDefinitions,
                operation = operation.operation,
                returnKind = operation.returnKind,
                conversationId = request.conversationId,
            ),
        )
        if (cacheKey != null) {
            when (val cached = operationCacheCoordinator.lookup(
                OperationCacheLookupRequest(cacheKey, securityContext, request.identity.correlationId, request.conversationId),
            )) {
                is OperationCacheLookupResult.Hit -> return cached.value
                is OperationCacheLookupResult.Miss -> Unit
            }
        }

        // Re-initialize messages list with history-injected content
        val messages = effectiveMessages.toMutableList()
        val initialTurnCount = history.size

        return executeStructuredRetryLoop(
            StructuredRetryContext(
                operation = operation,
                cacheKey = cacheKey,
                handler = handler,
                messages = messages,
                historySize = initialTurnCount,
                tokenBudgetTracker = request.tokenBudgetTracker,
                conversationId = request.conversationId,
                correlationId = request.identity.correlationId,
                securityContext = securityContext,
                identity = request.identity,
            ),
        )
    }

    suspend fun finalizeResumed(request: ResumedStructuredResponseRequest): Any {
        val operation = request.operation
        val loopResult = request.loopResult
        val handler = structuredOutputHandler
            ?: throw ConfigurationException(
                "Structured return type ${operation.returnTypeDescription} requires a StructuredOutputHandler implementation from tramai-structured",
            )
        val targetType = operation.returnType
            ?: throw ConfigurationException(
                "Structured return type ${operation.returnTypeDescription} could not be inspected without Kotlin reflection metadata",
            )

        // Fix 3: Parse FIRST before memory persistence and BEFORE_RESPONSE_RETURN
        val analysis = try {
            handler.analyze(
                rawResponse = loopResult.response.content,
                targetType = targetType,
            )
        } catch (failure: Throwable) {
            failure.rethrowIfCancellation()
            rethrowOrSanitizeStructuredHandlerFailure(
                operation = operation,
                result = loopResult,
                failure = failure,
                attempt = 1,
            )
        }
        return when (analysis) {
            is StructuredOutputResult.Success -> {
                // On success: enforce BEFORE_RESPONSE_RETURN, persist memory, complete observation, return value
                // BEFORE_RESPONSE_RETURN is enforced HERE (not in finalizeResumedOperation) per Fix 3
                // so that parse failure does not trip BEFORE_RESPONSE_RETURN
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                        correlationId = request.correlationId,
                    ).providerId(loopResult.providerId)
                        .modelName(loopResult.modelName)
                        .applySecurityContext(request.securityContext)
                        .build()
                )
                if (request.conversationId != null) {
                    conversationMemoryCoordinator.persistTurn(
                        PersistConversationTurnRequest(
                            request.conversationId,
                            request.messages,
                            request.historySize,
                            Message(
                                role = MessageRole.ASSISTANT,
                                content = loopResult.response.content,
                                toolCalls = loopResult.response.toolCalls,
                            ),
                        ),
                    )
                }
                loopResult.observation.onCallCompleted(parseSuccess = true)
                analysis.value
            }
            is StructuredOutputResult.Failure -> {
                // On failure: record parse failure, do NOT enforce BEFORE_RESPONSE_RETURN,
                // do NOT persist invalid data, leave continuation CLAIMED
                currentCoroutineContext().ensureActive()
                val rawPreview = boundedStructuredOutputDetailPreview(analysis.rawResponse)
                val detailSource = analysis.failure?.message ?: analysis.errorSummary
                val detailPreview = boundedStructuredOutputDetailPreview(detailSource)
                deliverStructuredOutputFailure(
                    StructuredOutputFailureDiagnosticEvent(
                        serviceName = serviceTypeName,
                        methodName = operation.method.name,
                        code = StructuredOutputFailureCode.OUTPUT_REJECTED,
                        attempt = 1,
                        willRetry = false,
                        rawResponsePreview = rawPreview.text,
                        rawResponseTruncated = rawPreview.truncated,
                        detailPreview = detailPreview.text,
                        detailTruncated = detailPreview.truncated,
                        failure = analysis.failure,
                        numericMetadata = mapOf("attempt" to 1L),
                    ),
                )
                loopResult.observation.onStructuredParseFailure(
                    rawResponse = "<redacted structured-output failure>",
                    errorSummary = "Structured output failed validation",
                )
                loopResult.observation.onCallCompleted(parseSuccess = false)
                throw safeStructuredOutputFailure(
                    code = StructuredOutputFailureCode.OUTPUT_REJECTED,
                    attemptCount = 1,
                )
            }
        }
    }

    private suspend fun executeStructuredRetryLoop(
        context: StructuredRetryContext,
    ): Any {
        val operation = context.operation
        val maxAttempts = operation.operation.maxRetries + 1
        val targetType = requireNotNull(operation.returnType) {
            "Structured return type ${operation.returnTypeDescription} could not be inspected without Kotlin reflection metadata"
        }

        repeat(maxAttempts) { attemptIndex ->
            val value = executeStructuredAttempt(
                StructuredRetryAttemptContext(
                    retry = context,
                    targetType = targetType,
                    attemptIndex = attemptIndex,
                    maxAttempts = maxAttempts,
                ),
            )
            if (value != null) {
                return value
            }
        }

        error("Structured retry loop exited without returning or throwing")
    }

    private data class StructuredRetryContext(
        val operation: OperationDefinition,
        val cacheKey: OperationCacheKey?,
        val handler: StructuredOutputHandler,
        val messages: MutableList<Message>,
        val historySize: Int,
        val tokenBudgetTracker: TokenBudgetTracker,
        val conversationId: String?,
        val correlationId: String,
        val securityContext: ExecutionSecurityContext,
        val identity: EngineExecutionIdentity,
    )

    private data class StructuredRetryAttemptContext(
        val retry: StructuredRetryContext,
        val targetType: kotlin.reflect.KType,
        val attemptIndex: Int,
        val maxAttempts: Int,
    )

    private suspend fun executeStructuredAttempt(
        context: StructuredRetryAttemptContext,
    ): Any? {
        val operation = context.retry.operation
        val cacheKey = context.retry.cacheKey
        val handler = context.retry.handler
        val messages = context.retry.messages
        val historySize = context.retry.historySize
        val tokenBudgetTracker = context.retry.tokenBudgetTracker
        val conversationId = context.retry.conversationId
        val targetType = context.targetType
        val attemptIndex = context.attemptIndex
        val maxAttempts = context.maxAttempts
        val correlationId = context.retry.correlationId
        val securityContext = context.retry.securityContext
        val identity = context.retry.identity
        val messagesBeforeCall = messages.size
        val result = attemptExecutor.execute(
            StructuredAttemptExecutionRequest(
                operation = operation,
                messages = messages,
                tokenBudgetTracker = tokenBudgetTracker,
                correlationId = correlationId,
                securityContext = securityContext,
                identity = identity,
                conversationId = conversationId,
                historySize = historySize,
            ),
        )

        // DLP is already applied inside ProviderAttemptExecutor — use the sanitized response directly

        val analysis = try {
            handler.analyze(
                rawResponse = result.response.content,
                targetType = targetType,
            )
        } catch (failure: Throwable) {
            failure.rethrowIfCancellation()
            rethrowOrSanitizeStructuredHandlerFailure(
                operation = operation,
                result = result,
                failure = failure,
                attempt = attemptIndex + 1,
            )
        }
        return when (analysis) {
            is StructuredOutputResult.Success -> {
                // Enforce BEFORE_RESPONSE_RETURN before any side effects (persist, cache)
                // and before onCallCompleted so external consumers don't assume availability
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                        correlationId = correlationId,
                    ).providerId(result.providerId)
                        .modelName(result.modelName)
                        .applySecurityContext(securityContext)
                        .build()
                )

                result.observation.onCallCompleted(parseSuccess = true)

                if (conversationId != null) {
                    conversationMemoryCoordinator.persistStructuredTurn(
                        PersistStructuredConversationTurnRequest(
                            conversationId = conversationId,
                            messages = messages,
                            historySize = historySize,
                            messagesBeforeCall = messagesBeforeCall,
                            assistantMessage = Message(
                                role = MessageRole.ASSISTANT,
                                content = result.response.content,
                                toolCalls = result.response.toolCalls,
                            ),
                        ),
                    )
                }
                cacheKey?.let { key ->
                    operationCacheCoordinator.store(
                        OperationCacheStoreRequest(key, analysis.value, result.providerId, result.modelName, securityContext, conversationId, result.approvedModel, operation.operation.cacheTtlMillis),
                    )
                }

                analysis.value
            }
            is StructuredOutputResult.Failure -> {
                handleStructuredFailure(
                    operation = operation,
                    analysis = analysis,
                    result = result,
                    messages = messages,
                    attemptIndex = attemptIndex,
                    maxAttempts = maxAttempts,
                )
                null
            }
        }
    }

    private suspend fun handleStructuredFailure(
        operation: OperationDefinition,
        analysis: StructuredOutputResult.Failure,
        result: ProviderCallResult,
        messages: MutableList<Message>,
        attemptIndex: Int,
        maxAttempts: Int,
    ) {
        currentCoroutineContext().ensureActive()
        val rawPreview = boundedStructuredOutputDetailPreview(analysis.rawResponse)
        // Privileged diagnostics keep the ACTUAL validation reason (original
        // throwable message) when one exists; the ordinary summary stays
        // compatibility-safe fixed text.
        val detailSource = analysis.failure?.message ?: analysis.errorSummary
        val detailPreview = boundedStructuredOutputDetailPreview(detailSource)
        deliverStructuredOutputFailure(
            StructuredOutputFailureDiagnosticEvent(
                serviceName = serviceTypeName,
                methodName = operation.method.name,
                code = StructuredOutputFailureCode.OUTPUT_REJECTED,
                attempt = attemptIndex + 1,
                willRetry = attemptIndex < maxAttempts - 1,
                rawResponsePreview = rawPreview.text,
                rawResponseTruncated = rawPreview.truncated,
                detailPreview = detailPreview.text,
                detailTruncated = detailPreview.truncated,
                failure = analysis.failure,
                numericMetadata = mapOf("attempt" to (attemptIndex + 1).toLong()),
            ),
        )
        result.observation.onStructuredParseFailure(
            rawResponse = "<redacted structured-output failure>",
            errorSummary = "Structured output failed validation",
        )
        if (attemptIndex == maxAttempts - 1) {
            result.observation.onCallCompleted(parseSuccess = false)
            throw safeStructuredOutputFailure(
                code = StructuredOutputFailureCode.REPAIR_EXHAUSTED,
                attemptCount = maxAttempts,
            )
        }

        result.observation.onCallCompleted(parseSuccess = false)
        messages += Message(MessageRole.ASSISTANT, analysis.rawResponse)
        messages += Message(MessageRole.USER, analysis.feedbackMessage)
    }

    private suspend fun rethrowOrSanitizeStructuredHandlerFailure(
        operation: OperationDefinition,
        result: ProviderCallResult,
        failure: Throwable,
        attempt: Int,
    ): Nothing {
        failure.rethrowIfCancellation()
        currentCoroutineContext().ensureActive()
        // Anything thrown by a handler is UNTRUSTED regardless of exception
        // type — including exceptions produced by the public factory. The
        // factory only guarantees fixed text; a handler could still construct
        // a raw StructuredOutputException with arbitrary text, so it is always
        // re-sanitized here.
        deliverStructuredOutputFailure(
            StructuredOutputFailureDiagnosticEvent(
                serviceName = serviceTypeName,
                methodName = operation.method.name,
                code = StructuredOutputFailureCode.HANDLER_FAILED,
                attempt = attempt,
                // A thrown handler failure is always terminal: the safe
                // exception is thrown below, never retried.
                willRetry = false,
                rawResponsePreview = null,
                rawResponseTruncated = false,
                detailPreview = null,
                detailTruncated = false,
                failure = failure,
                numericMetadata = mapOf("attempt" to attempt.toLong()),
            ),
        )
        // Complete the ordinary observation exactly like the terminal
        // structured-failure path: parse-failure signal + terminal completion.
        result.observation.onStructuredParseFailure(
            rawResponse = "<redacted structured-output failure>",
            errorSummary = "Structured output failed validation",
        )
        result.observation.onCallCompleted(parseSuccess = false)
        throw safeStructuredOutputFailure(
            code = StructuredOutputFailureCode.HANDLER_FAILED,
            attemptCount = attempt,
        )
    }

    private suspend fun rethrowContractFailure(
        operation: OperationDefinition,
        failure: Throwable,
    ): Nothing {
        failure.rethrowIfCancellation()
        currentCoroutineContext().ensureActive()
        deliverStructuredOutputFailure(
            StructuredOutputFailureDiagnosticEvent(
                serviceName = serviceTypeName,
                methodName = operation.method.name,
                code = StructuredOutputFailureCode.CONTRACT_FAILED,
                attempt = 1,
                willRetry = false,
                rawResponsePreview = null,
                rawResponseTruncated = false,
                detailPreview = null,
                detailTruncated = false,
                failure = failure,
                numericMetadata = mapOf("attempt" to 1L),
            ),
        )
        throw safeStructuredOutputFailure(
            code = StructuredOutputFailureCode.CONTRACT_FAILED,
            attemptCount = 1,
        )
    }

    private suspend fun deliverStructuredOutputFailure(event: StructuredOutputFailureDiagnosticEvent) {
        try {
            structuredOutputFailureDiagnosticObserver.onFailure(event)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
        }
        currentCoroutineContext().ensureActive()
    }
}

private fun ExecutionSecurityContext.toCacheSecurityPartition() = CacheSecurityPartition(
    dataClassification = dataClassification,
    classificationSource = classificationSource,
)

private fun PolicyContextBuilder.applySecurityContext(
    securityContext: ExecutionSecurityContext,
): PolicyContextBuilder = dataClassification(securityContext.dataClassification)
    .classificationSource(securityContext.classificationSource)
