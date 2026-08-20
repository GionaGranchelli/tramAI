package dev.tramai.orchestration

import dev.tramai.core.observation.secondary.SecondaryEffectAuthority
import dev.tramai.core.observation.secondary.SecondaryFailureDiagnostic
import kotlinx.coroutines.CancellationException

/**
 * Epic 5.3 — failure-isolating [TramaiWorkerObserver] boundary.
 *
 * Wraps a delegate observer so that a throwing telemetry callback can never
 * crash or derail the worker loop: poll, lease coordination, renewal, attempts,
 * takeover, recovery, and shutdown all proceed regardless of observer
 * behaviour. [kotlinx.coroutines.CancellationException] always escapes
 * unchanged.
 */
class FailureIsolatingTramaiWorkerObserver(
    private val delegate: TramaiWorkerObserver,
) : TramaiWorkerObserver {

    private inline fun <T> isolate(callback: String, block: () -> T) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            SecondaryFailureDiagnostic.report(
                extensionPoint = "worker_observer",
                callback = callback,
                errorType = error.javaClass.simpleName,
                failurePolicy = "FAIL_OPEN",
                authority = SecondaryEffectAuthority.NON_AUTHORITATIVE.name,
            )
        }
    }

    override fun onWorkerStarted(workerId: String) = isolate("onWorkerStarted") { delegate.onWorkerStarted(workerId) }
    override fun onWorkerStopped(workerId: String) = isolate("onWorkerStopped") { delegate.onWorkerStopped(workerId) }
    override fun onLeaseAcquired(workflowId: String, workerId: String) = isolate("onLeaseAcquired") { delegate.onLeaseAcquired(workflowId, workerId) }
    override fun onLeaseReleased(workflowId: String, workerId: String) = isolate("onLeaseReleased") { delegate.onLeaseReleased(workflowId, workerId) }
    override fun onLeaseExpired(workflowId: String, workerId: String) = isolate("onLeaseExpired") { delegate.onLeaseExpired(workflowId, workerId) }

    override fun onLeaseRenewalFailed(workflowId: String, workerId: String, error: Throwable) {
        isolate("onLeaseRenewalFailed") { delegate.onLeaseRenewalFailed(workflowId, workerId, error) }
    }

    override fun onLeaseReleaseFailed(workflowId: String, workerId: String, error: Throwable) {
        isolate("onLeaseReleaseFailed") { delegate.onLeaseReleaseFailed(workflowId, workerId, error) }
    }

    override fun onPollFailed(workerId: String, error: Throwable) {
        isolate("onPollFailed") { delegate.onPollFailed(workerId, error) }
    }

    override fun onWorkTakenOver(workflowId: String, previousWorkerId: String, newWorkerId: String) {
        isolate("onWorkTakenOver") { delegate.onWorkTakenOver(workflowId, previousWorkerId, newWorkerId) }
    }

    override fun onUnknownAttempt(runId: String, stepName: String, priorWorkerId: String, attemptTime: Long) {
        isolate("onUnknownAttempt") { delegate.onUnknownAttempt(runId, stepName, priorWorkerId, attemptTime) }
    }

    override fun onStepAttemptStarted(runId: String, stepName: String, attemptId: String, workerId: String) {
        isolate("onStepAttemptStarted") { delegate.onStepAttemptStarted(runId, stepName, attemptId, workerId) }
    }

    override fun onStepAttemptCompleted(runId: String, stepName: String, attemptId: String, workerId: String) {
        isolate("onStepAttemptCompleted") { delegate.onStepAttemptCompleted(runId, stepName, attemptId, workerId) }
    }

    override fun onStepAttemptFailed(runId: String, stepName: String, attemptId: String, workerId: String, error: Throwable) {
        isolate("onStepAttemptFailed") { delegate.onStepAttemptFailed(runId, stepName, attemptId, workerId, error) }
    }

    override fun onShutdownStarted(workerId: String) = isolate("onShutdownStarted") { delegate.onShutdownStarted(workerId) }
    override fun onDrainProgress(workerId: String, done: Int, pending: Int) = isolate("onDrainProgress") { delegate.onDrainProgress(workerId, done, pending) }
    override fun onShutdownComplete(workerId: String) = isolate("onShutdownComplete") { delegate.onShutdownComplete(workerId) }

    override fun onWorkerHeartbeat(workerId: String, uptimeMillis: Long, claimedCount: Int) {
        isolate("onWorkerHeartbeat") { delegate.onWorkerHeartbeat(workerId, uptimeMillis, claimedCount) }
    }

    override fun onLeaseRenewed(workflowId: String, workerId: String, newExpiry: Long) {
        isolate("onLeaseRenewed") { delegate.onLeaseRenewed(workflowId, workerId, newExpiry) }
    }

    override fun onLeaseContested(workflowId: String, claimantWorkerId: String, currentWorkerId: String) {
        isolate("onLeaseContested") { delegate.onLeaseContested(workflowId, claimantWorkerId, currentWorkerId) }
    }

    override fun onWorkflowAbandoned(workflowId: String, workerId: String, lastStep: String?, timeoutMillis: Long) {
        isolate("onWorkflowAbandoned") { delegate.onWorkflowAbandoned(workflowId, workerId, lastStep, timeoutMillis) }
    }
}
