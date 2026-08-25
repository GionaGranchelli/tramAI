package dev.tramai.orchestration

/**
 * Pure independent oracle for the worker lifecycle (Epic 8.2c).
 *
 * The model is deliberately NOT derived from production internals: it
 * reasons over observable lifecycle facts (phase, generation, registry
 * presence, accepting-work, root ownership, event counts) and predicts
 * what a conforming [TramaiWorker] must observe after every action.
 *
 * Failure is a first-class outcome carrying post-failure state: a failed
 * startup (registration throws) must roll the lifecycle back to STOPPED —
 * no started event, no hook, no accepting work, root discarded, retryable.
 *
 * Sequential semantics (the corpus is synchronous): start completes
 * atomically to RUNNING, shutdown completes atomically to STOPPED, crash
 * is a distinct terminal-ish phase that retains the registry record (so it
 * can go stale and be taken over) while stopping work.
 */
internal enum class WorkerLifecyclePhase {
    STOPPED,
    STARTING,
    RUNNING,
    SHUTTING_DOWN,
    CRASHED,
}

internal enum class WorkerLifecycleFailureKind {
    REGISTRATION_FAILED,
}

internal sealed interface WorkerLifecycleOutcome {
    data class Success(val next: WorkerLifecycleModel) : WorkerLifecycleOutcome
    data class Failure(val kind: WorkerLifecycleFailureKind, val next: WorkerLifecycleModel) : WorkerLifecycleOutcome
}

internal enum class WorkerLifecycleAction {
    START,
    START_AGAIN,
    SHUTDOWN,
    SHUTDOWN_AGAIN,
    CRASH,
    SHUTDOWN_AFTER_CRASH,
    CLOSE,
}

internal data class WorkerLifecycleModel(
    val phase: WorkerLifecyclePhase,
    val generation: Long,
    val registered: Boolean,
    val acceptingWork: Boolean,
    val rootOwned: Boolean,
    val workerStartedEvents: Int,
    val shutdownStartedEvents: Int,
    val shutdownCompleteEvents: Int,
    val workerStoppedEvents: Int,
    val registrations: Int,
    val unregistrations: Int,
) {
    companion object {
        fun stopped() = WorkerLifecycleModel(
            phase = WorkerLifecyclePhase.STOPPED,
            generation = 0L,
            registered = false,
            acceptingWork = false,
            rootOwned = false,
            workerStartedEvents = 0,
            shutdownStartedEvents = 0,
            shutdownCompleteEvents = 0,
            workerStoppedEvents = 0,
            registrations = 0,
            unregistrations = 0,
        )
    }

    /** Invariants that must hold after EVERY action. */
    fun invariantViolation(): String? = when {
        generation < 0L -> "generation negative"
        workerStartedEvents < 0 || shutdownStartedEvents < 0 || shutdownCompleteEvents < 0 || workerStoppedEvents < 0 -> "negative event count"
        registrations < 0 || unregistrations < 0 -> "negative registry counter"
        shutdownCompleteEvents > shutdownStartedEvents -> "shutdown complete before started"
        workerStoppedEvents > shutdownStartedEvents -> "worker stopped before shutdown started"
        registrations < unregistrations -> "more unregistrations than registrations"
        phase == WorkerLifecyclePhase.RUNNING && !registered -> "running without registry presence"
        phase == WorkerLifecyclePhase.RUNNING && !rootOwned -> "running without root ownership"
        phase == WorkerLifecyclePhase.STOPPED && registered -> "stopped with registry presence"
        phase == WorkerLifecyclePhase.STOPPED && rootOwned -> "stopped with root ownership"
        else -> null
    }

    fun apply(action: WorkerLifecycleAction, registrationFails: Boolean = false): WorkerLifecycleOutcome = when (action) {
        WorkerLifecycleAction.START,
        WorkerLifecycleAction.START_AGAIN,
        -> applyStart(registrationFails)

        WorkerLifecycleAction.SHUTDOWN,
        WorkerLifecycleAction.SHUTDOWN_AGAIN,
        WorkerLifecycleAction.SHUTDOWN_AFTER_CRASH,
        WorkerLifecycleAction.CLOSE,
        -> applyShutdown()

        WorkerLifecycleAction.CRASH -> applyCrash()
    }

    private fun applyStart(registrationFails: Boolean): WorkerLifecycleOutcome {
        if (phase != WorkerLifecyclePhase.STOPPED) {
            // RUNNING start is idempotent; SHUTTING_DOWN/CRASHED start is a no-op.
            return WorkerLifecycleOutcome.Success(this)
        }
        if (registrationFails) {
            // Failed startup rolls back to STOPPED: no started event, no hook,
            // no accepting work, root discarded, subsequent start retryable.
            return WorkerLifecycleOutcome.Failure(
                kind = WorkerLifecycleFailureKind.REGISTRATION_FAILED,
                next = this,
            )
        }
        return WorkerLifecycleOutcome.Success(
            next = copy(
                phase = WorkerLifecyclePhase.RUNNING,
                generation = generation + 1L,
                registered = true,
                acceptingWork = true,
                rootOwned = true,
                workerStartedEvents = workerStartedEvents + 1,
                registrations = registrations + 1,
            ),
        )
    }

    private fun applyShutdown(): WorkerLifecycleOutcome {
        return when (phase) {
            WorkerLifecyclePhase.STOPPED -> WorkerLifecycleOutcome.Success(this)
            // Shutdown during STARTING aborts the startup: the generation
            // never reaches RUNNING and no started event fires.
            WorkerLifecyclePhase.STARTING,
            WorkerLifecyclePhase.RUNNING,
            WorkerLifecyclePhase.CRASHED,
            -> WorkerLifecycleOutcome.Success(
                next = copy(
                    phase = WorkerLifecyclePhase.STOPPED,
                    registered = false,
                    acceptingWork = false,
                    rootOwned = false,
                    shutdownStartedEvents = shutdownStartedEvents + 1,
                    shutdownCompleteEvents = shutdownCompleteEvents + 1,
                    workerStoppedEvents = workerStoppedEvents + 1,
                    unregistrations = unregistrations + 1,
                ),
            )

            WorkerLifecyclePhase.SHUTTING_DOWN -> WorkerLifecycleOutcome.Success(this)
        }
    }

    private fun applyCrash(): WorkerLifecycleOutcome {
        if (phase != WorkerLifecyclePhase.RUNNING) {
            return WorkerLifecycleOutcome.Success(this)
        }
        return WorkerLifecycleOutcome.Success(
            next = copy(
                phase = WorkerLifecyclePhase.CRASHED,
                acceptingWork = false,
                // Registry record retained after crash so it can become stale
                // and be taken over — crash is not a graceful departure.
                registered = true,
                rootOwned = true,
            ),
        )
    }
}
