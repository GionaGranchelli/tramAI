package dev.tramai.core.identity

/**
 * Identity of one authoritative execution of a governed workload deployment:
 *
 * ```text
 * WorkloadDeploymentIdentity + RunId = GovernedRunIdentity
 * ```
 *
 * This is the canonical structure later candidates (approval, policy evidence,
 * routing evidence, checkpoints, scheduler ticks, engine invocation,
 * control-plane projection, reconstruction) reference instead of inventing
 * their own identity tuple. It only represents identity: propagation through
 * orchestration/engine/approval/evidence belongs to the run attribution
 * candidate (Epic 0.7.1 candidate 0.7.1d), and no identifier is generated
 * here.
 */
data class GovernedRunIdentity(
    val deployment: WorkloadDeploymentIdentity,
    val runId: RunId,
)
