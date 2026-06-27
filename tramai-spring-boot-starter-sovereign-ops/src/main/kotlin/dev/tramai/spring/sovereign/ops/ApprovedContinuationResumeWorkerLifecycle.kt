package dev.tramai.spring.sovereign.ops

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.logging.LogFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.time.Instant

class ApprovedContinuationResumeWorkerLifecycle(
    private val worker: ApprovedContinuationResumeWorker,
    private val properties: SovereignOpsApprovedResumeWorkerProperties,
    private val observer: ApprovedContinuationResumeWorkerObserver = ApprovedContinuationResumeWorkerObserver.Noop,
    private val statusStore: ApprovedContinuationResumeWorkerStatusStore? = null,
) : SmartLifecycle {

    private val logger = LogFactory.getLog(ApprovedContinuationResumeWorkerLifecycle::class.java)
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    private var running = false

    override fun start() {
        if (!properties.lifecycleEnabled) {
            logger.debug("Approved continuation resume worker lifecycle is disabled - not starting")
            return
        }
        if (running) return

        validateProperties(properties)

        running = true
        statusStore?.markLifecycleStarted()
        job = scope.launch {
            while (running) {
                val cycleStart = Instant.now()
                statusStore?.recordCycleStartedAt(cycleStart)
                notifyCycleStarted()
                try {
                    val result = worker.runOnce(properties.batchSize)
                    val duration = Duration.between(cycleStart, Instant.now())
                    notifyCycleCompleted(result, duration)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notifyCycleFailed(e)
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

    private fun notifyCycleStarted() {
        try {
            observer.cycleStarted(properties.workerId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "ApprovedContinuationResumeWorkerObserver cycleStarted callback failed: " +
                    "errorCode=${e::class.simpleName ?: "Exception"}",
            )
        }
    }

    private fun notifyCycleCompleted(
        result: ApprovedContinuationResumeWorkerResult,
        duration: Duration,
    ) {
        try {
            observer.cycleCompleted(properties.workerId, result, duration)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "ApprovedContinuationResumeWorkerObserver cycleCompleted callback failed: " +
                    "errorCode=${e::class.simpleName ?: "Exception"}",
            )
        }
    }

    private fun notifyCycleFailed(error: Throwable) {
        try {
            observer.cycleFailed(properties.workerId, error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "ApprovedContinuationResumeWorkerObserver cycleFailed callback failed: " +
                    "errorCode=${e::class.simpleName ?: "Exception"}",
            )
        }
    }

    companion object {
        fun validateProperties(properties: SovereignOpsApprovedResumeWorkerProperties) {
            require(!properties.interval.isZero && !properties.interval.isNegative) {
                "tramai-sovereign-ops-approved-resume-worker-invalid-interval"
            }
            require(properties.batchSize in 1..500) {
                "tramai-sovereign-ops-approved-resume-worker-invalid-batch-size"
            }
        }
    }
}
