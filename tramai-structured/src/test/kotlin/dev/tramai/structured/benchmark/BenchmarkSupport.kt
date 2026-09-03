package dev.tramai.structured.benchmark

import java.io.File
import java.net.InetAddress
import java.time.Instant
import kotlin.math.ceil

/**
 * Minimal latency-measurement support for [module] whose test classpath must
 * not take the tramai-testing testFixtures edge (module dependency boundary —
 * canonical BenchmarkHarness lives there for tramai-engine/orchestration).
 * Mirrors the BenchmarkHarness contract exactly (see
 * docs/EPIC-12.1-performance-resource-baseline.md §4): warm-up >= 3, timed
 * >= 10 nanoTime iterations, nearest-rank p50/p95, JSON with env metadata +
 * raw samples, no thresholds.
 *
 * ponytail: duplicate of the canonical harness — revisit if a third module
 * needs benches or the dependency-boundary decision changes.
 */
internal object BenchmarkSupport {
    private const val NANOS_PER_MICRO = 1_000.0
    private const val WARM_UP_ITERATIONS = 3
    private const val QUANTILE_P50 = 0.50
    private const val QUANTILE_P95 = 0.95
    private const val ROUND_TO_TENTHS = 10.0

    fun latency(
        operation: String,
        module: String,
        fixture: String,
        count: Int = 10,
        block: () -> Unit,
    ) {
        repeat(WARM_UP_ITERATIONS) { block() }
        val samplesNs =
            (1..count).map {
                val start = System.nanoTime()
                block()
                System.nanoTime() - start
            }
        require(samplesNs.all { it > 0L }) { "all latency samples must be positive" }
        val samplesUs = samplesNs.map { it / NANOS_PER_MICRO }
        val meanUs = samplesUs.average()
        val p50Us = percentile(samplesUs, QUANTILE_P50)
        val p95Us = percentile(samplesUs, QUANTILE_P95)
        println(
            "benchmark $operation: mean=${round1(meanUs)}us p50=${round1(p50Us)}us " +
                "p95=${round1(p95Us)}us n=$count",
        )
        emitJson(
            operation = operation,
            module = module,
            fixture = fixture,
            body =
                "\"samplesNs\": [${samplesNs.joinToString(",")}]," +
                    " \"stats\": { \"meanUs\": $meanUs, \"p50Us\": $p50Us, \"p95Us\": $p95Us }," +
                    " \"unit\": \"microseconds\"",
        )
    }

    private fun percentile(
        values: List<Double>,
        q: Double,
    ): Double {
        val sorted = values.sorted()
        val rank = ceil(q * sorted.size).toInt() - 1
        return sorted[rank.coerceIn(0, sorted.size - 1)]
    }

    private fun emitJson(
        operation: String,
        module: String,
        fixture: String,
        body: String,
    ) {
        val outDir = File(System.getProperty("tramai.benchmark.out") ?: "build/reports/benchmark")
        outDir.mkdirs()
        val timestamp = Instant.now().toString().replace(":", "-")
        val file = File(outDir, "$timestamp-$operation.json")
        val iterationOverride = System.getProperty("tramai.benchmark.iterations")
        file.writeText(
            """
            |{
            |  "operation": "$operation",
            |  "module": "$module",
            |  "fixture": "$fixture",
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
            |  $body
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

    private fun round1(v: Double): Double = (v * ROUND_TO_TENTHS).toLong() / ROUND_TO_TENTHS
}
