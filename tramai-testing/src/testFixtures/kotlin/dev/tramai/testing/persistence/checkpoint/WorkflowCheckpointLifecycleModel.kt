package dev.tramai.testing.persistence.checkpoint

/** Pure checkpoint-incarnation oracle. It intentionally has no store imports. */
data class WorkflowCheckpointLifecycleModel(
    val current: ModeledCheckpoint?,
    val predecessors: List<ModeledCheckpointGeneration>,
) {
    fun apply(action: WorkflowCheckpointLifecycleAction): WorkflowCheckpointLifecycleOutcome = when (action) {
        WorkflowCheckpointLifecycleAction.Create -> if (current == null) {
            success(
                copy(
                    current = ModeledCheckpoint(
                        generation = "G${predecessors.size + 1}",
                        revision = 1,
                        nextStepIndex = 1,
                        stepExecutions = 1,
                        statePayload = "state-create-${predecessors.size + 1}",
                        recoveryState = ModeledRecoveryState.Normal,
                        metadata = mapOf("incarnation" to "${predecessors.size + 1}"),
                    ),
                ),
            )
        } else {
            rejected()
        }

        is WorkflowCheckpointLifecycleAction.UpdateCurrent -> currentMutation { checkpoint ->
            checkpoint.copy(
                revision = checkpoint.revision + 1,
                nextStepIndex = action.nextStepIndex,
                stepExecutions = checkpoint.stepExecutions + 1,
                statePayload = action.statePayload,
            )
        }

        WorkflowCheckpointLifecycleAction.UpdateStaleRevision,
        is WorkflowCheckpointLifecycleAction.UpdatePredecessorGeneration,
        WorkflowCheckpointLifecycleAction.RequireRecoveryStaleRevision,
        is WorkflowCheckpointLifecycleAction.RequireRecoveryPredecessor,
        WorkflowCheckpointLifecycleAction.ClearRecoveryStaleRevision,
        is WorkflowCheckpointLifecycleAction.ClearRecoveryPredecessor,
        WorkflowCheckpointLifecycleAction.DeleteStaleRevision,
        is WorkflowCheckpointLifecycleAction.DeletePredecessor,
        -> rejected()

        WorkflowCheckpointLifecycleAction.RequireRecoveryCurrent -> currentMutation { checkpoint ->
            checkpoint.copy(
                revision = checkpoint.revision + 1,
                recoveryState = ModeledRecoveryState.Required,
            )
        }

        WorkflowCheckpointLifecycleAction.ClearRecoveryCurrent -> currentMutation { checkpoint ->
            checkpoint.copy(
                revision = checkpoint.revision + 1,
                recoveryState = ModeledRecoveryState.Normal,
            )
        }

        WorkflowCheckpointLifecycleAction.DeleteCurrent -> deleteCurrent(rejectedWhenAbsent = true)
        WorkflowCheckpointLifecycleAction.DeleteUnconditional -> deleteCurrent(rejectedWhenAbsent = false)
        WorkflowCheckpointLifecycleAction.Observe -> WorkflowCheckpointLifecycleOutcome.Observed(this)
    }

    private fun currentMutation(
        mutate: (ModeledCheckpoint) -> ModeledCheckpoint,
    ): WorkflowCheckpointLifecycleOutcome = current?.let { success(copy(current = mutate(it))) } ?: rejected()

    private fun deleteCurrent(rejectedWhenAbsent: Boolean): WorkflowCheckpointLifecycleOutcome {
        val checkpoint = current
        if (checkpoint == null) {
            return if (rejectedWhenAbsent) rejected() else success(this)
        }
        return success(
            copy(
                current = null,
                predecessors = predecessors + ModeledCheckpointGeneration(
                    generation = checkpoint.generation,
                    finalRevision = checkpoint.revision,
                    recoveryState = checkpoint.recoveryState,
                ),
            ),
        )
    }

    private fun success(next: WorkflowCheckpointLifecycleModel) = WorkflowCheckpointLifecycleOutcome.Success(next)
    private fun rejected() = WorkflowCheckpointLifecycleOutcome.Rejected(this)

    companion object {
        fun empty(): WorkflowCheckpointLifecycleModel = WorkflowCheckpointLifecycleModel(null, emptyList())
    }
}

