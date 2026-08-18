package dev.tramai.engine.invocation

import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.IdempotencyKeyUtil
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.ToolInvalidInputException
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolResult
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.provider.ResolvedProviderRoute
import dev.tramai.core.security.DlpContentType
import dev.tramai.core.security.DlpContentLocation
import dev.tramai.core.security.DlpContext
import dev.tramai.core.security.DlpInspectionException
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.PolicyContextBuilder
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.ReturnKind
import dev.tramai.engine.WorkflowDigestHelper
import dev.tramai.engine.approval.ApprovalSuspensionCoordinator
import dev.tramai.engine.approval.ClaimedResumeExecutionRequest
import dev.tramai.engine.approval.ClaimedResumeExecutor
import dev.tramai.engine.approval.ResumeOperationRegistry
import dev.tramai.engine.budget.TokenBudgetCoordinator
import dev.tramai.engine.budget.TokenBudgetTracker
import dev.tramai.engine.cache.OperationCacheCoordinator
import dev.tramai.engine.components.ApprovalCapability
import dev.tramai.engine.components.EngineComponents
import dev.tramai.engine.memory.ConversationMemoryCoordinator
import dev.tramai.engine.memory.PersistConversationTurnRequest
import dev.tramai.engine.ModelRegistryEnforcer
import dev.tramai.engine.planning.ServiceDefinition
import dev.tramai.engine.provider.ProviderAttemptExecutor
import dev.tramai.engine.provider.ProviderAuthorizationService
import dev.tramai.engine.provider.ProviderCallResult
import dev.tramai.engine.provider.ProviderExecutionCoordinator
import dev.tramai.engine.provider.ProviderExecutionRequest
import dev.tramai.engine.provider.ProviderFallbackGate
import dev.tramai.engine.provider.ProviderFallbackPolicy
import dev.tramai.engine.provider.ProviderInvocationGate
import dev.tramai.engine.provider.ProviderResolutionGate
import dev.tramai.engine.provider.ProviderResponseSanitizer
import dev.tramai.engine.provider.ProviderRetryPolicy
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.engine.ProviderRetryDelayPolicy
import dev.tramai.engine.streaming.StreamingBeforeResponseReturnGate
import dev.tramai.engine.streaming.StreamingExecutionCoordinator
import dev.tramai.engine.streaming.StreamingExecutionRequest
import dev.tramai.engine.structured.ResumedStructuredResponseRequest
import dev.tramai.engine.structured.StructuredAttemptExecutor
import dev.tramai.engine.structured.StructuredResponseCoordinator
import dev.tramai.engine.structured.StructuredResponseRequest
import dev.tramai.engine.tool.ToolAuthorizationCoordinator
import dev.tramai.engine.tool.ToolCallBatchRequest
import dev.tramai.engine.tool.ToolExecutionRequest
import dev.tramai.engine.tool.ToolExposureCoordinator
import dev.tramai.engine.tool.ToolInvocationExecutor
import dev.tramai.engine.tool.ToolReinjectionCoordinator
import dev.tramai.engine.tool.ToolResultSanitizer
import dev.tramai.engine.tool.ToolRetryPolicy
import dev.tramai.engine.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Top-level semantic dispatcher for one service's invocations.
 *
 * Owns the per-service invocation execution graph (provider, tool, approval,
 * memory, cache, budget, streaming, structured, raw, tool-loop) and the
 * approval-resume executor. It sequences return-kind dispatch; it does not
 * implement provider, tool, approval, DLP, cache, memory, or streaming
 * algorithms.
 */
