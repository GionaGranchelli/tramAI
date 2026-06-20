package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.spring.sovereign.ops.SovereignOpsOutboxWorkerProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SovereignOpsAuditOutboxWorkerStatusStoreTest {

    @Test
    fun `initial snapshot reflects worker properties and has no cycle state`() {
        val properties = SovereignOpsOutboxWorkerProperties(
            enabled = true,
            initialDelay = Duration.ofSeconds(10),
            interval = Duration.ofSeconds(60),
            batchSize = 42,
            recoverPrepared = true,
            dispatchPending = false,
        )
        val store = InMemorySovereignOpsAuditOutboxWorkerStatusStore(properties)
        val snapshot = store.snapshot()

        assertThat(snapshot.enabled).isTrue()
        assertThat(snapshot.running).isFalse()
        assertThat(snapshot.recoverPreparedEnabled).isTrue()
        assertThat(snapshot.dispatchPendingEnabled).isFalse()
        assertThat(snapshot.batchSize).isEqualTo(42)
        assertThat(snapshot.intervalMillis).isEqualTo(60000)
        assertThat(snapshot.initialDelayMillis).isEqualTo(10000)
        assertThat(snapshot.lastCycleStartedAt).isNull()
        assertThat(snapshot.lastCycleCompletedAt).isNull()
        assertThat(snapshot.lastCycleDurationMillis).isNull()
        assertThat(snapshot.lastRecovered).isNull()
        assertThat(snapshot.lastDispatched).isNull()
        assertThat(snapshot.lastFailure).isNull()
        assertThat(snapshot.lastFailureAt).isNull()
        assertThat(snapshot.totalCyclesCompleted).isZero()
        assertThat(snapshot.totalCyclesFailed).isZero()
    }

    @Test
    fun `recordCycleCompleted updates last summary and increments completed counter`() {
        val store = InMemorySovereignOpsAuditOutboxWorkerStatusStore(
            SovereignOpsOutboxWorkerProperties(),
        )

        val startedAt = Instant.parse("2026-06-01T00:00:00Z")
        val completedAt = Instant.parse("2026-06-01T00:00:05Z")

        val summary = SovereignOpsAuditOutboxWorkerRunSummary(
            recovered = SovereignOpsAuditOutboxRecoverySummary(
                inspected = 10,
                movedToPending = 3,
            ),
            dispatched = SovereignOpsAuditOutboxDispatchResult(
                claimed = 5,
                emitted = 4,
                failedRetryable = 1,
                failedPermanent = 0,
            ),
            failure = null,
            startedAt = startedAt,
            completedAt = completedAt,
        )

        store.recordCycleCompleted(summary)
        val snapshot = store.snapshot()

        assertThat(snapshot.totalCyclesCompleted).isEqualTo(1)
        assertThat(snapshot.totalCyclesFailed).isZero()
        assertThat(snapshot.lastCycleStartedAt).isEqualTo(startedAt)
        assertThat(snapshot.lastCycleCompletedAt).isEqualTo(completedAt)
        assertThat(snapshot.lastCycleDurationMillis).isEqualTo(5000)
        val recovered = snapshot.lastRecovered ?: error("expected recovery summary")
        assertThat(recovered.inspected).isEqualTo(10)
        assertThat(recovered.movedToPending).isEqualTo(3)
        val dispatched = snapshot.lastDispatched ?: error("expected dispatch result")
        assertThat(dispatched.emitted).isEqualTo(4)
        assertThat(snapshot.lastFailure).isNull()
        assertThat(snapshot.lastFailureAt).isNull()
    }

    @Test
    fun `recordCycleFailed stores sanitized failure only`() {
        val store = InMemorySovereignOpsAuditOutboxWorkerStatusStore(
            SovereignOpsOutboxWorkerProperties(),
        )

        store.recordCycleFailed("recoverPrepared", "IllegalStateException")
        val snapshot = store.snapshot()

        assertThat(snapshot.totalCyclesFailed).isEqualTo(1)
        assertThat(snapshot.totalCyclesCompleted).isZero()
        val failure = snapshot.lastFailure ?: error("expected failure summary")
        assertThat(failure.action).isEqualTo("recoverPrepared")
        assertThat(failure.errorCode).isEqualTo("IllegalStateException")
        assertThat(failure.errorCode).doesNotContain("sensitive")
        assertThat(failure.errorCode).doesNotContain("/secret/path")
        assertThat(snapshot.lastFailureAt).isNotNull()
    }

    @Test
    fun `recordCycleFailed does not reuse old cycle timestamps`() {
        val store = InMemorySovereignOpsAuditOutboxWorkerStatusStore(
            SovereignOpsOutboxWorkerProperties(),
        )

        // Record a successful cycle first
        val startedAt = Instant.parse("2026-06-01T00:00:00Z")
        val completedAt = Instant.parse("2026-06-01T00:00:05Z")
        store.recordCycleCompleted(
            SovereignOpsAuditOutboxWorkerRunSummary(
                recovered = SovereignOpsAuditOutboxRecoverySummary(inspected = 1),
                dispatched = SovereignOpsAuditOutboxDispatchResult(
                    claimed = 1, emitted = 1, failedRetryable = 0, failedPermanent = 0,
                ),
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )

        // Now record a failure — timestamps should NOT be reused
        store.recordCycleFailed("recoverPrepared", "IllegalStateException")
        val snapshot = store.snapshot()

        // Failure has its own timestamp distinct from the old cycle timestamps
        assertThat(snapshot.lastFailure).isNotNull()
        assertThat(snapshot.lastFailureAt).isNotNull()
        assertThat(snapshot.lastCycleStartedAt).isEqualTo(startedAt)
        assertThat(snapshot.lastCycleCompletedAt).isEqualTo(completedAt)
        // The failure timestamp should be after the cycle timestamps
        assertThat(snapshot.lastFailureAt).isAfter(completedAt)
    }

    @Test
    fun `markLifecycleStarted and markLifecycleStopped update running state`() {
        val store = InMemorySovereignOpsAuditOutboxWorkerStatusStore(
            SovereignOpsOutboxWorkerProperties(),
        )

        assertThat(store.snapshot().running).isFalse()

        store.markLifecycleStarted()
        assertThat(store.snapshot().running).isTrue()

        store.markLifecycleStopped()
        assertThat(store.snapshot().running).isFalse()
    }

    @Test
    fun `multiple successful cycles increment the completed counter`() {
        val store = InMemorySovereignOpsAuditOutboxWorkerStatusStore(
            SovereignOpsOutboxWorkerProperties(),
        )
        val clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)

        store.recordCycleCompleted(
            SovereignOpsAuditOutboxWorkerRunSummary(
                recovered = SovereignOpsAuditOutboxRecoverySummary(inspected = 1),
                dispatched = SovereignOpsAuditOutboxDispatchResult(
                    claimed = 1,
                    emitted = 1,
                    failedRetryable = 0,
                    failedPermanent = 0,
                ),
                startedAt = clock.instant(),
                completedAt = clock.instant().plusSeconds(1),
            ),
        )

        store.recordCycleCompleted(
            SovereignOpsAuditOutboxWorkerRunSummary(
                recovered = SovereignOpsAuditOutboxRecoverySummary(inspected = 2),
                dispatched = SovereignOpsAuditOutboxDispatchResult(
                    claimed = 2,
                    emitted = 2,
                    failedRetryable = 0,
                    failedPermanent = 0,
                ),
                startedAt = clock.instant().plusSeconds(10),
                completedAt = clock.instant().plusSeconds(11),
            ),
        )

        assertThat(store.snapshot().totalCyclesCompleted).isEqualTo(2)
    }
}
