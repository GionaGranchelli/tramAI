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
import dev.tramai.core.provider.ProviderRoutingPlan
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

/** Runtime snapshot of provider routing. The snapshot reference is immutable; supplied providers retain their existing ownership and thread-safety contracts. */
internal data class ProviderComponents(val routingPlan: ProviderRoutingPlan)

/** Runtime snapshot of tool resolution and filtering settings. Caller-supplied registries remain caller-owned. */
internal data class ToolComponents(val toolRegistry: ToolRegistry, val toolResultFilteringSettings: ToolResultFilteringSettings)

/** Runtime snapshot of security enforcement. Caller-supplied policy, registry, DLP, and audit collaborators remain caller-owned. */
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

/** Explicit approval capability: partial approval state is unrepresentable. */
internal sealed interface ApprovalCapability {
    data object Disabled : ApprovalCapability
    data class Enabled(
        val continuationStore: ApprovalContinuationStore,
        val argumentsDigester: ToolArgumentsDigester,
        val gateCoordinator: ApprovalGateCoordinator,
    ) : ApprovalCapability
}

/** Approval collaborators used by the engine. Caller-supplied stores and emitters remain caller-owned. */
internal data class ApprovalComponents(
    val suspendedInvocationStore: SuspendedInvocationStore,
    val approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter,
    val capability: ApprovalCapability,
)

/** Runtime snapshot of persistence. Caller-supplied cache and memory remain caller-owned. */
internal data class PersistenceComponents(
    val responseCache: OperationResponseCache,
    val chatMemory: ChatMemory?,
    val conversationIdProvider: ConversationIdProvider,
)

/** Runtime snapshot of observation hooks. Caller-supplied observers and interceptors remain caller-owned. */
internal data class ObservationComponents(
    val operationObserver: OperationObserver,
    val operationInterceptor: OperationInterceptor,
    val engineEventObserver: EngineEventObserver,
    val toolFailureDiagnosticObserver: ToolFailureDiagnosticObserver,
    val structuredOutputFailureDiagnosticObserver: StructuredOutputFailureDiagnosticObserver,
)

/**
 * Runtime snapshot of execution mechanics. Engine execution parents to its internally owned
 * lifecycle job/scope (PR #226 lifecycle model); the legacy job/scope constructor parameters
 * of [dev.tramai.engine.TramaiEngine] exist for ABI compatibility only and never cross into
 * this snapshot.
 */
internal data class ExecutionComponents(
    val structuredOutputHandler: StructuredOutputHandler?,
    val circuitBreakerSettings: CircuitBreakerSettings,
    val retryPolicySettings: RetryPolicySettings,
    val tokenBudgetSettings: TokenBudgetSettings,
    val clock: Clock,
)
