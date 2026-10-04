package dev.tramai.controlplane

/**
 * Monotonic version of the authoritative mutable registration record.
 *
 * - a fresh registration starts at 1;
 * - a successful authoritative mutation advances N -> N + 1;
 * - reads, rejected mutations, failed/stale CAS and idempotent re-registration
 *   leave N unchanged.
 *
 * The version lives on the mutable authoritative record, never on the
 * immutable [dev.tramai.core.identity.WorkloadDeploymentIdentity] or
 * [dev.tramai.core.identity.GovernedRunIdentity]: a state update must never
 * change the identity of historical runs. Metadata changes (e.g. owner team
 * handover) preserve identity but advance this version.
 */
data class WorkloadStateVersion(
    val value: Long,
) {
    init {
        require(value >= 1L) { "WorkloadStateVersion must be at least 1, got $value" }
    }

    /**
     * Advances the version for the next authoritative mutation, failing
     * closed on overflow rather than silently wrapping.
     */
    fun next(): WorkloadStateVersion {
        val advanced =
            try {
                Math.addExact(value, 1L)
            } catch (e: ArithmeticException) {
                throw IllegalStateException("WorkloadStateVersion overflow at $value", e)
            }
        return WorkloadStateVersion(advanced)
    }

    override fun toString(): String = value.toString()

    companion object {
        val INITIAL: WorkloadStateVersion = WorkloadStateVersion(1L)
    }
}
