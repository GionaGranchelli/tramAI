@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.engine.components

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ApprovalRecoveryCoordinator
import dev.tramai.core.approval.ApprovalResumeAdmission
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.ConversationIdProvider
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.observation.FailureIsolatingOperationObserver
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.observation.ToolFailureDiagnosticObserver
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.core.policy.PolicyDecisionAuditEmitter
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.retry.DefaultRetryJitterSource
import dev.tramai.core.retry.RetryJitterSource
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedactionAuditEmitter
import dev.tramai.core.security.PromptSanitizer
import dev.tramai.core.structured.NoOpStructuredOutputFailureDiagnosticObserver
import dev.tramai.core.structured.StructuredOutputFailureDiagnosticObserver
import dev.tramai.core.structured.StructuredOutputHandler
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.DefaultEngineIdentitySource
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.EngineIdentitySource
import dev.tramai.engine.FailureIsolatingEngineEventObserver
import dev.tramai.engine.LegacyPermissivePolicyEngine
import dev.tramai.engine.OperationResponseCache
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.TokenBudgetSettings
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.ToolResultFilteringSettings
import dev.tramai.engine.provider.ProviderRetryDelayPolicy
import dev.tramai.security.ProviderRoutingConfigurationSource
import java.time.Clock

/** One authoritative composition boundary: validates collaborators and creates the immutable snapshot. */
internal object EngineComponentFactory {
    @Suppress("LongParameterList")
    fun create(
        providerRegistry: ProviderRegistry,
        structuredOutputHandler: StructuredOutputHandler?,
        toolRegistry: ToolRegistry,
        operationObserver: OperationObserver,
        operationInterceptor: OperationInterceptor,
        responseCache: OperationResponseCache,
        modelRegistry: ModelRegistry,
        modelRegistrySettings: ModelRegistrySettings,
        circuitBreakerSettings: CircuitBreakerSettings,
        retryPolicySettings: RetryPolicySettings,
        tokenBudgetSettings: TokenBudgetSettings,
        promptSanitizer: PromptSanitizer?,
        chatMemory: ChatMemory?,
        conversationIdProvider: ConversationIdProvider,
        policyEngine: PolicyEngine?,
        dlpInterceptor: DlpInterceptor,
        dlpRedactionAuditEmitter: DlpRedactionAuditEmitter,
        toolResultFilteringSettings: ToolResultFilteringSettings,
        engineEventObserver: EngineEventObserver,
        toolFailureDiagnosticObserver: ToolFailureDiagnosticObserver,
        policyDecisionAuditEmitter: PolicyDecisionAuditEmitter,
        suspendedInvocationStore: SuspendedInvocationStore,
        approvalContinuationStore: ApprovalContinuationStore?,
        toolArgumentsDigester: ToolArgumentsDigester?,
        approvalGateCoordinator: ApprovalGateCoordinator?,
        approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter,
        clock: Clock,
        approvalResumeAdmission: ApprovalResumeAdmission? = null,
        approvalRecoveryCoordinator: ApprovalRecoveryCoordinator? = null,
        retryJitterSource: RetryJitterSource = DefaultRetryJitterSource,
        identitySource: EngineIdentitySource = DefaultEngineIdentitySource,
        structuredOutputFailureDiagnosticObserver: StructuredOutputFailureDiagnosticObserver =
            NoOpStructuredOutputFailureDiagnosticObserver,
    ): EngineComponents {
        val capability = approvalCapability(approvalContinuationStore, toolArgumentsDigester, approvalGateCoordinator)
        requireApprovalResumeComposition(approvalResumeAdmission, approvalRecoveryCoordinator)
        val resolvedPolicy = policyEngine ?: LegacyPermissivePolicyEngine
        return EngineComponents(
            ProviderComponents(providerRegistry.routingPlan),
            ToolComponents(toolRegistry, toolResultFilteringSettings),
            SecurityComponents(
                resolvedPolicy,
                policyEngine == null,
                promptSanitizer,
                modelRegistry,
                modelRegistrySettings,
                dlpInterceptor,
                dlpRedactionAuditEmitter,
                policyDecisionAuditEmitter,
                (policyEngine as? ProviderRoutingConfigurationSource)?.providerRoutingConfiguration,
            ),
            ApprovalComponents(
                suspendedInvocationStore,
                approvalLifecycleAuditEmitter,
                capability,
                approvalResumeAdmission,
                approvalRecoveryCoordinator,
            ),
            PersistenceComponents(responseCache, chatMemory, conversationIdProvider),
            ObservationComponents(
                // Epic 5.3: telemetry observers are non-authoritative. The
                // failure-isolating boundary lives here, once, so no engine
                // execution component can be derailed by a throwing observer.
                FailureIsolatingOperationObserver(operationObserver),
                operationInterceptor,
                FailureIsolatingEngineEventObserver(engineEventObserver),
                toolFailureDiagnosticObserver,
                structuredOutputFailureDiagnosticObserver,
            ),
            ExecutionComponents(
                structuredOutputHandler = structuredOutputHandler,
                circuitBreakerSettings = circuitBreakerSettings,
                retryDelayPolicy = ProviderRetryDelayPolicy(retryPolicySettings, retryJitterSource),
                tokenBudgetSettings = tokenBudgetSettings,
                clock = clock,
                identitySource = identitySource,
            ),
        )
    }

    /**
     * E9-COMP-001: a workflow-bound resume authority is only usable together with the audited transition that closes a
     * refused claim.
     *
     * A refusal path is part of the admission contract, not an optional extra: `ApprovalResumeCoordinator` refuses a
     * resume, closes the already-claimed continuation through [ApprovalRecoveryCoordinator], and reports which of the
     * two happened. Configured without it, the engine accepts a composition whose own refusal leaves the continuation
     * CLAIMED with nothing in-process to reconcile it — the state E9 called out as an operational gap.
     *
     * Recovery without admission stays supported and is not rejected: the recovery coordinator is a standalone
     * operator surface for stale-claim reconciliation and does not depend on workflow-bound admission.
     */
    fun requireApprovalResumeComposition(
        approvalResumeAdmission: ApprovalResumeAdmission?,
        approvalRecoveryCoordinator: ApprovalRecoveryCoordinator?,
    ) {
        if (approvalResumeAdmission != null && approvalRecoveryCoordinator == null) {
            throw ConfigurationException(
                "Workflow-bound approval resume admission requires an approval recovery coordinator: a refused claim " +
                    "could not be closed through the audited recovery transition",
            )
        }
    }

    fun approvalCapability(
        continuationStore: ApprovalContinuationStore?,
        digester: ToolArgumentsDigester?,
        coordinator: ApprovalGateCoordinator?,
    ): ApprovalCapability =
        when {
            continuationStore != null || digester != null || coordinator != null -> {
                require(continuationStore != null && digester != null && coordinator != null) {
                    "Approval suspension requires continuation store, arguments digester, and gate coordinator"
                }
                ApprovalCapability.Enabled(continuationStore, digester, coordinator)
            }

            else -> {
                ApprovalCapability.Disabled
            }
        }
}
