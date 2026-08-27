package dev.tramai.orchestration

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Deterministic creation-order authority for step attempts with EQUAL persisted
 * startedAt (same real millisecond — the fake-lease takeover path can complete
 * within one ms). Invariant: "latest" and listing order must use a stable
 * creation-order authority, never random identity (the attemptId UUID).
 *
 * The attemptIds are chosen so UUID lexicographic order is the OPPOSITE of
 * insertion order: with the old (startedAt, stepName, attemptId) comparators
 * these assertions fail deterministically — no timing involved.
 */
class StepAttemptOrderingDiscriminatorTest {

    private fun attempt(
        attemptId: String,
        status: StepAttemptStatus,
    ) = StepAttemptRecord(
        runId = "w-1",
        stepName = "plan",
        attemptId = attemptId,
        workerId = "worker",
        leaseToken = "lease",
        status = status,
        startedAt = 1_000L,
        replayPolicy = ReplayPolicy.IDEMPOTENT,
    )

    @Test
    fun `listStepAttempts orders same-startedAt attempts by creation, never by attempt id`() {
        val store = InMemoryWorkflowCheckpointStore()
        runBlocking {
            val original = attempt("zzz-original", StepAttemptStatus.UNKNOWN)
            val rerun = attempt("aaa-rerun", StepAttemptStatus.COMPLETED)
            store.recordStepAttempt(original)
            store.recordStepAttempt(rerun)

            val listed = store.listStepAttempts("w-1")
            assertThat(listed.map { it.attemptId })
                .withFailMessage(
                    "Equal startedAt must order by creation (original before re-run), " +
                        "never by the random attemptId UUID",
                )
                .containsExactly("zzz-original", "aaa-rerun")
        }
    }

    @Test
    fun `latestStepAttempt prefers the last-created attempt on equal startedAt`() {
        val store = InMemoryWorkflowCheckpointStore()
        runBlocking {
            val original = attempt("zzz-original", StepAttemptStatus.UNKNOWN)
            val rerun = attempt("aaa-rerun", StepAttemptStatus.COMPLETED)
            store.recordStepAttempt(original)
            store.recordStepAttempt(rerun)

            val latest = store.latestStepAttempt("w-1", "plan")
            assertThat(latest?.attemptId)
                .withFailMessage(
                    "Equal startedAt must resolve 'latest' to the last-created attempt " +
                        "(the re-run), never the random attemptId UUID",
                )
                .isEqualTo("aaa-rerun")
        }
    }
}
