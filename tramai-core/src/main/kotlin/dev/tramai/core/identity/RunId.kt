package dev.tramai.core.identity

/**
 * One immutable identifier for one authoritative execution of a governed
 * workload deployment.
 *
 * Run semantics:
 * - resume of the same authoritative run keeps the same [RunId];
 * - retry/restart within the same authoritative run keeps the same [RunId];
 * - approval-resume keeps the same [RunId];
 * - a genuinely new execution receives a new [RunId].
 *
 * This type only REPRESENTS run identity; it never generates identifiers.
 * Creation of the id at the supported execution boundary belongs to the run
 * attribution candidate (Epic 0.7.1 candidate 0.7.1d). It is distinct from a
 * diagnostic/cross-operation correlation id, which TramAI already models in
 * `EngineExecutionIdentity.correlationId`.
 */
data class RunId(
    val value: String,
) {
    init {
        validateIdentity("RunId", value)
    }

    override fun toString(): String = value
}
