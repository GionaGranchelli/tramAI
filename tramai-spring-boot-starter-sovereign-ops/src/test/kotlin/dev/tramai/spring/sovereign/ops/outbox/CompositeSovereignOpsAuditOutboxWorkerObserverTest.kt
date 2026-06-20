package dev.tramai.spring.sovereign.ops.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException

class CompositeSovereignOpsAuditOutboxWorkerObserverTest {

    private val now = Instant.now()
    private val summary = SovereignOpsAuditOutboxWorkerRunSummary(
        recovered = null,
        dispatched = null,
        startedAt = now,
        completedAt = now.plus(Duration.ofMillis(500)),
    )

    @Test
    fun `delegates onCycleCompleted to all observers`() {
        val calls = mutableListOf<String>()
        val observer1 = RecordingTestObserver(onCompleted = { calls.add("a") })
        val observer2 = RecordingTestObserver(onCompleted = { calls.add("b") })
        val composite = CompositeSovereignOpsAuditOutboxWorkerObserver(listOf(observer1, observer2))

        composite.onCycleCompleted(summary)

        assertThat(calls).containsExactly("a", "b")
    }

    @Test
    fun `delegates onCycleFailed to all observers`() {
        val calls = mutableListOf<String>()
        val observer1 = RecordingTestObserver(onFailed = { calls.add("a") })
        val observer2 = RecordingTestObserver(onFailed = { calls.add("b") })
        val composite = CompositeSovereignOpsAuditOutboxWorkerObserver(listOf(observer1, observer2))

        composite.onCycleFailed("unexpected", "test")

        assertThat(calls).containsExactly("a", "b")
    }

    @Test
    fun `delegate RuntimeException does not prevent other delegates from receiving cycle callbacks`() {
        val calls = mutableListOf<String>()
        val throwing = RecordingTestObserver(onCompleted = { throw RuntimeException("fail") })
        val working = RecordingTestObserver(onCompleted = { calls.add("b") })
        val composite = CompositeSovereignOpsAuditOutboxWorkerObserver(listOf(throwing, working))

        composite.onCycleCompleted(summary)

        assertThat(calls).containsExactly("b")
    }

    @Test
    fun `delegate RuntimeException does not prevent other delegates from receiving failure callbacks`() {
        val calls = mutableListOf<String>()
        val throwing = RecordingTestObserver(onFailed = { throw RuntimeException("fail") })
        val working = RecordingTestObserver(onFailed = { calls.add("b") })
        val composite = CompositeSovereignOpsAuditOutboxWorkerObserver(listOf(throwing, working))

        composite.onCycleFailed("unexpected", "test")

        assertThat(calls).containsExactly("b")
    }

    @Test
    fun `CancellationException from onCycleCompleted is rethrown`() {
        val throwing = RecordingTestObserver(onCompleted = { throw CancellationException() })
        val composite = CompositeSovereignOpsAuditOutboxWorkerObserver(listOf(throwing))

        org.junit.jupiter.api.assertThrows<CancellationException> {
            composite.onCycleCompleted(summary)
        }
    }

    @Test
    fun `CancellationException from onCycleFailed is rethrown`() {
        val throwing = RecordingTestObserver(onFailed = { throw CancellationException() })
        val composite = CompositeSovereignOpsAuditOutboxWorkerObserver(listOf(throwing))

        org.junit.jupiter.api.assertThrows<CancellationException> {
            composite.onCycleFailed("unexpected", "test")
        }
    }

    @Test
    fun `empty observers list does not throw`() {
        val composite = CompositeSovereignOpsAuditOutboxWorkerObserver(emptyList())

        composite.onCycleCompleted(summary)
        composite.onCycleFailed("unexpected", "test")
    }
}

private class RecordingTestObserver(
    private val onCompleted: (() -> Unit)? = null,
    private val onFailed: (() -> Unit)? = null,
) : SovereignOpsAuditOutboxWorkerObserver {
    override fun onCycleCompleted(summary: SovereignOpsAuditOutboxWorkerRunSummary) {
        onCompleted?.invoke()
    }

    override fun onCycleFailed(action: String, errorCode: String) {
        onFailed?.invoke()
    }
}
