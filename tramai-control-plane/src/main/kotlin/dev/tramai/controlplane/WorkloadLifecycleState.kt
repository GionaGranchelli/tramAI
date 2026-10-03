package dev.tramai.controlplane

/**
 * Lifecycle of a workload REGISTRATION — deliberately distinct from the
 * lifecycle of any individual workflow run. Suspending or retiring a workload
 * registration does not cancel already-running workflows (that is runtime
 * control, not registration authority).
 *
 * Transition graph:
 *
 * ```text
 * ACTIVE <-> SUSPENDED
 * ACTIVE    -> RETIRED
 * SUSPENDED -> RETIRED
 * RETIRED   -> (terminal)
 * ```
 */
enum class WorkloadLifecycleState {
    /** Registration is eligible to admit new governed runs. */
    ACTIVE,

    /** No new governed runs should be admitted. */
    SUSPENDED,

    /** Terminal registration; cannot admit new runs or reactivate. */
    RETIRED,
    ;

    /**
     * Whether a transition from this state to [target] is part of the
     * authoritative lifecycle graph.
     */
    fun canTransitionTo(target: WorkloadLifecycleState): Boolean =
        when (this) {
            ACTIVE -> target == SUSPENDED || target == RETIRED
            SUSPENDED -> target == ACTIVE || target == RETIRED
            RETIRED -> false
        }
}
