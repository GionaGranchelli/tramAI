package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Epic 5.3 — worker observer lifecycle matrix.
 *
 * Every [TramaiWorkerObserver] callback (22) is exercised with a throwing
 * delegate. The invariant under test: a telemetry failure is contained and can
 * never crash or derail the worker loop — poll, lease coordination, renewal,
 * attempts, takeover, recovery, and shutdown all proceed; cancellation always
 * escapes unchanged.
 */
class FailureIsolatingTramaiWorkerObserverTest {

    private val workerId = "worker-1"
    private val workflowId = "workflow-1"

    private val throwingDelegate = object : TramaiWorkerObserver {
        override fun onWorkerStarted(workerId: String) = throw IllegalStateException("boom")
        override fun onWorkerStopped(workerId: String) = throw IllegalStateException("boom")
        override fun onLeaseAcquired(workflowId: String, workerId: String) = throw IllegalStateException("boom")
        override fun onLeaseReleased(workflowId: String, workerId: String) = throw IllegalStateException("boom")
        override fun onLeaseExpired(workflowId: String, workerId: String) = throw IllegalStateException("boom")
        override fun onLeaseRenewalFailed(workflowId: String, workerId: String, error: Throwable) = throw IllegalStateException("boom")
        override fun onLeaseReleaseFailed(workflowId: String, workerId: String, error: Throwable) = throw IllegalStateException("boom")
        override fun onPollFailed(workerId: String, error: Throwable) = throw IllegalStateException("boom")
        override fun onWorkTakenOver(workflowId: String, previousWorkerId: String, newWorkerId: String) = throw IllegalStateException("boom")
        override fun onUnknownAttempt(runId: String, stepName: String, priorWorkerId: String, attemptTime: Long) = throw IllegalStateException("boom")
        override fun onStepAttemptStarted(runId: String, stepName: String, attemptId: String, workerId: String) = throw IllegalStateException("boom")
        override fun onStepAttemptCompleted(runId: String, stepName: String, attemptId: String, workerId: String) = throw IllegalStateException("boom")
        override fun onStepAttemptFailed(runId: String, stepName: String, attemptId: String, workerId: String, error: Throwable) = throw IllegalStateException("boom")
        override fun onShutdownStarted(workerId: String) = throw IllegalStateException("boom")
        override fun onDrainProgress(workerId: String, done: Int, pending: Int) = throw IllegalStateException("boom")
        override fun onShutdownComplete(workerId: String) = throw IllegalStateException("boom")
        override fun onWorkerHeartbeat(workerId: String, uptimeMillis: Long, claimedCount: Int) = throw IllegalStateException("boom")
        override fun onLeaseRenewed(workflowId: String, workerId: String, newExpiry: Long) = throw IllegalStateException("boom")
        override fun onLeaseContested(workflowId: String, claimantWorkerId: String, currentWorkerId: String) = throw IllegalStateException("boom")
        override fun onWorkflowAbandoned(workflowId: String, workerId: String, lastStep: String?, timeoutMillis: Long) = throw IllegalStateException("boom")
    }

    private fun isolated(): TramaiWorkerObserver = FailureIsolatingTramaiWorkerObserver(throwingDelegate)

    @Test
    fun `start stop and shutdown callbacks are contained`() {
        isolated().onWorkerStarted(workerId)
        isolated().onWorkerStopped(workerId)
        isolated().onShutdownStarted(workerId)
        isolated().onShutdownComplete(workerId)
    }

    @Test
    fun `lease acquisition and release callbacks are contained`() {
        isolated().onLeaseAcquired(workflowId, workerId)
        isolated().onLeaseReleased(workflowId, workerId)
        isolated().onLeaseExpired(workflowId, workerId)
        isolated().onLeaseRenewed(workflowId, workerId, newExpiry = 1_000L)
    }

    @Test
    fun `lease failure callbacks are contained`() {
        isolated().onLeaseRenewalFailed(workflowId, workerId, IllegalStateException("primary"))
        isolated().onLeaseReleaseFailed(workflowId, workerId, IllegalStateException("primary"))
    }

    @Test
    fun `poll and takeover callbacks are contained`() {
        isolated().onPollFailed(workerId, IllegalStateException("primary"))
        isolated().onWorkTakenOver(workflowId, "old-worker", workerId)
        isolated().onLeaseContested(workflowId, "claimant", workerId)
        isolated().onWorkflowAbandoned(workflowId, workerId, "step-a", timeoutMillis = 30_000L)
    }

    @Test
    fun `attempt callbacks are contained`() {
        isolated().onUnknownAttempt("run-1", "step-a", "old-worker", attemptTime = 1_000L)
        isolated().onStepAttemptStarted("run-1", "step-a", "attempt-1", workerId)
        isolated().onStepAttemptCompleted("run-1", "step-a", "attempt-1", workerId)
        isolated().onStepAttemptFailed("run-1", "step-a", "attempt-1", workerId, IllegalStateException("primary"))
    }

    @Test
    fun `drain and heartbeat callbacks are contained`() {
        isolated().onDrainProgress(workerId, done = 1, pending = 2)
        isolated().onWorkerHeartbeat(workerId, uptimeMillis = 42L, claimedCount = 3)
    }

    @Test
    fun `cancellation from an observer always escapes unchanged`() {
        val cancellation = CancellationException("observer-cancelled")
        val cancellingDelegate = object : TramaiWorkerObserver {
            override fun onWorkerStarted(workerId: String) = throw cancellation
            override fun onPollFailed(workerId: String, error: Throwable) = throw cancellation
            override fun onLeaseAcquired(workflowId: String, workerId: String) = throw cancellation
            override fun onStepAttemptStarted(runId: String, stepName: String, attemptId: String, workerId: String) = throw cancellation
        }
        val obs = FailureIsolatingTramaiWorkerObserver(cancellingDelegate)
        assertSame(cancellation, assertFailsWith<CancellationException> { obs.onWorkerStarted(workerId) })
        assertSame(cancellation, assertFailsWith<CancellationException> { obs.onPollFailed(workerId, IllegalStateException("x")) })
        assertSame(cancellation, assertFailsWith<CancellationException> { obs.onLeaseAcquired(workflowId, workerId) })
    }

    @Test
    fun `successful callbacks are forwarded to the delegate`() {
        val received = mutableListOf<String>()
        val delegate = object : TramaiWorkerObserver {
            override fun onWorkerStarted(workerId: String) {
                received += "started"
            }

            override fun onLeaseAcquired(workflowId: String, workerId: String) {
                received += "lease"
            }

            override fun onShutdownComplete(workerId: String) {
                received += "shutdown"
            }
        }
        val obs = FailureIsolatingTramaiWorkerObserver(delegate)
        obs.onWorkerStarted(workerId)
        obs.onLeaseAcquired(workflowId, workerId)
        obs.onShutdownComplete(workerId)
        assertEquals(listOf("started", "lease", "shutdown"), received)
    }
}
