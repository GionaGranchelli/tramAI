package dev.tramai.orchestration

import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadDeploymentIdentity

/**
 * Additive governed execution envelope (0.7.1d).
 *
 * Carries the canonical [GovernedRunIdentity] ALONGSIDE the existing
 * [WorkflowContext] instead of widening it: `WorkflowContext(workflowId,
 * attributes)` stays the public contract, so ungoverned execution and its ABI are
 * untouched and an ungoverned run cannot accidentally acquire attribution.
 *
 * The boundary invariant is enforced once, at construction:
 * `context.workflowId == identity.runId.value`. An envelope that could describe two
 * different runs cannot exist, so no subsystem has to re-check it later.
 *
 * This type states identity only. Nothing here generates, rewrites or interprets
 * attribution: the run id IS the workflow id, for the whole life of the run.
 */
class GovernedRun(
    val context: WorkflowContext,
    val identity: GovernedRunIdentity,
) {
    init {
        require(identity.runId.value == context.workflowId) {
            "GovernedRunIdentity.runId ('${identity.runId.value}') must equal " +
                "WorkflowContext.workflowId ('${context.workflowId}')"
        }
    }

    companion object {
        /**
         * Starts a NEW governed execution of a workload deployment.
         *
         * The canonical identity is established exactly once, here: the generated
         * workflow id becomes the run id, so identity and run cannot drift apart.
         */
        fun start(deployment: WorkloadDeploymentIdentity): GovernedRun {
            val context = WorkflowContext()
            return GovernedRun(
                context = context,
                identity = GovernedRunIdentity(deployment = deployment, runId = RunId(context.workflowId)),
            )
        }

        /**
         * Rebuilds the boundary for an EXISTING governed run (restart, resume,
         * worker recovery or delayed wake-up). The run id is never regenerated: a new
         * one would silently turn a resume into a second execution of the same run.
         */
        fun resume(
            deployment: WorkloadDeploymentIdentity,
            runId: String,
        ): GovernedRun =
            GovernedRun(
                context = WorkflowContext(workflowId = runId),
                identity = GovernedRunIdentity(deployment = deployment, runId = RunId(runId)),
            )
    }
}
