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
import dev.tramai.core.structured.StructuredOutputFailureCode
import dev.tramai.core.structured.StructuredOutputFailureDiagnosticEvent
import dev.tramai.core.structured.StructuredOutputFailureDiagnosticObserver
import dev.tramai.core.structured.NoOpStructuredOutputFailureDiagnosticObserver
import dev.tramai.core.structured.StructuredOutputResult
import dev.tramai.core.structured.boundedStructuredOutputDetailPreview
import dev.tramai.core.exception.StructuredOutputException
import dev.tramai.core.exception.safeStructuredOutputFailure
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
import dev.tramai.core.provider.resolveCandidates

private const val UNREGISTERED_TOOL_NAME = "unregistered_tool"

/** Compatibility alias for internal characterization fixtures; implementation lives in provider. */
internal typealias ProviderRetryDelayPolicy = dev.tramai.engine.provider.ProviderRetryDelayPolicy
internal typealias ResumeOperationRegistry = ApprovalResumeOperationRegistry

/**
 * Runtime engine that turns annotated service interfaces into AI-backed proxies.
 */
class TramaiEngine private constructor(
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
    private val retryPolicySettings = components.execution.retryPolicySettings
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
    private val retryDelayPolicy = ProviderRetryDelayPolicy(retryPolicySettings)
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
        approvalGateCoordinator = approvalGateCoordinator,
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
    private val lifecycleScope: CoroutineScope = CoroutineScope(
        lifecycleJob + Dispatchers.Default + engineThreadMarker.asContextElement(true) + CoroutineExceptionHandler { _, error ->
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
    )
    /**
     * Suspend-invocation jobs launched for caller continuations. They are
     * children of the CALLER's job (so parent cancellation propagates), but the
     * engine tracks them so close() terminates in-flight work it owns.
     */
    private val activeInvocationJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()

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
        val handler = TramaiInvocationHandler(
            components = components,
            circuitBreaker = circuitBreaker,
            retryDelayPolicy = retryDelayPolicy,
            migrationWarningGuard = migrationWarningGuard,
            lifecycleJob = lifecycleJob,
            lifecycleScope = lifecycleScope,
            isClosed = closed,
            engineThreadMarker = engineThreadMarker,
            activeInvocationJobs = activeInvocationJobs,
            serviceDefinition = definition,
            resumeOperationRegistry = resumeOperationRegistry,
        )

        @Suppress("UNCHECKED_CAST")
        return (Proxy.newProxyInstance(
            serviceType.java.classLoader,
            arrayOf(serviceType.java),
            handler,
        ) as T).also {
            resumeOperationRegistry.registerAll(
                serviceDefinition = definition,
                resumeExecutor = handler,
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
        val handler = TramaiInvocationHandler(
            components = components,
            circuitBreaker = circuitBreaker,
            retryDelayPolicy = retryDelayPolicy,
            migrationWarningGuard = migrationWarningGuard,
            lifecycleJob = lifecycleJob,
            lifecycleScope = lifecycleScope,
            isClosed = closed,
            engineThreadMarker = engineThreadMarker,
            activeInvocationJobs = activeInvocationJobs,
            serviceDefinition = definition,
            resumeOperationRegistry = resumeOperationRegistry,
        )
        resumeOperationRegistry.registerAll(
            serviceDefinition = definition,
            resumeExecutor = handler,
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

internal class TramaiInvocationHandler(
    private val components: EngineComponents,
    private val circuitBreaker: ProviderCircuitBreaker,
    private val retryDelayPolicy: ProviderRetryDelayPolicy,
    private val migrationWarningGuard: java.util.concurrent.atomic.AtomicBoolean,
    private val lifecycleJob: Job,
    private val lifecycleScope: CoroutineScope,
    private val isClosed: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
    private val engineThreadMarker: ThreadLocal<Boolean> = ThreadLocal(),
    private val activeInvocationJobs: MutableSet<Job> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
    private val serviceDefinition: ServiceDefinition,
    private val resumeOperationRegistry: ResumeOperationRegistry,
) : InvocationHandler, ClaimedResumeExecutor {

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
            beforeProviderInvocation = ProviderInvocationGate { providerId, modelName, correlationId, securityContext -> enforceBeforeProviderInvocation(providerId, modelName, correlationId, securityContext) },
            responseSanitizer = ProviderResponseSanitizer { response, operation, providerId, modelName, correlationId, securityContext, observation -> sanitizeProviderResponse(response, operation, providerId, modelName, correlationId, securityContext, observation) },
        ),
        fallbackPolicy = ProviderFallbackPolicy(),
        beforeResolution = ProviderResolutionGate { operation, correlationId, securityContext -> enforceBeforeProviderResolution(operation, correlationId, securityContext) },
        fallbackGate = ProviderFallbackGate { correlationId, previousProviderId, previousModelName, nextProviderId, reason, securityContext -> enforceFallbackTransition(correlationId, previousProviderId, previousModelName, nextProviderId, reason, securityContext) },
    )
    private val toolExposureCoordinator = ToolExposureCoordinator(toolRegistry, policyHelper)
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

    private fun OperationObservation.completeCancellation(cancellation: CancellationException) {
        try {
            onCallCancelled()
        } catch (observerError: Throwable) {
            cancellation.addSuppressed(observerError)
        }
    }

    /**
     * Finalises tool-processing observation without a suspend boundary,
     * so the cancellation scanner does not flag a broad [Throwable] catch.
     *
     * When [primaryError] is provided, observer failure is suppressed on it.
     * When it is null (successful tool processing), observer failure is
     * logged as a warning but does not invalidate the completed side effect.
     */
    private fun OperationObservation.completeAfterToolProcessing(
        primaryError: Throwable? = null,
    ) {
        try {
            onCallCompleted(parseSuccess = null)
        } catch (observerError: Throwable) {
            if (primaryError != null) {
                primaryError.addSuppressed(observerError)
            } else {
                System.getLogger("dev.tramai.engine.TramaiEngine").log(
                    System.Logger.Level.WARNING,
                    "Operation observer failed after successful tool processing",
                    observerError,
                )
            }
        }
    }

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass == Any::class.java) {
            return handleObjectMethod(proxy, method, args.orEmpty())
        }

        check(!isClosed.get()) { "Tramai runtime is closed" }

        val plan = serviceDefinition.operations[method]
            ?: throw ConfigurationException("No operation metadata registered for ${method.name}")
        val operation = plan.definition

        val conversationId = if (chatMemory != null) resolveConversationId(method, args.orEmpty()) else null
        return if (operation.isSuspend) {
            invokeSuspend(operation, args.orEmpty(), conversationId)
        } else {
            // Run the blocking call as a child of the engine's OWN lifecycle
            // job (not the caller-supplied job/scope): close() cancels and
            // joins lifecycleJob, so it can terminate a blocking provider
            // that is still executing when the engine is closed. The thread
            // marker marks this coroutine as engine-owned so a blocking call
            // that itself invokes close() skips the join (avoiding a
            // self-deadlock on lifecycleJob).
            val result = runBlocking(lifecycleJob + engineThreadMarker.asContextElement(true)) {
                execute(operation, args.orEmpty().toList(), conversationId)
            }
            // The engine may have closed while this blocking call was in
            // flight. Never deliver a result computed against a closed engine:
            // the caller sees the fixed lifecycle error instead.
            check(!isClosed.get()) { "Tramai runtime is closed" }
            result
        }
    }

    private fun invokeSuspend(
        operation: OperationDefinition,
        args: Array<out Any?>,
        conversationId: String?,
    ): Any {
        // Kotlin suspend proxies receive the continuation as the last JVM argument.
        @Suppress("UNCHECKED_CAST")
        val continuation = args.lastOrNull() as? Continuation<Any?>
            ?: throw ConfigurationException("Suspend invocation for ${operation.method.name} is missing its continuation")

        val callArguments = args.dropLast(1)
        // Launch as a child of the CALLER's job (continuation.context, with the
        // caller's Job element retained) so parent cancellation propagates
        // synchronously into the in-flight invocation (validated by the
        // ToolSafeFailureContract / StructuredOutputFailureBoundary
        // parent-cancellation tests). The invocation RUNS on the engine's own
        // dispatcher (lifecycleScope), NOT the caller's: if it ran on the
        // caller's single-threaded dispatcher, close() joining it could
        // deadlock when that thread is blocked inside close(). Engine close()
        // owns the work: the launch+add is synchronized with close()'s cancel
        // snapshot, the closed flag is re-checked INSIDE the lock, and close()
        // cancels AND joins every tracked invocation. Exactly-once resume: the
        // block records the outcome BEFORE resuming the continuation, and
        // invokeOnCompletion resumes with a cancellation when the block never
        // ran (job cancelled pre-start by close()) — otherwise the caller's
        // suspension would freeze forever.
        val resumed = java.util.concurrent.atomic.AtomicReference<Result<Any?>?>(null)
        val launched = synchronized(activeInvocationJobs) {
            check(!isClosed.get()) { "Tramai runtime is closed" }
            val job = lifecycleScope.launch(
                continuation.context.minusKey(kotlin.coroutines.ContinuationInterceptor) +
                    engineThreadMarker.asContextElement(true),
            ) {
                var result = runCatching { execute(operation, callArguments, conversationId) }
                // Never deliver a success computed against a closed engine: the
                // engine may have closed while the invocation was in flight.
                // The caller sees the fixed lifecycle error instead (mirrors
                // the blocking path).
                if (isClosed.get() && result.isSuccess) {
                    result = Result.failure(IllegalStateException("Tramai runtime is closed"))
                }
                resumed.set(result)
                continuation.resumeWith(result)
            }
            activeInvocationJobs += job
            job
        }
        launched.invokeOnCompletion { cause ->
            activeInvocationJobs -= launched
            if (resumed.get() == null) {
                // Block never ran (e.g. cancelled before the dispatcher started
                // it): resume so the caller's suspension does not freeze.
                continuation.resumeWith(
                    Result.failure(
                        cause as? CancellationException ?: CancellationException("Engine closed", cause),
                    ),
                )
            }
        }
        return COROUTINE_SUSPENDED
    }

    private suspend fun execute(
        operation: OperationDefinition,
        arguments: List<Any?>,
        conversationId: String?,
    ): Any? {
        val tokenBudgetTracker = TokenBudgetTracker(tokenBudgetSettings)
        val workflowRunId = java.util.UUID.randomUUID().toString()
        val workflowDigest = WorkflowDigestHelper.compute(operation, serviceDefinition)
        val identity = EngineExecutionIdentity(
            workflowRunId = workflowRunId,
            correlationId = "", // Will be set in executeRaw/executeStructured
            workflowDigest = workflowDigest,
            policyVersion = policyHelper.getPolicyVersion(),
            actorId = PolicyEnforcementHelper.ACTOR_ANONYMOUS,
        )
        return when (operation.returnKind) {
            ReturnKind.STRING -> executeRaw(operation, arguments, tokenBudgetTracker, conversationId, identity)
            ReturnKind.UNIT -> {
                executeRaw(operation, arguments, tokenBudgetTracker, conversationId, identity)
                Unit
            }
            ReturnKind.STRUCTURED -> executeStructured(operation, arguments, tokenBudgetTracker, conversationId, identity)
            ReturnKind.STREAMING -> executeStreaming(operation, arguments, tokenBudgetTracker, conversationId)
        }
    }

    private fun executeStreaming(
        operation: OperationDefinition,
        arguments: List<Any?>,
        tokenBudgetTracker: TokenBudgetTracker,
        conversationId: String?,
    ): Flow<StreamChunk> {
        val securityContext = ExecutionSecurityContext.fromArguments(arguments.toTypedArray())
        val initialMessages = operation.initialMessages(arguments)
        val (history, effectiveMessages) = injectMemoryMessages(initialMessages, conversationId)
            ?: (emptyList<Message>() to initialMessages)

        return flow {
            check(!isClosed.get()) { "Tramai runtime is closed" }
            // The provider collection runs as a child of the engine's OWN
            // lifecycle job (lifecycleScope), NOT the collector's job: close()
            // cancels lifecycleJob, which cancels an in-flight collection
            // (including the provider stream's cleanup), and close() joins
            // lifecycleJob — so the collection has terminated before close()
            // returns. Chunks are bridged to the caller's emit through a
            // RENDEZVOUS channel: emit must stay in the collector's coroutine
            // (SafeCollector invariant), and a rendezvous keeps Flow
            // backpressure semantics — a slow caller blocks the provider
            // instead of letting it race ahead into unbounded buffering.
            val chunks = kotlinx.coroutines.channels.Channel<StreamChunk>(kotlinx.coroutines.channels.Channel.RENDEZVOUS)
            val collectFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val collectJob = lifecycleScope.launch {
                try {
                    val correlationId = java.util.UUID.randomUUID().toString()
                    enforceBeforeProviderResolution(operation, correlationId, securityContext)
                    val candidates = routingPlan.resolveCandidates(operation.operation)
                    var lastFailure: Throwable? = null
                    var lastCircuitOpen: CircuitBreakerOpenException? = null
                    val attemptCounter = AttemptCounter()

                    for ((routeIndex, route) in candidates.withIndex()) {
                        val circuitOpen = handleCircuitBreakerOpenRoute(
                            route = route,
                            nextRoute = candidates.getOrNull(routeIndex + 1),
                            correlationId = correlationId,
                            securityContext = securityContext,
                        )
                        if (circuitOpen != null) {
                            lastCircuitOpen = circuitOpen
                            continue
                        }

                        when (
                            val result = executeStreamingRoute(
                                StreamingExecutionRoute(
                                    operation = operation,
                                    route = route,
                                    routeIndex = routeIndex,
                                    attempt = attemptCounter.next(),
                                    tokenBudgetTracker = tokenBudgetTracker,
                                    memoryMessages = effectiveMessages,
                                    historySize = history.size,
                                    conversationId = conversationId,
                                    emitChunk = { chunks.send(it) },
                                ),
                                correlationId = correlationId,
                                securityContext = securityContext,
                                arguments = arguments,
                            )
                        ) {
                            is StreamingRouteResult.Completed -> {
                                if (chatMemory != null && conversationId != null) {
                                    val assistantMessage = Message(
                                        role = MessageRole.ASSISTANT,
                                        content = result.fullText,
                                    )
                                    val turnMessages = effectiveMessages
                                        .drop(history.size)
                                        .filter { it.role != MessageRole.SYSTEM }
                                    chatMemory.add(conversationId, turnMessages + assistantMessage)
                                }
                                return@launch
                            }
                            is StreamingRouteResult.StartupFailure -> {
                                enforceStreamingFallbackAfterFailure(
                                    error = result.error,
                                    route = route,
                                    nextRoute = candidates.getOrNull(routeIndex + 1),
                                    correlationId = correlationId,
                                    securityContext = securityContext,
                                )
                                lastFailure = result.error
                            }
                            is StreamingRouteResult.TerminalError -> {
                                chunks.send(result.errorChunk)
                                return@launch
                            }
                        }
                    }

                    chunks.send(noAvailableStreamingRouteChunk(operation, lastFailure, lastCircuitOpen))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // The engine closed (or the collector stopped): terminate
                    // the collection job normally; the invokeOnCompletion
                    // below closes the channel with the cancellation cause.
                    throw e
                } catch (failure: Throwable) {
                    // Surface the failure to the collector WITHOUT rethrowing
                    // it here: rethrowing would let an arbitrary (possibly
                    // sensitive, externally supplied) throwable reach the
                    // lifecycle scope's CoroutineExceptionHandler and the
                    // normal logger. The collector rethrows it after the
                    // channel drains.
                    collectFailure.set(failure)
                }
            }
            // Channel termination depends on JOB completion, not on the
            // coroutine body having started: if close() cancels lifecycleJob
            // after the flow's open check but before this launch's body runs,
            // the body's finally never executes — but invokeOnCompletion still
            // fires, closing the channel so the collector terminates instead
            // of hanging forever on receive.
            collectJob.invokeOnCompletion { cause -> chunks.close(cause) }
            try {
                for (chunk in chunks) {
                    check(!isClosed.get()) { "Tramai runtime is closed" }
                    emit(chunk)
                }
                // The collection job may have failed (e.g. provider does not
                // support streaming, or a route error aborted the loop): the
                // channel closes either way, so rethrow the job's failure here
                // instead of silently completing the flow.
                collectFailure.get()?.let { throw it }
            } finally {
                // If the caller stops collecting (or the engine closed), the
                // engine-owned collection job must not keep running.
                collectJob.cancel()
                collectJob.join()
            }
        }
    }

    private data class StreamingExecutionRoute(
        val operation: OperationDefinition,
        val route: ResolvedProviderRoute,
        val routeIndex: Int,
        val attempt: Int,
        val tokenBudgetTracker: TokenBudgetTracker,
        val memoryMessages: List<Message>?,
        val historySize: Int,
        val conversationId: String?,
        val emitChunk: suspend (StreamChunk) -> Unit,
    )

    private suspend fun executeStreamingRoute(
        request: StreamingExecutionRoute,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        arguments: List<Any?>,
    ): StreamingRouteResult {
        val route = request.route
        val observation = startStreamingObservation(route, request.operation, request.attempt, request.routeIndex)
        authorizeStreamingRoute(route, observation)
        enforceBeforeResponseReturn(route, correlationId, securityContext)
        toolExposureCoordinator.enforce(request.operation, correlationId, securityContext)
        enforceBeforeProviderInvocation(route.providerName, route.effectiveModelName, correlationId, securityContext)

        val streamCapable = route.provider as? StreamCapable
            ?: throw ProviderCapabilityException(route.providerName, "streaming")
        val modelRequest = request.operation.toRequest(arguments, modelName = route.effectiveModelName)
        val memoryInjectedRequest = request.memoryMessages?.let { modelRequest.copy(messages = it) } ?: modelRequest

        return collectStreamingRoute(
            StreamingRouteCall(
                streamCapable = streamCapable,
                request = memoryInjectedRequest,
                operation = request.operation,
                route = route,
                attempt = request.attempt,
                observation = observation,
                tokenBudgetTracker = request.tokenBudgetTracker,
                emitChunk = request.emitChunk,
            ),
        )
    }

    private suspend fun authorizeStreamingRoute(
        route: ResolvedProviderRoute,
        observation: OperationObservation,
    ) {
        try {
            modelRegistryEnforcer.authorize(route.providerName, route.effectiveModelName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelRegistryException) {
            observation.onCallCompleted(parseSuccess = null)
            throw e
        }
    }


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

    private suspend fun handleCircuitBreakerOpenRoute(
        route: ResolvedProviderRoute,
        nextRoute: ResolvedProviderRoute?,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ): CircuitBreakerOpenException? {
        val blockedUntil = circuitBreaker.beforeCall(route.providerName) ?: return null
        val circuitOpen = CircuitBreakerOpenException(route.providerName, blockedUntil)
        if (nextRoute != null) {
            try {
                enforceFallbackTransition(
                    correlationId = correlationId,
                    previousProviderId = route.providerName,
                    previousModelName = route.effectiveModelName,
                    nextProviderId = nextRoute.providerName,
                    reason = "circuit-breaker-open",
                    securityContext = securityContext,
                )
            } catch (policyError: PolicyViolationException) {
                policyError.addSuppressed(circuitOpen)
                throw policyError
            }
        }
        return circuitOpen
    }

    private suspend fun enforceStreamingFallbackAfterFailure(
        error: Throwable,
        route: ResolvedProviderRoute,
        nextRoute: ResolvedProviderRoute?,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) {
        if (nextRoute == null) return
        try {
            enforceFallbackTransition(
                correlationId = correlationId,
                previousProviderId = route.providerName,
                previousModelName = route.effectiveModelName,
                nextProviderId = nextRoute.providerName,
                reason = "streaming-startup-failure",
                securityContext = securityContext,
            )
        } catch (policyError: PolicyViolationException) {
            policyError.addSuppressed(error)
            throw policyError
        }
    }

    private fun noAvailableStreamingRouteChunk(
        operation: OperationDefinition,
        lastFailure: Throwable?,
        lastCircuitOpen: CircuitBreakerOpenException?,
    ): StreamChunk.Error = StreamChunk.Error(
        (lastFailure ?: lastCircuitOpen ?: ProviderException(
            message = "No available streaming provider route for model '${operation.operation.model}'",
            retryable = true,
        )) as TramaiException,
    )

    private suspend fun collectStreamingRoute(
        call: StreamingRouteCall,
    ): StreamingRouteResult {
        val streamCapable = call.streamCapable
        val request = call.request
        val operation = call.operation
        val route = call.route
        val attempt = call.attempt
        val observation = call.observation
        val tokenBudgetTracker = call.tokenBudgetTracker
        val emitChunk = call.emitChunk
        var emittedAnyTokens = false
        val callContext = streamingCallContext(operation, route.providerName, attempt)
        val interceptedRequest = request.copy(
            messages = operationInterceptor.interceptRequest(callContext, request.messages),
        )

        return try {
            collectStreamingRouteChunks(
                streamCapable = streamCapable,
                request = interceptedRequest,
                timeoutMillis = request.timeoutMillis ?: operation.operation.timeoutMillis,
                ctx = StreamingRouteContext(
                    route = route,
                    operation = operation,
                    tokenBudgetTracker = tokenBudgetTracker,
                    callContext = callContext,
                    observation = observation,
                    emitChunk = emitChunk,
                ),
                hasEmittedTokens = { emittedAnyTokens },
                onToken = { chunk ->
                    emittedAnyTokens = true
                    emitChunk(chunk)
                },
            )
            error("Streaming route completed without a terminal result")
        } catch (finished: StreamingRouteFinished) {
            finished.result
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            val timeout = TimeoutException(
                message = buildTimeoutMessage(
                    providerId = route.providerName,
                    operation = operation,
                    timeoutMillis = request.timeoutMillis ?: operation.operation.timeoutMillis,
                ),
                cause = error,
            )
            observation.onProviderFailure(timeout)
            handleFallbackResult(timeout, emittedAnyTokens, route.providerName, observation)
        } catch (error: CancellationException) {
            observation.completeCancellation(error)
            throw error
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            val normalized = normalizeStreamingError(error, route.providerName, operation)
            observation.onProviderFailure(normalized)
            handleFallbackResult(normalized, emittedAnyTokens, route.providerName, observation)
        }
    }

    private data class StreamingRouteCall(
        val streamCapable: StreamCapable,
        val request: ModelRequest,
        val operation: OperationDefinition,
        val route: ResolvedProviderRoute,
        val attempt: Int,
        val observation: OperationObservation,
        val tokenBudgetTracker: TokenBudgetTracker,
        val emitChunk: suspend (StreamChunk) -> Unit,
    )

    private fun streamingCallContext(
        operation: OperationDefinition,
        providerId: String,
        attempt: Int,
    ) = OperationCallContext(
        serviceInterface = serviceDefinition.serviceType.qualifiedName ?: serviceDefinition.serviceType.simpleName.orEmpty(),
        methodName = operation.method.name,
        providerId = providerId,
        requestedModel = operation.operation.model,
        attempt = attempt,
    )

    private fun startStreamingObservation(
        route: ResolvedProviderRoute,
        operation: OperationDefinition,
        attempt: Int,
        routeIndex: Int,
    ): OperationObservation = startObservation(
        providerId = route.providerName,
        operation = operation,
        attempt = attempt,
    ).also { observation ->
        observation.onEngineEvent(
            name = EVENT_ROUTE_SELECTED,
            attributes = routeSelectedAttributes(route, routeIndex),
        )
    }

    private data class StreamingRouteContext(
        val route: ResolvedProviderRoute,
        val operation: OperationDefinition,
        val tokenBudgetTracker: TokenBudgetTracker,
        val callContext: OperationCallContext,
        val observation: OperationObservation,
        val emitChunk: suspend (StreamChunk) -> Unit,
    )

    private data class StructuredAttemptContext(
        val operation: OperationDefinition,
        val arguments: List<Any?>,
        val schemaJson: String,
        val handler: StructuredOutputHandler,
        val messages: MutableList<Message>,
        val historySize: Int,
        val tokenBudgetTracker: TokenBudgetTracker,
        val conversationId: String?,
    )

    private suspend fun collectStreamingRouteChunks(
        streamCapable: StreamCapable,
        request: ModelRequest,
        timeoutMillis: Long,
        ctx: StreamingRouteContext,
        hasEmittedTokens: () -> Boolean,
        onToken: suspend (StreamChunk.Token) -> Unit,
    ) {
        withTimeout(timeoutMillis) {
            streamCapable.stream(request).collect { chunk ->
                handleStreamingChunk(
                    chunk = chunk,
                    ctx = ctx,
                    emittedAnyTokens = hasEmittedTokens(),
                    onToken = onToken,
                )
            }
            handleStreamingTerminationWithoutTerminalChunk(
                route = ctx.route,
                operation = ctx.operation,
                observation = ctx.observation,
                emittedAnyTokens = hasEmittedTokens(),
            )
        }
    }

    private suspend fun handleStreamingChunk(
        chunk: StreamChunk,
        ctx: StreamingRouteContext,
        emittedAnyTokens: Boolean,
        onToken: suspend (StreamChunk.Token) -> Unit,
    ) {
        when (chunk) {
            is StreamChunk.Token -> onToken(chunk)
            is StreamChunk.Complete -> handleStreamingComplete(
                chunk = chunk,
                route = ctx.route,
                tokenBudgetTracker = ctx.tokenBudgetTracker,
                callContext = ctx.callContext,
                observation = ctx.observation,
                emitChunk = ctx.emitChunk,
            )
            is StreamChunk.Error -> {
                ctx.observation.onProviderFailure(chunk.cause)
                finishStreamingRoute(
                    handleFallbackResult(
                        error = chunk.cause,
                        emittedAnyTokens = emittedAnyTokens,
                        providerName = ctx.route.providerName,
                        observation = ctx.observation,
                        terminalChunk = chunk,
                    ),
                )
            }
        }
    }

    private suspend fun handleStreamingComplete(
        chunk: StreamChunk.Complete,
        route: ResolvedProviderRoute,
        tokenBudgetTracker: TokenBudgetTracker,
        callContext: OperationCallContext,
        observation: OperationObservation,
        emitChunk: suspend (StreamChunk) -> Unit,
    ) {
        val response = ModelResponse(
            content = chunk.fullText,
            inputTokens = chunk.usage.inputTokens,
            outputTokens = chunk.usage.outputTokens,
            thinkingTokens = chunk.usage.thinkingTokens,
            modelUsed = route.effectiveModelName,
            finishReason = FinishReason.STOP,
        )

        val interceptedResponse = operationInterceptor.interceptResponse(callContext, response)
        observation.onProviderResponse(interceptedResponse)

        try {
            enforceTokenBudget(
                tracker = tokenBudgetTracker,
                response = interceptedResponse,
                observation = observation,
                providerId = route.providerName,
                modelName = route.effectiveModelName,
            )
        } catch (error: TokenBudgetExceededException) {
            observation.onCallCompleted(parseSuccess = null)
            throw StreamingRouteFinished(
                StreamingRouteResult.TerminalError(StreamChunk.Error(error)),
            )
        }
        observation.onCallCompleted(parseSuccess = null)
        circuitBreaker.onSuccess(route.providerName)
        emitChunk(
            if (interceptedResponse.content != chunk.fullText) {
                chunk.copy(fullText = interceptedResponse.content)
            } else {
                chunk
            }
        )
        throw StreamingRouteFinished(StreamingRouteResult.Completed(interceptedResponse.content))
    }

    private fun handleStreamingTerminationWithoutTerminalChunk(
        route: ResolvedProviderRoute,
        operation: OperationDefinition,
        observation: OperationObservation,
        emittedAnyTokens: Boolean,
    ): Nothing {
        val error = ProviderException(
            message = "Provider ${route.providerName} ended streaming without a terminal chunk while invoking ${serviceDefinition.serviceType.qualifiedName}.${operation.method.name}",
        )
        observation.onProviderFailure(error)
        finishStreamingRoute(
            handleFallbackResult(
                error = error,
                emittedAnyTokens = emittedAnyTokens,
                providerName = route.providerName,
                observation = observation,
            ),
        )
    }

    private fun normalizeStreamingError(
        error: Throwable,
        providerName: String,
        operation: OperationDefinition,
    ): TramaiException = when (error) {
        is TramaiException -> error
        else -> ProviderException(
            message = "Provider $providerName failed while streaming ${serviceDefinition.serviceType.qualifiedName}.${operation.method.name}",
            cause = error,
        )
    }

    private fun recordCircuitBreakerFailure(
        providerName: String,
        error: Throwable,
        observation: OperationObservation,
    ) {
        val opened = circuitBreaker.onFailure(providerName, error)
        if (opened) {
            observation.onEngineEvent(
                name = EVENT_CIRCUIT_OPENED,
                attributes = mapOf(ATTR_PROVIDER_ID to providerName),
            )
        }
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

    private fun recordStartupRetryEvent(
        providerName: String,
        failureType: String,
        observation: OperationObservation,
    ) {
        observation.onEngineEvent(
            name = EVENT_STARTUP_RETRY,
            attributes = mapOf(
                ATTR_PROVIDER_ID to providerName,
                ATTR_FAILURE_TYPE to failureType,
            ),
        )
    }

    private fun handleFallbackResult(
        error: TramaiException,
        emittedAnyTokens: Boolean,
        providerName: String,
        observation: OperationObservation,
        terminalChunk: StreamChunk.Error = StreamChunk.Error(error),
    ): StreamingRouteResult {
        val result = if (!emittedAnyTokens && shouldFallbackFrom(error)) {
            recordStartupRetryEvent(providerName, error::class.simpleName ?: "unknown", observation)
            StreamingRouteResult.StartupFailure(error)
        } else {
            StreamingRouteResult.TerminalError(terminalChunk)
        }
        recordCircuitBreakerFailure(providerName, error, observation)
        observation.onCallCompleted(parseSuccess = null)
        return result
    }

    private fun finishStreamingRoute(result: StreamingRouteResult): Nothing {
        throw StreamingRouteFinished(result)
    }

    private fun injectMemoryMessages(
        operation: OperationDefinition,
        arguments: List<Any?>,
        conversationId: String?,
    ): Pair<List<Message>, List<Message>>? = injectMemoryMessages(
        initialMessages = operation.initialMessages(arguments),
        conversationId = conversationId,
    )

    private fun injectMemoryMessages(
        initialMessages: List<Message>,
        conversationId: String?,
    ): Pair<List<Message>, List<Message>>? {
        if (chatMemory == null || conversationId == null) return null
        val history = chatMemory.get(conversationId)
        if (history.isEmpty()) return null
        val currentSystem = initialMessages.firstOrNull { it.role == MessageRole.SYSTEM }
        val deduped = if (currentSystem != null && history.any { it.role == MessageRole.SYSTEM }) {
            initialMessages.filter { it.role != MessageRole.SYSTEM }
        } else {
            initialMessages
        }
        return history to (history + deduped)
    }

    private suspend fun executeRaw(
        operation: OperationDefinition,
        arguments: List<Any?>,
        tokenBudgetTracker: TokenBudgetTracker,
        conversationId: String?,
        identity: EngineExecutionIdentity,
    ): String {
        val securityContext = ExecutionSecurityContext.fromArguments(arguments.toTypedArray())
        val correlationId = java.util.UUID.randomUUID().toString()
        val effectiveIdentity = identity.copy(correlationId = correlationId)
        val initialMessages = operation.initialMessages(arguments)
        val (history, effectiveMessages) = injectMemoryMessages(initialMessages, conversationId)
            ?: (emptyList<Message>() to initialMessages)
        val cacheKey = operation.takeIf { isSafeCacheEligible(it, conversationId) }?.buildCacheKey(
            digestSource = effectiveMessages,
            securityPartition = securityContext.toCacheSecurityPartition(),
            operationFingerprint = serviceDefinition.operations[operation.method]?.fingerprint,
        )

        cacheKey?.let { key ->
            operation.cachedValue(key, conversationId)?.let { cached ->
                try {
                    authorizeCachedResult(
                        cacheKey = key,
                        cached = cached,
                        securityContext = securityContext,
                        correlationId = correlationId,
                    )
                    return cached.value as String
                } catch (_: CachedModelProvenanceMismatchException) {
                    responseCache.invalidate(key)
                }
            }
        }

        val effectiveMutableMessages = effectiveMessages.toMutableList()

        val result = executeWithTools(
            ToolLoopContext(
                operation = operation,
                messages = effectiveMutableMessages,
                tokenBudgetTracker = tokenBudgetTracker,
                correlationId = correlationId,
                securityContext = securityContext,
                identity = effectiveIdentity,
                conversationId = conversationId,
                historySize = history.size,
            ),
        )

        // DLP is already applied inside ProviderAttemptExecutor — use the sanitized response directly

        // Enforce BEFORE_RESPONSE_RETURN
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                correlationId = correlationId,
            ).providerId(result.providerId)
                .modelName(result.modelName)
                .applySecurityContext(securityContext)
                .build()
        )

        // Memory: persist response if chatMemory is configured
        if (chatMemory != null && conversationId != null) {
            val assistantMessage = Message(
                role = MessageRole.ASSISTANT,
                content = result.response.content,
                toolCalls = result.response.toolCalls,
            )
            // Persist non-system messages from this turn (tool rounds + final assistant)
            val turnMessages = effectiveMutableMessages.drop(history.size).filter { it.role != MessageRole.SYSTEM }
            chatMemory.add(conversationId, turnMessages + assistantMessage)
        }

        result.observation.onCallCompleted(parseSuccess = null)
        return result.response.content.also {
            cacheKey?.let { key ->
                operation.cacheValue(
                    key = key,
                    value = it,
                    providerId = result.providerId,
                    modelName = result.modelName,
                    securityContext = securityContext,
                    conversationId = conversationId,
                    approvedModel = result.approvedModel,
                )
            }
        }
    }

    private suspend fun executeStructured(
        operation: OperationDefinition,
        arguments: List<Any?>,
        tokenBudgetTracker: TokenBudgetTracker,
        conversationId: String?,
        identity: EngineExecutionIdentity,
    ): Any {
        val securityContext = ExecutionSecurityContext.fromArguments(arguments.toTypedArray())
        val handler = structuredOutputHandler ?: throw ConfigurationException(
            "Structured return type ${operation.returnTypeDescription} requires a StructuredOutputHandler implementation from tramai-structured",
        )
        val contract = try {
            operation.structuredContract(handler)
        } catch (failure: Throwable) {
            failure.rethrowIfCancellation()
            rethrowContractFailure(operation, failure)
        }
        val correlationId = java.util.UUID.randomUUID().toString()
        val effectiveIdentity = identity.copy(correlationId = correlationId)
        val initialMessages = operation.initialMessages(arguments, contract.schemaJson)
        val (history, effectiveMessages) = injectMemoryMessages(initialMessages, conversationId)
            ?: (emptyList<Message>() to initialMessages)
        val cacheKey = operation.takeIf { isSafeCacheEligible(it, conversationId) }?.buildCacheKey(
            digestSource = effectiveMessages,
            securityPartition = securityContext.toCacheSecurityPartition(),
            operationFingerprint = serviceDefinition.operations[operation.method]?.fingerprint,
        )

        cacheKey?.let { key ->
            operation.cachedValue(key, conversationId)?.let { cached ->
                try {
                    authorizeCachedResult(
                        cacheKey = key,
                        cached = cached,
                        securityContext = securityContext,
                        correlationId = correlationId,
                    )
                    return cached.value
                } catch (_: CachedModelProvenanceMismatchException) {
                    responseCache.invalidate(key)
                }
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
                tokenBudgetTracker = tokenBudgetTracker,
                conversationId = conversationId,
                correlationId = correlationId,
                securityContext = securityContext,
                identity = effectiveIdentity,
            ),
        )
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
        val result = executeWithTools(
            ToolLoopContext(
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

                persistStructuredSuccess(
                    result = result,
                    messages = messages,
                    historySize = historySize,
                    conversationId = conversationId,
                    messagesBeforeCall = messagesBeforeCall,
                )
                cacheKey?.let { key ->
                    operation.cacheValue(
                        key = key,
                        value = analysis.value,
                        providerId = result.providerId,
                        modelName = result.modelName,
                        securityContext = securityContext,
                        conversationId = conversationId,
                        approvedModel = result.approvedModel,
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

    private fun persistStructuredSuccess(
        result: ProviderCallResult,
        messages: MutableList<Message>,
        historySize: Int,
        conversationId: String?,
        messagesBeforeCall: Int,
    ) {
        if (chatMemory == null || conversationId == null) return
        val content = result.response.content
        val assistantMessage = Message(
            role = MessageRole.ASSISTANT,
            content = content,
            toolCalls = result.response.toolCalls,
        )
        val userPrompt = messages.subList(historySize, messagesBeforeCall)
            .filter { it.role != MessageRole.SYSTEM }
        val toolMessages = messages.drop(messagesBeforeCall)
        chatMemory.add(conversationId, userPrompt + toolMessages + assistantMessage)
    }

    /**
     * Persists the assistant response and this turn's non-system messages to chat memory.
     * Shared by [finalizeResumedOperation] for STRING/UNIT paths and by
     * [resumeStructuredResult] for the STRUCTURED path.
     */
    private fun persistMemory(
        loopResult: ProviderCallResult,
        messages: List<Message>,
        historySize: Int,
        conversationId: String?,
    ) {
        if (chatMemory == null || conversationId == null) return
        val assistantMessage = Message(
            role = MessageRole.ASSISTANT,
            content = loopResult.response.content,
            toolCalls = loopResult.response.toolCalls,
        )
        val turnMessages = messages.drop(historySize).filter { it.role != MessageRole.SYSTEM }
        chatMemory.add(conversationId, turnMessages + assistantMessage)
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
                serviceName = structuredDiagnosticServiceName(),
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
                serviceName = structuredDiagnosticServiceName(),
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
                serviceName = structuredDiagnosticServiceName(),
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

    /** Service identity for structured diagnostic events (qualified name when available). */
    private fun structuredDiagnosticServiceName(): String =
        serviceDefinition.serviceType.qualifiedName
            ?: serviceDefinition.serviceType.simpleName
            ?: "<unknown>"

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

    private suspend fun executeWithTools(
        context: ToolLoopContext,
    ): ProviderCallResult {
        val operation = context.operation
        val messages = context.messages
        val tokenBudgetTracker = context.tokenBudgetTracker
        val correlationId = context.correlationId
        val securityContext = context.securityContext
        val maxToolLoops = 5 // Guard against infinite tool loops
        val attemptCounter = AttemptCounter()
        repeat(maxToolLoops) {
            val result = providerExecutionCoordinator.execute(
                ProviderExecutionRequest(
                    operation = operation,
                    messages = messages,
                    attemptCounter = attemptCounter,
                    correlationId = correlationId,
                    securityContext = securityContext,
                    beforeRoute = { toolExposureCoordinator.enforce(operation, correlationId, securityContext) },
                ),
            )
            try {
                enforceTokenBudget(
                    tracker = tokenBudgetTracker,
                    response = result.response,
                    observation = result.observation,
                    providerId = result.providerId,
                    modelName = result.modelName,
                )
            } catch (error: TokenBudgetExceededException) {
                result.observation.onCallCompleted(parseSuccess = null)
                throw error
            }

            val toolCalls = result.response.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                return result
            }

            // Normalize unregistered tool calls: replace unknown names with safe placeholder
            val normalizedToolCalls = toolCalls.map { toolCall ->
                if (toolRegistry.resolve(toolCall.name) == null) {
                    toolCall.copy(name = UNREGISTERED_TOOL_NAME, argumentsJson = "{}")
                } else {
                    toolCall
                }
            }

            // Append assistant message with normalized tool calls
            messages += Message(
                role = MessageRole.ASSISTANT,
                content = result.response.content,
                toolCalls = normalizedToolCalls,
            )

            // Tool execution must complete before the observation is finalised,
            // so that cancellation during tool execution calls onCallCancelled
            // instead of onCallCompleted.
            // The try covers only tool reinjection, not onCallCompleted:
            // if the observer throws after successful tool execution, that
            // failure is suppressed on the process error rather than causing
            // a duplicate onCallCompleted call or invalidating the side effect.
            try {
                toolReinjectionCoordinator.process(
                    ToolCallBatchRequest(
                        operation = operation,
                        messages = messages,
                        toolCalls = normalizedToolCalls,
                        correlationId = correlationId,
                        securityContext = securityContext,
                        identity = context.identity,
                        tokenBudgetTracker = tokenBudgetTracker,
                        conversationId = context.conversationId,
                        historySize = context.historySize,
                        resumingApproval = context.resumingApproval,
                        parentApprovalId = context.parentApprovalId,
                    ),
                )
            } catch (cancellation: CancellationException) {
                result.observation.completeCancellation(cancellation)
                throw cancellation
            } catch (error: Throwable) {
                error.rethrowIfCancellation()

                // Suppress observer failure on the process error so that
                // a failing onCallCompleted cannot duplicate the callback,
                // and this non-suspend helper keeps the cancellation scanner
                // satisfied.
                result.observation.completeAfterToolProcessing(primaryError = error)

                throw error
            }

            result.observation.completeAfterToolProcessing()
        }
        error("Exceeded maximum tool call loops ($maxToolLoops)")
    }

    private data class ToolLoopContext(
        val operation: OperationDefinition,
        val messages: MutableList<Message>,
        val tokenBudgetTracker: TokenBudgetTracker,
        val correlationId: String,
        val securityContext: ExecutionSecurityContext,
        val identity: EngineExecutionIdentity,
        val conversationId: String? = null,
        val historySize: Int = 0,
        val resumingApproval: Boolean = false,
        val parentApprovalId: String? = null,
    )

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

    private fun buildTimeoutMessage(
        providerId: String,
        operation: OperationDefinition,
        timeoutMillis: Long,
    ): String = "Provider $providerId timed out after ${timeoutMillis}ms while invoking ${serviceDefinition.serviceType.qualifiedName}.${operation.method.name}"

    private fun startObservation(
        providerId: String,
        operation: OperationDefinition,
        attempt: Int,
    ): OperationObservation = operationObserver.onCallStarted(
        OperationCallContext(
            serviceInterface = serviceDefinition.serviceType.qualifiedName ?: serviceDefinition.serviceType.simpleName.orEmpty(),
            methodName = operation.method.name,
            providerId = providerId,
            requestedModel = operation.operation.model,
            attempt = attempt,
        ),
    )

    private fun shouldFallbackFrom(error: Throwable): Boolean = when (error) {
        is CircuitBreakerOpenException -> true
        is TimeoutException -> true
        is ProviderException -> error.retryable
        else -> false
    }


    private fun enforceTokenBudget(
        tracker: TokenBudgetTracker,
        response: ModelResponse,
        observation: OperationObservation,
        providerId: String,
        modelName: String,
    ) {
        when (val result = tracker.observe(response)) {
            is TokenBudgetCheckResult.Ok -> Unit
            is TokenBudgetCheckResult.UsageUnavailable -> observation.onEngineEvent(
                name = "tramai.token_budget.usage_unavailable",
                attributes = mapOf(
                    ATTR_PROVIDER_ID to providerId,
                    ATTR_EFFECTIVE_MODEL to modelName,
                ),
            )
            is TokenBudgetCheckResult.SoftLimitExceeded -> observation.onEngineEvent(
                name = "tramai.token_budget.soft_limit_exceeded",
                attributes = mapOf(
                    ATTR_PROVIDER_ID to providerId,
                    ATTR_EFFECTIVE_MODEL to modelName,
                    ATTR_LIMIT_TOKENS to result.limitTokens,
                    ATTR_OBSERVED_TOKENS to result.observedTokens,
                    ATTR_SCOPE to "operation",
                ),
            )
            is TokenBudgetCheckResult.HardLimitExceeded -> {
                observation.onEngineEvent(
                    name = "tramai.token_budget.hard_limit_exceeded",
                    attributes = mapOf(
                        ATTR_PROVIDER_ID to providerId,
                        ATTR_EFFECTIVE_MODEL to modelName,
                        ATTR_LIMIT_TOKENS to result.limitTokens,
                        ATTR_OBSERVED_TOKENS to result.observedTokens,
                        ATTR_SCOPE to result.scope,
                    ),
                )
                throw TokenBudgetExceededException(
                    scope = result.scope,
                    limitTokens = result.limitTokens,
                    observedTokens = result.observedTokens,
                    providerId = providerId,
                    modelName = modelName,
                )
            }
        }
    }

    private fun routeSelectedAttributes(
        route: ResolvedProviderRoute,
        routeIndex: Int,
    ): Map<String, Any?> = mapOf(
        ATTR_PROVIDER_ID to route.providerName,
        ATTR_EFFECTIVE_MODEL to route.effectiveModelName,
        ATTR_ROUTE_INDEX to routeIndex,
        ATTR_IS_FALLBACK to (routeIndex > 0),
    )

    private fun restoredTokenBudgetTracker(metadata: SuspendedInvocationMetadata): TokenBudgetTracker =
        TokenBudgetTracker(tokenBudgetSettings).also { tracker ->
            metadata.tokenBudgetSnapshot?.let { tracker.restore(it) }
        }

    override suspend fun execute(request: ClaimedResumeExecutionRequest): Any? {
        val metadata = request.metadata
        val registered = request.registered
        val tokenBudgetTracker = restoredTokenBudgetTracker(metadata)
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
     * Finalizes a resumed operation for all return kinds that don't need
     * structured parsing. Enforces BEFORE_RESPONSE_RETURN, persists conversation
     * memory, completes the observation, and returns the appropriate result.
     *
     * For [ReturnKind.STRUCTURED] callers must handle parsing separately via
     * [resumeStructuredResult] — this method enforces only the shared parts
     * (BEFORE_RESPONSE_RETURN, memory, observation) and then throws so the
     * caller knows to use the structured path instead.
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
                persistMemory(loopResult, messages, historySize, conversationId)
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
                persistMemory(loopResult, messages, historySize, conversationId)
                loopResult.observation.onCallCompleted(parseSuccess = null)
                loopResult.response.content // consume it
                return Unit
            }
            ReturnKind.STRUCTURED -> {
                // Structured: delegate to resumeStructuredResult which handles
                // BEFORE_RESPONSE_RETURN (on success only), memory, observation, and parse
                return resumeStructuredResult(
                    operation = operation,
                    loopResult = loopResult,
                    messages = messages,
                    correlationId = correlationId,
                    securityContext = securityContext,
                    conversationId = conversationId,
                    historySize = historySize,
                )
            }
            ReturnKind.STREAMING -> throw ConfigurationException("Streaming approval resume not supported")
        }
    }

    /**
     * Parses the structured (typed) result from a resumed provider loop.
     *
     * **Single-attempt limitation (v1):** Unlike the normal flow, which retries structured
     * parsing when the provider responds with content that cannot be parsed — feeding
     * the error back to the provider for a corrected attempt — this resume path makes
     * exactly one parse attempt. If parsing fails, the exception is thrown immediately
     * without a retry cycle. This is a deliberate v1 limitation: the resume path is a
     * linear re-entrant flow, not a multi-turn conversation, so retry-with-feedback
     * semantics are not available here.
     */
    private suspend fun resumeStructuredResult(
        operation: OperationDefinition,
        loopResult: ProviderCallResult,
        messages: List<Message>,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        conversationId: String?,
        historySize: Int,
    ): Any {
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
                        correlationId = correlationId,
                    ).providerId(loopResult.providerId)
                        .modelName(loopResult.modelName)
                        .applySecurityContext(securityContext)
                        .build()
                )
                persistMemory(loopResult, messages, historySize, conversationId)
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
                        serviceName = structuredDiagnosticServiceName(),
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

    /**
     * Continues the provider loop after a suspended tool has been executed on resume.
     *
     * 1. Enforces BEFORE_TOOL_RESULT_REINJECTION policy
     * 2. Formats and sanitizes the tool result message
     * 3. Appends the tool message to the messages list
     * 4. Processes any remaining unprocessed tool calls from the same batch
     * 5. Continues the provider loop via [executeWithTools]
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

        return executeWithTools(
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

    private fun handleObjectMethod(
        proxy: Any,
        method: Method,
        args: Array<out Any?>,
    ): Any? = when (method.name) {
        "toString" -> "TramaiProxy(${serviceDefinition.serviceType.qualifiedName})"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args.firstOrNull()
        else -> throw UnsupportedOperationException("Unsupported Object method: ${method.name}")
    }

    private fun resolveConversationId(method: Method, args: Array<out Any?>): String {
        val parameters = method.parameters
        for (i in parameters.indices) {
            if (parameters[i].isAnnotationPresent(ConversationId::class.java)) {
                val argument = args[i]
                    ?: throw IllegalArgumentException("@ConversationId parameter '${parameters[i].name}' at index $i is null")
                return argument.toString()
            }
        }
        return conversationIdProvider.resolve()
    }

    private suspend fun authorizeCachedResult(
        cacheKey: OperationCacheKey,
        cached: CachedOperationResult,
        securityContext: ExecutionSecurityContext,
        correlationId: String,
    ) {
        validateCachedEntry(cacheKey, cached)
        authorizeCachedModelProvenance(cached.provenance)
        enforceCacheReusePolicies(cacheKey, cached, securityContext, correlationId)
    }

    /**
     * Re-authorizes cached model provenance against the current registry entry.
     */
    private suspend fun authorizeCachedModelProvenance(provenance: CachedResponseProvenance) {
        if (!modelRegistrySettings.enabled) {
            return
        }
        val current = modelRegistryEnforcer.authorize(provenance.providerId, provenance.modelName)
            ?: error("ModelRegistryEnforcer.authorize returned null when registry is enabled")
        if (current.registryEntryId != provenance.modelRegistryEntryId ||
            current.revision != provenance.modelRevision ||
            current.artifactDigest != provenance.modelArtifactDigest
        ) {
            throw CachedModelProvenanceMismatchException()
        }
    }

    /**
     * Applies the same policy gates that a fresh provider call would cross on a cache hit.
     */
    private suspend fun enforceCacheReusePolicies(
        cacheKey: OperationCacheKey,
        cached: CachedOperationResult,
        securityContext: ExecutionSecurityContext,
        correlationId: String,
    ) {
        enforceCacheReuseProviderResolution(cacheKey, securityContext, correlationId)
        enforceCacheReuseProviderInvocation(cached.provenance, securityContext, correlationId)
        enforceCacheReuseResponseReturn(cached.provenance, securityContext, correlationId)
    }

    /**
     * Enforces provider-resolution policy for a reused cached response.
     */
    private suspend fun enforceCacheReuseProviderResolution(
        cacheKey: OperationCacheKey,
        securityContext: ExecutionSecurityContext,
        correlationId: String,
    ) {
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_RESOLUTION,
                correlationId = correlationId,
            ).modelName(cacheKey.requestedModel)
                .applySecurityContext(securityContext)
                .attribute("cacheReuse", "true")
                .build()
        )
    }

    /**
     * Enforces provider-invocation policy for a reused cached response.
     */
    private suspend fun enforceCacheReuseProviderInvocation(
        provenance: CachedResponseProvenance,
        securityContext: ExecutionSecurityContext,
        correlationId: String,
    ) {
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                correlationId = correlationId,
            ).providerId(provenance.providerId)
                .modelName(provenance.modelName)
                .applySecurityContext(securityContext)
                .attribute("cacheReuse", "true")
                .build()
        )
    }

    /**
     * Enforces response-return policy for a reused cached response.
     */
    private suspend fun enforceCacheReuseResponseReturn(
        provenance: CachedResponseProvenance,
        securityContext: ExecutionSecurityContext,
        correlationId: String,
    ) {
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                correlationId = correlationId,
            ).providerId(provenance.providerId)
                .modelName(provenance.modelName)
                .applySecurityContext(securityContext)
                .attribute("cacheReuse", "true")
                .build()
        )
    }

    private fun validateCachedEntry(
        key: OperationCacheKey,
        cached: CachedOperationResult,
    ) {
        val provenance = cached.provenance
        check(provenance.hasProviderEnvelope()) { "Cached entry envelope has blank provider provenance" }
        check(provenance.matchesSecurityPartition(key.securityPartition)) {
            cachedPartitionMismatchMessage(key.securityPartition, provenance)
        }
    }

    /**
     * Checks that cached provenance carries the provider identity needed for reuse policy gates.
     */
    private fun CachedResponseProvenance.hasProviderEnvelope(): Boolean =
        providerId.isNotBlank() && modelName.isNotBlank()

    /**
     * Checks that cached data cannot cross security classification partitions.
     */
    private fun CachedResponseProvenance.matchesSecurityPartition(partition: CacheSecurityPartition): Boolean =
        dataClassification == partition.dataClassification &&
            classificationSource == partition.classificationSource

    /**
     * Builds the explicit cache partition mismatch diagnostic.
     */
    private fun cachedPartitionMismatchMessage(
        partition: CacheSecurityPartition,
        provenance: CachedResponseProvenance,
    ): String =
        "Cached entry envelope mismatch: key partition " +
            "$partition != cached provenance partition " +
            "(${provenance.dataClassification}, ${provenance.classificationSource})"

    private fun OperationDefinition.cachedValue(
        key: OperationCacheKey,
        conversationId: String?,
    ): CachedOperationResult? = if (isSafeCacheEligible(this, conversationId)) {
        responseCache.get(key)
    } else {
        null
    }

    private fun OperationDefinition.cacheValue(
        key: OperationCacheKey,
        value: Any,
        providerId: String,
        modelName: String,
        securityContext: ExecutionSecurityContext,
        conversationId: String?,
        approvedModel: RegisteredModel?,
    ) {
        if (!isSafeCacheEligible(this, conversationId)) {
            return
        }
        responseCache.put(
            key = key,
            value = CachedOperationResult(
                value = value,
                provenance = CachedResponseProvenance(
                    providerId = providerId,
                    modelName = modelName,
                    dataClassification = securityContext.dataClassification,
                    classificationSource = securityContext.classificationSource,
                    modelRegistryEntryId = approvedModel?.registryEntryId,
                    modelRevision = approvedModel?.revision,
                    modelArtifactDigest = approvedModel?.artifactDigest,
                ),
            ),
            ttlMillis = operation.cacheTtlMillis,
        )
    }

    /**
     * Cache eligibility including the conversation-memory and custom-interceptor
     * gates. Both gates are engine-scoped, not operation-scoped, so they live
     * on the handler.
     *
     * - **No chat memory** in scope: a cache hit would skip the
     *   `chatMemory.add(...)` that a fresh execution performs.
     * - **No custom interceptor**: a cache hit would skip
     *   `operationInterceptor.interceptRequest(...)` and
     *   `operationInterceptor.interceptResponse(...)`, allowing stale
     *   redacted/audited responses to bypass current rules.
     *
     * Interceptor-aware caching is deferred to a follow-up that introduces a
     * dedicated cache-aware interceptor SPI.
     */
    private fun isSafeCacheEligible(
        operation: OperationDefinition,
        conversationId: String?,
    ): Boolean =
        operation.isOperationCacheEligible() &&
            conversationId == null &&
            operationInterceptor === NoOpOperationInterceptor &&
            dlpInterceptor === NoOpDlpInterceptor
}

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
     * The interceptor-aware portion of the check lives on the invocation handler
     * because the interceptor is engine-scoped, not operation-scoped. Use the
     * handler's [isSafeCacheEligible] when evaluating an actual cache read/write.
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