internal class InvocationExecutionCoordinator(
    private val components: EngineComponents,
    private val circuitBreaker: ProviderCircuitBreaker,
    private val retryDelayPolicy: ProviderRetryDelayPolicy,
    private val migrationWarningGuard: AtomicBoolean,
    private val lifecycleScope: CoroutineScope,
    private val isClosed: AtomicBoolean = AtomicBoolean(false),
    private val serviceDefinition: ServiceDefinition,
    private val resumeOperationRegistry: ResumeOperationRegistry,
) : ClaimedResumeExecutor {
    private val routingPlan = components.providers.routingPlan
    private val structuredOutputHandler = components.execution.structuredOutputHandler
    private val toolRegistry = components.tools.toolRegistry
    private val operationObserver = components.observation.operationObserver
    private val operationInterceptor = components.observation.operationInterceptor
    private val responseCache = components.persistence.responseCache
    private val modelRegistry = components.security.modelRegistry
    private val modelRegistrySettings = components.security.modelRegistrySettings
    private val tokenBudgetSettings = components.execution.tokenBudgetSettings
    private val promptSanitizer = components.security.promptSanitizer
    private val chatMemory = components.persistence.chatMemory
    private val conversationIdProvider = components.persistence.conversationIdProvider
    private val dlpInterceptor = components.security.dlpInterceptor
    private val dlpRedactionAuditEmitter = components.security.dlpRedactionAuditEmitter
    private val toolResultFilteringSettings = components.tools.toolResultFilteringSettings
    private val engineEventObserver = components.observation.engineEventObserver
    private val toolFailureDiagnosticObserver = components.observation.toolFailureDiagnosticObserver
    private val structuredOutputFailureDiagnosticObserver = components.observation.structuredOutputFailureDiagnosticObserver
    private val policyDecisionAuditEmitter = components.security.policyDecisionAuditEmitter
    private val suspendedInvocationStore = components.approvals.suspendedInvocationStore
    private val approvalLifecycleAuditEmitter = components.approvals.approvalLifecycleAuditEmitter
    private val clock = components.execution.clock
    private val approvalContinuationStore = (components.approvals.capability as? ApprovalCapability.Enabled)?.continuationStore
    private val toolArgumentsDigester = (components.approvals.capability as? ApprovalCapability.Enabled)?.argumentsDigester
    private val approvalGateCoordinator = (components.approvals.capability as? ApprovalCapability.Enabled)?.gateCoordinator

    private val policyHelper = PolicyEnforcementHelper(components.security.resolvedPolicyEngine, migrationWarningGuard, isLegacyFallback = components.security.isLegacyFallback, auditEmitter = policyDecisionAuditEmitter)
    private val modelRegistryEnforcer = ModelRegistryEnforcer(modelRegistry, modelRegistrySettings)
    private val beforeProviderInvocationGate = ProviderInvocationGate { providerId, modelName, correlationId, securityContext -> enforceBeforeProviderInvocation(providerId, modelName, correlationId, securityContext) }
    private val beforeResolutionGate = ProviderResolutionGate { operation, correlationId, securityContext -> enforceBeforeProviderResolution(operation, correlationId, securityContext) }
    private val fallbackGate = ProviderFallbackGate { correlationId, previousProviderId, previousModelName, nextProviderId, reason, securityContext -> enforceFallbackTransition(correlationId, previousProviderId, previousModelName, nextProviderId, reason, securityContext) }
    private val providerExecutionCoordinator = ProviderExecutionCoordinator(
        routingPlan = routingPlan,
        circuitBreaker = circuitBreaker,
        attemptExecutor = ProviderAttemptExecutor(
            serviceInterface = serviceDefinition.serviceType.qualifiedName ?: serviceDefinition.serviceType.simpleName.orEmpty(),
            operationObserver = operationObserver,
            operationInterceptor = operationInterceptor,
            circuitBreaker = circuitBreaker,
            retryPolicy = ProviderRetryPolicy(retryDelayPolicy),
            authorizationService = ProviderAuthorizationService(modelRegistryEnforcer),
            beforeProviderInvocation = beforeProviderInvocationGate,
            responseSanitizer = ProviderResponseSanitizer { response, operation, providerId, modelName, correlationId, securityContext, observation -> sanitizeProviderResponse(response, operation, providerId, modelName, correlationId, securityContext, observation) },
        ),
        fallbackPolicy = ProviderFallbackPolicy(),
        beforeResolution = beforeResolutionGate,
        fallbackGate = fallbackGate,
    )
    private val toolExposureCoordinator = ToolExposureCoordinator(toolRegistry, policyHelper)
    private val conversationMemoryCoordinator = ConversationMemoryCoordinator(
        chatMemory = chatMemory,
        conversationIdProvider = conversationIdProvider,
    )
    internal val contextFactory = InvocationContextFactory(chatMemory, conversationMemoryCoordinator)
    private val operationCacheCoordinator = OperationCacheCoordinator(
        responseCache = responseCache,
        operationInterceptor = operationInterceptor,
        dlpInterceptor = dlpInterceptor,
        modelRegistrySettings = modelRegistrySettings,
        modelRegistryEnforcer = modelRegistryEnforcer,
        policyHelper = policyHelper,
    )
    private val tokenBudgetCoordinator = TokenBudgetCoordinator(tokenBudgetSettings)
    private val streamingExecutionCoordinator = StreamingExecutionCoordinator(
        routingPlan = routingPlan,
        circuitBreaker = circuitBreaker,
        lifecycleScope = lifecycleScope,
        isClosed = isClosed,
        serviceTypeName = serviceDefinition.serviceType.qualifiedName ?: serviceDefinition.serviceType.simpleName.orEmpty(),
        qualifiedServiceName = serviceDefinition.serviceType.qualifiedName,
        operationObserver = operationObserver,
        operationInterceptor = operationInterceptor,
        toolExposureCoordinator = toolExposureCoordinator,
        conversationMemoryCoordinator = conversationMemoryCoordinator,
        tokenBudgetCoordinator = tokenBudgetCoordinator,
        modelRegistryEnforcer = modelRegistryEnforcer,
        beforeResolution = beforeResolutionGate,
        beforeInvocation = beforeProviderInvocationGate,
        fallbackGate = fallbackGate,
        beforeResponseReturn = StreamingBeforeResponseReturnGate { route, correlationId, securityContext ->
            enforceBeforeResponseReturn(route, correlationId, securityContext)
        },
    )
    private val toolResultSanitizer = ToolResultSanitizer(
        toolRegistry = toolRegistry,
        dlpInterceptor = dlpInterceptor,
        dlpRedactionAuditEmitter = dlpRedactionAuditEmitter,
        toolResultFilteringSettings = toolResultFilteringSettings,
        engineEventObserver = engineEventObserver,
    )
    private val toolAuthorizationCoordinator = ToolAuthorizationCoordinator(policyHelper)
    private val toolRetryPolicy = ToolRetryPolicy()
    private val approvalSuspensionCoordinator = ApprovalSuspensionCoordinator(
        approvalGateCoordinator = approvalGateCoordinator,
        approvalContinuationStore = approvalContinuationStore,
        suspendedInvocationStore = suspendedInvocationStore,
        resumeOperationRegistry = resumeOperationRegistry,
        serviceDefinition = serviceDefinition,
        resumeExecutor = this,
        toolArgumentsDigester = toolArgumentsDigester,
        clock = clock,
        approvalLifecycleAuditEmitter = approvalLifecycleAuditEmitter,
    )
    private val toolInvocationExecutor = ToolInvocationExecutor(
        authorizationCoordinator = toolAuthorizationCoordinator,
        retryPolicy = toolRetryPolicy,
        toolFailureDiagnosticObserver = toolFailureDiagnosticObserver,
        approvalGate = approvalSuspensionCoordinator,
    )
    private val toolReinjectionCoordinator = ToolReinjectionCoordinator(
        toolRegistry = toolRegistry,
        policyHelper = policyHelper,
        invocationExecutor = toolInvocationExecutor,
        resultSanitizer = toolResultSanitizer,
    )
    private val toolLoopCoordinator = ToolLoopCoordinator(
        providerExecutionCoordinator = providerExecutionCoordinator,
        toolExposureCoordinator = toolExposureCoordinator,
        tokenBudgetCoordinator = tokenBudgetCoordinator,
        toolRegistry = toolRegistry,
        toolReinjectionCoordinator = toolReinjectionCoordinator,
    )
    private val rawResponseCoordinator = RawResponseCoordinator(
        conversationMemoryCoordinator = conversationMemoryCoordinator,
        operationCacheCoordinator = operationCacheCoordinator,
        policyHelper = policyHelper,
        toolLoopCoordinator = toolLoopCoordinator,
    )
    private val structuredResponseCoordinator = StructuredResponseCoordinator(
        structuredOutputHandler = structuredOutputHandler,
        structuredOutputFailureDiagnosticObserver = structuredOutputFailureDiagnosticObserver,
        conversationMemoryCoordinator = conversationMemoryCoordinator,
        operationCacheCoordinator = operationCacheCoordinator,
        policyHelper = policyHelper,
        attemptExecutor = StructuredAttemptExecutor { request ->
            toolLoopCoordinator.execute(
                ToolLoopContext(
                    operation = request.operation,
                    messages = request.messages,
                    tokenBudgetTracker = request.tokenBudgetTracker,
                    correlationId = request.correlationId,
                    securityContext = request.securityContext,
                    identity = request.identity,
                    conversationId = request.conversationId,
                    historySize = request.historySize,
                ),
            )
        },
        serviceTypeName = serviceDefinition.serviceType.qualifiedName
            ?: serviceDefinition.serviceType.simpleName
            ?: "<unknown>",
    )
    private suspend fun enforceBeforeProviderResolution(
        operation: OperationDefinition,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) {
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_RESOLUTION,
                correlationId = correlationId,
            ).modelName(operation.operation.model)
                .applySecurityContext(securityContext)
                .build()
        )
    }

    private suspend fun enforceBeforeResponseReturn(
        route: ResolvedProviderRoute,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) {
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                correlationId = correlationId,
            ).providerId(route.providerName)
                .modelName(route.effectiveModelName)
                .applySecurityContext(securityContext)
                .build()
        )
    }

    private suspend fun enforceBeforeProviderInvocation(
        providerId: String,
        modelName: String,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) {
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                correlationId = correlationId,
            ).providerId(providerId)
                .modelName(modelName)
                .applySecurityContext(securityContext)
                .build()
        )
    }

    /**
     * Enforces [EnforcementPoint.BEFORE_FALLBACK] at any transition point.
     * Used for provider failures, circuit breaker opens, streaming startup failures,
     * and route-unavailable transitions.
     */
    private suspend fun enforceFallbackTransition(
        correlationId: String,
        previousProviderId: String?,
        previousModelName: String?,
        nextProviderId: String,
        reason: String,
        securityContext: ExecutionSecurityContext,
    ) {
        val ctx = policyHelper.buildContext(
            enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_FALLBACK,
            correlationId = correlationId,
        ).applySecurityContext(securityContext)
        if (previousProviderId != null) ctx.providerId(previousProviderId)
        if (previousModelName != null) ctx.modelName(previousModelName)
        ctx.fallbackProviderId(nextProviderId)
        ctx.attribute("fallbackReason", reason)
        policyHelper.enforce(ctx.build())
    }
    suspend fun execute(context: InvocationExecutionContext): Any? {
        val operation = context.plan.definition
        val arguments = context.arguments
        val conversationId = context.conversationId
        val tokenBudgetTracker = tokenBudgetCoordinator.createTracker()
        val workflowRunId = java.util.UUID.randomUUID().toString()
        val workflowDigest = WorkflowDigestHelper.compute(operation, serviceDefinition)
        val identity = EngineExecutionIdentity(
            workflowRunId = workflowRunId,
            correlationId = "", // Will be set in executeRaw/the structured coordinator
            workflowDigest = workflowDigest,
            policyVersion = policyHelper.getPolicyVersion(),
            actorId = PolicyEnforcementHelper.ACTOR_ANONYMOUS,
        )
        return when (operation.returnKind) {
            ReturnKind.STRING -> rawResponseCoordinator.execute(
                RawResponseRequest(
                    plan = context.plan,
                    arguments = arguments,
                    tokenBudgetTracker = tokenBudgetTracker,
                    conversationId = conversationId,
                    identity = identity,
                ),
            )
            ReturnKind.UNIT -> {
                rawResponseCoordinator.execute(
                    RawResponseRequest(
                        plan = context.plan,
                        arguments = arguments,
                        tokenBudgetTracker = tokenBudgetTracker,
                        conversationId = conversationId,
                        identity = identity,
                    ),
                )
                Unit
            }
            ReturnKind.STRUCTURED -> structuredResponseCoordinator.execute(
                StructuredResponseRequest(
                    operation = operation,
                    arguments = arguments,
                    tokenBudgetTracker = tokenBudgetTracker,
                    conversationId = conversationId,
                    identity = identity,
                    operationFingerprint = context.plan.fingerprint,
                ),
            )
            ReturnKind.STREAMING -> streamingExecutionCoordinator.execute(
                StreamingExecutionRequest(
                    operation = operation,
                    arguments = arguments,
                    tokenBudgetTracker = tokenBudgetTracker,
                    conversationId = conversationId,
                ),
            )
        }
    }
    /**
     * Performs the authoritative DLP scan for a single response boundary.
     *
     * `text` must be the raw pre-DLP text for the specific scan boundary being inspected.
     */
    private suspend fun inspectDlpAuthoritatively(
        context: DlpContext,
        text: String,
    ) = dlpInterceptor.inspect(context, text).also { result ->
        val sanitizedTextChanged = result.sanitizedText != text
        val hasRedactionEvidence = result.redactions.isNotEmpty()

        if (sanitizedTextChanged && !hasRedactionEvidence && dlpRedactionAuditEmitter !== NoOpDlpRedactionAuditEmitter) {
            throw DlpInspectionException("DLP modified output without redaction evidence")
        }
        if (!sanitizedTextChanged && hasRedactionEvidence) {
            throw DlpInspectionException("DLP redactions reported without modifying output")
        }
        if (result.redactions.isNotEmpty()) {
            try {
                dlpRedactionAuditEmitter.emit(context, result.redactions)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error.rethrowIfCancellation()
                throw DlpInspectionException(
                    message = "DLP redaction audit emission failed",
                    cause = error,
                )
            }
        }
    }

    private fun inspectDlpForDetectionOnly(
        context: DlpContext,
        text: String,
    ) = dlpInterceptor.inspect(context, text)

    /**
     * Applies authoritative DLP inspection to model output without marking failures as provider failures.
     */
    private suspend fun sanitizeProviderResponse(
        interceptedResponse: ModelResponse,
        operation: OperationDefinition,
        providerId: String,
        modelName: String,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        observation: OperationObservation,
    ): ModelResponse = try {
        if (dlpInterceptor === NoOpDlpInterceptor) {
            interceptedResponse
        } else {
            applyProviderOutputDlp(
                interceptedResponse = interceptedResponse,
                operation = operation,
                providerId = providerId,
                modelName = modelName,
                correlationId = correlationId,
                securityContext = securityContext,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DlpInspectionException) {
        throw e
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        observation.onEngineEvent(
            name = "tramai.dlp.inspection_failed",
            attributes = mapOf("providerId" to providerId, "correlationId" to correlationId),
        )
        throw DlpInspectionException(
            message = "DLP inspection failed for provider '$providerId'",
            cause = e,
        )
    }

    /**
     * Builds the model-output DLP context and returns the sanitized response.
     */
    private suspend fun applyProviderOutputDlp(
        interceptedResponse: ModelResponse,
        operation: OperationDefinition,
        providerId: String,
        modelName: String,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ): ModelResponse {
        val dlpContext = DlpContext(
            contentType = DlpContentType.MODEL_OUTPUT,
            contentLocation = DlpContentLocation.MODEL_RESPONSE_CONTENT,
            operationInterface = serviceDefinition.serviceType.qualifiedName
                ?: serviceDefinition.serviceType.simpleName.orEmpty(),
            operationMethod = operation.method.name,
            providerId = providerId,
            modelName = modelName,
            correlationId = correlationId,
            dataClassification = securityContext.dataClassification,
            classificationSource = securityContext.classificationSource,
        )
        val dlpResult = inspectDlpAuthoritatively(dlpContext, interceptedResponse.content)
        return if (dlpResult.sanitizedText != interceptedResponse.content) {
            interceptedResponse.copy(content = dlpResult.sanitizedText)
        } else {
            interceptedResponse
        }
    }
    override suspend fun execute(request: ClaimedResumeExecutionRequest): Any? {
        val metadata = request.metadata
        val registered = request.registered
        val tokenBudgetTracker = tokenBudgetCoordinator.restoreTracker(metadata.tokenBudgetSnapshot)
        val toolResult = executeResumedTool(
            request = request,
            tokenBudgetTracker = tokenBudgetTracker,
        )
        val messages = request.rehydratedPayload.messages.toMutableList()
        val loopResult = continueAfterToolResult(
            ContinueAfterToolResultRequest(
                operation = registered.operation,
                messages = messages,
                toolResult = toolResult,
                toolCallId = metadata.toolCallId,
                toolCallIndex = metadata.toolCallIndex,
                correlationId = metadata.correlationId,
                securityContext = metadata.securityContext,
                identity = metadata.identity,
                tokenBudgetTracker = tokenBudgetTracker,
                suspendedToolName = metadata.toolName,
                approvalId = request.command.approvalId,
                conversationId = metadata.conversationId,
                historySize = metadata.historySize,
                resumingApproval = true,
            ),
        )
        return finalizeResumedOperation(
            operation = registered.operation,
            loopResult = loopResult,
            messages = messages,
            correlationId = metadata.correlationId,
            securityContext = metadata.securityContext,
            conversationId = metadata.conversationId,
            historySize = metadata.historySize,
        )
    }
    private suspend fun executeResumedTool(
        request: ClaimedResumeExecutionRequest,
        tokenBudgetTracker: TokenBudgetTracker,
    ): ToolResult {
        val command = request.command
        val metadata = request.metadata
        val registered = request.registered
        val resolvedTool = request.resolvedTool
        val rehydratedPayload = request.rehydratedPayload
        val validatedInput = request.validatedInput
        val expectedArgsDigest = request.expectedArgsDigest
        val validatedToolCall = dev.tramai.core.model.ToolCall(
            id = metadata.toolCallId,
            name = metadata.toolName,
            argumentsJson = validatedInput,
        )
        approvalLifecycleAuditEmitter.onToolExecutionResumed(
            approvalId = command.approvalId,
            workflowRunId = metadata.identity.workflowRunId,
            toolName = metadata.toolName,
            resumedBy = command.resumedBy,
        )
        return try {
            toolInvocationExecutor.execute(
                ToolExecutionRequest(
                    tool = resolvedTool,
                    toolCall = validatedToolCall,
                    operation = registered.operation,
                    correlationId = metadata.correlationId,
                    securityContext = metadata.securityContext,
                    identity = metadata.identity,
                    messages = rehydratedPayload.messages,
                    tokenBudgetTracker = tokenBudgetTracker,
                    conversationId = metadata.conversationId,
                    historySize = metadata.historySize,
                    resumingApproval = true,
                    parentApprovalId = command.approvalId,
                    idempotencyKey = IdempotencyKeyUtil.deriveApprovalKey(command.approvalId, metadata.toolCallId, expectedArgsDigest),
                    allowRenewedApprovedBindingDuringResume = true,
                ),
            )
        } catch (e: dev.tramai.core.exception.NestedApprovalNotSupportedException) {
            throw e
        } catch (e: ToolInvalidInputException) {
            request.uncertainOutcomeEmitter("tool-execution-failed: ${e::class.simpleName ?: "unknown"}")
            throw e
        }
    }
    /**
     * Continues the provider loop after a suspended tool has been executed on resume.
     *
     * 1. Enforces BEFORE_TOOL_RESULT_REINJECTION policy
     * 2. Formats and sanitizes the tool result message
     * 3. Appends the tool message to the messages list
     * 4. Processes any remaining unprocessed tool calls from the same batch
     * 5. Continues the provider loop via the [ToolLoopCoordinator]
     */
    private suspend fun continueAfterToolResult(request: ContinueAfterToolResultRequest): ProviderCallResult {
        val operation = request.operation
        val messages = request.messages
        val toolResult = request.toolResult
        val toolCallId = request.toolCallId
        val correlationId = request.correlationId
        val securityContext = request.securityContext
        val suspendedToolName = request.suspendedToolName

        toolReinjectionCoordinator.reinjectKnownResult(
            request = ToolCallBatchRequest(
                operation = operation,
                messages = messages,
                toolCalls = emptyList(),
                correlationId = correlationId,
                securityContext = securityContext,
                identity = request.identity,
                tokenBudgetTracker = request.tokenBudgetTracker,
                conversationId = request.conversationId,
                historySize = request.historySize,
                resumingApproval = request.resumingApproval,
                parentApprovalId = request.approvalId,
            ),
            toolCallId = toolCallId,
            toolName = suspendedToolName,
            toolResult = toolResult,
        )
        processRemainingResumeToolCalls(request)

        return toolLoopCoordinator.execute(
            ToolLoopContext(
                operation = operation,
                messages = messages,
                tokenBudgetTracker = request.tokenBudgetTracker,
                correlationId = correlationId,
                securityContext = securityContext,
                identity = request.identity,
                conversationId = request.conversationId,
                historySize = request.historySize,
                resumingApproval = request.resumingApproval,
                parentApprovalId = request.approvalId,
            ),
        )
    }
    private suspend fun processRemainingResumeToolCalls(request: ContinueAfterToolResultRequest) {
        if (request.toolCallIndex < 0) return
        val allToolCalls = request.messages
            .lastOrNull { it.role == MessageRole.ASSISTANT && it.toolCalls != null }
            ?.toolCalls
            ?: emptyList()
        val remainingToolCalls = allToolCalls.drop(request.toolCallIndex + 1)
        for ((remainingIdx, toolCall) in remainingToolCalls.withIndex()) {
            appendRemainingResumeToolResult(
                request = request,
                toolCall = toolCall,
                actualIndex = request.toolCallIndex + 1 + remainingIdx,
            )
        }
    }
    private suspend fun appendRemainingResumeToolResult(
        request: ContinueAfterToolResultRequest,
        toolCall: ToolCall,
        actualIndex: Int,
    ) {
        try {
            toolReinjectionCoordinator.processOne(
                request = ToolCallBatchRequest(
                    operation = request.operation,
                    messages = request.messages,
                    toolCalls = listOf(toolCall),
                    correlationId = request.correlationId,
                    securityContext = request.securityContext,
                    identity = request.identity,
                    tokenBudgetTracker = request.tokenBudgetTracker,
                    conversationId = request.conversationId,
                    historySize = request.historySize,
                    resumingApproval = request.resumingApproval,
                    parentApprovalId = request.approvalId,
                ),
                toolCall = toolCall,
                toolCallIndex = actualIndex,
            )
        } catch (e: dev.tramai.core.exception.NestedApprovalNotSupportedException) {
            throw dev.tramai.core.exception.NestedApprovalNotSupportedException(
                approvalId = request.approvalId,
                message = "Nested approval not supported in v1: sibling tool ${toolCall.name} requires approval",
            )
        } catch (e: ApprovalSuspendedException) {
            approvalLifecycleAuditEmitter.onUncertainOutcome(
                approvalId = request.approvalId,
                workflowRunId = request.identity.workflowRunId,
                toolName = toolCall.name,
                reason = "nested-approval-not-supported: sibling tool ${toolCall.name} requires approval",
            )
            throw ConfigurationException("Nested approval not supported in v1: sibling tool ${toolCall.name} requires approval")
        }
    }
    /**
     * Finalizes a resumed operation for all return kinds that don't need
     * structured parsing. Enforces BEFORE_RESPONSE_RETURN, persists conversation
     * memory, completes the observation, and returns the appropriate result.
     *
     * The [ReturnKind.STRUCTURED] branch delegates parsing, memory, and
     * observation completion to the structured response coordinator.
     */
    private suspend fun finalizeResumedOperation(
        operation: OperationDefinition,
        loopResult: ProviderCallResult,
        messages: MutableList<Message>,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        conversationId: String?,
        historySize: Int,
    ): Any? {
        when (operation.returnKind) {
            ReturnKind.STRING -> {
                // Enforce BEFORE_RESPONSE_RETURN (Fix 3: per-return-kind, not before dispatch)
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                        correlationId = correlationId,
                    ).providerId(loopResult.providerId)
                        .modelName(loopResult.modelName)
                        .applySecurityContext(securityContext)
                        .build()
                )
                // Memory persistence + observation + return (once)
                if (conversationId != null) {
                    conversationMemoryCoordinator.persistTurn(
                        PersistConversationTurnRequest(
                            conversationId,
                            messages,
                            historySize,
                            Message(
                                role = MessageRole.ASSISTANT,
                                content = loopResult.response.content,
                                toolCalls = loopResult.response.toolCalls,
                            ),
                        ),
                    )
                }
                loopResult.observation.onCallCompleted(parseSuccess = null)
                return loopResult.response.content
            }
            ReturnKind.UNIT -> {
                // Enforce BEFORE_RESPONSE_RETURN (Fix 3: per-return-kind, not before dispatch)
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                        correlationId = correlationId,
                    ).providerId(loopResult.providerId)
                        .modelName(loopResult.modelName)
                        .applySecurityContext(securityContext)
                        .build()
                )
                if (conversationId != null) {
                    conversationMemoryCoordinator.persistTurn(
                        PersistConversationTurnRequest(
                            conversationId,
                            messages,
                            historySize,
                            Message(
                                role = MessageRole.ASSISTANT,
                                content = loopResult.response.content,
                                toolCalls = loopResult.response.toolCalls,
                            ),
                        ),
                    )
                }
                loopResult.observation.onCallCompleted(parseSuccess = null)
                loopResult.response.content // consume it
                return Unit
            }
            ReturnKind.STRUCTURED -> {
                return structuredResponseCoordinator.finalizeResumed(
                    ResumedStructuredResponseRequest(
                        operation = operation,
                        loopResult = loopResult,
                        messages = messages,
                        correlationId = correlationId,
                        securityContext = securityContext,
                        conversationId = conversationId,
                        historySize = historySize,
                    ),
                )
            }
            ReturnKind.STREAMING -> throw ConfigurationException("Streaming approval resume not supported")
        }
    }
    private data class ContinueAfterToolResultRequest(
        val operation: OperationDefinition,
        val messages: MutableList<Message>,
        val toolResult: ToolResult,
        val toolCallId: String,
        val toolCallIndex: Int,
        val correlationId: String,
        val securityContext: ExecutionSecurityContext,
        val identity: EngineExecutionIdentity,
        val tokenBudgetTracker: TokenBudgetTracker,
        val suspendedToolName: String = "",
        val approvalId: String = "",
        val conversationId: String? = null,
        val historySize: Int = 0,
        val resumingApproval: Boolean = false,
    )
}

private fun PolicyContextBuilder.applySecurityContext(
    securityContext: ExecutionSecurityContext,
): PolicyContextBuilder = dataClassification(securityContext.dataClassification)
    .classificationSource(securityContext.classificationSource)
