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
    private val providerResponseDlpSanitizer = ProviderResponseDlpSanitizer(
        dlpInterceptor = dlpInterceptor,
        dlpRedactionAuditEmitter = dlpRedactionAuditEmitter,
        serviceDefinition = serviceDefinition,
    )
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
            responseSanitizer = ProviderResponseSanitizer { response, operation, providerId, modelName, correlationId, securityContext, observation -> providerResponseDlpSanitizer.sanitizeProviderResponse(response, operation, providerId, modelName, correlationId, securityContext, observation) },
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
        retryPolicy = ProviderRetryPolicy(retryDelayPolicy),
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
    private val claimedResumeCoordinator = ClaimedResumeExecutionCoordinator(
        tokenBudgetCoordinator = tokenBudgetCoordinator,
        toolInvocationExecutor = toolInvocationExecutor,
        toolReinjectionCoordinator = toolReinjectionCoordinator,
        toolLoopCoordinator = toolLoopCoordinator,
        structuredResponseCoordinator = structuredResponseCoordinator,
        conversationMemoryCoordinator = conversationMemoryCoordinator,
        policyHelper = policyHelper,
        approvalLifecycleAuditEmitter = approvalLifecycleAuditEmitter,
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
    override suspend fun execute(request: ClaimedResumeExecutionRequest): Any? =
        claimedResumeCoordinator.execute(request)
}

private fun PolicyContextBuilder.applySecurityContext(
    securityContext: ExecutionSecurityContext,
): PolicyContextBuilder = dataClassification(securityContext.dataClassification)
    .classificationSource(securityContext.classificationSource)
