package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ChangePolicyEvaluator] — the pure-logic change policy engine.
 *
 * No Gradle, no git, no filesystem — pure function tests on strings and lists.
 */
class ChangePolicyEvaluatorTest {

    // --- Production path detection ---

    @Test
    fun `tramai-engine src main is production path`() {
        assertTrue(ChangePolicyEvaluator.isProductionPath("tramai-engine/src/main/kotlin/Engine.kt"))
    }

    @Test
    fun `build-logic src main is production path`() {
        assertTrue(ChangePolicyEvaluator.isProductionPath("build-logic/src/main/kotlin/dev/tramai/build/quality/Scanner.kt"))
    }

    @Test
    fun `test source is not production path`() {
        assertFalse(ChangePolicyEvaluator.isProductionPath("tramai-engine/src/test/kotlin/EngineTest.kt"))
    }

    @Test
    fun `config file is not production path`() {
        assertFalse(ChangePolicyEvaluator.isProductionPath("config/quality/0.6.0-baseline.json"))
    }

    @Test
    fun `docs are not production path`() {
        assertFalse(ChangePolicyEvaluator.isProductionPath("docs/board/tasks/TASK-TEMPLATE.md"))
    }

    // --- Analyzer path detection ---

    @Test
    fun `scanner file is analyzer path`() {
        assertTrue(ChangePolicyEvaluator.isAnalyzerPath(
            "build-logic/src/main/kotlin/dev/tramai/build/quality/KotlinCancellationCatchScanner.kt"))
    }

    @Test
    fun `verifier file is analyzer path`() {
        assertTrue(ChangePolicyEvaluator.isAnalyzerPath(
            "build-logic/src/main/kotlin/dev/tramai/build/quality/BaselineVerifier.kt"))
    }

    @Test
    fun `plugin file is analyzer path`() {
        assertTrue(ChangePolicyEvaluator.isAnalyzerPath(
            "build-logic/src/main/kotlin/dev/tramai/build/quality/MaintainabilityBaselinePlugin.kt"))
    }

    @Test
    fun `non-analyzer build-logic is adjacent`() {
        assertFalse(ChangePolicyEvaluator.isAnalyzerPath(
            "build-logic/src/main/kotlin/dev/tramai/build/quality/BaselineGenerator.kt"))
        assertTrue(ChangePolicyEvaluator.isAnalyzerAdjacentPath(
            "build-logic/src/main/kotlin/dev/tramai/build/quality/BaselineGenerator.kt"))
    }

    // --- Runtime production path detection ---

    @Test
    fun `runtime engine src main is runtime production`() {
        assertTrue(ChangePolicyEvaluator.isRuntimeProductionPath(
            "tramai-engine/src/main/kotlin/dev/tramai/engine/Engine.kt"))
    }

    @Test
    fun `runtime test is not runtime production`() {
        assertFalse(ChangePolicyEvaluator.isRuntimeProductionPath(
            "tramai-engine/src/test/kotlin/EngineTest.kt"))
    }

    @Test
    fun `spring boot starter is not runtime production`() {
        assertFalse(ChangePolicyEvaluator.isRuntimeProductionPath(
            "tramai-spring-boot-starter-sovereign/src/main/kotlin/Starter.kt"))
    }

    @Test
    fun `observability module is not runtime production`() {
        assertFalse(ChangePolicyEvaluator.isRuntimeProductionPath(
            "tramai-observability/src/main/kotlin/Observability.kt"))
    }

    // --- Rule 1: production + baseline separation ---

