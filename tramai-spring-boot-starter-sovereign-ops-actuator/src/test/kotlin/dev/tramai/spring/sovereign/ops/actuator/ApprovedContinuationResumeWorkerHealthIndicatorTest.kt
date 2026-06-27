package dev.tramai.spring.sovereign.ops.actuator

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

    private fun statusStore(lifecycleEnabled: Boolean): InMemoryApprovedContinuationResumeWorkerStatusStore =
        InMemoryApprovedContinuationResumeWorkerStatusStore(
            SovereignOpsApprovedResumeWorkerProperties(
                enabled = true,
                lifecycleEnabled = lifecycleEnabled,
                batchSize = 11,
                interval = Duration.ofSeconds(7),
            ),
        )
}
