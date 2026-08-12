package dev.tramai.engine.components

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.ConversationIdProvider
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.observation.ToolFailureDiagnosticObserver
import dev.tramai.core.policy.PolicyDecisionAuditEmitter
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedactionAuditEmitter
import dev.tramai.core.security.PromptSanitizer
import dev.tramai.core.structured.StructuredOutputFailureDiagnosticObserver
import dev.tramai.core.structured.StructuredOutputHandler
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.OperationResponseCache
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.TokenBudgetSettings
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.ToolResultFilteringSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.time.Clock

/**
 * Immutable, validated runtime configuration snapshot. It is the single frozen
 * composition input to an engine: all references are final, it retains no builder,
 * and it performs no lazy reads of builder configuration.
 */
internal data class EngineComponents(
    val providers: ProviderComponents,
    val tools: ToolComponents,
    val security: SecurityComponents,
    val approvals: ApprovalComponents,
    val persistence: PersistenceComponents,
    val observation: ObservationComponents,
    val execution: ExecutionComponents,
)

/** Provider routing owned by the engine; registry thread-safety remains its contract. */
internal data class ProviderComponents(val providerRegistry: ProviderRegistry)

/** Tool resolution owned by the engine; registry/settings are immutable composition references. */
internal data class ToolComponents(val toolRegistry: ToolRegistry, val toolResultFilteringSettings: ToolResultFilteringSettings)

/** Security enforcement owned by the engine; collaborators retain their documented thread-safety. */
internal data class SecurityComponents(
    val resolvedPolicyEngine: PolicyEngine,
    val isLegacyFallback: Boolean,
    val promptSanitizer: PromptSanitizer?,
    val modelRegistry: ModelRegistry,
    val modelRegistrySettings: ModelRegistrySettings,
    val dlpInterceptor: DlpInterceptor,
    val dlpRedactionAuditEmitter: DlpRedactionAuditEmitter,
    val policyDecisionAuditEmitter: PolicyDecisionAuditEmitter,
)

/** Complete approval capability owned by the engine; partial approval state is unrepresentable. */
internal sealed interface ApprovalCapability {
    data object Disabled : ApprovalCapability
    data class Enabled(
        val continuationStore: ApprovalContinuationStore,
        val argumentsDigester: ToolArgumentsDigester,
        val gateCoordinator: ApprovalGateCoordinator,
    ) : ApprovalCapability
}

/** Approval persistence and auditing owned by the engine; stores define their own thread-safety. */
internal data class ApprovalComponents(
    val suspendedInvocationStore: SuspendedInvocationStore,
    val approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter,
    val capability: ApprovalCapability,
)

/** Response persistence owned by the engine; supplied cache and memory retain their contracts. */
internal data class PersistenceComponents(
    val responseCache: OperationResponseCache,
    val chatMemory: ChatMemory?,
    val conversationIdProvider: ConversationIdProvider,
)

/** Observation hooks owned by the engine; observers must meet their existing thread-safety contracts. */
internal data class ObservationComponents(
    val operationObserver: OperationObserver,
    val operationInterceptor: OperationInterceptor,
    val engineEventObserver: EngineEventObserver,
    val toolFailureDiagnosticObserver: ToolFailureDiagnosticObserver,
    val structuredOutputFailureDiagnosticObserver: StructuredOutputFailureDiagnosticObserver,
)

/** Execution mechanics owned by the engine; caller-supplied job/scope are retained for ABI compatibility only — close() cancels the engine's internally owned lifecycle job. */
internal data class ExecutionComponents(
    val structuredOutputHandler: StructuredOutputHandler?,
    val circuitBreakerSettings: CircuitBreakerSettings,
    val retryPolicySettings: RetryPolicySettings,
    val tokenBudgetSettings: TokenBudgetSettings,
    val clock: Clock,
    val job: Job,
    val scope: CoroutineScope,
)
