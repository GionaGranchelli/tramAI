package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueStatusStore
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorkerStatusStore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

class ApprovedContinuationResumeWorkerHealthIndicator(
    private val statusStore: ApprovedContinuationResumeWorkerStatusStore,
    private val queueStatusStore: ApprovedContinuationResumeQueueStatusStore? = null,
) : HealthIndicator {

    override fun health(): Health {
        val snapshot = statusStore.snapshot()

        val builder = when {
            !snapshot.lifecycleEnabled -> Health.unknown()
            snapshot.lifecycleEnabled && !snapshot.running -> Health.down()
            snapshot.lifecycleEnabled && snapshot.running &&
                snapshot.totalCyclesCompleted == 0L && snapshot.totalCyclesFailed > 0L -> Health.down()
            else -> Health.up()
        }

        val healthBuilder = builder
            .withDetail("enabled", snapshot.enabled)
            .withDetail("lifecycleEnabled", snapshot.lifecycleEnabled)
            .withDetail("running", snapshot.running)
            .withDetail("batchSize", snapshot.batchSize)
            .withDetail("intervalMillis", snapshot.intervalMillis)
            .withDetailIfPresent("lastResultScanned", snapshot.lastResult?.scanned)
            .withDetailIfPresent("lastResultResumed", snapshot.lastResult?.resumed)
            .withDetailIfPresent("lastResultSkipped", snapshot.lastResult?.skipped)
            .withDetailIfPresent("lastResultFailed", snapshot.lastResult?.failed)
            .withDetailIfPresent("lastCycleCompletedAt", snapshot.lastCycleCompletedAt?.toString())
            .withDetailIfPresent("lastFailureAt", snapshot.lastFailureAt?.toString())
            .withDetail("totalCyclesCompleted", snapshot.totalCyclesCompleted)
            .withDetail("totalCyclesFailed", snapshot.totalCyclesFailed)

        if (queueStatusStore != null) {
            try {
                val queueSnapshot = runBlocking {
                    withTimeout(5_000) {
                        queueStatusStore.snapshot()
                    }
                }
                healthBuilder
                    .withDetail("eligibleNow", queueSnapshot.eligibleNow)
                    .withDetail("delayedRetry", queueSnapshot.delayedRetry)
                    .withDetail("activeLeases", queueSnapshot.activeLeases)
                    .withDetail("expiredLeases", queueSnapshot.expiredLeases)
                    .withDetail("terminalFailures", queueSnapshot.terminalFailures)
                    .withDetailIfPresent("oldestEligibleAgeSeconds", queueSnapshot.oldestEligibleAgeSeconds)
                    .withDetailIfPresent("oldestRetryDueInSeconds", queueSnapshot.oldestRetryDueInSeconds)
            } catch (_: Exception) {
                healthBuilder.withDetail("queueStatusError", "snapshot-failed")
            }
        }

        return healthBuilder.build()
    }

    private fun Health.Builder.withDetailIfPresent(
        key: String,
        value: Any?,
    ): Health.Builder =
        if (value == null) {
            this
        } else {
            withDetail(key, value)
        }
}
