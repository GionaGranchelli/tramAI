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
 * B11 — worker polling throughput at two queue depths, measured on the real
 * polling path (CheckpointPoller over in-memory persistence with lease claim
 * + supervisor dispatch + completion drain; see WorkerPollingFixture).
 *
 * - empty queue: poll cycles over a catalog with 0 due checkpoints
 *   (idle enumeration cadence);
 * - loaded queue: poll cycles over a catalog with 100 due checkpoints
 *   (100 claims + dispatches + completions per cycle — depth stays at 100
 *   across cycles because each drained cycle releases every lease).
 *
 * ops/sec = poll cycles per second (1s fixed-duration sampling windows); the
 * loaded fixture processes 100 checkpoints per cycle, so the equivalent
 * checkpoint rate is cycles/sec * 100. Workflow step bodies are trivial and
 * lease renewal is outside the timed region (documented boundary).
 */
@EnabledIfSystemProperty(named = "tramai.benchmark", matches = "true")
class WorkerPollingBenchmark {
    @Test
    fun `B11 worker polling - empty queue throughput`() {
        val fixture = build(dueCount = 0)
        try {
            val meanOps =
                BenchmarkHarness.throughput(
                    operation = "B11-worker-polling-empty",
                    module = "tramai-orchestration",
                    fixture =
                        "CheckpointPoller poll cycle over EMPTY catalog (0 due): " +
                            "idle enumerate+filter cycle; ops/sec = poll cycles/sec; " +
                            "no claims expected per cycle",
                ) {
                    runBlocking { fixture.pollCycleDrained() }
                    assertEquals(0, fixture.observer.leaseAcquired.size, "empty queue must never claim")
                }
            assertTrue(meanOps > 0.0)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun `B11 worker polling - loaded queue throughput`() {
        val fixture = build(dueCount = 100)
        var cycles = 0
        try {
            val meanOps =
                BenchmarkHarness.throughput(
                    operation = "B11-worker-polling-loaded",
                    module = "tramai-orchestration",
                    fixture =
                        "CheckpointPoller poll cycle over catalog with 100 due checkpoints: " +
                            "100 claims+dispatches+completions per cycle, depth stable across cycles; " +
                            "ops/sec = poll cycles/sec (x100 = checkpoint claims/sec); " +
                            "step bodies trivial, lease renewal outside timed region",
                ) {
                    runBlocking { fixture.pollCycleDrained() }
                    cycles++
                    assertEquals(cycles * 100, fixture.observer.leaseAcquired.size, "every cycle must claim all 100")
                }
            assertTrue(meanOps > 0.0)
        } finally {
            fixture.scope.cancel()
        }
    }
}
