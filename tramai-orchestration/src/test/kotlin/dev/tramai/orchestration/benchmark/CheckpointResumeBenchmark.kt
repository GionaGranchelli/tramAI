package dev.tramai.orchestration.benchmark

import dev.tramai.orchestration.benchmark.WorkerPollingFixture.build
import dev.tramai.testing.benchmark.BenchmarkHarness
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * B10 — checkpoint resume. Latency of one real poll cycle that claims and
 * executes a single due checkpoint: enumeration -> deterministic ordering ->
 * active/partition/lease/recovery filtering -> LeaseCoordinator claim ->
 * WorkflowExecutionSupervisor dispatch + completion drain of the registered
 * one-step workflow (stable depth: the next cycle observes the same single
 * pending checkpoint).
 *
 * Boundary: workflow step BODIES are trivial deterministic one-step bodies
 * (no network, no lease renewal in the timed region) — see
 * WorkerPollingFixture for the full boundary statement.
 */
@EnabledIfSystemProperty(named = "tramai.benchmark", matches = "true")
class CheckpointResumeBenchmark {
    @Test
    fun `B10 checkpoint resume latency`() {
        val fixture = build(dueCount = 1)
        var cycles = 0
        try {
            val (meanUs, p50Us, p95Us) =
                BenchmarkHarness.latency(
                    operation = "B10-checkpoint-resume",
                    module = "tramai-orchestration",
                    fixture =
                        "CheckpointPoller poll cycle over 1 due checkpoint: enumerate+order+filter+" +
                            "lease claim+supervisor dispatch+completion drain (one-step in-process workflow)",
                ) {
                    runBlocking { fixture.pollCycleDrained() }
                    cycles++
                    assertEquals(cycles, fixture.observer.leaseAcquired.size, "cycle must claim its checkpoint")
                }
            assertTrue(meanUs > 0.0 && p50Us > 0.0 && p95Us >= p50Us)
        } finally {
            fixture.scope.cancel()
        }
    }
}
