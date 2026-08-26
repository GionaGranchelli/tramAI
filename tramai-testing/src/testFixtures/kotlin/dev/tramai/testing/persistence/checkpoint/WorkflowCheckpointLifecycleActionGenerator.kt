package dev.tramai.testing.persistence.checkpoint

import kotlin.random.Random

/** Deterministic, state-aware checkpoint lifecycle corpus generator. */
internal object WorkflowCheckpointLifecycleActionGenerator {
    const val SEED_COUNT: Long = 32L
    const val ACTIONS_PER_SEQUENCE: Int = 32

    fun generate(
        seed: Long,
        count: Int = ACTIONS_PER_SEQUENCE,
    ): List<WorkflowCheckpointLifecycleAction> {
        val random = Random(seed)
        var model = WorkflowCheckpointLifecycleModel.empty()
        return buildList {
            repeat(count) { step ->
                val action = forcedCoverageAction(step, seed, model) ?: pick(random, model)
                add(action)
                model = model.apply(action).next
            }
        }
    }

    private fun forcedCoverageAction(
        step: Int,
        seed: Long,
        model: WorkflowCheckpointLifecycleModel,
    ): WorkflowCheckpointLifecycleAction? = when (step) {
        0 -> WorkflowCheckpointLifecycleAction.Observe
        1 -> WorkflowCheckpointLifecycleAction.DeleteUnconditional
        2 -> WorkflowCheckpointLifecycleAction.Create
        3 -> WorkflowCheckpointLifecycleAction.Create
        4 -> WorkflowCheckpointLifecycleAction.UpdateStaleRevision
        5 -> WorkflowCheckpointLifecycleAction.UpdateCurrent(2, "normal-$seed")
        6 -> WorkflowCheckpointLifecycleAction.RequireRecoveryStaleRevision
        7 -> WorkflowCheckpointLifecycleAction.RequireRecoveryCurrent
        8 -> WorkflowCheckpointLifecycleAction.UpdateCurrent(3, "required-$seed")
        9 -> WorkflowCheckpointLifecycleAction.ClearRecoveryStaleRevision
        10 -> WorkflowCheckpointLifecycleAction.ClearRecoveryCurrent
        11 -> WorkflowCheckpointLifecycleAction.ClearRecoveryCurrent
        12 -> WorkflowCheckpointLifecycleAction.DeleteStaleRevision
        13 -> WorkflowCheckpointLifecycleAction.DeleteCurrent
        14 -> WorkflowCheckpointLifecycleAction.Observe
        15 -> WorkflowCheckpointLifecycleAction.Create
        16 -> WorkflowCheckpointLifecycleAction.UpdatePredecessorGeneration(0)
        17 -> WorkflowCheckpointLifecycleAction.RequireRecoveryPredecessor(0)
        18 -> WorkflowCheckpointLifecycleAction.ClearRecoveryPredecessor(0)
        19 -> WorkflowCheckpointLifecycleAction.DeletePredecessor(0)
        20 -> WorkflowCheckpointLifecycleAction.RequireRecoveryCurrent
        21 -> WorkflowCheckpointLifecycleAction.DeleteCurrent
        22 -> WorkflowCheckpointLifecycleAction.Create
        23 -> WorkflowCheckpointLifecycleAction.UpdatePredecessorGeneration(model.predecessors.lastIndex)
        24 -> WorkflowCheckpointLifecycleAction.RequireRecoveryPredecessor(model.predecessors.lastIndex)
        25 -> WorkflowCheckpointLifecycleAction.ClearRecoveryPredecessor(model.predecessors.lastIndex)
        26 -> WorkflowCheckpointLifecycleAction.DeletePredecessor(model.predecessors.lastIndex)
        27 -> WorkflowCheckpointLifecycleAction.DeleteUnconditional
        28 -> WorkflowCheckpointLifecycleAction.DeleteUnconditional
        29 -> WorkflowCheckpointLifecycleAction.Create
        30 -> WorkflowCheckpointLifecycleAction.Observe
        else -> null
    }

    private fun pick(
        random: Random,
        model: WorkflowCheckpointLifecycleModel,
    ): WorkflowCheckpointLifecycleAction {
        val predecessorIndex = if (model.predecessors.isEmpty()) 0 else random.nextInt(model.predecessors.size)
        if (model.current == null) {
            return when (random.nextInt(5)) {
                0, 1 -> WorkflowCheckpointLifecycleAction.Create
                2 -> WorkflowCheckpointLifecycleAction.DeleteUnconditional
                3 -> WorkflowCheckpointLifecycleAction.Observe
                else -> WorkflowCheckpointLifecycleAction.DeleteStaleRevision
            }
        }
        return when (random.nextInt(15)) {
            0 -> WorkflowCheckpointLifecycleAction.UpdateCurrent(random.nextInt(1, 10), "random-${random.nextInt()}")
            1 -> WorkflowCheckpointLifecycleAction.UpdateStaleRevision
            2 -> WorkflowCheckpointLifecycleAction.UpdatePredecessorGeneration(predecessorIndex)
            3 -> WorkflowCheckpointLifecycleAction.RequireRecoveryCurrent
            4 -> WorkflowCheckpointLifecycleAction.RequireRecoveryStaleRevision
            5 -> WorkflowCheckpointLifecycleAction.RequireRecoveryPredecessor(predecessorIndex)
            6 -> WorkflowCheckpointLifecycleAction.ClearRecoveryCurrent
            7 -> WorkflowCheckpointLifecycleAction.ClearRecoveryStaleRevision
            8 -> WorkflowCheckpointLifecycleAction.ClearRecoveryPredecessor(predecessorIndex)
            9 -> WorkflowCheckpointLifecycleAction.DeleteCurrent
            10 -> WorkflowCheckpointLifecycleAction.DeleteStaleRevision
            11 -> WorkflowCheckpointLifecycleAction.DeletePredecessor(predecessorIndex)
            12 -> WorkflowCheckpointLifecycleAction.DeleteUnconditional
            13 -> WorkflowCheckpointLifecycleAction.Create
            else -> WorkflowCheckpointLifecycleAction.Observe
        }
    }
}
