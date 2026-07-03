package dev.tramai.examples.spring

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.openai.OpenAiCompatibleProvider
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.test.context.ContextConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.system.measureTimeMillis

/**
 * Opt-in diagnostic benchmark that runs against the same sovereign-lab profile
 * and local OpenAI-compatible provider used by [SovereignLabLocalModelInvocationTest].
 *
 * Writes a machine-readable JSON report to
 * `build/reports/sovereign-lab/local-model-benchmark/benchmark.json`.
 *
 * This benchmark is **diagnostic-only**. It is not part of CI and does not
 * define production performance thresholds. It captures observed latency and
 * success profile for the local machine, model, and endpoint in use.
 *
 * Prerequisites:
 * ```
 * ollama serve
 * ollama pull qwen2.5:7b
 * ```
 *
 * Run:
 * ```
 * TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK=true ./gradlew benchmarkSovereignLabLocalModel
 * ```
 *
 * Configuration via environment variables:
 * | Variable | Default | Description |
 * |----------|---------|-------------|
 * | `TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK` | — | Must be `true` to enable |
 * | `TRAMAI_LOCAL_BENCHMARK_WARMUP` | `1` | Warmup calls (0–10) |
 * | `TRAMAI_LOCAL_BENCHMARK_CALLS` | `3` | Measured calls (1–20) |
 */
@Tag("local-benchmark")
@EnabledIfEnvironmentVariable(
    named = "TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK",
    matches = "true",
    disabledReason = """
        This benchmark requires a real local OpenAI-compatible model endpoint.
        Set TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK=true and ensure a local endpoint
        is reachable at the TRAMAI_LOCAL_BASE_URL (default: http://localhost:11434/v1).
    """,
)
@SpringBootTest(
    classes = [SpringSovereignStarterApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.profiles.active=sovereign-lab",
        "tramai.sovereign.ops.mutations-enabled=true",
        "tramai.sovereign.ops.resume-enabled=true",
    ],
)
@ContextConfiguration(initializers = [LabProfileInitializer::class])
class SovereignLabLocalModelBenchmarkTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var env: Environment

    @Test
    fun `benchmarks local OpenAI-compatible model invocation`() {
        runBlocking {
            val provider = context.getBean(OpenAiCompatibleProvider::class.java)
            val model = requireNotNull(
                env.getProperty("tramai.providers.local-lab-provider.model"),
            ) {
                "tramai.providers.local-lab-provider.model must be configured"
            }

            val baseUrl = requireNotNull(
                env.getProperty("tramai.providers.local-lab-provider.base-url"),
            ) {
                "tramai.providers.local-lab-provider.base-url must be configured"
            }

            val warmupCalls = System.getenv("TRAMAI_LOCAL_BENCHMARK_WARMUP")
                ?.toIntOrNull()
                ?: 1

            val measuredCalls = System.getenv("TRAMAI_LOCAL_BENCHMARK_CALLS")
                ?.toIntOrNull()
                ?: 3

            require(warmupCalls in 0..10) {
                "TRAMAI_LOCAL_BENCHMARK_WARMUP must be between 0 and 10"
            }

            require(measuredCalls in 1..20) {
                "TRAMAI_LOCAL_BENCHMARK_CALLS must be between 1 and 20"
            }

            // Warmup phase
            repeat(warmupCalls) { index ->
                provider.complete(benchmarkRequest(model, "warmup-$index"))
            }

            // Measured phase
            val samples = mutableListOf<BenchmarkSample>()

            repeat(measuredCalls) { index ->
                var content: String = ""
                val latencyMs = measureTimeMillis {
                    val response = provider.complete(benchmarkRequest(model, "sample-$index"))
                    assertThat(response.content).isNotBlank
                    assertThat(response.modelUsed).isNotNull
                    content = response.content
                }

                samples += BenchmarkSample(
                    index = index,
                    latencyMs = latencyMs,
                    responseChars = content.length,
                )
            }

            val report = BenchmarkReport(
                timestamp = Instant.now().toString(),
                providerId = provider.providerId(),
                model = model,
                baseUrl = baseUrl,
                warmupCalls = warmupCalls,
                measuredCalls = measuredCalls,
                samples = samples,
            )

            writeReport(report)
        }
    }

    private fun benchmarkRequest(model: String, runId: String): ModelRequest =
        ModelRequest(
            model = model,
            messages = listOf(
                Message(
                    role = MessageRole.USER,
                    content = "Reply with one short sentence confirming benchmark run $runId.",
                ),
            ),
            temperature = 0.0,
        )

    private fun writeReport(report: BenchmarkReport) {
        val outputDir = Path.of(
            "build/reports/sovereign-lab/local-model-benchmark",
        )
        Files.createDirectories(outputDir)

        val reportFile = outputDir.resolve("benchmark.json")
        Files.writeString(reportFile, report.toJson())

        println("Sovereign lab local-model benchmark report: $reportFile")
        println("Provider: ${report.providerId}")
        println("Model: ${report.model}")
        println("Calls: ${report.measuredCalls}")
        println("Latency ms: min=${report.minLatencyMs()}, avg=${report.avgLatencyMs()}, max=${report.maxLatencyMs()}")
    }
}

private data class BenchmarkSample(
    val index: Int,
    val latencyMs: Long,
    val responseChars: Int,
)

private data class BenchmarkReport(
    val timestamp: String,
    val providerId: String,
    val model: String,
    val baseUrl: String,
    val warmupCalls: Int,
    val measuredCalls: Int,
    val samples: List<BenchmarkSample>,
) {
    fun minLatencyMs(): Long = samples.minOf { it.latencyMs }
    fun maxLatencyMs(): Long = samples.maxOf { it.latencyMs }
    fun avgLatencyMs(): Long = samples.map { it.latencyMs }.average().toLong()

    fun toJson(): String =
        """
        {
          "timestamp": "$timestamp",
          "providerId": "$providerId",
          "model": "$model",
          "baseUrl": "$baseUrl",
          "warmupCalls": $warmupCalls,
          "measuredCalls": $measuredCalls,
          "latencyMs": {
            "min": ${minLatencyMs()},
            "avg": ${avgLatencyMs()},
            "max": ${maxLatencyMs()}
          },
          "samples": [
            ${samples.joinToString(",\n            ") {
                """{"index": ${it.index}, "latencyMs": ${it.latencyMs}, "responseChars": ${it.responseChars}}"""
            }}
          ]
        }
        """.trimIndent()
}
