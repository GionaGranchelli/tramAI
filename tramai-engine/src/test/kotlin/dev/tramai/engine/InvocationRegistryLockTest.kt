package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression for the invocation-registry lock protocol.
 *
 * Production invariant: every mutation of [TramaiEngine]'s
 * `activeInvocationJobs` registry — launch+add (in invokeSuspend), snapshot
 * (in close()), and completion removal (in invokeOnCompletion) — participates
 * in the same monitor (`synchronized(activeInvocationJobs)`).
 *
 * When removal was unsynchronized, close() could throw
 * NoSuchElementException: Kotlin's `toList()` size-1 fast path reads
 * `size()==1`, a concurrent completion removed the job, then
 * `iterator().next()` hit an empty map. close() throwing killed the closer
 * thread silently, which hung the close-race test for 60–90 minutes on CI.
 *
 * This test fails if the removal is changed back to unsynchronized.
 */
class InvocationRegistryLockTest {
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `completion removal participates in the registry lock`() { runBlocking {
        val gate = CompletableDeferred<Unit>()
        val providerCalls = AtomicInteger()
        val provider = object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                providerCalls.incrementAndGet()
                gate.await()
                return ModelResponse(content = "ok")
            }
        }
        val engine = TramaiEngine(provider)
        val service = engine.create<HandshakeService>()
        val invocation = async(Dispatchers.Default) { runCatching { service.answer() } }

        val registry = activeInvocationJobsOf(engine)
        awaitUntil(10, TimeUnit.SECONDS) { registry.size == 1 }
        val tracked = registry.first()

        // Hold the registry monitor, THEN release the provider. The invocation
        // completes on a Default worker; its invokeOnCompletion removal must
        // block on the monitor we hold. If removal were unsynchronized, it
        // would run immediately and the registry would already be empty.
        synchronized(registry) {
            gate.complete(Unit)
            awaitUntil(10, TimeUnit.SECONDS) { tracked.isCompleted }
            // Give the completing worker time to attempt the removal while we
            // still hold the monitor: with the fix it stays blocked here for
            // the whole window; without it the registry empties immediately.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)
            while (System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertThat(registry)
                .describedAs("completion removal must wait for the registry monitor held by close()'s snapshot")
                .contains(tracked)
        }

        // Monitor released: the blocked removal completes.
        awaitUntil(10, TimeUnit.SECONDS) { registry.isEmpty() }

        assertThat(providerCalls.get()).isEqualTo(1)
        assertThat(invocation.await().exceptionOrNull()).isNull()
        engine.close()
    }
    }

    private fun awaitUntil(timeout: Long, unit: TimeUnit, condition: () -> Boolean) {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within ${unit.toSeconds(timeout)}s" }
            Thread.yield()
        }
    }

    private fun activeInvocationJobsOf(engine: TramaiEngine): MutableSet<Job> {
        val field = TramaiEngine::class.java.getDeclaredField("activeInvocationJobs")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(engine) as MutableSet<Job>
    }
}

@AiService
private interface HandshakeService {
    @Operation(prompt = "Answer", model = "test-model")
    suspend fun answer(): String
}