private typealias ProviderCallResult = dev.tramai.engine.provider.ProviderCallResult

private sealed class StreamingRouteResult {
    data class Completed(
        val fullText: String,
    ) : StreamingRouteResult()

    data class StartupFailure(
        val error: TramaiException,
    ) : StreamingRouteResult()

    data class TerminalError(
        val errorChunk: StreamChunk.Error,
    ) : StreamingRouteResult()
}

private class StreamingRouteFinished(
    val result: StreamingRouteResult,
) : RuntimeException(null, null, false, false)

private typealias AttemptCounter = dev.tramai.engine.provider.AttemptCounter

internal open class ProviderCircuitBreaker(
    private val settings: CircuitBreakerSettings,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val states = mutableMapOf<String, ProviderCircuitState>()

    @Synchronized
    fun beforeCall(providerId: String): Long? {
        if (!settings.enabled) {
            return null
        }

        val state = states[providerId] ?: return null
        val now = clockMillis()
        val openUntil = state.openUntilMillis ?: return null
        if (now < openUntil) {
            return openUntil
        }

        state.openUntilMillis = null
        state.consecutiveFailures = 0
        return null
    }

    @Synchronized
    fun openUntilMillis(providerId: String): Long? {
        if (!settings.enabled) {
            return null
        }

        val state = states[providerId] ?: return null
        val openUntil = state.openUntilMillis ?: return null
        return if (clockMillis() < openUntil) openUntil else null
    }

    @Synchronized
    fun onSuccess(providerId: String) {
        if (!settings.enabled) {
            return
        }

        states.remove(providerId)
    }

    @Synchronized
    open fun onFailure(
        providerId: String,
        error: Throwable,
    ): Boolean {
        if (!settings.enabled || !isCircuitBreakingFailure(error)) {
            return false
        }

        val state = states.getOrPut(providerId) { ProviderCircuitState() }
        state.consecutiveFailures += 1
        if (state.consecutiveFailures >= settings.failureThreshold) {
            state.consecutiveFailures = 0
            state.openUntilMillis = clockMillis() + settings.openDurationMillis
            return true
        }
        return false
    }

    private fun isCircuitBreakingFailure(error: Throwable): Boolean = when (error) {
        is TimeoutException -> true
        is ProviderException -> error.retryable
        else -> false
    }
}

