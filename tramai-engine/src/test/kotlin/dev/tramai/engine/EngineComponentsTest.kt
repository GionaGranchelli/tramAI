package dev.tramai.engine

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.ConversationIdProvider
import dev.tramai.core.memory.UuidConversationIdProvider
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.NoOpModelRegistry
import dev.tramai.core.observation.NoOpOperationInterceptor
import dev.tramai.core.observation.NoOpOperationObserver
import dev.tramai.core.observation.NoOpToolFailureDiagnosticObserver
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.observation.ToolFailureDiagnosticObserver
import dev.tramai.core.policy.NoOpPolicyDecisionAuditEmitter
import dev.tramai.core.policy.PolicyDecisionAuditEmitter
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedactionAuditEmitter
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.core.security.PromptSanitizer
import dev.tramai.core.structured.StructuredOutputHandler
import dev.tramai.engine.components.ApprovalCapability
import dev.tramai.engine.components.EngineComponentFactory
import dev.tramai.engine.components.EngineComponents
import java.lang.reflect.Proxy
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

class EngineComponentsTest {
    @Test
    fun `approval disabled is explicit`() {
        assertIs<ApprovalCapability.Disabled>(EngineComponentFactory.approvalCapability(null, null, null))
    }

    @Test
    fun `complete approval capability is accepted`() {
        val store = collaborator<ApprovalContinuationStore>()
        val digester = collaborator<ToolArgumentsDigester>()
        val coordinator = collaborator<ApprovalGateCoordinator>()

        val capability = assertIs<ApprovalCapability.Enabled>(
            EngineComponentFactory.approvalCapability(store, digester, coordinator),
        )

        assertSame(store, capability.continuationStore)
        assertSame(digester, capability.argumentsDigester)
        assertSame(coordinator, capability.gateCoordinator)
    }

    @Test
    fun `every partial approval combination fails at the composition boundary`() {
        val store = collaborator<ApprovalContinuationStore>()
        val digester = collaborator<ToolArgumentsDigester>()
        val coordinator = collaborator<ApprovalGateCoordinator>()
        val partials = listOf(
            Triple(store, null, null), Triple(null, digester, null), Triple(null, null, coordinator),
            Triple(store, digester, null), Triple(store, null, coordinator), Triple(null, digester, coordinator),
        )

        partials.forEach { (continuationStore, argumentsDigester, gateCoordinator) ->
            val failure = runCatching {
                EngineComponentFactory.approvalCapability(continuationStore, argumentsDigester, gateCoordinator)
            }.exceptionOrNull()
            assertIs<IllegalArgumentException>(failure)
            assertEquals(
                "Approval suspension requires continuation store, arguments digester, and gate coordinator",
                failure.message,
            )
        }
    }

    @Test
    fun `creates a complete default component snapshot`() {
        val registry = ProviderRegistry.singleProvider(registryTestProvider())
        val components = EngineComponentFactory.create(
            providerRegistry = registry,
            structuredOutputHandler = null,
            toolRegistry = ToolRegistry(),
            operationObserver = NoOpOperationObserver,
            operationInterceptor = NoOpOperationInterceptor,
            responseCache = NoOpOperationResponseCache,
            modelRegistry = NoOpModelRegistry,
            modelRegistrySettings = ModelRegistrySettings(),
            circuitBreakerSettings = CircuitBreakerSettings(),
            retryPolicySettings = RetryPolicySettings(),
            tokenBudgetSettings = TokenBudgetSettings(),
            promptSanitizer = null,
            chatMemory = null,
            conversationIdProvider = UuidConversationIdProvider(),
            job = SupervisorJob(),
            scope = CoroutineScope(Dispatchers.Default),
            policyEngine = null,
            dlpInterceptor = NoOpDlpInterceptor,
            dlpRedactionAuditEmitter = NoOpDlpRedactionAuditEmitter,
            toolResultFilteringSettings = ToolResultFilteringSettings(),
            engineEventObserver = NoOpEngineEventObserver,
            toolFailureDiagnosticObserver = NoOpToolFailureDiagnosticObserver,
            policyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter,
            suspendedInvocationStore = InMemorySuspendedInvocationStore(),
            approvalContinuationStore = null,
            toolArgumentsDigester = null,
            approvalGateCoordinator = null,
            approvalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
            clock = Clock.systemUTC(),
        )

        assertSame(registry, components.providers.providerRegistry)
        assertIs<ApprovalCapability.Disabled>(components.approvals.capability)
        assertTrue(components.security.isLegacyFallback)
        assertSame(LegacyPermissivePolicyEngine, components.security.resolvedPolicyEngine)
        assertSame(NoOpOperationObserver, components.observation.operationObserver)
        assertSame(NoOpOperationResponseCache, components.persistence.responseCache)
    }

    @Test
    fun `policy resolution happens once at construction`() {
        val customPolicy = collaborator<PolicyEngine>()
        val components = createComponents(policyEngine = customPolicy)

        assertFalse(components.security.isLegacyFallback)
        assertSame(customPolicy, components.security.resolvedPolicyEngine)
    }

    @Test
    fun `component snapshot holds final references to the supplied collaborators`() {
        val store = collaborator<ApprovalContinuationStore>()
        val digester = collaborator<ToolArgumentsDigester>()
        val coordinator = collaborator<ApprovalGateCoordinator>()
        val suspended = InMemorySuspendedInvocationStore()

        val components = createComponents(
            suspendedInvocationStore = suspended,
            approvalContinuationStore = store,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
        )

        val enabled = assertIs<ApprovalCapability.Enabled>(components.approvals.capability)
        assertSame(store, enabled.continuationStore)
        assertSame(digester, enabled.argumentsDigester)
        assertSame(coordinator, enabled.gateCoordinator)
        assertSame(suspended, components.approvals.suspendedInvocationStore)
    }

    private fun createComponents(
        providerRegistry: ProviderRegistry = ProviderRegistry.singleProvider(registryTestProvider()),
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
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
        policyEngine: PolicyEngine? = null,
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
    ): EngineComponents = EngineComponentFactory.create(
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
        job = job,
        scope = scope,
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

    private fun registryTestProvider(): ModelProvider = object : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse = ModelResponse(content = "test")
        override fun providerId(): String = "test-provider"
    }

    private inline fun <reified T> collaborator(): T {
        assertTrue(T::class.java.isInterface)
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> error("unused") } as T
    }
}
