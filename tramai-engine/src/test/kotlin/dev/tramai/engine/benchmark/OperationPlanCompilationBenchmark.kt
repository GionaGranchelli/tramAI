package dev.tramai.engine.benchmark

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.planning.OperationDefinitionCompiler
import dev.tramai.engine.planning.OperationFingerprintFactory
import dev.tramai.engine.planning.ServiceDefinitionCompiler
import dev.tramai.testing.benchmark.BenchmarkHarness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * B02 — operation-plan compilation (ServiceDefinitionCompiler.compile on a
 * validated @AiService interface; mirrors EnginePlanningIntegrationTest's
 * repeated-compilation fixture, no engine construction inside the timed loop).
 */
@EnabledIfSystemProperty(named = "tramai.benchmark", matches = "true")
class OperationPlanCompilationBenchmark {
    @Test
    fun `B02 operation-plan compilation latency`() {
        val compiler =
            ServiceDefinitionCompiler(
                OperationDefinitionCompiler(
                    toolRegistry = ToolRegistry(),
                    promptSanitizer = null,
                    fingerprintFactory = OperationFingerprintFactory(),
                ),
            )
        val probe = compiler.compile(BenchPlanService::class)
        assertEquals(2, probe.operations.size, "fixture must compile two operations")

        val (meanUs, p50Us, p95Us) =
            BenchmarkHarness.latency(
                operation = "B02-operation-plan-compilation",
                module = "tramai-engine",
                fixture = "ServiceDefinitionCompiler.compile<BenchPlanService>() (2 ops, no tools)",
            ) {
                val plan = compiler.compile(BenchPlanService::class)
                assertEquals(2, plan.operations.size)
            }
        assertTrue(meanUs > 0.0 && p50Us > 0.0 && p95Us >= p50Us)
    }
}

@AiService
private interface BenchPlanService {
    @Operation(prompt = "Classify the document", model = "test-model")
    suspend fun classify(text: String): String

    @Operation(prompt = "Summarize the document", model = "test-model", providerRetries = 1)
    suspend fun summarize(text: String): String
}
