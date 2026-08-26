package dev.tramai.testing.persistence.lease

import kotlin.random.Random

/** Deterministic, state-aware lease lifecycle corpus generator. */
internal object WorkflowLeaseLifecycleActionGenerator {

    const val SEED_COUNT: Long = 32L
    const val ACTIONS_PER_SEQUENCE: Int = 32

    private val owners = listOf("worker-a", "worker-b")
    private val revisions = listOf<Long?>(null, 1, 7, null)

    fun generate(
        seed: Long,
        count: Int = ACTIONS_PER_SEQUENCE,
        initialNow: Long,
        durationMillis: Long,
    ): List<WorkflowLeaseLifecycleAction> {
        val rng = Random(seed)
        var model = WorkflowLeaseLifecycleModel.absent(initialNow)
        return buildList {
            repeat(count) { step ->
                val action = forcedCoverageAction(step, seed, model) ?: pick(rng, model)
                add(action)
                when (val outcome = model.apply(action, durationMillis)) {
                    is WorkflowLeaseLifecycleOutcome.Success -> model = outcome.next
                    is WorkflowLeaseLifecycleOutcome.NoOp -> model = outcome.next
                    is WorkflowLeaseLifecycleOutcome.Failure -> Unit
                }
            }
        }
    }

    /**
     * A short deterministic spine guarantees every primary discriminator in
     * the 32-seed corpus. Remaining actions are random but state-aware.
     */
    private fun forcedCoverageAction(
        step: Int,
        seed: Long,
        model: WorkflowLeaseLifecycleModel,
    ): WorkflowLeaseLifecycleAction? = when (step) {
        0 -> WorkflowLeaseLifecycleAction.Claim("worker-a", checkpointRevision = null)
        1 -> WorkflowLeaseLifecycleAction.Claim("worker-b", checkpointRevision = 1)
        2 -> WorkflowLeaseLifecycleAction.Claim("worker-a", checkpointRevision = 7)
        3 -> WorkflowLeaseLifecycleAction.RenewCurrent(checkpointRevision = 1)
        4 -> WorkflowLeaseLifecycleAction.RenewCurrentOldSnapshot(checkpointRevision = null)
        5 -> WorkflowLeaseLifecycleAction.RenewCurrent(checkpointRevision = 7)
        6 -> WorkflowLeaseLifecycleAction.AdvanceBeforeExpiry
        7 -> WorkflowLeaseLifecycleAction.RenewWrongOwner(model.currentGeneration())
        8 -> WorkflowLeaseLifecycleAction.ReleaseWrongOwner(model.currentGeneration())
        9 -> WorkflowLeaseLifecycleAction.RenewForgedToken(model.currentGeneration())
        10 -> WorkflowLeaseLifecycleAction.ReleaseForgedToken(model.currentGeneration())
        11 -> WorkflowLeaseLifecycleAction.AdvanceToExactExpiry
        12 -> WorkflowLeaseLifecycleAction.Claim(
            ownerId = if (seed % 2L == 0L) "worker-a" else "worker-b",
            checkpointRevision = null,
        )
        13 -> WorkflowLeaseLifecycleAction.RenewStalePredecessor(model.predecessors.first().generation)
        14 -> WorkflowLeaseLifecycleAction.ReleaseStalePredecessor(model.predecessors.first().generation)
        15 -> WorkflowLeaseLifecycleAction.AdvancePastExpiry
        16 -> WorkflowLeaseLifecycleAction.Claim(
            ownerId = model.predecessors.last().ownerId.otherOwner(),
            checkpointRevision = 7,
        )
        17 -> WorkflowLeaseLifecycleAction.ReleaseCurrent
        18 -> WorkflowLeaseLifecycleAction.Claim("worker-a", checkpointRevision = null)
        19 -> WorkflowLeaseLifecycleAction.RenewCurrent(checkpointRevision = 5)
        20 -> WorkflowLeaseLifecycleAction.ReleaseCurrentOldSnapshot
        21 -> WorkflowLeaseLifecycleAction.Claim("worker-b", checkpointRevision = 1)
        else -> null
    }

