package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.ConversationId
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.System as SystemMessage
import dev.tramai.core.annotations.SystemPrompt
import dev.tramai.core.annotations.User as UserMessage
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.ConversationIdProvider
import dev.tramai.core.memory.UuidConversationIdProvider
import dev.tramai.core.exception.ModelRegistryException
import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.CachedModelProvenanceMismatchException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.ProviderCapabilityException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.TokenBudgetExceededException
import dev.tramai.core.exception.TramaiException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.ClassifiedDocument
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolDefinition
import dev.tramai.core.model.ToolResult
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.observation.NoOpOperationInterceptor
import dev.tramai.core.observation.NoOpOperationObserver
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.observation.ToolFailureDiagnosticObserver
import dev.tramai.core.observation.NoOpToolFailureDiagnosticObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.provider.ResolvedProviderRoute
import dev.tramai.core.provider.StreamCapable
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.policy.PolicyDecisionAuditEmitter
import dev.tramai.core.policy.NoOpPolicyDecisionAuditEmitter
import dev.tramai.core.security.DlpContentType
import dev.tramai.core.security.DlpContentLocation
import dev.tramai.core.security.DlpContext
import dev.tramai.core.security.DlpInspectionException
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedactionAuditEmitter
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.core.security.PromptSanitizer
import dev.tramai.core.structured.StructuredOutputHandler
import dev.tramai.core.structured.StructuredOutputFailureDiagnosticObserver
import dev.tramai.core.structured.NoOpStructuredOutputFailureDiagnosticObserver
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ClaimedApprovalContinuation
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.IdempotencyKeyUtil
import dev.tramai.engine.provider.AttemptCounter
import dev.tramai.engine.provider.ProviderAttemptExecutor
import dev.tramai.engine.provider.ProviderAuthorizationService
import dev.tramai.engine.provider.ProviderExecutionCoordinator
import dev.tramai.engine.provider.ProviderExecutionRequest
import dev.tramai.engine.provider.ProviderFallbackGate
import dev.tramai.engine.provider.ProviderFallbackPolicy
import dev.tramai.engine.provider.ProviderInvocationGate
import dev.tramai.engine.provider.ProviderResolutionGate
import dev.tramai.engine.provider.ProviderResponseSanitizer
import dev.tramai.engine.provider.ProviderRetryPolicy
import dev.tramai.engine.tool.ToolApprovalGate
import dev.tramai.engine.tool.ToolAuthorizationCoordinator
import dev.tramai.engine.tool.ToolCallBatchRequest
import dev.tramai.engine.tool.ToolExecutionRequest
import dev.tramai.engine.tool.ToolExposureCoordinator
import dev.tramai.engine.tool.ToolInvocationExecutor
import dev.tramai.engine.tool.ToolReinjectionCoordinator
import dev.tramai.engine.tool.ToolResultSanitizer
import dev.tramai.engine.tool.ToolRetryPolicy
import dev.tramai.engine.approval.ApprovalResumeCoordinator
import dev.tramai.engine.approval.ApprovalSuspensionCoordinator
import dev.tramai.engine.approval.ClaimedResumeExecutionRequest
import dev.tramai.engine.approval.ClaimedResumeExecutor
import dev.tramai.engine.approval.ContinuationClaimService
import dev.tramai.engine.approval.ReplayAuthorizationService
import dev.tramai.engine.approval.ResumeOperationRegistry as ApprovalResumeOperationRegistry
import dev.tramai.engine.budget.TokenBudgetCoordinator
import dev.tramai.engine.cache.OperationCacheCoordinator
import dev.tramai.engine.cache.OperationCacheKeyRequest
import dev.tramai.engine.cache.OperationCacheLookupRequest
import dev.tramai.engine.cache.OperationCacheLookupResult
import dev.tramai.engine.cache.OperationCacheStoreRequest
import dev.tramai.engine.memory.ConversationMemoryCoordinator
import dev.tramai.engine.memory.PersistConversationTurnRequest
import dev.tramai.engine.structured.ResumedStructuredResponseRequest
import dev.tramai.engine.structured.StructuredAttemptExecutor
import dev.tramai.engine.structured.StructuredResponseCoordinator
import dev.tramai.engine.structured.StructuredResponseRequest
import dev.tramai.engine.streaming.StreamingBeforeResponseReturnGate
import dev.tramai.engine.streaming.StreamingExecutionCoordinator
import dev.tramai.engine.streaming.StreamingExecutionRequest
import dev.tramai.core.approval.ValidateResumeCommand
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ApprovalNotFoundException
import dev.tramai.core.exception.ToolInvalidInputException
import dev.tramai.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.reflect.KClass
import dev.tramai.engine.components.ApprovalCapability
import dev.tramai.engine.components.EngineComponentFactory
import dev.tramai.engine.components.EngineComponents
import dev.tramai.engine.planning.OperationDefinitionCompiler
import dev.tramai.engine.planning.OperationFingerprintFactory
import dev.tramai.engine.planning.ServiceDefinition
import dev.tramai.engine.planning.ServiceDefinitionCompiler
import dev.tramai.engine.invocation.InvocationExecutionCoordinator
import dev.tramai.engine.invocation.TramaiInvocationHandler
import dev.tramai.core.provider.resolveCandidates

private const val UNREGISTERED_TOOL_NAME = "unregistered_tool"

