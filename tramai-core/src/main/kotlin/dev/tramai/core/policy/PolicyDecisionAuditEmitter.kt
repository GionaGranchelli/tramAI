package dev.tramai.core.policy

fun interface PolicyDecisionAuditEmitter {
    suspend fun emit(
        enforcementPoint: EnforcementPoint,
        context: PolicyContext,
        decision: PolicyDecision,
    )
}

object NoOpPolicyDecisionAuditEmitter : PolicyDecisionAuditEmitter {
    override suspend fun emit(
        enforcementPoint: EnforcementPoint,
        context: PolicyContext,
        decision: PolicyDecision,
    ) = Unit
}