    private fun pick(
        rng: Random,
        model: WorkflowLeaseLifecycleModel,
    ): WorkflowLeaseLifecycleAction {
        val active = model.current
        if (active == null) {
            val target = model.predecessors.lastOrNull()?.generation ?: model.generation
            return when (rng.nextInt(100)) {
                in 0..51 -> WorkflowLeaseLifecycleAction.Claim(pickOwner(rng), pickRevision(rng))
                in 52..61 -> WorkflowLeaseLifecycleAction.ReleaseCurrent
                in 62..69 -> WorkflowLeaseLifecycleAction.ReleaseCurrentOldSnapshot
                in 70..77 -> WorkflowLeaseLifecycleAction.ReleaseWrongOwner(target)
                in 78..85 -> WorkflowLeaseLifecycleAction.ReleaseForgedToken(target)
                in 86..91 -> WorkflowLeaseLifecycleAction.RenewCurrent(pickRevision(rng))
                in 92..95 -> WorkflowLeaseLifecycleAction.AdvancePastExpiry
                else -> WorkflowLeaseLifecycleAction.ObserveCurrent
            }
        }

        val predecessor = model.predecessors.randomOrNull(rng)
        return when (rng.nextInt(100)) {
            in 0..10 -> WorkflowLeaseLifecycleAction.Claim(active.ownerId, pickRevision(rng))
            in 11..20 -> WorkflowLeaseLifecycleAction.Claim(active.ownerId.otherOwner(), pickRevision(rng))
            in 21..36 -> WorkflowLeaseLifecycleAction.RenewCurrent(pickRevision(rng))
            in 37..44 -> if (model.hasOlderSnapshot) {
                WorkflowLeaseLifecycleAction.RenewCurrentOldSnapshot(pickRevision(rng))
            } else {
                WorkflowLeaseLifecycleAction.RenewCurrent(pickRevision(rng))
            }
            in 45..50 -> WorkflowLeaseLifecycleAction.RenewWrongOwner(active.generation)
            in 51..56 -> WorkflowLeaseLifecycleAction.RenewForgedToken(active.generation)
            in 57..62 -> if (predecessor != null) {
                WorkflowLeaseLifecycleAction.RenewStalePredecessor(predecessor.generation)
            } else {
                WorkflowLeaseLifecycleAction.RenewWrongOwner(active.generation)
            }
            in 63..68 -> WorkflowLeaseLifecycleAction.ReleaseWrongOwner(active.generation)
            in 69..74 -> WorkflowLeaseLifecycleAction.ReleaseForgedToken(active.generation)
            in 75..79 -> if (predecessor != null) {
                WorkflowLeaseLifecycleAction.ReleaseStalePredecessor(predecessor.generation)
            } else {
                WorkflowLeaseLifecycleAction.ReleaseForgedToken(active.generation)
            }
            in 80..84 -> if (model.hasOlderSnapshot) {
                WorkflowLeaseLifecycleAction.ReleaseCurrentOldSnapshot
            } else {
                WorkflowLeaseLifecycleAction.ReleaseCurrent
            }
            in 85..88 -> WorkflowLeaseLifecycleAction.ReleaseCurrent
            in 89..92 -> WorkflowLeaseLifecycleAction.AdvanceBeforeExpiry
            in 93..95 -> WorkflowLeaseLifecycleAction.AdvanceToExactExpiry
            in 96..97 -> WorkflowLeaseLifecycleAction.AdvancePastExpiry
            else -> WorkflowLeaseLifecycleAction.ObserveCurrent
        }
    }

    private fun WorkflowLeaseLifecycleModel.currentGeneration(): Long =
        current?.generation ?: predecessors.lastOrNull()?.generation ?: generation

    private fun pickOwner(rng: Random): String = owners[rng.nextInt(owners.size)]

    private fun pickRevision(rng: Random): Long? = revisions[rng.nextInt(revisions.size)]

    private fun String.otherOwner(): String = owners.first { it != this }

    private fun <T> List<T>.randomOrNull(rng: Random): T? = if (isEmpty()) null else this[rng.nextInt(size)]
}
