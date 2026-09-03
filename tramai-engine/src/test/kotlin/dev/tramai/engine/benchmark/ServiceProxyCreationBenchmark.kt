package dev.tramai.engine.benchmark

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.engine.TramaiEngine
import dev.tramai.engine.create
import dev.tramai.testing.benchmark.BenchmarkHarness
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.lang.reflect.Proxy

/**
 * B01 — service proxy creation (engine.create warm path).
 *
 * Fixture mirrors the behavioural surface of TramaiEngineTest: single stub
 * provider, one @AiService interface; measures `create<T>()` after warm-up
 * (JIT + definition-compiler caches). Gated by `tramai.benchmark=true`; see
 * BenchmarkHarness for the measurement contract.
 */
@EnabledIfSystemProperty(named = "tramai.benchmark", matches = "true")
class ServiceProxyCreationBenchmark {
    @Test
    fun `B01 service proxy creation - warm engine path latency`() {
        val provider =
            object : ModelProvider {
                override suspend fun complete(request: ModelRequest): ModelResponse = ModelResponse("benchmark")
            }
        val engine = TramaiEngine(provider)
        try {
            // Probe: create() must return a dynamic proxy (real check, not a type tautology).
            val probe = engine.create<BenchAskService>()
            assertTrue(Proxy.isProxyClass(probe.javaClass), "create() must return a dynamic proxy")

            val (meanUs, p50Us, p95Us) =
                BenchmarkHarness.latency(
                    operation = "B01-service-proxy-creation",
                    module = "tramai-engine",
                    fixture = "engine.create<BenchAskService>() on single stub provider (warm path)",
                ) {
                    val proxy = engine.create<BenchAskService>()
                    assertTrue(Proxy.isProxyClass(proxy.javaClass), "create() must return a dynamic proxy")
                }
            assertTrue(meanUs > 0.0 && p50Us > 0.0 && p95Us >= p50Us)
        } finally {
            engine.close()
        }
    }
}

@AiService
private interface BenchAskService {
    @Operation(prompt = "Answer concisely", model = "claude-sonnet-4-20250514")
    suspend fun ask(question: String): String
}
