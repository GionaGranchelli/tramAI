package dev.tramai.core.benchmark

import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * B06 — provider routing. Resolves the primary route + configured fallbacks
 * for a model through a warm ProviderRegistry (3 providers, 2 models, 1
 * fallback chain). Mirrors the ProviderRegistryTest resolve-candidates
 * fixture; the registry + operation are built once, resolution is timed.
 */
@EnabledIfSystemProperty(named = "tramai.benchmark", matches = "true")
class ProviderRoutingBenchmark {
    private val registry: ProviderRegistry =
        ProviderRegistry
            .builder()
            .provider("primary", NamedProvider("primary"))
            .provider("fallback-a", NamedProvider("fallback-a"))
            .provider("fallback-b", NamedProvider("fallback-b"))
            .model("gpt-5.1-chat-latest", "primary")
            .model("gpt-5.1-mini", "fallback-a")
            .fallbackModel("gpt-5.1-chat-latest", "gpt-5.1-mini", "fallback-a")
            .fallbackModel("gpt-5.1-mini", "gpt-5.1-mini", "fallback-b")
            .build()

    private val operation = Operation(prompt = "unused", model = "gpt-5.1-chat-latest")

    @Test
    fun `B06 provider routing latency`() {
        val probe = registry.resolveCandidates(operation)
        assertEquals(2, probe.size, "fixture must resolve primary + one fallback")

        BenchmarkSupport.latency(
            operation = "B06-provider-routing",
            module = "tramai-core",
            fixture =
                "ProviderRegistry.resolveCandidates(Operation model=gpt-5.1-chat-latest) " +
                    "on 3-provider/2-model registry with 1 fallback hop",
        ) {
            val candidates = registry.resolveCandidates(operation)
            assertEquals(2, candidates.size)
        }
        assertTrue(probe.isNotEmpty())
    }

    private class NamedProvider(
        private val id: String,
    ) : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse {
            error("routing benchmark must not invoke providers")
        }

        override fun providerId(): String = id
    }
}
