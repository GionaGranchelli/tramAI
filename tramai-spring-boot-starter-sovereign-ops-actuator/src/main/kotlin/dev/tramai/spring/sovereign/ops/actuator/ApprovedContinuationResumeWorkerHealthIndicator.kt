package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorkerStatusStore
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

class ApprovedContinuationResumeWorkerHealthIndicator(
    private val statusStore: ApprovedContinuationResumeWorkerStatusStore,
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

        return builder
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
            .build()
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
