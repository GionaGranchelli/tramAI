package dev.tramai.engine

import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ApprovalRequiredException
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyDecisionAuditEmitter
import dev.tramai.core.policy.NoOpPolicyDecisionAuditEmitter
import dev.tramai.core.policy.PolicyEngine
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Centralized policy-enforcement helper used by [TramaiInvocationHandler].
 *
 * Every enforcement point routes through a single [enforce] call to avoid
 * scattered decision-handling logic across the engine.
 *
 * When no explicit [PolicyEngine] is provided, the engine uses
 * [dev.tramai.engine.LegacyPermissivePolicyEngine] for backward compatibility.
 * One migration warning is emitted per engine instance.
 */
internal class PolicyEnforcementHelper(
    private val policyEngine: PolicyEngine,
    private val migrationWarningGuard: AtomicBoolean,
    private val policyVersion: String = DEFAULT_POLICY_VERSION,
    private val isLegacyFallback: Boolean = false,
    private val auditEmitter: PolicyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter,
) {
    private val logger = Logger.getLogger(PolicyEnforcementHelper::class.java.name)

    /**
     * Evaluates policy at the given [enforcementPoint] and returns the [PolicyDecision]
     * without throwing. Callers that support suspension should use this and handle
     * [PolicyDecision.RequireApproval] explicitly.
     */
    suspend fun evaluate(context: PolicyContext): PolicyDecision {
        logMigrationWarningOnce()
        val decision = policyEngine.evaluate(context)
        auditEmitter.emit(context.enforcementPoint, context, decision)
        return decision
    }

    /**
     * Evaluates policy at the given [enforcementPoint] and enforces the decision
     * by throwing on Deny or RequireApproval.
     */
    suspend fun enforce(context: PolicyContext) {
        when (val decision = evaluate(context)) {
            is PolicyDecision.Allow -> { /* continue */ }
            is PolicyDecision.Deny -> throw PolicyViolationException(decision)
            is PolicyDecision.RequireApproval -> throw ApprovalRequiredException(decision.requirement)
        }
    }

    private fun logMigrationWarningOnce() {
        if (isLegacyFallback && migrationWarningGuard.compareAndSet(false, true)) {
            logger.log(
                Level.WARNING,
                "TramAI Engine: No PolicyEngine configured. Operations are allowed through " +
                    "LegacyPermissivePolicyEngine for 0.4.x backward compatibility. " +
                    "Configure an explicit PolicyEngine before production use.",
            )
        }
    }

    /**
     * Creates a [PolicyContext] with a stable [correlationId] tied to an execution flow.
     */
    fun buildContext(
        enforcementPoint: EnforcementPoint,
        correlationId: String = UUID.randomUUID().toString(),
        base: ContextDefaults = ContextDefaults(),
    ): PolicyContextBuilder = PolicyContextBuilder(enforcementPoint, correlationId, policyVersion, base)

    fun getPolicyVersion(): String = policyVersion

    data class ContextDefaults(
        val workflowId: String? = null,
        val workflowRunId: String? = null,
        val actorId: String = ACTOR_ANONYMOUS,
        val providerId: String? = null,
        val modelName: String? = null,
        val workflowDigest: String? = null,
    )

    companion object {
        const val ACTOR_ANONYMOUS = "system.anonymous"
        const val DEFAULT_POLICY_VERSION = "0.4.0-preview"
    }
}

/**
 * Builder for [PolicyContext] to avoid massive constructor call sites.
 */
internal class PolicyContextBuilder(
    private val enforcementPoint: EnforcementPoint,
    private val correlationId: String,
    private val policyVersion: String,
    private val base: PolicyEnforcementHelper.ContextDefaults,
) {
    private var workflowId: String? = base.workflowId
    private var workflowRunId: String? = base.workflowRunId
    private var actorId: String = base.actorId
    private var providerId: String? = base.providerId
    private var modelName: String? = base.modelName
    private var fallbackProviderId: String? = null
    private var dataClassification: dev.tramai.core.policy.DataClassification? = null
    private var classificationSource: dev.tramai.core.policy.ClassificationSource? = null
    private var toolName: String? = null
    private var toolSecurity: dev.tramai.core.policy.ToolSecurityMetadata? = null
    private var targetDestination: String? = null
    private var workflowDigest: String? = base.workflowDigest
    private val attributes = mutableMapOf<String, String>()

    fun providerId(id: String?) = apply { this.providerId = id }
    fun modelName(name: String?) = apply { this.modelName = name }
    fun fallbackProviderId(id: String?) = apply { this.fallbackProviderId = id }
    fun toolName(name: String?) = apply { this.toolName = name }
    fun toolSecurity(meta: dev.tramai.core.policy.ToolSecurityMetadata?) = apply { this.toolSecurity = meta }
    fun workflowRunId(id: String?) = apply { this.workflowRunId = id }
    fun dataClassification(value: dev.tramai.core.policy.DataClassification?) = apply { this.dataClassification = value }
    fun classificationSource(value: dev.tramai.core.policy.ClassificationSource?) = apply { this.classificationSource = value }
    fun targetDestination(value: String?) = apply { this.targetDestination = value }
    fun workflowDigest(value: String?) = apply { this.workflowDigest = value }
    fun actorId(value: String) = apply { this.actorId = value }
    fun workflowId(value: String?) = apply { this.workflowId = value }
    fun attribute(key: String, value: String) = apply { this.attributes[key] = value }

    fun build(): PolicyContext = PolicyContext(
        enforcementPoint = enforcementPoint,
        workflowId = workflowId,
        workflowRunId = workflowRunId,
        correlationId = correlationId,
        actorId = actorId,
        providerId = providerId,
        modelName = modelName,
        fallbackProviderId = fallbackProviderId,
        dataClassification = dataClassification,
        classificationSource = classificationSource,
        toolName = toolName,
        toolSecurity = toolSecurity,
        targetDestination = targetDestination,
        policyVersion = policyVersion,
        workflowDigest = workflowDigest,
        attributes = attributes.toMap(),
    )
}