/** Compatibility alias for internal characterization fixtures; implementation lives in provider. */
internal typealias ProviderRetryDelayPolicy = dev.tramai.engine.provider.ProviderRetryDelayPolicy
internal typealias ResumeOperationRegistry = ApprovalResumeOperationRegistry

/**
 * Runtime engine that turns annotated service interfaces into AI-backed proxies.
 */
class TramaiEngine internal constructor(
    private val components: EngineComponents,
) : AutoCloseable {
    private val routingPlan = components.providers.routingPlan
    private val structuredOutputHandler = components.execution.structuredOutputHandler
    private val toolRegistry = components.tools.toolRegistry
    private val operationObserver = components.observation.operationObserver
    private val operationInterceptor = components.observation.operationInterceptor
    private val responseCache = components.persistence.responseCache
    private val modelRegistry = components.security.modelRegistry
    private val modelRegistrySettings = components.security.modelRegistrySettings
    private val circuitBreakerSettings = components.execution.circuitBreakerSettings
    private val retryDelayPolicy = components.execution.retryDelayPolicy
    private val tokenBudgetSettings = components.execution.tokenBudgetSettings
    private val promptSanitizer = components.security.promptSanitizer
    private val serviceDefinitionCompiler by lazy {
        ServiceDefinitionCompiler(
            OperationDefinitionCompiler(toolRegistry, promptSanitizer, OperationFingerprintFactory()),
        )
    }
    private val chatMemory = components.persistence.chatMemory
    private val conversationIdProvider = components.persistence.conversationIdProvider
    private val dlpInterceptor = components.security.dlpInterceptor
    private val dlpRedactionAuditEmitter = components.security.dlpRedactionAuditEmitter
    private val toolResultFilteringSettings = components.tools.toolResultFilteringSettings
    private val engineEventObserver = components.observation.engineEventObserver
    private val toolFailureDiagnosticObserver = components.observation.toolFailureDiagnosticObserver
    private val policyDecisionAuditEmitter = components.security.policyDecisionAuditEmitter
    private val suspendedInvocationStore = components.approvals.suspendedInvocationStore
    private val approvalContinuationStore = (components.approvals.capability as? ApprovalCapability.Enabled)?.continuationStore
    private val toolArgumentsDigester = (components.approvals.capability as? ApprovalCapability.Enabled)?.argumentsDigester
    private val approvalGateCoordinator = (components.approvals.capability as? ApprovalCapability.Enabled)?.gateCoordinator
    private val approvalLifecycleAuditEmitter = components.approvals.approvalLifecycleAuditEmitter
    private val clock = components.execution.clock
    private val structuredOutputFailureDiagnosticObserver = components.observation.structuredOutputFailureDiagnosticObserver

    constructor(
        providerRegistry: ProviderRegistry,
    structuredOutputHandler: StructuredOutputHandler? = null,
    toolRegistry: ToolRegistry = ToolRegistry(),
    operationObserver: OperationObserver = NoOpOperationObserver,
    operationInterceptor: OperationInterceptor = NoOpOperationInterceptor,
    responseCache: OperationResponseCache = NoOpOperationResponseCache,
    modelRegistry: ModelRegistry = NoOpModelRegistry,
    modelRegistrySettings: ModelRegistrySettings = ModelRegistrySettings(),
    circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(),
    retryPolicySettings: RetryPolicySettings = RetryPolicySettings(),
    tokenBudgetSettings: TokenBudgetSettings = TokenBudgetSettings(),
    promptSanitizer: PromptSanitizer? = null,
    chatMemory: ChatMemory? = null,
    conversationIdProvider: ConversationIdProvider = UuidConversationIdProvider(),
    job: Job = SupervisorJob(),
    scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default),
    policyEngine: dev.tramai.core.policy.PolicyEngine? = null,
    dlpInterceptor: DlpInterceptor = NoOpDlpInterceptor,
    dlpRedactionAuditEmitter: DlpRedactionAuditEmitter = NoOpDlpRedactionAuditEmitter,
    toolResultFilteringSettings: ToolResultFilteringSettings = ToolResultFilteringSettings(),
    engineEventObserver: EngineEventObserver = NoOpEngineEventObserver,
    toolFailureDiagnosticObserver: ToolFailureDiagnosticObserver = NoOpToolFailureDiagnosticObserver,
    policyDecisionAuditEmitter: PolicyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter,
    // Approval suspension dependencies
    suspendedInvocationStore: SuspendedInvocationStore = InMemorySuspendedInvocationStore(),
    approvalContinuationStore: ApprovalContinuationStore? = null,
    toolArgumentsDigester: ToolArgumentsDigester? = null,
    approvalGateCoordinator: ApprovalGateCoordinator? = null,
    approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
    clock: Clock = Clock.systemUTC(),
) : this(EngineComponentFactory.create(
    providerRegistry, structuredOutputHandler, toolRegistry, operationObserver, operationInterceptor, responseCache,
    modelRegistry, modelRegistrySettings, circuitBreakerSettings, retryPolicySettings, tokenBudgetSettings, promptSanitizer,
    chatMemory, conversationIdProvider, policyEngine, dlpInterceptor, dlpRedactionAuditEmitter,
    toolResultFilteringSettings, engineEventObserver, toolFailureDiagnosticObserver, policyDecisionAuditEmitter,
    suspendedInvocationStore, approvalContinuationStore, toolArgumentsDigester, approvalGateCoordinator,
    approvalLifecycleAuditEmitter, clock,
))
    private val circuitBreaker = ProviderCircuitBreaker(circuitBreakerSettings)
    private val migrationWarningGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    private val resolvedPolicyEngine: PolicyEngine = components.security.resolvedPolicyEngine
    private val isLegacyFallback: Boolean = components.security.isLegacyFallback
    private val resumeOperationRegistry: ResumeOperationRegistry = ResumeOperationRegistry()
    private val approvalPolicyHelper = PolicyEnforcementHelper(
        resolvedPolicyEngine,
        migrationWarningGuard,
        isLegacyFallback = isLegacyFallback,
        auditEmitter = policyDecisionAuditEmitter,
    )
    private val continuationClaimService = ContinuationClaimService(
        approvalContinuationStore = approvalContinuationStore,
    )
    private val replayAuthorizationService = ReplayAuthorizationService(
        approvalGateCoordinator = approvalGateCoordinator,
        suspendedInvocationStore = suspendedInvocationStore,
        approvalLifecycleAuditEmitter = approvalLifecycleAuditEmitter,
        policyHelper = approvalPolicyHelper,
        engineEventObserver = engineEventObserver,
    )
    private val approvalResumeCoordinator = ApprovalResumeCoordinator(
        approvalContinuationStore = approvalContinuationStore,
        suspendedInvocationStore = suspendedInvocationStore,
        resumeOperationRegistry = resumeOperationRegistry,
        toolRegistry = toolRegistry,
        toolArgumentsDigester = toolArgumentsDigester,
        approvalLifecycleAuditEmitter = approvalLifecycleAuditEmitter,
        engineEventObserver = engineEventObserver,
        claimService = continuationClaimService,
        authorizationService = replayAuthorizationService,
    )
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val engineThreadMarker = ThreadLocal<Boolean>()
    /**
     * Internally owned lifecycle job and scope. The engine's OWN work (blocking
     * calls, streaming collections) parents here — never to the caller-supplied
     * legacy job / scope constructor parameters, which remain for ABI compatibility
     * only. close() cancels and joins [lifecycleJob], so it can prove that
     * engine-initiated work has terminated, regardless of where the caller's
     * job lives (and without risking the caller-job join deadlock).
     */
    private val lifecycleJob: Job = SupervisorJob()
    // Every engine-owned child (streaming collection, future lifecycle tasks)
    // carries the engine-thread marker: close() called from ANY engine-owned
    // coroutine (provider/interceptor/observer re-entering close) skips the
    // join and cannot self-deadlock. Encoding ownership once at the scope
    // level is stronger than decorating individual launches.
    private var lifecycleDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val lifecycleScope: CoroutineScope by lazy { CoroutineScope(
        lifecycleJob + lifecycleDispatcher + engineThreadMarker.asContextElement(true) + CoroutineExceptionHandler { _, error ->
            // Engine-owned background work can outlive its caller (e.g. a
            // streaming collection abandoned mid-flight). Its failure is
            // already surfaced to the caller's continuation when one exists.
            // Orphaned failures must not crash the process or leak onto a
            // shared global handler. Log FIXED safe metadata only — never the
            // raw throwable: the failure may carry externally supplied
            // exception messages (PII), which the safe-error-boundary work
            // (Epic 1.2) keeps out of normal logs.
            System.getLogger("dev.tramai.engine.TramaiEngine").log(
                System.Logger.Level.WARNING,
                "Engine-owned coroutine failed after close or abandonment (type: ${error::class.qualifiedName})",
            )
        },
    ) }
    /**
     * Suspend-invocation jobs launched for caller continuations. They are
     * children of the CALLER's job (so parent cancellation propagates), but the
     * engine tracks them so close() terminates in-flight work it owns.
     */
    private val activeInvocationJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()

    /**
     * Internal deterministic-dispatch seam for the engine-owned lifecycle
     * scope. Controls ALL engine-owned work scheduled on [lifecycleScope]:
     * suspend-invocation execution AND streaming collection — not just one
     * path. Defaults to [Dispatchers.Default]; tests may inject a controlled
     * dispatcher to manufacture scheduler states deterministically.
     */
    internal constructor(
        provider: ModelProvider,
        lifecycleDispatcher: CoroutineDispatcher,
    ) : this(provider) {
        this.lifecycleDispatcher = lifecycleDispatcher
    }

    /**
     * Creates an engine backed by a single provider.
     */
    constructor(
        provider: ModelProvider,
        structuredOutputHandler: StructuredOutputHandler? = null,
        toolRegistry: ToolRegistry = ToolRegistry(),
        operationObserver: OperationObserver = NoOpOperationObserver,
        operationInterceptor: OperationInterceptor = NoOpOperationInterceptor,
        responseCache: OperationResponseCache = NoOpOperationResponseCache,
        modelRegistry: ModelRegistry = NoOpModelRegistry,
        modelRegistrySettings: ModelRegistrySettings = ModelRegistrySettings(),
        circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(),
        retryPolicySettings: RetryPolicySettings = RetryPolicySettings(),
        tokenBudgetSettings: TokenBudgetSettings = TokenBudgetSettings(),
        promptSanitizer: PromptSanitizer? = null,
        chatMemory: ChatMemory? = null,
        conversationIdProvider: ConversationIdProvider = UuidConversationIdProvider(),
        job: Job = SupervisorJob(),
        scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default),
        policyEngine: dev.tramai.core.policy.PolicyEngine? = null,
        dlpInterceptor: DlpInterceptor = NoOpDlpInterceptor,
        dlpRedactionAuditEmitter: DlpRedactionAuditEmitter = NoOpDlpRedactionAuditEmitter,
        toolResultFilteringSettings: ToolResultFilteringSettings = ToolResultFilteringSettings(),
        engineEventObserver: EngineEventObserver = NoOpEngineEventObserver,
        toolFailureDiagnosticObserver: ToolFailureDiagnosticObserver = NoOpToolFailureDiagnosticObserver,
        policyDecisionAuditEmitter: PolicyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter,
        suspendedInvocationStore: SuspendedInvocationStore = InMemorySuspendedInvocationStore(),
        approvalContinuationStore: ApprovalContinuationStore? = null,
        toolArgumentsDigester: ToolArgumentsDigester? = null,
        approvalGateCoordinator: ApprovalGateCoordinator? = null,
        approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        providerRegistry = ProviderRegistry.singleProvider(provider),
        structuredOutputHandler = structuredOutputHandler,
        toolRegistry = toolRegistry,
        operationObserver = operationObserver,
        operationInterceptor = operationInterceptor,
        responseCache = responseCache,
        modelRegistry = modelRegistry,
        modelRegistrySettings = modelRegistrySettings,
        circuitBreakerSettings = circuitBreakerSettings,
        retryPolicySettings = retryPolicySettings,
        tokenBudgetSettings = tokenBudgetSettings,
        promptSanitizer = promptSanitizer,
        chatMemory = chatMemory,
        conversationIdProvider = conversationIdProvider,
        policyEngine = policyEngine,
        dlpInterceptor = dlpInterceptor,
        dlpRedactionAuditEmitter = dlpRedactionAuditEmitter,
        toolResultFilteringSettings = toolResultFilteringSettings,
        engineEventObserver = engineEventObserver,
        toolFailureDiagnosticObserver = toolFailureDiagnosticObserver,
        policyDecisionAuditEmitter = policyDecisionAuditEmitter,
        suspendedInvocationStore = suspendedInvocationStore,
        approvalContinuationStore = approvalContinuationStore,
        toolArgumentsDigester = toolArgumentsDigester,
        approvalGateCoordinator = approvalGateCoordinator,
        approvalLifecycleAuditEmitter = approvalLifecycleAuditEmitter,
        clock = clock,
    )

    /**
     * Additive configuration: creates an engine backed by a single provider
     * with a structured-output failure diagnostic observer.
     *
     * The existing constructors remain byte-for-byte unchanged; this is the
     * additive path for the Epic 1.2 structured-output failure boundary.
     */
    constructor(
        provider: ModelProvider,
        structuredOutputHandler: StructuredOutputHandler? = null,
        toolRegistry: ToolRegistry = ToolRegistry(),
        operationObserver: OperationObserver = NoOpOperationObserver,
        operationInterceptor: OperationInterceptor = NoOpOperationInterceptor,
        responseCache: OperationResponseCache = NoOpOperationResponseCache,
        modelRegistry: ModelRegistry = NoOpModelRegistry,
        modelRegistrySettings: ModelRegistrySettings = ModelRegistrySettings(),
        circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(),
        retryPolicySettings: RetryPolicySettings = RetryPolicySettings(),
        tokenBudgetSettings: TokenBudgetSettings = TokenBudgetSettings(),
        promptSanitizer: PromptSanitizer? = null,
        chatMemory: ChatMemory? = null,
        conversationIdProvider: ConversationIdProvider = UuidConversationIdProvider(),
        job: Job = SupervisorJob(),
        scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default),
        policyEngine: dev.tramai.core.policy.PolicyEngine? = null,
        dlpInterceptor: DlpInterceptor = NoOpDlpInterceptor,
        dlpRedactionAuditEmitter: DlpRedactionAuditEmitter = NoOpDlpRedactionAuditEmitter,
        toolResultFilteringSettings: ToolResultFilteringSettings = ToolResultFilteringSettings(),
        engineEventObserver: EngineEventObserver = NoOpEngineEventObserver,
        toolFailureDiagnosticObserver: ToolFailureDiagnosticObserver = NoOpToolFailureDiagnosticObserver,
        structuredOutputFailureDiagnosticObserver: StructuredOutputFailureDiagnosticObserver = NoOpStructuredOutputFailureDiagnosticObserver,
        policyDecisionAuditEmitter: PolicyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter,
        suspendedInvocationStore: SuspendedInvocationStore = InMemorySuspendedInvocationStore(),
        approvalContinuationStore: ApprovalContinuationStore? = null,
        toolArgumentsDigester: ToolArgumentsDigester? = null,
        approvalGateCoordinator: ApprovalGateCoordinator? = null,
        approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
        clock: Clock = Clock.systemUTC(),
    ) : this(EngineComponentFactory.create(
        providerRegistry = ProviderRegistry.singleProvider(provider),
        structuredOutputHandler = structuredOutputHandler,
        toolRegistry = toolRegistry,
        operationObserver = operationObserver,
        operationInterceptor = operationInterceptor,
        responseCache = responseCache,
        modelRegistry = modelRegistry,
        modelRegistrySettings = modelRegistrySettings,
        circuitBreakerSettings = circuitBreakerSettings,
        retryPolicySettings = retryPolicySettings,
        tokenBudgetSettings = tokenBudgetSettings,
        promptSanitizer = promptSanitizer,
        chatMemory = chatMemory,
        conversationIdProvider = conversationIdProvider,
        policyEngine = policyEngine,
        dlpInterceptor = dlpInterceptor,
        dlpRedactionAuditEmitter = dlpRedactionAuditEmitter,
        toolResultFilteringSettings = toolResultFilteringSettings,
        engineEventObserver = engineEventObserver,
        toolFailureDiagnosticObserver = toolFailureDiagnosticObserver,
        policyDecisionAuditEmitter = policyDecisionAuditEmitter,
        suspendedInvocationStore = suspendedInvocationStore,
        approvalContinuationStore = approvalContinuationStore,
        toolArgumentsDigester = toolArgumentsDigester,
        approvalGateCoordinator = approvalGateCoordinator,
        approvalLifecycleAuditEmitter = approvalLifecycleAuditEmitter,
        clock = clock,
        structuredOutputFailureDiagnosticObserver = structuredOutputFailureDiagnosticObserver,
    ))

    /**
     * Additive configuration: creates an engine from a provider registry with
     * a structured-output failure diagnostic observer. Same contract as the
     * ModelProvider additive constructor; the existing primary constructor is
     * unchanged.
     */
    constructor(
        providerRegistry: ProviderRegistry,
        structuredOutputHandler: StructuredOutputHandler? = null,
        toolRegistry: ToolRegistry = ToolRegistry(),
        operationObserver: OperationObserver = NoOpOperationObserver,
        operationInterceptor: OperationInterceptor = NoOpOperationInterceptor,
        responseCache: OperationResponseCache = NoOpOperationResponseCache,
        modelRegistry: ModelRegistry = NoOpModelRegistry,
        modelRegistrySettings: ModelRegistrySettings = ModelRegistrySettings(),
        circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(),
        retryPolicySettings: RetryPolicySettings = RetryPolicySettings(),
        tokenBudgetSettings: TokenBudgetSettings = TokenBudgetSettings(),
        promptSanitizer: PromptSanitizer? = null,
        chatMemory: ChatMemory? = null,
        conversationIdProvider: ConversationIdProvider = UuidConversationIdProvider(),
        job: Job = SupervisorJob(),
        scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default),
        policyEngine: dev.tramai.core.policy.PolicyEngine? = null,
        dlpInterceptor: DlpInterceptor = NoOpDlpInterceptor,
        dlpRedactionAuditEmitter: DlpRedactionAuditEmitter = NoOpDlpRedactionAuditEmitter,
        toolResultFilteringSettings: ToolResultFilteringSettings = ToolResultFilteringSettings(),
        engineEventObserver: EngineEventObserver = NoOpEngineEventObserver,
        toolFailureDiagnosticObserver: ToolFailureDiagnosticObserver = NoOpToolFailureDiagnosticObserver,
        structuredOutputFailureDiagnosticObserver: StructuredOutputFailureDiagnosticObserver = NoOpStructuredOutputFailureDiagnosticObserver,
        policyDecisionAuditEmitter: PolicyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter,
        suspendedInvocationStore: SuspendedInvocationStore = InMemorySuspendedInvocationStore(),
        approvalContinuationStore: ApprovalContinuationStore? = null,
        toolArgumentsDigester: ToolArgumentsDigester? = null,
        approvalGateCoordinator: ApprovalGateCoordinator? = null,
        approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
        clock: Clock = Clock.systemUTC(),
    ) : this(EngineComponentFactory.create(
        providerRegistry = providerRegistry,
        structuredOutputHandler = structuredOutputHandler,
        toolRegistry = toolRegistry,
        operationObserver = operationObserver,
        operationInterceptor = operationInterceptor,
        responseCache = responseCache,
        modelRegistry = modelRegistry,
        modelRegistrySettings = modelRegistrySettings,
        circuitBreakerSettings = circuitBreakerSettings,
        retryPolicySettings = retryPolicySettings,
        tokenBudgetSettings = tokenBudgetSettings,
        promptSanitizer = promptSanitizer,
        chatMemory = chatMemory,
        conversationIdProvider = conversationIdProvider,
        policyEngine = policyEngine,
        dlpInterceptor = dlpInterceptor,
        dlpRedactionAuditEmitter = dlpRedactionAuditEmitter,
        toolResultFilteringSettings = toolResultFilteringSettings,
        engineEventObserver = engineEventObserver,
        toolFailureDiagnosticObserver = toolFailureDiagnosticObserver,
        policyDecisionAuditEmitter = policyDecisionAuditEmitter,
        suspendedInvocationStore = suspendedInvocationStore,
        approvalContinuationStore = approvalContinuationStore,
        toolArgumentsDigester = toolArgumentsDigester,
        approvalGateCoordinator = approvalGateCoordinator,
        approvalLifecycleAuditEmitter = approvalLifecycleAuditEmitter,
        clock = clock,
        structuredOutputFailureDiagnosticObserver = structuredOutputFailureDiagnosticObserver,
    ))

    /**
     * Creates a proxy implementation for the given Tramai service interface.
     */
    fun <T : Any> create(serviceType: KClass<T>): T {
        check(!closed.get()) { "Tramai runtime is closed" }
        val definition = serviceDefinitionCompiler.compile(serviceType)
        val executionCoordinator = InvocationExecutionCoordinator(
            components = components,
            circuitBreaker = circuitBreaker,
            retryDelayPolicy = retryDelayPolicy,
            migrationWarningGuard = migrationWarningGuard,
            lifecycleScope = lifecycleScope,
            isClosed = closed,
            serviceDefinition = definition,
            resumeOperationRegistry = resumeOperationRegistry,
        )
        val handler = TramaiInvocationHandler(
            serviceDefinition = definition,
            lifecycleJob = lifecycleJob,
            lifecycleScope = lifecycleScope,
            isClosed = closed,
            engineThreadMarker = engineThreadMarker,
            activeInvocationJobs = activeInvocationJobs,
            contextFactory = executionCoordinator.contextFactory,
            executionCoordinator = executionCoordinator,
        )

        @Suppress("UNCHECKED_CAST")
        return (Proxy.newProxyInstance(
            serviceType.java.classLoader,
            arrayOf(serviceType.java),
            handler,
        ) as T).also {
            resumeOperationRegistry.registerAll(
                serviceDefinition = definition,
                resumeExecutor = executionCoordinator,
            )
        }
    }

    /**
     * Registers a service type's operations in the [ResumeOperationRegistry]
     * without creating a Java proxy. After restart, call this before [resumeApproval]
     * to make suspended operations resolvable.
     *
     * Repeated identical registration is idempotent.
     * Conflicting registration (same key, different digest) fails closed.
     */
    fun registerService(serviceType: KClass<*>) {
        check(!closed.get()) { "Tramai runtime is closed" }
        val definition = serviceDefinitionCompiler.compile(serviceType)
        val executionCoordinator = InvocationExecutionCoordinator(
            components = components,
            circuitBreaker = circuitBreaker,
            retryDelayPolicy = retryDelayPolicy,
            migrationWarningGuard = migrationWarningGuard,
            lifecycleScope = lifecycleScope,
            isClosed = closed,
            serviceDefinition = definition,
            resumeOperationRegistry = resumeOperationRegistry,
        )
        resumeOperationRegistry.registerAll(
            serviceDefinition = definition,
            resumeExecutor = executionCoordinator,
        )
    }

    /**
     * Resume an approval-suspended tool execution and return the operation result.
     *
     * Validates the approval, authorises the resume, claims the continuation,
     * executes the suspended tool, and continues the provider loop.
     *
     * @throws dev.tramai.core.exception.ApprovalNotFoundException if the approval does not exist.
     * @throws dev.tramai.core.exception.ApprovalTokenRejectedException if the presented token is invalid.
     * @throws dev.tramai.core.exception.ApprovalBindingMismatchException if binding metadata does not match.
     * @throws dev.tramai.core.exception.ApprovalAuthorizationException on store-level failures.
     */
    suspend fun resumeApproval(command: ResumeApprovalCommand): Any? {
        check(!closed.get()) { "Tramai runtime is closed" }
        return approvalResumeCoordinator.resume(command)
    }

    /**
     * Typed convenience overload for [resumeApproval].
     */
    @Suppress("UNCHECKED_CAST")
    suspend inline fun <reified R> resumeApprovalTyped(command: ResumeApprovalCommand): R =
        resumeApproval(command) as R

    /**
     * Cancels all engine-initiated work and, except from one of the engine's
     * own coroutines, waits for it to terminate. The caller-supplied legacy job /
     * scope constructor parameters are NEVER cancelled or joined here — the
     * engine owns its own [lifecycleJob], so closing cannot deadlock a caller
     * that passed its current job. Dependencies supplied by callers are not
     * closed.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            lifecycleJob.cancel()
            // Suspend invocations are children of their CALLER's job, not the
            // engine scope job; cancel them explicitly so close() owns them.
            // Synchronized with the launch+add in invokeSuspend: either the
            // launch completed first (its job is in the set and gets cancelled
            // here) or close() won and the launch's in-lock re-check rejects it.
            val tracked = synchronized(activeInvocationJobs) {
                activeInvocationJobs.toList()
            }
            tracked.forEach { it.cancel() }
            if (engineThreadMarker.get() != true) {
                // Wait for the engine-owned hierarchy AND every tracked
                // invocation: cancellation is a request, not termination —
                // cleanup (e.g. NonCancellable finally blocks) must complete
                // before close() returns. Invocation jobs run on the engine's
                // own dispatcher (lifecycleScope's Dispatchers.Default; the
                // caller's ContinuationInterceptor is stripped at launch so a
                // single-threaded caller loop can't be blocked by close()),
                // so joining is safe as long as close() is not called from a
                // coroutine dispatched on that same engine dispatcher
                // (documented caller constraint, matching the self-close
                // marker guard below).
                runBlocking {
                    lifecycleJob.join()
                    tracked.forEach { it.join() }
                }
            }
        }
    }
}

/**
 * Reified convenience overload for [TramaiEngine.create].
 */
