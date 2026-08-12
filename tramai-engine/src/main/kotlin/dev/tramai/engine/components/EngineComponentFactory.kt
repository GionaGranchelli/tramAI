package dev.tramai.engine.components

import dev.tramai.core.approval.*
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.ConversationIdProvider
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.observation.*
import dev.tramai.core.policy.*
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.security.*
import dev.tramai.core.structured.*
import dev.tramai.engine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.time.Clock

/** One authoritative composition boundary: validates collaborators and creates the immutable snapshot. */
internal object EngineComponentFactory {
    @Suppress("LongParameterList")
    fun create(providerRegistry: ProviderRegistry, structuredOutputHandler: StructuredOutputHandler?, toolRegistry: ToolRegistry,
        operationObserver: OperationObserver, operationInterceptor: OperationInterceptor, responseCache: OperationResponseCache,
        modelRegistry: ModelRegistry, modelRegistrySettings: ModelRegistrySettings, circuitBreakerSettings: CircuitBreakerSettings,
        retryPolicySettings: RetryPolicySettings, tokenBudgetSettings: TokenBudgetSettings, promptSanitizer: PromptSanitizer?,
        chatMemory: ChatMemory?, conversationIdProvider: ConversationIdProvider, job: Job, scope: CoroutineScope,
        policyEngine: PolicyEngine?, dlpInterceptor: DlpInterceptor, dlpRedactionAuditEmitter: DlpRedactionAuditEmitter,
        toolResultFilteringSettings: ToolResultFilteringSettings, engineEventObserver: EngineEventObserver,
        toolFailureDiagnosticObserver: ToolFailureDiagnosticObserver, policyDecisionAuditEmitter: PolicyDecisionAuditEmitter,
        suspendedInvocationStore: SuspendedInvocationStore, approvalContinuationStore: ApprovalContinuationStore?,
        toolArgumentsDigester: ToolArgumentsDigester?, approvalGateCoordinator: ApprovalGateCoordinator?,
        approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter, clock: Clock,
        structuredOutputFailureDiagnosticObserver: StructuredOutputFailureDiagnosticObserver = NoOpStructuredOutputFailureDiagnosticObserver,
    ): EngineComponents {
        val capability = approvalCapability(approvalContinuationStore, toolArgumentsDigester, approvalGateCoordinator)
        val resolvedPolicy = policyEngine ?: LegacyPermissivePolicyEngine
        return EngineComponents(
            ProviderComponents(providerRegistry), ToolComponents(toolRegistry, toolResultFilteringSettings),
            SecurityComponents(resolvedPolicy, policyEngine == null, promptSanitizer, modelRegistry, modelRegistrySettings, dlpInterceptor, dlpRedactionAuditEmitter, policyDecisionAuditEmitter),
            ApprovalComponents(suspendedInvocationStore, approvalLifecycleAuditEmitter, capability),
            PersistenceComponents(responseCache, chatMemory, conversationIdProvider),
            ObservationComponents(operationObserver, operationInterceptor, engineEventObserver, toolFailureDiagnosticObserver, structuredOutputFailureDiagnosticObserver),
            ExecutionComponents(structuredOutputHandler, circuitBreakerSettings, retryPolicySettings, tokenBudgetSettings, clock, job, scope),
        )
    }

    fun approvalCapability(continuationStore: ApprovalContinuationStore?, digester: ToolArgumentsDigester?, coordinator: ApprovalGateCoordinator?): ApprovalCapability = when {
        continuationStore != null || digester != null || coordinator != null -> {
            require(continuationStore != null && digester != null && coordinator != null) {
                "Approval suspension requires continuation store, arguments digester, and gate coordinator"
            }
            ApprovalCapability.Enabled(continuationStore, digester, coordinator)
        }
        else -> ApprovalCapability.Disabled
    }
}