private data class ProviderCircuitState(
    var consecutiveFailures: Int = 0,
    var openUntilMillis: Long? = null,
)


internal class TokenBudgetTracker(
    private val settings: TokenBudgetSettings,
) {
    private var totalInputTokensObserved: Long = 0
    private var totalOutputTokensObserved: Long = 0
    private var totalInputCostObserved: Double = 0.0
    private var totalOutputCostObserved: Double = 0.0
    private var softLimitReported: Boolean = false

    /**
     * Capture a snapshot of the current budget state for suspension.
     */
    fun snapshot(): TokenBudgetSnapshot = TokenBudgetSnapshot(
        totalInputTokens = totalInputTokensObserved,
        totalOutputTokens = totalOutputTokensObserved,
        totalInputCost = totalInputCostObserved,
        totalOutputCost = totalOutputCostObserved,
        warnIfExceeded = !softLimitReported,
    )

    /**
     * Restore budget state from a snapshot taken at suspension time.
     */
    fun restore(snapshot: TokenBudgetSnapshot) {
        totalInputTokensObserved = snapshot.totalInputTokens
        totalOutputTokensObserved = snapshot.totalOutputTokens
        totalInputCostObserved = snapshot.totalInputCost
        totalOutputCostObserved = snapshot.totalOutputCost
        softLimitReported = !snapshot.warnIfExceeded
    }

    fun observe(response: ModelResponse): TokenBudgetCheckResult {
        if (!isEnabled()) {
            return TokenBudgetCheckResult.Ok
        }

        val attemptInputTokens = response.inputTokens?.toLong() ?: return TokenBudgetCheckResult.UsageUnavailable
        val attemptOutputTokens = response.outputTokens?.toLong() ?: return TokenBudgetCheckResult.UsageUnavailable
        val attemptTokens = attemptInputTokens + attemptOutputTokens
        totalInputTokensObserved += attemptInputTokens
        totalOutputTokensObserved += attemptOutputTokens

        settings.hardMaxTokensPerAttempt?.let { limit ->
            if (attemptTokens > limit) {
                return TokenBudgetCheckResult.HardLimitExceeded(
                    scope = "attempt",
                    limitTokens = limit,
                    observedTokens = attemptTokens,
                )
            }
        }

        settings.hardMaxTokensPerOperation?.let { limit ->
            val totalTokensObserved = totalInputTokensObserved + totalOutputTokensObserved
            if (totalTokensObserved > limit) {
                return TokenBudgetCheckResult.HardLimitExceeded(
                    scope = "operation",
                    limitTokens = limit,
                    observedTokens = totalTokensObserved,
                )
            }
        }

        settings.softMaxTokensPerOperation?.let { limit ->
            val totalTokensObserved = totalInputTokensObserved + totalOutputTokensObserved
            if (!softLimitReported && totalTokensObserved > limit) {
                softLimitReported = true
                return TokenBudgetCheckResult.SoftLimitExceeded(
                    limitTokens = limit,
                    observedTokens = totalTokensObserved,
                )
            }
        }

        return TokenBudgetCheckResult.Ok
    }

    private fun isEnabled(): Boolean =
        settings.hardMaxTokensPerAttempt != null ||
            settings.hardMaxTokensPerOperation != null ||
            settings.softMaxTokensPerOperation != null
}

