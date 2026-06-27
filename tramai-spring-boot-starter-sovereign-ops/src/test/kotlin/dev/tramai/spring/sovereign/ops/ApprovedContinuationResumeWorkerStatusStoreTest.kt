package dev.tramai.spring.sovereign.ops

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ApprovedContinuationResumeWorkerStatusStoreTest {

    @Test
    fun `snapshot reflects initial state`() {
        val store = InMemoryApprovedContinuationResumeWorkerStatusStore(
            SovereignOpsApprovedResumeWorkerProperties(
                enabled = true,
                lifecycleEnabled = true,
                batchSize = 17,
                interval = Duration.ofSeconds(9),
            ),
        )

        val snapshot = store.snapshot()

        assertThat(snapshot.enabled).isTrue()
        assertThat(snapshot.lifecycleEnabled).isTrue()
        assertThat(snapshot.running).isFalse()
        assertThat(snapshot.batchSize).isEqualTo(17)
        assertThat(snapshot.intervalMillis).isEqualTo(9000)
        assertThat(snapshot.lastCycleStartedAt).isNull()
        assertThat(snapshot.lastCycleCompletedAt).isNull()
        assertThat(snapshot.lastCycleDurationMillis).isNull()
        assertThat(snapshot.lastResult).isNull()
        assertThat(snapshot.lastFailureAt).isNull()
        assertThat(snapshot.lastFailureErrorCode).isNull()
        assertThat(snapshot.totalCyclesCompleted).isZero()
        assertThat(snapshot.totalCyclesFailed).isZero()
    }

    @Test
    fun `markLifecycleStarted sets running true`() {
        val store = InMemoryApprovedContinuationResumeWorkerStatusStore(
            SovereignOpsApprovedResumeWorkerProperties(),
        )

        store.markLifecycleStarted()

        assertThat(store.snapshot().running).isTrue()
    }

    @Test
    fun `recordCycleCompleted updates snapshot`() {
        val store = InMemoryApprovedContinuationResumeWorkerStatusStore(
            SovereignOpsApprovedResumeWorkerProperties(),
        )
        val before = Instant.now()
        val result = ApprovedContinuationResumeWorkerResult(
            scanned = 5,
            resumed = 3,
            skipped = 1,
            failed = 1,
        )

        store.recordCycleCompleted(
            workerId = "worker-a",
            result = result,
            duration = Duration.ofMillis(125),
        )

        val snapshot = store.snapshot()
        assertThat(snapshot.totalCyclesCompleted).isEqualTo(1)
        assertThat(snapshot.totalCyclesFailed).isZero()
        assertThat(snapshot.lastResult).isEqualTo(result)
        assertThat(snapshot.lastCycleDurationMillis).isEqualTo(125)
        assertThat(snapshot.lastCycleCompletedAt).isNotNull()
        assertThat(snapshot.lastCycleStartedAt).isNotNull()
        assertThat(snapshot.lastCycleStartedAt).isAfterOrEqualTo(before.minusMillis(125))
    }

    @Test
    fun `recordCycleFailed updates snapshot`() {
        val store = InMemoryApprovedContinuationResumeWorkerStatusStore(
            SovereignOpsApprovedResumeWorkerProperties(),
        )

        store.recordCycleFailed("worker-a", IllegalStateException("sensitive token"))

        val snapshot = store.snapshot()
        assertThat(snapshot.totalCyclesCompleted).isZero()
        assertThat(snapshot.totalCyclesFailed).isEqualTo(1)
        assertThat(snapshot.lastFailureAt).isNotNull()
        assertThat(snapshot.lastFailureErrorCode).isEqualTo("IllegalStateException")
        assertThat(snapshot.lastFailureErrorCode).doesNotContain("sensitive")
    }

    @Test
    fun `thread safety is preserved under concurrent updates`() {
        val store = InMemoryApprovedContinuationResumeWorkerStatusStore(
            SovereignOpsApprovedResumeWorkerProperties(),
        )
        val executor = Executors.newFixedThreadPool(8)
        val tasks = 200
        val latch = CountDownLatch(tasks)

        repeat(tasks) { index ->
            executor.submit {
                try {
                    if (index % 2 == 0) {
                        store.recordCycleCompleted(
                            workerId = "worker-a",
                            result = ApprovedContinuationResumeWorkerResult(1, 1, 0, 0),
                            duration = Duration.ofMillis(10),
                        )
                    } else {
                        store.recordCycleFailed("worker-a", IllegalArgumentException("boom"))
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
        executor.shutdown()
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()

        val snapshot = store.snapshot()
        assertThat(snapshot.totalCyclesCompleted).isEqualTo(100)
        assertThat(snapshot.totalCyclesFailed).isEqualTo(100)
        assertThat(snapshot.lastCycleDurationMillis).isEqualTo(10)
        assertThat(snapshot.lastFailureErrorCode).isIn("IllegalArgumentException", null)
    }
}
