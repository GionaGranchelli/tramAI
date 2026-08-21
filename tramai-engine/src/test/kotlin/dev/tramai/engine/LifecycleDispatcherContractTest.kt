package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Deterministic lifecycle-scheduling contracts for the engine-owned dispatcher
 * seam (TramaiEngine.lifecycleDispatcher).
 *
 * These tests were born as the negative-result reproducers for the
 * resume-before-suspension ABI-race hypothesis (rejected: all variants pass on
 * the pre-fix engine). The two retained tests pin real lifecycle invariants:
 *
 * 1. An invocation whose engine block runs inline on the caller thread and
 *    resumes the continuation inline still completes (no lost completion, no
 *    hang) even though invokeSuspend has not yet returned COROUTINE_SUSPENDED.
 * 2. An invocation cancelled before its block starts never invokes the
 *    provider and delivers exactly one CancellationException to the caller —
 *    the contract the invokeOnCompletion pre-start fallback exists for.
 */
class LifecycleDispatcherContractTest {
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `inline completion before suspension publication does not lose the result`() {
        val providerCalls = AtomicInteger()
        val engine = TramaiEngine(
            provider = ImmediateProvider(providerCalls),
            lifecycleDispatcher = Dispatchers.Unconfined,
        )
        val service = engine.create<DispatcherContractService>()

        // BOTH sides unconfined: the engine block runs inline on the caller
        // thread AND the caller's continuation resumes inline (no dispatch).
        // This is the dangerous window: resumeWith executes before
        // invokeSuspend returns COROUTINE_SUSPENDED.
        val result = runBlocking(Dispatchers.Unconfined) { service.answer() }

        assertThat(result).isEqualTo("ok")
        assertThat(providerCalls.get()).isEqualTo(1)
        engine.close()
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `pre-start cancellation never invokes provider and cancels the caller once`() { runBlocking {
        val dispatcher = ControlledDispatcher()
        val providerCalls = AtomicInteger()
        val engine = TramaiEngine(ImmediateProvider(providerCalls), dispatcher)
        val service = engine.create<DispatcherContractService>()
        // Invoke on a real dispatcher so invokeSuspend can progress; the engine's
        // ControlledDispatcher holds the launched block until released.
        val outcome = async(Dispatchers.Default) { runCatching { service.answer() } }

        awaitUntil(10, TimeUnit.SECONDS) { dispatcher.queuedTasks() > 0 }
        // close() blocks joining the tracked job, which only completes when
        // the queued task runs — so close on a separate thread, and release
        // only after close() has cancelled the job (pre-start cancel).
        val closer = Thread { engine.close() }.apply { start() }
        waitForTrackedCancellation(engine, 10, TimeUnit.SECONDS)
        dispatcher.releaseAll()
        closer.join(5_000)
        assertThat(closer.isAlive).describedAs("close() must terminate").isFalse()

        assertThat(outcome.await().exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(providerCalls.get()).isZero()
        assertThat(activeInvocationCount(engine)).isZero()
    }
    }

    private fun awaitUntil(timeout: Long, unit: TimeUnit, condition: () -> Boolean) {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within ${unit.toSeconds(timeout)}s" }
            Thread.yield()
        }
    }

    private fun waitForTrackedCancellation(engine: TramaiEngine, timeout: Long, unit: TimeUnit) {
        val field = TramaiEngine::class.java.getDeclaredField("activeInvocationJobs")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val jobs = field.get(engine) as Set<*>
        awaitUntil(timeout, unit) { jobs.any { (it as Job).isCancelled } }
    }

    private fun activeInvocationCount(engine: TramaiEngine): Int {
        val field = TramaiEngine::class.java.getDeclaredField("activeInvocationJobs")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(engine) as Set<*>).size
    }

    private class ControlledDispatcher : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks += block
        }

        fun queuedTasks(): Int = tasks.size

        fun releaseAll() {
            while (true) {
                val task = tasks.poll() ?: break
                task.run()
            }
        }
    }

    private class ImmediateProvider(
        private val calls: AtomicInteger,
    ) : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse {
            calls.incrementAndGet()
            return ModelResponse(content = "ok")
        }
    }
}

@AiService
private interface DispatcherContractService {
    @Operation(prompt = "Answer", model = "test-model")
    suspend fun answer(): String
}
