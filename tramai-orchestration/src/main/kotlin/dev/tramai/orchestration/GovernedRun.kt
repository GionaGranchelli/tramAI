package dev.tramai.orchestration

import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadDeploymentIdentity

/**
 * Recovers the governed execution envelope of an ALREADY EXISTING run (0.7.1d).
 *
 * Continuation paths recover identity; they never recreate it. The deployment identity
 * is therefore read from the run's own durable checkpoint — never rebuilt from whatever
 * schedule, registration or binding happens to exist at continuation time, which could
 * have changed after the run was created.
 *
 * Returns null for an intentionally ungoverned (legacy) run, and fails closed (throws)
 * when persisted attribution is partial or malformed: a run that was governed must never
 * continue un-attributed.
 */
suspend fun <S> WorkflowPersistence<S>.recoverGovernedRun(
    workflowName: String,
    workflowId: String,
): GovernedRun? {
    // Missing checkpoint and legacy-without-attribution are both "nothing to recover";
    // PARTIAL attribution throws inside the decoder, so a governed run can never be
    // silently downgraded to an unattributed one.
    val checkpoint = checkpointStore.load(workflowName, workflowId) ?: return null
    val identity = decodeGovernedRunAttribution(checkpoint.workflowId, checkpoint.metadata)
    return identity?.let {
        GovernedRun(context = WorkflowContext(workflowId = checkpoint.workflowId), identity = it)
    }
}

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
        fun start(
            deployment: WorkloadDeploymentIdentity,
            attributes: Map<String, Any?> = emptyMap(),
        ): GovernedRun {
            val context = WorkflowContext(attributes = attributes)
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
