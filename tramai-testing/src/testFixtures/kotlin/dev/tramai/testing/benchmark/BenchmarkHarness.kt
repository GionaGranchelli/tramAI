package dev.tramai.testing.benchmark

import java.io.File
import java.net.InetAddress
import java.time.Instant
import kotlin.math.ceil

/**
 * Epic 12.1b shared measurement harness (test-side only, no production deps).
 *
 * Contract (docs/EPIC-12.1-performance-resource-baseline.md §4, established by
 * the 12.1a harness PR #379):
 *  - latency: warm-up >= 3, then >= 10 individually timed iterations
 *    (`System.nanoTime()`), mean / p50 / p95 with nearest-rank percentiles;
 *  - throughput: fixed-duration sampling windows counted in ops/sec;
 *  - one JSON document per run with full environment/provenance metadata and
 *    raw samples, emitted to `build/reports/benchmark/` (deep lane uploads);
 *  - no thresholds — 12.1b records the reference point, 12.1d owns policy.
 *
 * Activated only when `-Dtramai.benchmark=true` is forwarded to the test
 * worker JVM (see root `build.gradle.kts`); benchmark classes carry
 * `@EnabledIfSystemProperty`, so ordinary `test` runs stay timing-free.
 */
@Suppress("TooManyFunctions") // cohesive measurement API; splitting for the threshold would hurt readability
object BenchmarkHarness {
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val NANOS_PER_MICRO = 1_000.0
    private const val WARM_UP_ITERATIONS = 3
    private const val WARM_UP_WINDOW_MILLIS = 1_000L
    private const val QUANTILE_P50 = 0.50
    private const val QUANTILE_P95 = 0.95
    private const val ROUND_TO_TENTHS = 10.0

    /** Iteration count: `-Dtramai.benchmark.iterations` override, else default. */
    fun iterations(default: Int = 10): Int = System.getProperty("tramai.benchmark.iterations")?.toIntOrNull() ?: default

    /** Runs [warmUp] un-timed iterations, then [count] timed ones; raw ns samples. */
    fun sampleLatencyNs(
        warmUp: Int = WARM_UP_ITERATIONS,
        count: Int = iterations(),
        block: () -> Unit,
    ): List<Long> {
        repeat(warmUp) { block() }
        return (1..count).map {
            val start = System.nanoTime()
            block()
            System.nanoTime() - start
        }
    }

    /** ops/sec over repeated sampling windows (warm-up window first). */
    fun sampleThroughputOpsPerSec(
        windows: Int = 5,
        windowMillis: Long = WARM_UP_WINDOW_MILLIS,
        block: () -> Unit,
    ): List<Double> {
        fun oneWindow(): Double {
            val start = System.nanoTime()
            val deadline = start + windowMillis * NANOS_PER_MILLI
            var ops = 0L
            while (System.nanoTime() < deadline) {
                block()
                ops++
            }
            return ops / ((System.nanoTime() - start) / NANOS_PER_SECOND)
        }
        oneWindow() // warm-up window (JIT/caches)
        return (1..windows).map { oneWindow() }
    }

    /**
     * Latency benchmark: warms up [WARM_UP_ITERATIONS], samples [count] timed
     * iterations, emits the JSON document and prints a summary. Returns
     * (mean, p50, p95) in microseconds.
     */
    fun latency(
        operation: String,
        module: String,
        fixture: String,
        count: Int = iterations(),
        block: () -> Unit,
    ): Triple<Double, Double, Double> {
        val samplesNs = sampleLatencyNs(count = count, block = block)
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
        return Triple(meanUs, p50Us, p95Us)
    }

    /** Throughput benchmark: [windows] ops/sec windows; emits JSON. Returns mean ops/sec. */
    fun throughput(
        operation: String,
        module: String,
        fixture: String,
        windows: Int = 5,
        block: () -> Unit,
    ): Double {
        val opsPerSec = sampleThroughputOpsPerSec(windows = windows, block = block)
        require(opsPerSec.all { it >= 0.0 }) { "throughput samples must be non-negative" }
        val mean = opsPerSec.average()
        val p50 = percentile(opsPerSec, QUANTILE_P50)
        val p95 = percentile(opsPerSec, QUANTILE_P95)
        println("benchmark $operation: mean=${round1(mean)} ops/sec windows=${opsPerSec.size}")
        emitJson(
            operation = operation,
            module = module,
            fixture = fixture,
            body =
                "\"samplesOpsPerSec\": [${opsPerSec.joinToString(",")}]," +
                    " \"stats\": { \"meanOpsPerSec\": $mean, \"p50OpsPerSec\": $p50," +
                    " \"p95OpsPerSec\": $p95 }, \"unit\": \"ops/sec\"",
        )
        return mean
    }

    /** Nearest-rank percentile (ceil(q * n) - 1, bounded) — see EPIC-12.1 doc §4. */
    fun percentile(
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
        // Filesystem-safe timestamp: GitHub artifact upload rejects ':' in names.
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
