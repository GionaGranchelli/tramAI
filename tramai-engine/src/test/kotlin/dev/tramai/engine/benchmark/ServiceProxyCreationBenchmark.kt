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
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.time.Instant
import kotlin.math.ceil
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
 * environment metadata and raw samples to build/reports/benchmark/. No
 * thresholds — 12.1b records the committed baseline, 12.1d defines policy.
 */
@EnabledIfSystemProperty(named = "tramai.benchmark", matches = "true")
class ServiceProxyCreationBenchmark {
    private val iterations: Int =
        System.getProperty("tramai.benchmark.iterations")?.toIntOrNull() ?: 10

    @Test
    fun `service proxy creation - warm engine path latency`() {
        val provider =
            object : ModelProvider {
                override suspend fun complete(request: ModelRequest): ModelResponse = ModelResponse("benchmark")
            }
        val engine = TramaiEngine(provider)
        try {
            // Warm-up: JIT + proxy class generation + definition-compiler caches.
            repeat(3) { engine.create<BenchAskService>() }

            val samplesNs =
                (1..iterations).map {
                    val start = System.nanoTime()
                    val proxy = engine.create<BenchAskService>()
                    assertTrue(Proxy.isProxyClass(proxy.javaClass), "create() must return a dynamic proxy")
                    System.nanoTime() - start
                }
            assertTrue(samplesNs.all { it > 0L }, "all samples must be positive")

            val samplesUs = samplesNs.map { it / 1_000.0 }
            val meanUs = samplesUs.average()
            val p50Us = percentile(samplesUs, 0.50)
            val p95Us = percentile(samplesUs, 0.95)
            println(
                "benchmark service-proxy-creation: " +
                    "mean=${round1(meanUs)}us p50=${round1(p50Us)}us " +
                    "p95=${round1(p95Us)}us n=$iterations",
            )
            emitJson(meanUs, p50Us, p95Us, samplesNs)
        } finally {
            engine.close()
        }
    }

    private fun emitJson(
        meanUs: Double,
        p50Us: Double,
        p95Us: Double,
        samplesNs: List<Long>,
    ) {
        val outDir = File(System.getProperty("tramai.benchmark.out") ?: "build/reports/benchmark")
        outDir.mkdirs()
        val file = File(outDir, "${Instant.now()}-service-proxy-creation.json")
        val iterationOverride = System.getProperty("tramai.benchmark.iterations")
        file.writeText(
            """
            |{
            |  "operation": "service-proxy-creation",
            |  "module": "tramai-engine",
            |  "fixture": "engine.create<BenchAskService>() on single stub provider (warm path)",
            |  "gitSha": "${System.getProperty("tramai.benchmark.gitSha") ?: "unknown"}",
            |  "timestamp": "${Instant.now()}",
            |  "iterationOverride": ${iterationOverride?.let { "\"$it\"" } ?: "null"},
            |  "env": {
            |    "java.version": "${System.getProperty("java.version") ?: "unknown"}",
            |    "java.vendor": "${System.getProperty("java.vendor") ?: "unknown"}",
            |    "os.name": "${System.getProperty("os.name") ?: "unknown"}",
            |    "os.arch": "${System.getProperty("os.arch") ?: "unknown"}",
            |    "gradleJvmArgs": "${System.getProperty("tramai.benchmark.gradleJvmArgs") ?: "unknown"}",
            |    "hostname": "${hostname()}",
            |    "runnerLabel": "${System.getenv("RUNNER_NAME") ?: "local"}"
            |  },
            |  "samplesNs": [${samplesNs.joinToString(",")}],
            |  "stats": { "meanUs": $meanUs, "p50Us": $p50Us, "p95Us": $p95Us },
            |  "unit": "microseconds"
            |}
            """.trimMargin() + "\n",
        )
        println("benchmark JSON: ${file.absolutePath}")
    }

    private fun hostname(): String =
        try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            "unknown"
        }

    /** Nearest-rank percentile (see EPIC-12.1 doc §4): ceil(q * n) - 1, bounded. */
    private fun percentile(
        sortedable: List<Double>,
        q: Double,
    ): Double {
        val sorted = sortedable.sorted()
        val rank = ceil(q * sorted.size).toInt() - 1
        return sorted[rank.coerceIn(0, sorted.size - 1)]
    }

    private fun round1(v: Double): Double = (v * 10.0).roundToLong() / 10.0
}

@AiService
private interface BenchAskService {
    @Operation(prompt = "Answer concisely", model = "claude-sonnet-4-20250514")
    suspend fun ask(question: String): String
}