data class ModeledCheckpoint(
    val generation: String,
    val revision: Long,
    val nextStepIndex: Int,
    val stepExecutions: Int,
    val statePayload: String,
    val recoveryState: ModeledRecoveryState,
    val metadata: Map<String, String>,
)

data class ModeledCheckpointGeneration(
    val generation: String,
    val finalRevision: Long,
    val recoveryState: ModeledRecoveryState,
)

enum class ModeledRecoveryState {
    Normal,
    Required,
}

sealed interface WorkflowCheckpointLifecycleOutcome {
    val next: WorkflowCheckpointLifecycleModel

    data class Success(override val next: WorkflowCheckpointLifecycleModel) : WorkflowCheckpointLifecycleOutcome
    data class Rejected(override val next: WorkflowCheckpointLifecycleModel) : WorkflowCheckpointLifecycleOutcome
    data class Observed(override val next: WorkflowCheckpointLifecycleModel) : WorkflowCheckpointLifecycleOutcome
}

sealed interface WorkflowCheckpointLifecycleAction {
    data object Create : WorkflowCheckpointLifecycleAction
    data class UpdateCurrent(val nextStepIndex: Int, val statePayload: String) : WorkflowCheckpointLifecycleAction
    data object UpdateStaleRevision : WorkflowCheckpointLifecycleAction
    data class UpdatePredecessorGeneration(val predecessorIndex: Int) : WorkflowCheckpointLifecycleAction
    data object RequireRecoveryCurrent : WorkflowCheckpointLifecycleAction
    data object RequireRecoveryStaleRevision : WorkflowCheckpointLifecycleAction
    data class RequireRecoveryPredecessor(val predecessorIndex: Int) : WorkflowCheckpointLifecycleAction
    data object ClearRecoveryCurrent : WorkflowCheckpointLifecycleAction
    data object ClearRecoveryStaleRevision : WorkflowCheckpointLifecycleAction
    data class ClearRecoveryPredecessor(val predecessorIndex: Int) : WorkflowCheckpointLifecycleAction
    data object DeleteCurrent : WorkflowCheckpointLifecycleAction
    data object DeleteStaleRevision : WorkflowCheckpointLifecycleAction
    data class DeletePredecessor(val predecessorIndex: Int) : WorkflowCheckpointLifecycleAction
    data object DeleteUnconditional : WorkflowCheckpointLifecycleAction
    data object Observe : WorkflowCheckpointLifecycleAction

    fun describe(): String = when (this) {
        Create -> "Create"
        is UpdateCurrent -> "UpdateCurrent(next=$nextStepIndex,payload=$statePayload)"
        UpdateStaleRevision -> "UpdateStaleRevision"
        is UpdatePredecessorGeneration -> "UpdatePredecessorGeneration(index=$predecessorIndex)"
        RequireRecoveryCurrent -> "RequireRecoveryCurrent"
        RequireRecoveryStaleRevision -> "RequireRecoveryStaleRevision"
        is RequireRecoveryPredecessor -> "RequireRecoveryPredecessor(index=$predecessorIndex)"
        ClearRecoveryCurrent -> "ClearRecoveryCurrent"
        ClearRecoveryStaleRevision -> "ClearRecoveryStaleRevision"
        is ClearRecoveryPredecessor -> "ClearRecoveryPredecessor(index=$predecessorIndex)"
        DeleteCurrent -> "DeleteCurrent"
        DeleteStaleRevision -> "DeleteStaleRevision"
        is DeletePredecessor -> "DeletePredecessor(index=$predecessorIndex)"
        DeleteUnconditional -> "DeleteUnconditional"
        Observe -> "Observe"
    }
}
