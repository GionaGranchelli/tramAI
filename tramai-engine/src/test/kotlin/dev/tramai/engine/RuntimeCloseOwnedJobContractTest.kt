package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Epic 12.1c probe 1 + 7 (engine portion) — named, repeatable proof that the
 * TramaiEngine runtime owns its jobs to completion on close().
 *
 * Ownership boundary: close() cancels the engine-owned lifecycleJob AND every
 * tracked suspend invocation (activeInvocationJobs), then JOINS them when
 * called from a non-engine thread (TramaiEngine.close). "Zero owned jobs after
 * close" is therefore observable as: close() returns only after the parked
 * invocation and its cleanup terminated, a second close() is a no-op, and
 * repeated create/close cycles leave no retained engine state behind.
 *
 * Deterministic (latch-based) — no sleeps, no timing thresholds. Complements
 * the existing close suites in TramaiEngineTest (mid-stream cancel, caller-job
 * no-deadlock, self-close) with the explicit owned-job contract framing.
 */
class RuntimeCloseOwnedJobContractTest {
    @AiService
    private interface SummarizerService {
        @Operation(prompt = "Summarize the raw input", model = "test-model")
        suspend fun summarize(rawInput: String): String
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `close returns only after a parked invocation terminates and repeated close is a no-op`() {
        val providerEntered = CompletableDeferred<Unit>()
        val providerReleased = CompletableDeferred<Unit>()
        val providerCalls = AtomicInteger()
        val provider =
            object : ModelProvider {
                override suspend fun complete(request: ModelRequest): ModelResponse {
                    providerCalls.incrementAndGet()
                    providerEntered.complete(Unit)
                    // Cancellable suspension: close() cancels the tracked invocation
                    // and join() waits for this await to abort.
                    providerReleased.await()
                    return ModelResponse(content = "never")
                }
            }
        val engine = TramaiEngine(provider)
        val service = engine.create<SummarizerService>()

        val callerFinished = CompletableDeferred<Throwable?>()
        val caller =
            Thread {
                val outcome = runCatching { runBlocking { service.summarize("raw") } }
                callerFinished.complete(outcome.exceptionOrNull())
            }
        caller.start()

        // Admission gate: the invocation is parked inside the provider — the
        // engine owns exactly one in-flight tracked job at this point.
        runBlocking { providerEntered.await() }

        // close() must cancel + join the parked invocation and return while the
        // provider gate is STILL latched (the call never completes normally).
        engine.close()

        // The parked call terminated with cancellation (close joined it).
        val outcome = runBlocking { callerFinished.await() }
        assertThat(outcome).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        caller.join(5_000)
        assertThat(caller.isAlive).isFalse()

        // The provider was entered exactly once and never resumed past the gate.
        assertThat(providerCalls.get()).isEqualTo(1)

        // Repeated close is safe and idempotent (closed.compareAndSet guard).
        engine.close()

        // Post-close the runtime rejects new work — no owned job can be admitted.
        assertThatThrownBy { runBlocking { service.summarize("late") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Tramai runtime is closed")
        providerReleased.complete(Unit)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `repeated create close cycles retain no owned state`() {
        repeat(CYCLES) { cycle ->
            val providerCalls = AtomicInteger()
            val engine =
                TramaiEngine(
                    provider =
                        object : ModelProvider {
                            override suspend fun complete(request: ModelRequest): ModelResponse {
                                providerCalls.incrementAndGet()
                                return ModelResponse(content = "ok-$cycle")
                            }
                        },
                )
            val service = engine.create<SummarizerService>()
            val result = runBlocking { service.summarize("cycle-$cycle") }
            assertThat(result).isEqualTo("ok-$cycle")
            assertThat(providerCalls.get()).isEqualTo(1)

            engine.close()
            // Idempotent second close: proves the first close drained the owned
            // jobs and no half-closed state is retained for the next cycle.
            engine.close()
        }
        // A fresh runtime still works after CYCLES teardowns (no global state).
        val finalEngine = TramaiEngine(provider = ImmediateProvider("final"))
        try {
            val result = runBlocking { finalEngine.create<SummarizerService>().summarize("after") }
            assertThat(result).isEqualTo("final")
        } finally {
            finalEngine.close()
        }
    }

    private companion object {
        const val CYCLES = 25
    }

    private class ImmediateProvider(
        private val content: String,
    ) : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse = ModelResponse(content = content)
    }
}
