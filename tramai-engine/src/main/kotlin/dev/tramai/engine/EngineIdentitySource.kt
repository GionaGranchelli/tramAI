package dev.tramai.engine

/**
 * Explicit engine composition boundary for execution identity generation.
 *
 * The two methods keep the semantic roles separate: [newWorkflowRunId] answers
 * "which concrete invocation is this?" while [newCorrelationId] answers "which
 * related operations belong to this trace?". Reusing one as the other would
 * silently erase an architectural distinction, so they are deliberately
 * distinct methods rather than one generic provider.
 *
 * Contract: both methods return opaque NON-BLANK identity strings. Callers
 * validate blankness at the consumption boundary; UUID syntax is NOT required.
 *
 * Invariant (8.3b2a): an invocation samples each required identity exactly once
 * at the invocation boundary, propagates it unchanged through its lifecycle,
 * and resume never creates replacement identity.
 */
internal interface EngineIdentitySource {
    fun newWorkflowRunId(): String
    fun newCorrelationId(): String
}

/** Production source: unpredictably unique random identities. */
internal object DefaultEngineIdentitySource : EngineIdentitySource {
    override fun newWorkflowRunId(): String = java.util.UUID.randomUUID().toString()
    override fun newCorrelationId(): String = java.util.UUID.randomUUID().toString()
}
