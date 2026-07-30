package dev.tramai.orchestration

/**
 * Controller for resolving workflows in [WorkflowRecoveryState.Required] state.
 *
 * All methods are fenced by [expectedRevision] — if the checkpoint's revision
 * has changed since it was loaded, the operation throws [WorkflowCheckpointConflictException].
 *
 * NOTE: [confirmCompleted] is intentionally omitted from this PR. Safely advancing
 * a workflow past an unknown step without re-executing requires reconstructing or
 * supplying the step's post-state, which is not yet supported.
 */
interface WorkflowRecoveryController {
    /**
     * Clear the recovery state, allowing the worker to re-attempt the unresolved step
     * on the next poll cycle.
     *
     * The original [WorkflowRecoveryRecord] and unknown attempt record remain in the
     * stores as audit evidence. For [ReplayPolicy.EXTERNALLY_IDEMPOTENT] steps the
     * stored idempotency key is reused.
     *
     * @return the checkpoint after clearing recovery (revision advanced by one).
     * @throws WorkflowCheckpointConflictException if [expectedRevision] is stale.
     */
    suspend fun retryStep(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        reason: String,
    ): WorkflowCheckpoint

    /**
     * Clear the recovery state and permanently delete the checkpoint.
     *
     * The workflow will not be polled or executed again. Step attempt records
     * remain in the store as audit evidence. Calling [retryStep] after [failWorkflow]
     * is not possible because the checkpoint no longer exists.
     *
     * @throws WorkflowCheckpointConflictException if [expectedRevision] is stale.
     */
    suspend fun failWorkflow(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        reason: String,
    )
}

/**
 * Default in-memory implementation of [WorkflowRecoveryController].
 *
 * Delegates to the store's [WorkflowCheckpointStore.clearRecovery] and
 * [WorkflowCheckpointStore.delete] primitives, both fenced by expected revision.
 * When both operations are required (failWorkflow), they are applied sequentially
 * under the store's optimistic concurrency — the first call consumes a revision,
 * so the second call uses [expectedRevision] + 1.
 *
 * Override for a genuinely atomic two-phase operation (e.g. JDBC transaction).
 */
class InMemoryWorkflowRecoveryController(
    private val checkpointStore: WorkflowCheckpointStore,
) : WorkflowRecoveryController {

    override suspend fun retryStep(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        reason: String,
    ): WorkflowCheckpoint {
        return checkpointStore.clearRecovery(
            workflowName = workflowName,
            workflowId = workflowId,
            expectedRevision = expectedRevision,
        )
    }

    override suspend fun failWorkflow(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        reason: String,
    ) {
        // Clear recovery first — consumes one revision.
        checkpointStore.clearRecovery(
            workflowName = workflowName,
            workflowId = workflowId,
            expectedRevision = expectedRevision,
        )
        // Then delete — revision has been bumped by clearRecovery.
        checkpointStore.delete(
            workflowName = workflowName,
            workflowId = workflowId,
            expectedRevision = expectedRevision + 1,
        )
    }
}
