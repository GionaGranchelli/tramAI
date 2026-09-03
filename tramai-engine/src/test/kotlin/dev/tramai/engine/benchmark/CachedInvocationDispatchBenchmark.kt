package dev.tramai.engine.benchmark

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.engine.InMemoryOperationResponseCache
import dev.tramai.engine.TramaiEngine
import dev.tramai.engine.create
import dev.tramai.testing.benchmark.BenchmarkHarness
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.util.concurrent.atomic.AtomicInteger

/**
 * B03 — cached invocation dispatch. First call populates the in-memory cache;
 * timed iterations hit the cache (provider untouched — asserted). Mirrors the
 * cacheable-op fixture used across engine tests.
 */
@EnabledIfSystemProperty(named = "tramai.benchmark", matches = "true")
class CachedInvocationDispatchBenchmark {
    @Test
    fun `B03 cached invocation dispatch latency`() {
        val provider = CountingProvider()
        val engine = TramaiEngine(provider, responseCache = InMemoryOperationResponseCache())
        try {
            val service = engine.create<BenchCacheService>()
            // Probe: identical input must be served from cache after the first call.
            val first = runBlocking { service.respond("same-input") }
            val cached = runBlocking { service.respond("same-input") }
            assertEquals(first, cached)
            assertEquals(1, provider.calls.get(), "second identical call must hit the cache")

            val (meanUs, p50Us, p95Us) =
                BenchmarkHarness.latency(
                    operation = "B03-cached-invocation-dispatch",
                    module = "tramai-engine",
                    fixture =
                        "engine.create<BenchCacheService>().respond('same-input') with " +
                            "InMemoryOperationResponseCache, cacheable=true (warm, cache-hit)",
                ) {
                    val result = runBlocking { service.respond("same-input") }
                    assertTrue(result.isNotEmpty())
                }
            assertEquals(1, provider.calls.get(), "cache must absorb every timed iteration")
            assertTrue(meanUs > 0.0 && p50Us > 0.0 && p95Us >= p50Us)
        } finally {
            engine.close()
        }
    }

    private class CountingProvider : ModelProvider {
        val calls = AtomicInteger(0)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            calls.incrementAndGet()
            return ModelResponse("cached-response")
        }
    }
}

@AiService
private interface BenchCacheService {
    @Operation(prompt = "Respond", model = "test-model", cacheable = true, cacheTtlMillis = 60_000, providerRetries = 0)
    suspend fun respond(input: String): String
}