internal sealed class TokenBudgetCheckResult {
    data object Ok : TokenBudgetCheckResult()

    data object UsageUnavailable : TokenBudgetCheckResult()

    data class SoftLimitExceeded(
        val limitTokens: Long,
        val observedTokens: Long,
    ) : TokenBudgetCheckResult()

    data class HardLimitExceeded(
        val scope: String,
        val limitTokens: Long,
        val observedTokens: Long,
    ) : TokenBudgetCheckResult()
}

enum class ReturnKind {
    STRING,
    UNIT,
    STRUCTURED,
    STREAMING,
}

private fun PolicyContextBuilder.applySecurityContext(
    securityContext: ExecutionSecurityContext,
): PolicyContextBuilder = dataClassification(securityContext.dataClassification)
    .classificationSource(securityContext.classificationSource)

private fun ExecutionSecurityContext.toCacheSecurityPartition() = CacheSecurityPartition(
    dataClassification = dataClassification,
    classificationSource = classificationSource,
)

internal fun buildRequestDigest(messages: List<Message>): String = sha256Hex(CanonicalMessageEncoder.encode(messages))

internal fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}


private const val ATTR_PROVIDER_ID = "provider_id"
private const val ATTR_EFFECTIVE_MODEL = "effective_model"
private const val ATTR_ROUTE_INDEX = "route_index"
private const val ATTR_IS_FALLBACK = "is_fallback"
private const val ATTR_RETRY_INDEX = "retry_index"
private const val ATTR_DELAY_MILLIS = "delay_millis"
private const val ATTR_DELAY_SOURCE = "delay_source"
private const val ATTR_LIMIT_TOKENS = "limit_tokens"
private const val ATTR_OBSERVED_TOKENS = "observed_tokens"
private const val ATTR_SCOPE = "scope"
private const val ATTR_FAILURE_TYPE = "failure_type"
private const val EVENT_CIRCUIT_OPENED = "tramai.circuit.opened"
private const val EVENT_STARTUP_RETRY = "tramai.streaming.startup_retry"
private const val EVENT_ROUTE_SELECTED = "tramai.route.selected"

/** @see TramaiEngine */

/** @see TramaiEngine */
