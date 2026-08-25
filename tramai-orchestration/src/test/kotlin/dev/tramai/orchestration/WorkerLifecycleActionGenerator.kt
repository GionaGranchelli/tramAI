package dev.tramai.orchestration

/**
 * Deterministic 32 × 32 worker-lifecycle action corpus (Epic 8.2c).
 *
 * The corpus is synchronous: start/shutdown complete atomically in the
 * model, so the generated sequences exercise legal sequential histories
 * (multi-generation, duplicate starts/shutdowns, crash recovery, close
 * equivalence). The asynchronous interleavings (shutdown during suspended
 * registration, concurrent starts/shutdowns, start during drain) are
 * pinned by dedicated properties, not by the ordinary generator.
 *
 * Forced archetypes per `seed % 6` guarantee every interesting shape is
 * present in the corpus regardless of random drift; the rest is a
 * state-aware free run (each candidate action is applied to the running
 * model so the emitted sequence stays legal).
 */
internal object WorkerLifecycleActionGenerator {
    const val SEED_COUNT = 32L
    const val ACTION_COUNT = 32

    fun generate(seed: Long, initial: WorkerLifecycleModel = WorkerLifecycleModel.stopped()): List<WorkerLifecycleAction> {
        val rng = kotlin.random.Random(seed)
        val actions = ArrayList<WorkerLifecycleAction>(ACTION_COUNT)
        var model = initial

        val forcedPrefix: List<WorkerLifecycleAction> = when (seed % 6) {
            0L -> listOf(
                WorkerLifecycleAction.START,
                WorkerLifecycleAction.SHUTDOWN,
                WorkerLifecycleAction.START,
                WorkerLifecycleAction.SHUTDOWN,
            )
            1L -> listOf(
                WorkerLifecycleAction.SHUTDOWN, // before start — no-op
                WorkerLifecycleAction.START,
                WorkerLifecycleAction.START_AGAIN, // idempotent
                WorkerLifecycleAction.SHUTDOWN,
            )
            2L -> listOf(
                WorkerLifecycleAction.START,
                WorkerLifecycleAction.CRASH,
                WorkerLifecycleAction.SHUTDOWN_AFTER_CRASH,
                WorkerLifecycleAction.START,
            )
            3L -> listOf(
                WorkerLifecycleAction.START,
                WorkerLifecycleAction.SHUTDOWN,
                WorkerLifecycleAction.START,
                WorkerLifecycleAction.SHUTDOWN,
                WorkerLifecycleAction.START,
            )
            4L -> listOf(
                WorkerLifecycleAction.START,
                WorkerLifecycleAction.START_AGAIN,
                WorkerLifecycleAction.SHUTDOWN,
                WorkerLifecycleAction.SHUTDOWN_AGAIN,
            )
            else -> listOf(
                WorkerLifecycleAction.START,
                WorkerLifecycleAction.CLOSE, // close == shutdown
                WorkerLifecycleAction.START,
            )
        }
        forcedPrefix.forEach { action ->
            actions.add(action)
            model = applyLegal(action, model)
        }

        val alphabet = WorkerLifecycleAction.entries
        while (actions.size < ACTION_COUNT) {
            val action = alphabet[rng.nextInt(alphabet.size)]
            actions.add(action)
            model = applyLegal(action, model)
        }
        return actions
    }

    /** Applies the action to the model, keeping Failure results in place. */
    private fun applyLegal(action: WorkerLifecycleAction, model: WorkerLifecycleModel): WorkerLifecycleModel =
        when (val outcome = model.apply(action)) {
            is WorkerLifecycleOutcome.Success -> outcome.next
            is WorkerLifecycleOutcome.Failure -> outcome.next
        }
}
