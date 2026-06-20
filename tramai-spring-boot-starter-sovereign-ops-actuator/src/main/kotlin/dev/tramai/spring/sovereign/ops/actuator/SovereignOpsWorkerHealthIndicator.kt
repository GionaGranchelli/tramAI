package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerStatusStore
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

class SovereignOpsWorkerHealthIndicator(
    private val statusStore: SovereignOpsAuditOutboxWorkerStatusStore,
) : HealthIndicator {

    override fun health(): Health {
        val snapshot = statusStore.snapshot()

        val builder = when {
            !snapshot.enabled -> Health.unknown()
            snapshot.enabled && !snapshot.running -> Health.down()
            snapshot.running && snapshot.totalCyclesCompleted == 0L && snapshot.totalCyclesFailed > 0L -> Health.down()
            else -> Health.up()
        }

        return builder
            .withDetail("enabled", snapshot.enabled)
            .withDetail("running", snapshot.running)
            .withDetail("recoverPreparedEnabled", snapshot.recoverPreparedEnabled)
            .withDetail("dispatchPendingEnabled", snapshot.dispatchPendingEnabled)
            .withDetail("batchSize", snapshot.batchSize)
            .withDetail("intervalMillis", snapshot.intervalMillis)
            .withDetail("initialDelayMillis", snapshot.initialDelayMillis)
            .withDetailIfPresent("lastCycleStartedAt", snapshot.lastCycleStartedAt?.toString())
            .withDetailIfPresent("lastCycleCompletedAt", snapshot.lastCycleCompletedAt?.toString())
            .withDetailIfPresent("lastCycleDurationMillis", snapshot.lastCycleDurationMillis)
            .withDetailIfPresent("lastFailureAt", snapshot.lastFailureAt?.toString())
            .withDetail("totalCyclesCompleted", snapshot.totalCyclesCompleted)
            .withDetail("totalCyclesFailed", snapshot.totalCyclesFailed)
            .withDetail("hasLastRecovered", snapshot.lastRecovered != null)
            .withDetail("hasLastDispatched", snapshot.lastDispatched != null)
            .withDetail("hasLastFailure", snapshot.lastFailure != null)
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
