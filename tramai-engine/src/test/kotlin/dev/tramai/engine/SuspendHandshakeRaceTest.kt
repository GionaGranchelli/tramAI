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
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class SuspendHandshakeRaceTest {
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `completion before suspension publication completes exactly once`() {
        repeat(100) {
            val providerCalls = AtomicInteger()
            val engine = TramaiEngine(
                provider = ImmediateProvider(providerCalls),
                suspendDispatcher = Dispatchers.Unconfined,
            )
            val service = engine.create<HandshakeService>()

            val result = runBlocking { service.answer() }

            assertThat(result).isEqualTo("ok")
            assertThat(providerCalls.get()).isEqualTo(1)
            engine.close()
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `synchronous inline resume before suspension publication completes exactly once`() {
        repeat(100) {
            val providerCalls = AtomicInteger()
            val engine = TramaiEngine(
                provider = ImmediateProvider(providerCalls),
                suspendDispatcher = Dispatchers.Unconfined,
            )
            val service = engine.create<HandshakeService>()

            // BOTH sides unconfined: the engine block runs inline on the caller
            // thread AND the caller's continuation resumes inline (no dispatch).
            // This is the dangerous window: resumeWith executes before
            // invokeSuspend returns COROUTINE_SUSPENDED.
            val result = runBlocking(Dispatchers.Unconfined) { service.answer() }

            assertThat(result).isEqualTo("ok")
            assertThat(providerCalls.get()).isEqualTo(1)
            engine.close()
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `inline continuation resume before suspension publication completes exactly once`() {
        repeat(100) {
            val providerCalls = AtomicInteger()
            val engine = TramaiEngine(
                provider = ImmediateProvider(providerCalls),
                suspendDispatcher = Dispatchers.Unconfined,
            )
            val service = engine.create<HandshakeService>()

            // The suspension point's continuation has an Unconfined interceptor
            // (no runBlocking event loop deferral): resumeWith executes the
            // caller state machine inline, before invokeSuspend returns.
            val result = runBlocking {
                withContext(Dispatchers.Unconfined) { service.answer() }
            }

            assertThat(result).isEqualTo("ok")
            assertThat(providerCalls.get()).isEqualTo(1)
            engine.close()
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `pre-start cancellation completes exactly once without invoking provider`() = runBlocking {
        repeat(100) {
            val dispatcher = ControlledDispatcher()
            val providerCalls = AtomicInteger()
            val engine = TramaiEngine(ImmediateProvider(providerCalls), dispatcher)
            val service = engine.create<HandshakeService>()
            // Invoke on a real dispatcher so invokeSuspend can progress; the engine's
            // ControlledDispatcher holds the launched block until released.
            val outcome = async(Dispatchers.Default) { runCatching { service.answer() } }

            while (dispatcher.queuedTasks() == 0) {
                Thread.yield()
            }
            // close() blocks joining the tracked job, which only completes when
            // the queued task runs — so close on a separate thread, and release
            // only after close() has cancelled the job (pre-start cancel).
            val closer = Thread { engine.close() }.apply { start() }
            waitForTrackedCancellation(engine)
            dispatcher.releaseAll()
            closer.join(5_000)

            assertThat(outcome.await().exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            assertThat(providerCalls.get()).isZero()
            assertThat(activeInvocationCount(engine)).isZero()
        }
    }

    private fun waitForTrackedCancellation(engine: TramaiEngine) {
        val field = TramaiEngine::class.java.getDeclaredField("activeInvocationJobs")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val jobs = field.get(engine) as Set<*>
        while (jobs.none { (it as Job).isCancelled }) {
            Thread.yield()
        }
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
private interface HandshakeService {
    @Operation(prompt = "Answer", model = "test-model")
    suspend fun answer(): String
}
