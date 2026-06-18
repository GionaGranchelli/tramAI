package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.spring.sovereign.ops.SovereignOpsOutboxWorkerProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.logging.LogFactory
import org.springframework.context.SmartLifecycle

class SovereignOpsAuditOutboxWorkerLifecycle(
    private val worker: SovereignOpsAuditOutboxBackgroundWorker,
    private val properties: SovereignOpsOutboxWorkerProperties,
) : SmartLifecycle {

    private val logger = LogFactory.getLog(SovereignOpsAuditOutboxWorkerLifecycle::class.java)
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    private var running = false

    override fun start() {
        if (!properties.enabled) {
            logger.debug("Sovereign ops audit outbox worker is disabled - not starting")
            return
        }
        if (running) return

        validateSovereignOpsAuditOutboxWorkerProperties(properties)

        running = true
        job = scope.launch {
            delay(properties.initialDelay.toMillis())

            while (running) {
                try {
                    worker.runOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Runtime failures must not kill the background loop.
                }

                if (running) {
                    delay(properties.interval.toMillis())
                }
            }
        }
    }

    override fun stop() {
        running = false
        job?.cancel()
        job = null
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = 0
}

internal fun validateSovereignOpsAuditOutboxWorkerProperties(
    properties: SovereignOpsOutboxWorkerProperties,
) {
    require(!properties.interval.isZero && !properties.interval.isNegative) {
        "tramai-sovereign-ops-outbox-worker-invalid-interval"
    }
    require(!properties.initialDelay.isNegative) {
        "tramai-sovereign-ops-outbox-worker-invalid-initial-delay"
    }
    require(properties.batchSize in 1..500) {
        "tramai-sovereign-ops-outbox-worker-invalid-batch-size"
    }
    require(properties.recoverPrepared || properties.dispatchPending) {
        "tramai-sovereign-ops-outbox-worker-invalid-actions: at least one of recoverPrepared or dispatchPending must be true when worker is enabled"
    }
}
