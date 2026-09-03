package dev.tramai.engine.benchmark

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.engine.TramaiEngine
import dev.tramai.engine.create
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import java.net.InetAddress
import java.time.Instant
import kotlin.math.roundToLong

/**
 * Epic 12.1a benchmark harness — first enrolled operation: service proxy creation.
 *
 * Gated by `-Dtramai.benchmark=true` (forwarded to the test worker JVM by the
 * module build): ordinary `test` runs report this class as skipped, so PR CI
 * stays timing-free. The deep/release lane activates it (see
 * `.github/workflows/sovereign-runtime-release-candidate.yml`, dispatch branch).
 *
 * Methodology (docs/EPIC-12.1-performance-resource-baseline.md §4):
 * warm-up >= 3 iterations, then >= 10 individually timed iterations with
 * System.nanoTime(); report mean / p50 / p95; emit one JSON document with
 * environment metadata to build/reports/benchmark/. No thresholds — 12.1b
 * records the committed baseline, 12.1d defines regression policy.
 */
@EnabledIfSystemProperty(named = "tramai.benchmark", matches = "true")
class ServiceProxyCreationBenchmark {

    private val iterations: Int =
        System.getProperty("tramai.benchmark.iterations")?.toIntOrNull() ?: 10

    @Test
    fun `service proxy creation - warm engine path latency`() {
        val provider =
            object : ModelProvider {
                override suspend fun complete(request: ModelRequest): ModelResponse =
                    ModelResponse(content = "benchmark")
            }
        val engine = TramaiEngine(provider)
        try {
            // Warm-up: JIT + proxy class generation + definition-compiler caches.
            repeat(3) { engine.create<BenchAskService>() }

            val samples = (1..iterations).map {
                val start = System.nanoTime()
                val proxy = engine.create<BenchAskService>()
                assertTrue(proxy is BenchAskService, "create() must return a working proxy")
                System.nanoTime() - start
            }

            val nanos = samples.map { it.toDouble() }
            val meanUs = nanos.average() / 1_000.0
            val p50Us = percentile(nanos, 0.50) / 1_000.0
            val p95Us = percentile(nanos, 0.95) / 1_000.0
            assertTrue(samples.all { it > 0L }, "all samples must be positive")

            println("benchmark service-proxy-creation: mean=${roundTo(meanUs)}us p50=${roundTo(p50Us)}us p95=${roundTo(p95Us)}us n=$iterations")
            emitJson(meanUs, p50Us, p95Us, samples)
        } finally {
            engine.close()
        }
    }

    private fun emitJson(meanUs: Double, p50Us: Double, p95Us: Double, samplesNs: List<Long>) {
        val outDir = File(System.getProperty("tramai.benchmark.out") ?: "build/reports/benchmark")
        outDir.mkdirs()
        val file = File(outDir, "${Instant.now()}-service-proxy-creation.json")
        file.writeText(
            """
            {
              "operation": "service-proxy-creation",
              "module": "tramai-engine",
              "fixture": "engine.create<BenchAskService>() on single stub provider (warm path)",
              "gitSha": "${System.getProperty("tramai.benchmark.gitSha") ?: "unknown"}",
              "timestamp": "${Instant.now()}",
              "env": {
                "java.version": "${System.getProperty("java.version")}",
                "java.vendor": "${System.getProperty("java.vendor")}",
                "os.name": "${System.getProperty("os.name")}",
                "os.arch": "${System.getProperty("os.arch")}",
                "hostname": "${hostname()}"
              },
              "iterations": $iterations,
              "stats": { "meanUs": $meanUs, "p50Us": $p50Us, "p95Us": $p95Us },
              "unit": "microseconds"
            }
            """.trimIndent() + "\n",
        )
        println("benchmark JSON: ${file.absolutePath}")
    }

    private fun hostname(): String =
        try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            "unknown"
        }

    private fun percentile(sortedable: List<Double>, q: Double): Double {
        val sorted = sortedable.sorted()
        val rank = (q * (sorted.size - 1)).roundToLong().toInt()
        return sorted[rank]
    }

    private fun roundTo(v: Double): Double = (v * 10.0).roundToLong() / 10.0
}

@AiService
private interface BenchAskService {
    @Operation(prompt = "Answer concisely", model = "claude-sonnet-4-20250514")
    suspend fun ask(question: String): String
}