    @Test
    fun `production and baseline change together without migration class fails`() {
        val input = ChangePolicyInput(
            changeClass = null, // auto-detect
            changedFiles = listOf(
                "tramai-engine/src/main/kotlin/Engine.kt",
                "config/quality/0.6.0-baseline.json"
            ),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertFalse(result.passed)
        assertTrue(result.violations.any { it.rule == "production-baseline-separation" })
    }

    @Test
    fun `production and baseline change with baseline-migration class passes`() {
        val input = ChangePolicyInput(
            changeClass = "baseline-migration",
            changedFiles = listOf(
                "build-logic/src/main/kotlin/dev/tramai/build/quality/KotlinCancellationCatchScanner.kt",
                "config/quality/0.6.0-baseline.json"
            ),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.passed) { "baseline-migration class should allow production + baseline changes" }
    }

    @Test
    fun `production only change passes rule 1`() {
        val input = ChangePolicyInput(
            changeClass = null,
            changedFiles = listOf("tramai-engine/src/main/kotlin/Engine.kt"),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.none { it.rule == "production-baseline-separation" })
    }

    // --- Rule 2: analyzer + runtime separation ---

    @Test
    fun `analyzer and runtime change together fails`() {
        val input = ChangePolicyInput(
            changeClass = null,
            changedFiles = listOf(
                "build-logic/src/main/kotlin/dev/tramai/build/quality/KotlinCancellationCatchScanner.kt",
                "tramai-engine/src/main/kotlin/Engine.kt"
            ),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertFalse(result.passed)
        assertTrue(result.violations.any { it.rule == "analyzer-runtime-separation" })
    }

    @Test
    fun `analyzer change without runtime passes`() {
        val input = ChangePolicyInput(
            changeClass = null,
            changedFiles = listOf(
                "build-logic/src/main/kotlin/dev/tramai/build/quality/KotlinCancellationCatchScanner.kt",
                "build-logic/src/main/kotlin/dev/tramai/build/quality/BaselineModel.kt"
            ),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.none { it.rule == "analyzer-runtime-separation" })
    }

    @Test
    fun `runtime change without analyzer passes`() {
        val input = ChangePolicyInput(
            changeClass = null,
            changedFiles = listOf("tramai-engine/src/main/kotlin/Engine.kt"),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.none { it.rule == "analyzer-runtime-separation" })
    }

    @Test
    fun `baseline migration with analyzer and runtime passes rule 2`() {
        val input = ChangePolicyInput(
            changeClass = "baseline-migration",
            changedFiles = listOf(
                "build-logic/src/main/kotlin/dev/tramai/build/quality/KotlinCancellationCatchScanner.kt",
                "config/quality/0.6.0-baseline.json"
            ),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        // Should pass because baseline-migration exempts both rules 1 and 2
        assertTrue(result.passed)
    }

    // --- Rule 3: deviation evidence ---

    private val validDeviationYaml = """
        |deviations:
        |  - id: MQ-0001
        |    metric: constructorParameterCount
        |    scope: ":tramai-engine"
        |    baseline: 32
        |    allowed: 32
        |    reason: "Engine proxy dispatch centralizes many concerns."
        |    acceptedAt: "2026-07-18"
        |    targetPhase: "0.6.1"
        |    owner: "GionaGranchelli"
    """.trimMargin()

    @Test
    fun `valid deviation with all fields passes`() {
        val input = ChangePolicyInput(
            changeClass = "build-logic",
            changedFiles = listOf("config/quality/maintainability-deviations.yml"),
            baseDeviationsYaml = null,
            currentDeviationsYaml = validDeviationYaml
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.none { it.rule == "deviation-evidence" })
    }

    @Test
    fun `deviation missing baseline fails`() {
        val yaml = """
            |deviations:
            |  - id: MQ-NO-BASELINE
            |    metric: someMetric
            |    scope: ":tramai-engine"
            |    allowed: 35
            |    reason: "No baseline provided."
            |    acceptedAt: "2026-07-26"
            |    targetPhase: "0.6.1"
            |    owner: "agent"
        """.trimMargin()
        val input = ChangePolicyInput(
            changeClass = "build-logic",
            changedFiles = listOf("config/quality/maintainability-deviations.yml"),
            baseDeviationsYaml = null,
            currentDeviationsYaml = yaml
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        val evidenceViolations = result.violations.filter { it.rule == "deviation-evidence" }
        assertTrue(evidenceViolations.isNotEmpty())
        assertTrue(evidenceViolations.any { it.message.contains("MQ-NO-BASELINE") && it.message.contains("baseline") })
    }

    @Test
    fun `deviation missing targetPhase fails`() {
        val yaml = """
            |deviations:
            |  - id: MQ-NO-PHASE
            |    metric: someMetric
            |    scope: ":tramai-engine"
            |    baseline: 10
            |    allowed: 10
            |    reason: "Temporary."
            |    acceptedAt: "2026-07-26"
            |    owner: "agent"
        """.trimMargin()
        val input = ChangePolicyInput(
            changeClass = "build-logic",
            changedFiles = listOf("config/quality/maintainability-deviations.yml"),
            baseDeviationsYaml = null,
            currentDeviationsYaml = yaml
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.any {
            it.rule == "deviation-evidence" && it.message.contains("targetPhase")
        })
    }

    @Test
    fun `deviation allowed value increase detected without justification fails`() {
        val baseYaml = """
            |deviations:
            |  - id: MQ-INCREASE
            |    metric: someMetric
            |    scope: ":tramai-engine"
            |    baseline: 10
            |    allowed: 10
            |    reason: "Original."
            |    acceptedAt: "2026-07-01"
            |    targetPhase: "0.6.1"
            |    owner: "agent"
        """.trimMargin()
        val currentYaml = """
            |deviations:
            |  - id: MQ-INCREASE
            |    metric: someMetric
            |    scope: ":tramai-engine"
            |    baseline: 10
            |    allowed: 15
            |    reason: ""  # Empty reason
            |    acceptedAt: "2026-07-26"
            |    targetPhase: ""
            |    owner: "agent"
        """.trimMargin()
        val input = ChangePolicyInput(
            changeClass = "build-logic",
            changedFiles = listOf("config/quality/maintainability-deviations.yml"),
            baseDeviationsYaml = baseYaml,
            currentDeviationsYaml = currentYaml
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.any {
            it.rule == "deviation-evidence" && it.message.contains("MQ-INCREASE") && it.message.contains("increased")
        })
    }

    @Test
    fun `deviation allowed value increase with justification passes`() {
        val baseYaml = """
            |deviations:
            |  - id: MQ-INCREASE-OK
            |    metric: someMetric
            |    scope: ":tramai-engine"
            |    baseline: 10
            |    allowed: 10
            |    reason: "Original."
            |    acceptedAt: "2026-07-01"
            |    targetPhase: "0.6.1"
            |    owner: "agent"
        """.trimMargin()
        val currentYaml = """
            |deviations:
            |  - id: MQ-INCREASE-OK
            |    metric: someMetric
            |    scope: ":tramai-engine"
            |    baseline: 10
            |    allowed: 15
            |    reason: "New code paths added in 0.6.2."
            |    acceptedAt: "2026-07-26"
            |    targetPhase: "0.6.2"
            |    owner: "agent"
        """.trimMargin()
        val input = ChangePolicyInput(
            changeClass = "build-logic",
            changedFiles = listOf("config/quality/maintainability-deviations.yml"),
            baseDeviationsYaml = baseYaml,
            currentDeviationsYaml = currentYaml
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.none { it.rule == "deviation-evidence" })
    }

    @Test
    fun `deletion of deviations file fails`() {
        val input = ChangePolicyInput(
            changeClass = "build-logic",
            changedFiles = listOf("config/quality/maintainability-deviations.yml"),
            baseDeviationsYaml = validDeviationYaml,
            currentDeviationsYaml = null // file deleted
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.any {
            it.rule == "deviation-evidence" && it.message.contains("deleted")
        })
    }

    // --- Empty / clean state ---

    @Test
    fun `empty changed files passes`() {
        val input = ChangePolicyInput(
            changeClass = null,
            changedFiles = emptyList(),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        assertTrue(ChangePolicyEvaluator.evaluate(input).passed)
    }

    @Test
    fun `documentation only change passes`() {
        val input = ChangePolicyInput(
            changeClass = null,
            changedFiles = listOf("AGENTS.md", "docs/guide.md"),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        assertTrue(ChangePolicyEvaluator.evaluate(input).passed)
    }

    // --- YAML parsing ---

    @Test
    fun `parseDeviations returns null for null input`() {
        assertEquals(null, ChangePolicyEvaluator.parseDeviations(null))
    }

    @Test
    fun `parseDeviations returns empty for blank input`() {
        assertEquals(emptyMap(), ChangePolicyEvaluator.parseDeviations(""))
    }

    @Test
    fun `parseDeviations parses valid deviation YAML`() {
        val result = ChangePolicyEvaluator.parseDeviations(validDeviationYaml)
        assertEquals(1, result?.size)
        assertTrue(result?.containsKey("MQ-0001") == true)
        assertEquals(32, result?.get("MQ-0001")?.get("baseline"))
    }

    @Test
    fun `parseDeviations handles malformatted YAML gracefully`() {
        val result = ChangePolicyEvaluator.parseDeviations("{{invalid yaml: [broken")
        // Should not crash — return empty map
        assertEquals(emptyMap(), result)
    }
}
