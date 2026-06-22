package dev.tramai.core.policy

fun interface PolicyDecisionAuditEmitter {
    suspend fun emit(
        enforcementPoint: EnforcementPoint,
        context: PolicyContext,
        decision: PolicyDecision,
    )
}

val NoOpPolicyDecisionAuditEmitter: PolicyDecisionAuditEmitter = PolicyDecisionAuditEmitter { _, _, _ -> Unit }
