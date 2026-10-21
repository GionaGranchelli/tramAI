package dev.tramai.core.observation.attempt

/**
 * In-memory [AttemptObservationStore] for tests and single-process use.
 *
 * Idempotency is keyed exactly as the durable implementation keys it — `(invocationId, attempt)` —
 * so a test that passes here exercises the same uniqueness rule the database enforces.
 */
class InMemoryAttemptObservationStore : AttemptObservationStore {
    private val attempts = LinkedHashMap<Pair<String, Int>, ModelAttemptObservation>()

    override fun append(observation: ModelAttemptObservation): AttemptAppendOutcome =
        synchronized(attempts) {
            val key = observation.invocationId to observation.attempt
            if (attempts.containsKey(key)) {
                AttemptAppendOutcome.ALREADY_RECORDED
            } else {
                attempts[key] = observation
                AttemptAppendOutcome.RECORDED
            }
        }

    override fun findByWorkflowRun(workflowRunId: String): AttemptHistory =
        synchronized(attempts) {
            AttemptHistory.Available(
                attempts.values
                    .filter { it.workflowRunId == workflowRunId }
                    .sortedWith(compareBy({ it.startedAt }, { it.attempt })),
            )
        }

    override fun findByInvocation(invocationId: String): AttemptHistory =
        synchronized(attempts) {
            AttemptHistory.Available(
                attempts.values
                    .filter { it.invocationId == invocationId }
                    .sortedBy { it.attempt },
            )
        }
}
