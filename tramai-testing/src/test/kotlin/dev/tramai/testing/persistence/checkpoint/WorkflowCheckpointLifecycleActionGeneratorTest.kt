package dev.tramai.testing.persistence.checkpoint

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkflowCheckpointLifecycleActionGeneratorTest {
    @Test
    fun `same seed produces exactly the same checkpoint action trace`() {
        for (seed in 0L until WorkflowCheckpointLifecycleActionGenerator.SEED_COUNT) {
            val first = WorkflowCheckpointLifecycleActionGenerator.generate(seed)
            val second = WorkflowCheckpointLifecycleActionGenerator.generate(seed)
            assertThat(first.map { it.describe() })
                .withFailMessage("seed $seed must be deterministic")
                .isEqualTo(second.map { it.describe() })
        }
    }

    @Test
    fun `fixed seed corpus covers every checkpoint lifecycle category`() {
        val coverage = recordCorpus()
        val expected = setOf(
            "observe-absent",
            "observe-present",
            "delete-unconditional-absent",
            "delete-unconditional-present",
            "create-fresh",
            "recreate",
            "create-conflict",
            "update-current-normal",
            "update-current-required",
            "update-stale-revision",
            "update-predecessor-normal",
            "update-predecessor-required",
            "require-current-normal",
            "require-stale-normal",
            "require-predecessor-normal",
            "require-predecessor-required",
            "clear-current-required",
            "clear-current-normal",
            "clear-stale-required",
            "clear-predecessor-normal",
            "clear-predecessor-required",
            "delete-current-normal",
            "delete-current-required",
            "delete-stale-revision",
            "delete-predecessor-normal",
            "delete-predecessor-required",
            "three-plus-incarnations",
        )
        val missing = expected - coverage
        assertThat(missing)
            .withFailMessage("corpus missing categories: ${missing.sorted()}; available: ${coverage.sorted()}")
            .isEmpty()
        assertThat(coverage.size).isGreaterThanOrEqualTo(25)
    }

    private fun recordCorpus(): Set<String> = buildSet {
        for (seed in 0L until WorkflowCheckpointLifecycleActionGenerator.SEED_COUNT) {
            var model = WorkflowCheckpointLifecycleModel.empty()
            WorkflowCheckpointLifecycleActionGenerator.generate(seed).forEach { action ->
                record(action, model)
                model = model.apply(action).next
                if (model.predecessors.size >= 2 && model.current != null) add("three-plus-incarnations")
            }
        }
    }

    private fun MutableSet<String>.record(
        action: WorkflowCheckpointLifecycleAction,
        before: WorkflowCheckpointLifecycleModel,
    ) {
        val currentState = before.current?.recoveryState
        fun predecessorState(index: Int): ModeledRecoveryState? = before.predecessors.getOrNull(index)?.recoveryState
        when (action) {
            WorkflowCheckpointLifecycleAction.Create -> when {
                before.current != null -> add("create-conflict")
                before.predecessors.isEmpty() -> add("create-fresh")
                else -> add("recreate")
            }
            is WorkflowCheckpointLifecycleAction.UpdateCurrent -> add("update-current-${currentState.label()}")
            WorkflowCheckpointLifecycleAction.UpdateStaleRevision -> add("update-stale-revision")
            is WorkflowCheckpointLifecycleAction.UpdatePredecessorGeneration ->
                add("update-predecessor-${predecessorState(action.predecessorIndex).label()}")
            WorkflowCheckpointLifecycleAction.RequireRecoveryCurrent -> add("require-current-${currentState.label()}")
            WorkflowCheckpointLifecycleAction.RequireRecoveryStaleRevision -> add("require-stale-${currentState.label()}")
            is WorkflowCheckpointLifecycleAction.RequireRecoveryPredecessor ->
                add("require-predecessor-${predecessorState(action.predecessorIndex).label()}")
            WorkflowCheckpointLifecycleAction.ClearRecoveryCurrent -> add("clear-current-${currentState.label()}")
            WorkflowCheckpointLifecycleAction.ClearRecoveryStaleRevision -> add("clear-stale-${currentState.label()}")
            is WorkflowCheckpointLifecycleAction.ClearRecoveryPredecessor ->
                add("clear-predecessor-${predecessorState(action.predecessorIndex).label()}")
            WorkflowCheckpointLifecycleAction.DeleteCurrent -> add("delete-current-${currentState.label()}")
            WorkflowCheckpointLifecycleAction.DeleteStaleRevision -> add("delete-stale-revision")
            is WorkflowCheckpointLifecycleAction.DeletePredecessor ->
                add("delete-predecessor-${predecessorState(action.predecessorIndex).label()}")
            WorkflowCheckpointLifecycleAction.DeleteUnconditional ->
                add("delete-unconditional-${if (before.current == null) "absent" else "present"}")
            WorkflowCheckpointLifecycleAction.Observe ->
                add("observe-${if (before.current == null) "absent" else "present"}")
        }
    }

    private fun ModeledRecoveryState?.label(): String = this?.name?.lowercase() ?: "absent"
}