inline fun <reified T : Any> TramaiEngine.create(): T = create(T::class)

/**
 * Command to resume an approval-suspended tool execution.
 *
 * @property approvalId The approval ID of the suspended execution.
 * @property approvalExpectedVersion The expected version of the approval.
 * @property continuationExpectedVersion The expected version of the continuation.
 * @property presentedToken The approval token presented for authorization.
 * @property resumedBy The identity initiating the resume.
 */
data class ResumeApprovalCommand(
    val approvalId: String,
    val approvalExpectedVersion: Long,
    val continuationExpectedVersion: Long,
    val presentedToken: ApprovalToken,
    val resumedBy: String,
)

data class OperationDefinition(
    val method: Method,
    val operation: Operation,
    val classLevelSystemPrompt: String?,
    val systemAnnotations: List<String>,
    val userAnnotations: List<String>,
    val isSuspend: Boolean,
    val parameterNames: List<String>,
    val returnKind: ReturnKind,
    val returnType: kotlin.reflect.KType?,
    val returnTypeDescription: String,
    val toolDefinitions: List<ToolDefinition>,
    val promptSanitizer: PromptSanitizer?,
) {
    /**
     * Operation-static cache eligibility (no chat memory, no tools, no streaming,
     * no custom [dev.tramai.core.observation.OperationInterceptor]).
     *
     * The interceptor-aware portion of the check lives on the cache coordinator
     * because the interceptor is engine-scoped, not operation-scoped. Use the
     * coordinator when evaluating an actual cache read/write.
     */
    fun isOperationCacheEligible(): Boolean =
        operation.cacheable &&
            returnKind != ReturnKind.STREAMING &&
            toolDefinitions.isEmpty()

    /**
     * The effective system message, resolved by precedence:
     * 1. Method-level @System annotations (concatenated)
     * 2. Class-level @SystemPrompt
     * 3. null (engine will construct a default)
     */
    val effectiveSystemMessage: String? get() {
        if (systemAnnotations.isNotEmpty()) {
            return systemAnnotations.joinToString("\n")
        }
        return classLevelSystemPrompt?.takeIf { it.isNotBlank() }
    }

    /**
     * Whether multi-message annotations (@System / @User) are present.
     * When true, [initialMessages] builds messages from annotations instead of [Operation.prompt].
     */
    val hasMultiMessageAnnotations: Boolean get() =
        systemAnnotations.isNotEmpty() || userAnnotations.isNotEmpty()

    private fun sanitizedArgumentValues(arguments: List<Any?>): List<String> = arguments.map { argument ->
        val rendered = when (argument) {
            is ClassifiedDocument<*> -> argument.payload?.toString() ?: ""
            else -> argument?.toString() ?: ""
        }
        promptSanitizer?.sanitize(rendered) ?: rendered
    }

    fun toRequest(
        arguments: List<Any?>,
        modelName: String = operation.model,
    ): ModelRequest {
        return ModelRequest(
            model = modelName,
            messages = initialMessages(arguments),
            tools = toolDefinitions.takeIf { it.isNotEmpty() },
            timeoutMillis = operation.timeoutMillis,
            operationInterface = method.declaringClass.name,
            operationMethod = method.name,
        )
    }

    fun initialMessages(
        arguments: List<Any?>,
        schemaJson: String? = null,
    ): List<Message> {
        val sanitizedArguments = sanitizedArgumentValues(arguments)
        return if (hasMultiMessageAnnotations) {
            buildMessagesFromAnnotations(sanitizedArguments, schemaJson)
        } else {
            buildMessagesFromPrompt(sanitizedArguments, schemaJson)
        }
    }

    private fun buildMessagesFromAnnotations(
        arguments: List<String>,
        schemaJson: String?,
    ): List<Message> = buildList {
        add(annotationSystemMessage(arguments))
        addAll(annotationUserMessages(arguments))
        appendSchemaConstraint(schemaJson)
    }

    /**
     * Builds the system message used by multi-message annotations.
     */
    private fun annotationSystemMessage(arguments: List<String>): Message {
        val system = effectiveSystemMessage
        return if (!system.isNullOrBlank()) {
            Message(role = MessageRole.SYSTEM, content = interpolate(system, arguments))
        } else {
            Message(role = MessageRole.SYSTEM, content = defaultSystemMessage())
        }
    }

    /**
     * Builds user messages from @User annotations, @Operation.prompt, or the default operation text.
     */
    private fun annotationUserMessages(arguments: List<String>): List<Message> =
        when {
            userAnnotations.isNotEmpty() -> userAnnotations.map { template ->
                Message(role = MessageRole.USER, content = interpolate(template, arguments))
            }
            operation.prompt.isNotBlank() -> listOf(
                Message(role = MessageRole.USER, content = interpolate(operation.prompt, arguments))
            )
            else -> listOf(
                Message(
                    role = MessageRole.USER,
                    content = "Execute the operation ${method.name} with the provided parameters.",
                )
            )
        }

    /**
     * Adds the structured-output schema constraint to the final user message.
     */
    private fun MutableList<Message>.appendSchemaConstraint(schemaJson: String?) {
        if (schemaJson.isNullOrBlank()) {
            return
        }
        val lastUserIndex = indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0) {
            return
        }
        val last = this[lastUserIndex]
        this[lastUserIndex] = last.copy(
            content = last.content + "\n\nRespond only with valid JSON matching this schema:\n$schemaJson",
        )
    }

    private fun buildMessagesFromPrompt(
        arguments: List<String>,
        schemaJson: String?,
    ): List<Message> {
        val messages = buildList {
            // Class-level @SystemPrompt still applies for backward compat
            if (!classLevelSystemPrompt.isNullOrBlank()) {
                add(Message(role = MessageRole.SYSTEM, content = classLevelSystemPrompt))
            }

            val userMessage = buildString {
                append(operation.prompt)
                if (!schemaJson.isNullOrBlank()) {
                    append("\n\nRespond only with valid JSON matching this schema:\n")
                    append(schemaJson)
                }
                if (arguments.isNotEmpty()) {
                    append("\n\nArguments:")
                    arguments.forEachIndexed { index, value ->
                        append("\n- ")
                        append(parameterNames.getOrElse(index) { "arg$index" })
                        append(": ")
                        append(value)
                    }
                }
            }
            add(Message(role = MessageRole.USER, content = userMessage))
        }

        return messages
    }

    private fun interpolate(template: String, arguments: List<String>): String {
        var result = template
        arguments.forEachIndexed { index, value ->
            val name = parameterNames.getOrElse(index) { "arg$index" }
            result = result.replace("{$name}", value)
        }
        return result
    }

    private fun defaultSystemMessage(): String = buildString {
        append("You are an AI assistant implementing the \"")
        append(method.declaringClass.simpleName)
        append("\" service.\nMethod: ")
        append(method.name)
        append("(")
        append(parameterNames.joinToString(", "))
        append(")\nReturn type: ")
        append(returnTypeDescription)
    }

    fun cacheKey(
        arguments: List<Any?>,
        schemaJson: String? = null,
    ): OperationCacheKey = buildCacheKey(
        digestSource = initialMessages(arguments, schemaJson),
        securityPartition = ExecutionSecurityContext.fromArguments(arguments.toTypedArray()).toCacheSecurityPartition(),
    )

    internal fun buildCacheKey(
        digestSource: List<Message>,
        securityPartition: CacheSecurityPartition,
        operationFingerprint: String? = null,
    ): OperationCacheKey = OperationCacheKey(
        serviceInterface = method.declaringClass.name,
        methodName = method.name,
        requestedModel = operation.model,
        explicitProvider = operation.provider.takeIf { it.isNotBlank() },
        requestDigest = sha256Hex(CanonicalMessageEncoder.encode(digestSource)),
        operationFingerprint = operationFingerprint ?: OperationFingerprintFactory().create(toolDefinitions, operation),
        securityPartition = securityPartition,
    )

    fun structuredContract(handler: StructuredOutputHandler) = handler.createContract(
        requireNotNull(returnType) {
            "Structured return type $returnTypeDescription could not be inspected without Kotlin reflection metadata"
        },
    )

    companion object {
        /**
         * Public compatibility façade. The reflection/validation implementation
         * lives in [dev.tramai.engine.planning.OperationDefinitionCompiler.compileDefinition].
         */
        fun create(
            method: Method,
            operation: Operation,
            classLevelSystemPrompt: String?,
            systemAnnotations: List<String> = emptyList(),
            userAnnotations: List<String> = emptyList(),
            toolDefinitions: List<ToolDefinition> = emptyList(),
            promptSanitizer: PromptSanitizer? = null,
        ): OperationDefinition = dev.tramai.engine.planning.OperationDefinitionCompiler.compileDefinition(
            method = method,
            operation = operation,
            classLevelSystemPrompt = classLevelSystemPrompt,
            systemAnnotations = systemAnnotations,
            userAnnotations = userAnnotations,
            toolDefinitions = toolDefinitions,
            promptSanitizer = promptSanitizer,
        )
    }
}

