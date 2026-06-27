package dev.tramai.spring.sovereign.ops

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ApprovedContinuationResumeWorkerLifecycleTest {

    @Test
    fun `lifecycle disabled by default does not run periodic cycles`() {
        runBlocking {
            val worker = FakeWorker { ApprovedContinuationResumeWorkerResult(0, 0, 0, 0) }
            val lifecycle = ApprovedContinuationResumeWorkerLifecycle(
                worker = worker,
                properties = SovereignOpsApprovedResumeWorkerProperties(),
            )

            lifecycle.start()
            delay(60)
            lifecycle.stop()

            assertThat(worker.invocations).isEmpty()
        }
    }

    @Test
    fun `lifecycle runs when lifecycleEnabled is true`() {
        runBlocking {
            val worker = FakeWorker { ApprovedContinuationResumeWorkerResult(1, 1, 0, 0) }
            val lifecycle = ApprovedContinuationResumeWorkerLifecycle(
                worker = worker,
                properties = SovereignOpsApprovedResumeWorkerProperties(
                    enabled = true,
                    lifecycleEnabled = true,
                    batchSize = 23,
                    interval = Duration.ofSeconds(1),
                ),
            )

            lifecycle.start()
            waitUntil(timeoutMillis = 300) { worker.invocations.isNotEmpty() }
            lifecycle.stop()

            assertThat(worker.invocations).containsExactly(23)
        }
    }

    @Test
    fun `lifecycle keeps running after transient worker exception`() {
        runBlocking {
            val attempts = AtomicInteger(0)
            val observer = CapturingApprovedContinuationResumeWorkerObserver()
            val worker = FakeWorker {
                if (attempts.incrementAndGet() == 1) {
                    throw IllegalStateException("transient")
                }
                ApprovedContinuationResumeWorkerResult(1, 1, 0, 0)
            }
            val lifecycle = ApprovedContinuationResumeWorkerLifecycle(
                worker = worker,
                properties = SovereignOpsApprovedResumeWorkerProperties(
                    enabled = true,
                    lifecycleEnabled = true,
                    interval = Duration.ofMillis(25),
                ),
                observer = observer,
            )

            lifecycle.start()
            waitUntil(timeoutMillis = 500) {
                worker.invocations.size >= 2 &&
                    observer.failures.size == 1 &&
                    observer.completed.size >= 1
            }
            lifecycle.stop()

            assertThat(worker.invocations.size).isGreaterThanOrEqualTo(2)
            assertThat(observer.failures).hasSize(1)
            assertThat(observer.completed).isNotEmpty()
        }
    }

    @Test
    fun `stop cancels loop and prevents further calls`() {
        runBlocking {
            val worker = FakeWorker { ApprovedContinuationResumeWorkerResult(1, 0, 0, 0) }
            val lifecycle = ApprovedContinuationResumeWorkerLifecycle(
                worker = worker,
                properties = SovereignOpsApprovedResumeWorkerProperties(
                    enabled = true,
                    lifecycleEnabled = true,
                    interval = Duration.ofMillis(25),
                ),
            )

            lifecycle.start()
            waitUntil(timeoutMillis = 300) { worker.invocations.isNotEmpty() }
            lifecycle.stop()
            val invocationsAtStop = worker.invocations.size
            delay(90)

            assertThat(lifecycle.isRunning).isFalse()
            assertThat(worker.invocations).hasSize(invocationsAtStop)
        }
    }

    @Test
    fun `custom interval is honored by the lifecycle cadence`() {
        runBlocking {
            val timestamps = CopyOnWriteArrayList<Long>()
            val worker = FakeWorker {
                timestamps.add(System.nanoTime())
                ApprovedContinuationResumeWorkerResult(1, 0, 0, 0)
            }
            val lifecycle = ApprovedContinuationResumeWorkerLifecycle(
                worker = worker,
                properties = SovereignOpsApprovedResumeWorkerProperties(
                    enabled = true,
                    lifecycleEnabled = true,
                    interval = Duration.ofMillis(70),
                ),
            )

            lifecycle.start()
            waitUntil(timeoutMillis = 500) { timestamps.size >= 2 }
            lifecycle.stop()

            val firstGapMillis = (timestamps[1] - timestamps[0]) / 1_000_000
            assertThat(firstGapMillis).isGreaterThanOrEqualTo(45)
        }
    }

    private suspend fun waitUntil(
        timeoutMillis: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition() && System.currentTimeMillis() < deadline) {
            delay(10)
        }
        assertThat(condition()).isTrue()
    }

    private class FakeWorker(
        private val block: suspend (Int) -> ApprovedContinuationResumeWorkerResult,
    ) : ApprovedContinuationResumeWorker {
        val invocations: MutableList<Int> = Collections.synchronizedList(mutableListOf())

        override suspend fun runOnce(limit: Int): ApprovedContinuationResumeWorkerResult {
            invocations.add(limit)
            return block(limit)
        }
    }

    private class CapturingApprovedContinuationResumeWorkerObserver : ApprovedContinuationResumeWorkerObserver {
        val completed = mutableListOf<ApprovedContinuationResumeWorkerResult>()
        val failures = mutableListOf<String>()

        override fun cycleStarted(workerId: String) = Unit

        override fun cycleCompleted(
            workerId: String,
            result: ApprovedContinuationResumeWorkerResult,
            duration: Duration,
        ) {
            completed.add(result)
        }

        override fun cycleFailed(workerId: String, error: Throwable) {
            failures.add(error::class.simpleName ?: "Exception")
        }
    }
}
