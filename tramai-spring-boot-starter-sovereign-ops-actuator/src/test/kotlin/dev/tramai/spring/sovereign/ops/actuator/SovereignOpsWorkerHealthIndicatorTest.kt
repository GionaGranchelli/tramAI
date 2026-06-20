package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.SovereignOpsOutboxWorkerProperties
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsAuditOutboxWorkerStatusStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatchResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecoverySummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerFailureSummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerRunSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status
import java.time.Duration
import java.time.Instant

class SovereignOpsWorkerHealthIndicatorTest {

    @Test
    fun `disabled worker reports unknown health`() {
        val health = SovereignOpsWorkerHealthIndicator(statusStore(enabled = false)).health()

        assertThat(health.status).isEqualTo(Status.UNKNOWN)
    }

    @Test
    fun `enabled running worker reports up health`() {
        val store = statusStore(enabled = true)
        store.markLifecycleStarted()

        val health = SovereignOpsWorkerHealthIndicator(store).health()

        assertThat(health.status).isEqualTo(Status.UP)
    }

    @Test
    fun `enabled worker that is not running reports down health`() {
        val health = SovereignOpsWorkerHealthIndicator(statusStore(enabled = true)).health()

        assertThat(health.status).isEqualTo(Status.DOWN)
    }

    @Test
    fun `disabled worker that is somehow still running reports unknown health`() {
        val store = statusStore(enabled = false)
        store.markLifecycleStarted()

        val health = SovereignOpsWorkerHealthIndicator(store).health()

        assertThat(health.status).isEqualTo(Status.UNKNOWN)
    }

    @Test
    fun `enabled running worker with failed cycles and successful cycles reports up health`() {
        val store = statusStore(enabled = true)
        store.markLifecycleStarted()
        store.recordCycleFailed(action = "dispatchPending", errorCode = "IOException")
        store.recordCycleCompleted(successfulSummary(failure = failureSummary()))

        val health = SovereignOpsWorkerHealthIndicator(store).health()

        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(health.details["totalCyclesCompleted"]).isEqualTo(1L)
        assertThat(health.details["totalCyclesFailed"]).isEqualTo(1L)
        assertThat(health.details["hasLastFailure"]).isEqualTo(true)
    }

    @Test
    fun `enabled running worker with failed cycles and zero completed cycles reports down health`() {
        val store = statusStore(enabled = true)
        store.markLifecycleStarted()
        store.recordCycleFailed(action = "recoverPrepared", errorCode = "IllegalStateException")

        val health = SovereignOpsWorkerHealthIndicator(store).health()

        assertThat(health.status).isEqualTo(Status.DOWN)
        assertThat(health.details["totalCyclesCompleted"]).isEqualTo(0L)
        assertThat(health.details["totalCyclesFailed"]).isEqualTo(1L)
        assertThat(health.details["hasLastFailure"]).isEqualTo(true)
        assertThat(health.details["lastFailureAt"]).isNotNull
    }

    @Test
    fun `health details contain expected operational keys`() {
        val store = statusStore(enabled = true)
        store.markLifecycleStarted()
        store.recordCycleCompleted(successfulSummary())

        val health = SovereignOpsWorkerHealthIndicator(store).health()

        assertThat(health.details.keys).contains(
            "enabled",
            "running",
            "totalCyclesCompleted",
            "totalCyclesFailed",
            "hasLastRecovered",
            "hasLastDispatched",
            "hasLastFailure",
        )
    }

    @Test
    fun `health details do not contain sensitive or verbose diagnostic keys`() {
        val store = statusStore(enabled = true)
        store.markLifecycleStarted()
        store.recordCycleFailed(action = "dispatchPending", errorCode = "RuntimeException")
        store.recordCycleCompleted(successfulSummary(failure = failureSummary()))

        val health = SovereignOpsWorkerHealthIndicator(store).health()

        assertThat(health.details.keys).doesNotContain(
            "approvalId",
            "outboxId",
            "recordId",
            "token",
            "replayEnvelope",
            "prompt",
            "modelResponse",
            "toolArguments",
            "filePath",
            "stackTrace",
            "exceptionMessage",
            "reason",
        )
    }

    @Test
    fun `health detail values are safe types only`() {
        val store = statusStore(enabled = true)
        store.markLifecycleStarted()
        store.recordCycleFailed(action = "dispatchPending", errorCode = "RuntimeException")
        store.recordCycleCompleted(successfulSummary(failure = failureSummary()))

        val health = SovereignOpsWorkerHealthIndicator(store).health()

        // All values must be safe scalars — no nested objects, no complex types
        val isoInstant = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$")
        health.details.values.forEach { value ->
            val isSafe = value is Boolean || value is Number ||
                (value is String && isoInstant.matches(value))
            assertThat(isSafe)
                .describedAs("Health detail value has an unexpected type: $value (${value?.javaClass?.name})")
                .isTrue()
        }
    }

    private fun statusStore(enabled: Boolean): InMemorySovereignOpsAuditOutboxWorkerStatusStore =
        InMemorySovereignOpsAuditOutboxWorkerStatusStore(
            SovereignOpsOutboxWorkerProperties(
                enabled = enabled,
                initialDelay = Duration.ofSeconds(5),
                interval = Duration.ofSeconds(30),
                batchSize = 10,
                recoverPrepared = true,
                dispatchPending = true,
            ),
        )

    private fun successfulSummary(
        failure: SovereignOpsAuditOutboxWorkerFailureSummary? = null,
    ): SovereignOpsAuditOutboxWorkerRunSummary {
        val startedAt = Instant.parse("2026-06-20T10:00:00Z")
        return SovereignOpsAuditOutboxWorkerRunSummary(
            recovered = SovereignOpsAuditOutboxRecoverySummary(inspected = 1),
            dispatched = SovereignOpsAuditOutboxDispatchResult(
                claimed = 1,
                emitted = 1,
                failedRetryable = 0,
                failedPermanent = 0,
            ),
            failure = failure,
            startedAt = startedAt,
            completedAt = startedAt.plusMillis(25),
        )
    }

    private fun failureSummary(): SovereignOpsAuditOutboxWorkerFailureSummary =
        SovereignOpsAuditOutboxWorkerFailureSummary(
            action = "dispatchPending",
            errorCode = "RuntimeException",
        )
}
