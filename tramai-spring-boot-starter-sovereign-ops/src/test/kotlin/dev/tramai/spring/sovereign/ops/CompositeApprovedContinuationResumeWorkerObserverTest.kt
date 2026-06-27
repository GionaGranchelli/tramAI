package dev.tramai.spring.sovereign.ops

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CancellationException

class CompositeApprovedContinuationResumeWorkerObserverTest {

    @Test
    fun `all observers are notified`() {
        val calls = mutableListOf<String>()
        val observer1 = TestApprovedContinuationResumeWorkerObserver(
            onStarted = { calls.add("started-a") },
            onCompleted = { calls.add("completed-a") },
            onFailed = { calls.add("failed-a") },
        )
        val observer2 = TestApprovedContinuationResumeWorkerObserver(
            onStarted = { calls.add("started-b") },
            onCompleted = { calls.add("completed-b") },
            onFailed = { calls.add("failed-b") },
        )
        val composite = CompositeApprovedContinuationResumeWorkerObserver(listOf(observer1, observer2))

        composite.cycleStarted("worker-a")
        composite.cycleCompleted("worker-a", ApprovedContinuationResumeWorkerResult(1, 1, 0, 0), Duration.ofMillis(5))
        composite.cycleFailed("worker-a", IllegalStateException("boom"))

        assertThat(calls).containsExactly(
            "started-a",
            "started-b",
            "completed-a",
            "completed-b",
            "failed-a",
            "failed-b",
        )
    }

    @Test
    fun `one observer exception does not prevent others`() {
        val calls = mutableListOf<String>()
        val throwing = TestApprovedContinuationResumeWorkerObserver(
            onStarted = { throw RuntimeException("fail") },
            onCompleted = { throw RuntimeException("fail") },
            onFailed = { throw RuntimeException("fail") },
        )
        val working = TestApprovedContinuationResumeWorkerObserver(
            onStarted = { calls.add("started") },
            onCompleted = { calls.add("completed") },
            onFailed = { calls.add("failed") },
        )
        val composite = CompositeApprovedContinuationResumeWorkerObserver(listOf(throwing, working))

        composite.cycleStarted("worker-a")
        composite.cycleCompleted("worker-a", ApprovedContinuationResumeWorkerResult(1, 0, 0, 0), Duration.ofMillis(5))
        composite.cycleFailed("worker-a", IllegalStateException("boom"))

        assertThat(calls).containsExactly("started", "completed", "failed")
    }

    @Test
    fun `CancellationException is rethrown`() {
        val composite = CompositeApprovedContinuationResumeWorkerObserver(
            listOf(
                TestApprovedContinuationResumeWorkerObserver(
                    onStarted = { throw CancellationException() },
                ),
            ),
        )

        org.junit.jupiter.api.assertThrows<CancellationException> {
            composite.cycleStarted("worker-a")
        }
    }

    private class TestApprovedContinuationResumeWorkerObserver(
        private val onStarted: (() -> Unit)? = null,
        private val onCompleted: (() -> Unit)? = null,
        private val onFailed: (() -> Unit)? = null,
    ) : ApprovedContinuationResumeWorkerObserver {
        override fun cycleStarted(workerId: String) {
            onStarted?.invoke()
        }

        override fun cycleCompleted(
            workerId: String,
            result: ApprovedContinuationResumeWorkerResult,
            duration: Duration,
        ) {
            onCompleted?.invoke()
        }

        override fun cycleFailed(workerId: String, error: Throwable) {
            onFailed?.invoke()
        }
    }
}
