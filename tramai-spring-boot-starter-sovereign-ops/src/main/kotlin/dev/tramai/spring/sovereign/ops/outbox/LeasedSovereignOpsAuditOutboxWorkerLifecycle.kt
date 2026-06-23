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

/**
 * [SmartLifecycle] wrapper that drives [LeasedSovereignOpsAuditOutboxBackgroundWorker]
 * on a periodic schedule.
 *
 * Identical in structure to [SovereignOpsAuditOutboxWorkerLifecycle] but
 * parameterized for the lease-coordinated worker type.
 */
class LeasedSovereignOpsAuditOutboxWorkerLifecycle(
    private val worker: LeasedSovereignOpsAuditOutboxBackgroundWorker,
    private val properties: SovereignOpsOutboxWorkerProperties,
    private val observer: SovereignOpsAuditOutboxWorkerObserver = SovereignOpsAuditOutboxWorkerObserver.Noop,
    private val statusStore: SovereignOpsAuditOutboxWorkerStatusStore? = null,
) : SmartLifecycle {

    private val logger = LogFactory.getLog(LeasedSovereignOpsAuditOutboxWorkerLifecycle::class.java)
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    private var running = false

    override fun start() {
        if (!properties.enabled) {
            logger.debug("Leased sovereign ops audit outbox worker is disabled - not starting")
            return
        }
        if (running) return

        validateSovereignOpsAuditOutboxWorkerProperties(properties)

        running = true
        statusStore?.markLifecycleStarted()
        job = scope.launch {
            delay(properties.initialDelay.toMillis())

            while (running) {
                try {
                    val summary = worker.runOnce()
                    notifyCycleCompleted(summary)
                    summary.failure?.let {
                        notifyCycleFailed(it.action, it.errorCode)
                        logger.warn(
                            "Leased sovereign ops audit outbox worker cycle failed: action=${it.action}, errorCode=${it.errorCode}",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val errorCode = e::class.simpleName ?: "Exception"
                    notifyCycleFailed("unexpected", errorCode)
                    logger.warn(
                        "Leased sovereign ops audit outbox worker cycle failed unexpectedly: " +
                            "errorCode=$errorCode",
                    )
                }

                if (running) {
                    delay(properties.interval.toMillis())
                }
            }
        }
    }

    override fun stop() {
        running = false
        statusStore?.markLifecycleStopped()
        job?.cancel()
        job = null
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = 0

    // ── Safe observer notification ─────────────────────────────────────

    private fun notifyCycleCompleted(summary: SovereignOpsAuditOutboxWorkerRunSummary) {
        try {
            observer.onCycleCompleted(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Leased sovereign ops audit outbox worker observer callback failed: " +
                    "callback=onCycleCompleted, errorCode=${e::class.simpleName ?: "Exception"}",
            )
        }
    }

    private fun notifyCycleFailed(
        action: String,
        errorCode: String,
    ) {
        try {
            observer.onCycleFailed(action, errorCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Leased sovereign ops audit outbox worker observer callback failed: " +
                    "callback=onCycleFailed, errorCode=${e::class.simpleName ?: "Exception"}",
            )
        }
    }
}