internal fun buildOperationCacheKeyForTesting(
    serviceType: KClass<*>,
    methodName: String,
    arguments: List<Any?>,
    schemaJson: String? = null,
    promptSanitizer: PromptSanitizer? = null,
    toolRegistry: ToolRegistry = ToolRegistry(),
): OperationCacheKey {
    val definition = ServiceDefinitionCompiler(
        OperationDefinitionCompiler(toolRegistry, promptSanitizer, OperationFingerprintFactory()),
    ).compile(serviceType)
    val method = serviceType.java.methods.firstOrNull { it.name == methodName }
        ?: throw IllegalArgumentException("No method named '$methodName' on ${serviceType.java.name}")
    val operation = definition.operations[method]?.definition
        ?: throw IllegalArgumentException("No operation metadata for ${serviceType.java.name}.$methodName")
    return operation.buildCacheKey(
        digestSource = operation.initialMessages(arguments, schemaJson),
        securityPartition = ExecutionSecurityContext.fromArguments(arguments.toTypedArray()).toCacheSecurityPartition(),
    )
}



enum class ReturnKind {
    STRING,
    UNIT,
    STRUCTURED,
    STREAMING,
}


private fun ExecutionSecurityContext.toCacheSecurityPartition() = CacheSecurityPartition(
    dataClassification = dataClassification,
    classificationSource = classificationSource,
)

internal fun buildRequestDigest(messages: List<Message>): String = sha256Hex(CanonicalMessageEncoder.encode(messages))

internal fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}



/** @see TramaiEngine */

/** @see TramaiEngine */
