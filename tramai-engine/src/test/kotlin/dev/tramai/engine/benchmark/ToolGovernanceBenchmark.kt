package dev.tramai.engine.benchmark

import dev.tramai.engine.tool.ToolAuthorizationCoordinator
import dev.tramai.engine.tool.ToolAuthorizationDecision
import dev.tramai.engine.tool.policyHelper
import dev.tramai.engine.tool.testTool
import dev.tramai.engine.tool.toolRequest
import dev.tramai.testing.benchmark.BenchmarkHarness
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * B07 — tool-governance overhead. Runs the full pre-execution governance
 * decision for one tool call: context build + policy evaluation against the
 * enforcement point (BEFORE_TOOL_EXECUTION) via ToolAuthorizationCoordinator.
 * Mirrors the ToolAuthorizationCoordinatorTest allow-path fixture (existing
 * TestToolFixtures helpers); allow-by-default policy, no execution.
 */
@EnabledIfSystemProperty(named = "tramai.benchmark", matches = "true")
class ToolGovernanceBenchmark {
    private val coordinator = ToolAuthorizationCoordinator(policyHelper())
    private val request = toolRequest(testTool(name = "governed-tool"))

    @Test
    fun `B07 tool governance authorization latency`() {
        val probe = runBlocking { coordinator.authorize(request, "{}") }
        assertEquals(ToolAuthorizationDecision.Allow, probe)

        val (meanUs, p50Us, p95Us) =
            BenchmarkHarness.latency(
                operation = "B07-tool-governance",
                module = "tramai-engine",
                fixture =
                    "ToolAuthorizationCoordinator.authorize(governed-tool, '{}') " +
                        "with allow-by-default policy (context build + policy evaluation)",
            ) {
                val decision = runBlocking { coordinator.authorize(request, "{}") }
                assertEquals(ToolAuthorizationDecision.Allow, decision)
            }
        assertTrue(meanUs > 0.0 && p50Us > 0.0 && p95Us >= p50Us)
    }
}
