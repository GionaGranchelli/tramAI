package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueSnapshot
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueStatusStore
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorkerResult
import dev.tramai.spring.sovereign.ops.InMemoryApprovedContinuationResumeWorkerStatusStore
import dev.tramai.spring.sovereign.ops.SovereignOpsApprovedResumeWorkerProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status
import java.time.Duration

class ApprovedContinuationResumeWorkerHealthIndicatorTest {

    @Test
    fun `disabled lifecycle reports unknown`() {
        val health = ApprovedContinuationResumeWorkerHealthIndicator(
            statusStore(lifecycleEnabled = false),
        ).health()

        assertThat(health.status).isEqualTo(Status.UNKNOWN)
    }

    @Test
    fun `lifecycle enabled and running reports up`() {
        val store = statusStore(lifecycleEnabled = true)
        store.markLifecycleStarted()

        val health = ApprovedContinuationResumeWorkerHealthIndicator(store).health()

        assertThat(health.status).isEqualTo(Status.UP)
    }

    @Test
    fun `lifecycle enabled and not running reports down`() {
        val health = ApprovedContinuationResumeWorkerHealthIndicator(
            statusStore(lifecycleEnabled = true),
        ).health()

        assertThat(health.status).isEqualTo(Status.DOWN)
    }

    @Test
    fun `lifecycle enabled running with only failures reports down`() {
        val store = statusStore(lifecycleEnabled = true)
        store.markLifecycleStarted()
        store.recordCycleFailed("worker-a", IllegalStateException("secret"))

        val health = ApprovedContinuationResumeWorkerHealthIndicator(store).health()

        assertThat(health.status).isEqualTo(Status.DOWN)
    }

    @Test
    fun `lifecycle enabled running with mixed failures and success reports up`() {
        val store = statusStore(lifecycleEnabled = true)
        store.markLifecycleStarted()
        store.recordCycleFailed("worker-a", IllegalStateException("secret"))
        store.recordCycleCompleted(
            workerId = "worker-a",
            result = ApprovedContinuationResumeWorkerResult(4, 2, 1, 1),
            duration = Duration.ofMillis(25),
        )

        val health = ApprovedContinuationResumeWorkerHealthIndicator(store).health()

        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(health.details["totalCyclesCompleted"]).isEqualTo(1L)
        assertThat(health.details["totalCyclesFailed"]).isEqualTo(1L)
    }

    @Test
    fun `health details have expected keys`() {
        val store = statusStore(lifecycleEnabled = true)
        store.markLifecycleStarted()
        store.recordCycleCompleted(
            workerId = "worker-a",
            result = ApprovedContinuationResumeWorkerResult(3, 2, 1, 0),
            duration = Duration.ofMillis(20),
        )

        val health = ApprovedContinuationResumeWorkerHealthIndicator(store).health()

        assertThat(health.details.keys).contains(
            "enabled",
            "lifecycleEnabled",
            "running",
            "batchSize",
            "intervalMillis",
            "lastResultScanned",
            "lastResultResumed",
            "lastResultSkipped",
            "lastResultFailed",
            "lastCycleCompletedAt",
            "totalCyclesCompleted",
            "totalCyclesFailed",
        )
    }

    @Test
    fun `health details do not contain sensitive keys`() {
        val store = statusStore(lifecycleEnabled = true)
        store.markLifecycleStarted()
        store.recordCycleFailed("worker-a", IllegalStateException("secret-token"))
        store.recordCycleCompleted(
            workerId = "worker-a",
            result = ApprovedContinuationResumeWorkerResult(3, 1, 1, 1),
            duration = Duration.ofMillis(20),
        )

        val health = ApprovedContinuationResumeWorkerHealthIndicator(store).health()

        assertThat(health.details.keys).doesNotContain(
            "approvalId",
            "token",
            "resumeToken",
            "approvalIds",
            "metadata",
            "reason",
            "lastFailureErrorCode",
        )
    }

    @Test
    fun `health includes queue counts when queue status store is provided`() {
        val store = statusStore(lifecycleEnabled = true)
        store.markLifecycleStarted()
        store.recordCycleCompleted(
            workerId = "worker-a",
            result = successfulSummary(),
            duration = Duration.ofMillis(20),
        )
        val queueStore = FakeApprovedContinuationResumeQueueStatusStore(
            ApprovedContinuationResumeQueueSnapshot(
                eligibleNow = 3,
                delayedRetry = 1,
                activeLeases = 0,
                expiredLeases = 0,
                terminalFailures = 0,
                oldestEligibleAgeSeconds = 42,
                oldestRetryDueInSeconds = null,
                lastErrorCodeCounts = mapOf("IllegalStateException" to 2L),
            ),
        )

        val health = ApprovedContinuationResumeWorkerHealthIndicator(
            statusStore = store,
            queueStatusStore = queueStore,
        ).health()

        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(health.details["eligibleNow"]).isEqualTo(3L)
        assertThat(health.details["oldestEligibleAgeSeconds"]).isEqualTo(42L)
        assertThat(health.details).doesNotContainKeys("lastErrorCodeCounts")
    }

    @Test
    fun `health does not include last error code counts from queue snapshot`() {
        val store = statusStore(lifecycleEnabled = true)
        store.markLifecycleStarted()
        val queueStore = FakeApprovedContinuationResumeQueueStatusStore(
            ApprovedContinuationResumeQueueSnapshot(
                eligibleNow = 1,
                delayedRetry = 0,
                activeLeases = 0,
                expiredLeases = 0,
                terminalFailures = 1,
                oldestEligibleAgeSeconds = null,
                oldestRetryDueInSeconds = null,
                lastErrorCodeCounts = mapOf("TimeoutException" to 1L),
            ),
        )

        val health = ApprovedContinuationResumeWorkerHealthIndicator(
            statusStore = store,
            queueStatusStore = queueStore,
        ).health()

        assertThat(health.details).doesNotContainKey("lastErrorCodeCounts")
    }

    private fun statusStore(lifecycleEnabled: Boolean): InMemoryApprovedContinuationResumeWorkerStatusStore =
        InMemoryApprovedContinuationResumeWorkerStatusStore(
            SovereignOpsApprovedResumeWorkerProperties(
                enabled = true,
                lifecycleEnabled = lifecycleEnabled,
                batchSize = 11,
                interval = Duration.ofSeconds(7),
            ),
        )

    private fun successfulSummary(): ApprovedContinuationResumeWorkerResult =
        ApprovedContinuationResumeWorkerResult(4, 2, 1, 1)

    private class FakeApprovedContinuationResumeQueueStatusStore(
        private val snapshot: ApprovedContinuationResumeQueueSnapshot,
    ) : ApprovedContinuationResumeQueueStatusStore {
        override suspend fun snapshot(now: java.time.Instant): ApprovedContinuationResumeQueueSnapshot = snapshot
    }
}
