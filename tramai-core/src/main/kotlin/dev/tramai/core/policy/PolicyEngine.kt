package dev.tramai.core.policy

/**
 * SPI for the policy enforcement engine.
 *
 * Implementations decide whether an operation is allowed, denied, or requires
 * human approval. The engine calls [evaluate] at every [EnforcementPoint].
 *
 * In secure mode, absence of a [PolicyEngine] implementation causes fail-closed
 * behavior. In legacy-permissive mode, all operations are allowed.
 */
fun interface PolicyEngine {
    /**
     * Evaluate the given [context] and return a decision.
     *
     * This method must be deterministic for identical contexts. Security token
     * generation (nonce, approval ID) is handled by the approval subsystem,
     * not by this method.
     */
    suspend fun evaluate(context: PolicyContext): PolicyDecision
}
